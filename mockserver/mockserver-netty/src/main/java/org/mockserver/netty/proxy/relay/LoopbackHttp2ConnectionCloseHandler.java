package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionAdapter;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Stream;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Ends the proxy client's HTTP/2 streams at once when the CONNECT/SOCKS relay's HTTP/2 loopback connection closes, for
 * any reason, instead of leaving them to Netty's 30 s graceful-shutdown timeout. The client is sent a {@code GOAWAY},
 * then each active stream that has no whole response is reset: {@code REFUSED_STREAM} when no request on it has been
 * relayed, so MockServer never saw it and RFC 9113 section 8.7 lets the client retry it, otherwise
 * {@code INTERNAL_ERROR}, since MockServer may have acted on it. A stream still uploading may have been relayed
 * already: a request with {@code Expect} is relayed as its headers before its body arrives. A stream whose whole
 * response has been relayed is not cut short: while the response is still queued behind the client's flow-control
 * window it is left to finish, and once it is written, if the client is still uploading, the stream is reset with
 * {@code NO_ERROR} (RFC 9113 section 8.1). {@link DownstreamProxyRelayHandler} then closes the client connection
 * gracefully, which waits only for those responses.
 * <p>
 * A {@code GOAWAY} the loopback receives is passed on to the client, so it opens new streams on another connection
 * rather than on this one, whose loopback can no longer carry them. Sits after {@link LoopbackHttp2StreamIdRemapper},
 * which records which client streams have been relayed and answered, and before
 * {@link DownstreamProxyRelayHandler}; one per loopback. It calls the client's {@link Http2ConnectionHandler}
 * directly, which is safe only because the loopback shares the client's event loop.
 */
public class LoopbackHttp2ConnectionCloseHandler extends ChannelInboundHandlerAdapter {

    private final MockServerLogger mockServerLogger;
    private final Channel proxyClientChannel;
    private final LoopbackHttp2StreamIdRemapper streamIdRemapper;

    public LoopbackHttp2ConnectionCloseHandler(MockServerLogger mockServerLogger, Http2Connection loopbackConnection, Channel proxyClientChannel, LoopbackHttp2StreamIdRemapper streamIdRemapper) {
        this.mockServerLogger = mockServerLogger;
        this.proxyClientChannel = proxyClientChannel;
        this.streamIdRemapper = streamIdRemapper;
        loopbackConnection.addListener(new Http2ConnectionAdapter() {
            @Override
            public void onGoAwayReceived(int lastStreamId, long errorCode, ByteBuf debugData) {
                ChannelHandlerContext clientCtx = proxyClientHttp2Context();
                if (clientCtx != null) {
                    goAway(clientCtx);
                    clientCtx.flush();
                }
            }
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        endProxyClientStreams();
        super.channelInactive(ctx);
    }

    private void endProxyClientStreams() {
        ChannelHandlerContext clientCtx = proxyClientHttp2Context();
        if (clientCtx == null) {
            return;
        }
        Http2ConnectionHandler clientHandler = (Http2ConnectionHandler) clientCtx.handler();
        List<Http2Stream> activeStreams = new ArrayList<>();
        try {
            clientHandler.connection().forEachActiveStream(stream -> activeStreams.add(stream));
        } catch (Http2Exception unreachable) {
            // only the visitor can throw, and it does not
        }
        if (activeStreams.isEmpty()) {
            return;
        }
        // the GOAWAY goes first, so a client that retries a refused stream does not retry it on this connection
        goAway(clientCtx);
        int unanswered = 0;
        boolean draining = false;
        for (Http2Stream stream : activeStreams) {
            Http2Error error = closeError(stream);
            if (error == null) {
                draining = true;
                continue;
            }
            if (error != Http2Error.NO_ERROR) {
                unanswered++;
            }
            clientHandler.resetStream(clientCtx, stream.id(), error.code(), clientCtx.newPromise());
        }
        if (draining) {
            clientHandler.connection().addListener(new Http2ConnectionAdapter() {
                @Override
                public void onStreamHalfClosed(Http2Stream stream) {
                    if (stream.state() == Http2Stream.State.HALF_CLOSED_LOCAL && streamIdRemapper.answered(stream.id())) {
                        // not from inside the encoder's write of the response's last frame
                        clientCtx.executor().execute(() -> stopUpload(clientCtx, stream.id()));
                    }
                }
            });
        }
        if (unanswered > 0 && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("relay connection for {} closed with {} streams unanswered, resetting them")
                    .setArguments(proxyClientChannel.remoteAddress(), unanswered)
            );
        }
        clientCtx.flush();
    }

    /**
     * The code to reset a client stream with, or {@code null} for a stream whose whole response is still being written
     * out as the client's flow-control window allows: it is left to the client connection's graceful close, which
     * waits for it up to the client handler's graceful-shutdown timeout.
     */
    private Http2Error closeError(Http2Stream stream) {
        if (!streamIdRemapper.relayed(stream.id())) {
            return Http2Error.REFUSED_STREAM;
        }
        if (!streamIdRemapper.answered(stream.id())) {
            return Http2Error.INTERNAL_ERROR;
        }
        // answered in full, so the client is only told to stop uploading (RFC 9113 section 8.1)
        return stream.state().localSideOpen() ? null : Http2Error.NO_ERROR;
    }

    private void stopUpload(ChannelHandlerContext clientCtx, int streamId) {
        Http2ConnectionHandler clientHandler = (Http2ConnectionHandler) clientCtx.handler();
        Http2Stream stream = clientHandler.connection().stream(streamId);
        if (stream != null && stream.state() == Http2Stream.State.HALF_CLOSED_LOCAL && !stream.isResetSent()) {
            clientHandler.resetStream(clientCtx, streamId, Http2Error.NO_ERROR.code(), clientCtx.newPromise());
            clientCtx.flush();
        }
    }

    private void goAway(ChannelHandlerContext clientCtx) {
        Http2ConnectionHandler clientHandler = (Http2ConnectionHandler) clientCtx.handler();
        Http2Connection clientConnection = clientHandler.connection();
        if (!clientConnection.goAwaySent()) {
            // every stream the client has opened may have reached MockServer; any that did not is refused on its own
            clientHandler.goAway(clientCtx, clientConnection.remote().lastStreamCreated(), Http2Error.NO_ERROR.code(), Unpooled.EMPTY_BUFFER, clientCtx.newPromise());
        }
    }

    private ChannelHandlerContext proxyClientHttp2Context() {
        return proxyClientChannel.isActive() ? proxyClientChannel.pipeline().context(Http2ConnectionHandler.class) : null;
    }
}
