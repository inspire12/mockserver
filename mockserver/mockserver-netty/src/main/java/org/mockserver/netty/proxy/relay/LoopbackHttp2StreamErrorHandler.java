package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http2.*;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_ID;

/**
 * Answers the proxy client's HTTP/2 stream when the CONNECT/SOCKS relay's HTTP/2 loopback stream for it ends without
 * a response: the response failed to decode, passed {@code maxRequestBodySize}, or MockServer reset the stream. The
 * loopback stream ids are not the client's ({@link LoopbackHttp2StreamIdRemapper} pairs them), so the client stream
 * paired with the loopback stream is reset, and the other streams on both connections carry on.
 * <ul>
 *   <li>MockServer reset the loopback stream: the client's stream is reset with the same error code;</li>
 *   <li>the request never reached MockServer (its HEADERS were not sent, or a GOAWAY says it was not processed):
 *       {@code REFUSED_STREAM}, which tells the client it may safely retry (RFC 9113 section 8.7);</li>
 *   <li>otherwise, a failure in the relay itself: {@code INTERNAL_ERROR}.</li>
 * </ul>
 * A stream whose whole final response has been relayed is answered ({@link LoopbackHttp2StreamIdRemapper#answered}),
 * so its close is not reset. A stream the client resets is unpaired, then reset on the loopback with the client's code
 * ({@link #proxyClientFrameListener}). A loopback connection that closes outright is left to
 * {@link LoopbackHttp2ConnectionCloseHandler}, which answers every client stream still open.
 * Sits between the loopback's {@link Http2ConnectionHandler} and the {@link LoopbackHttp2StreamIdRemapper}, so a response
 * still carries its loopback stream id here; one per loopback. It calls the other channel's
 * {@link Http2ConnectionHandler} directly, which is safe only because the loopback is bootstrapped on the proxy
 * client's event loop ({@code RelayConnectHandler.channelRead0}).
 */
public class LoopbackHttp2StreamErrorHandler extends ChannelInboundHandlerAdapter {

    private final MockServerLogger mockServerLogger;
    private final Http2Connection loopbackConnection;
    private final LoopbackHttp2StreamIdRemapper streamIds;
    private final Channel proxyClientChannel;
    private final Http2Connection.PropertyKey remoteResetCodeKey;
    private final Http2Connection.PropertyKey localFailureKey;
    private Channel loopbackChannel;

    public LoopbackHttp2StreamErrorHandler(MockServerLogger mockServerLogger, Http2Connection loopbackConnection, LoopbackHttp2StreamIdRemapper streamIds, Channel proxyClientChannel) {
        this.mockServerLogger = mockServerLogger;
        this.loopbackConnection = loopbackConnection;
        this.streamIds = streamIds;
        this.proxyClientChannel = proxyClientChannel;
        this.remoteResetCodeKey = loopbackConnection.newKey();
        this.localFailureKey = loopbackConnection.newKey();
        loopbackConnection.addListener(new Http2ConnectionAdapter() {
            @Override
            public void onStreamClosed(Http2Stream stream) {
                loopbackStreamClosed(stream);
            }
        });
    }

    /**
     * Wraps the loopback's frame listener to record why a stream is about to be reset.
     */
    public Http2FrameListener frameListener(Http2FrameListener delegate) {
        return new Http2FrameListenerDecorator(delegate) {
            @Override
            public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) throws Http2Exception {
                try {
                    return super.onDataRead(ctx, streamId, data, padding, endOfStream);
                } catch (Http2Exception.StreamException streamException) {
                    recordLocalFailure(streamException);
                    throw streamException;
                }
            }

            @Override
            public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding, boolean endOfStream) throws Http2Exception {
                try {
                    super.onHeadersRead(ctx, streamId, headers, padding, endOfStream);
                } catch (Http2Exception.StreamException streamException) {
                    recordLocalFailure(streamException);
                    throw streamException;
                }
            }

            @Override
            public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endOfStream) throws Http2Exception {
                try {
                    super.onHeadersRead(ctx, streamId, headers, streamDependency, weight, exclusive, padding, endOfStream);
                } catch (Http2Exception.StreamException streamException) {
                    recordLocalFailure(streamException);
                    throw streamException;
                }
            }

            @Override
            public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
                // Not passed on: InboundHttp2ToHttpAdapter would only release its partial message, which it also does
                // when the stream is removed, then report the reset as an exception that closes the whole loopback.
                Http2Stream stream = loopbackConnection.stream(streamId);
                if (stream != null) {
                    stream.setProperty(remoteResetCodeKey, errorCode);
                }
            }
        };
    }

    /**
     * Wraps the proxy client's frame listener so a stream the client resets is reset on the loopback too, and the
     * client's other streams carry on.
     */
    public Http2FrameListener proxyClientFrameListener(Http2FrameListener delegate) {
        return new Http2FrameListenerDecorator(delegate) {
            @Override
            public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
                // not passed on, for the same reason as the loopback's: the adapter would close the whole tunnel
                ChannelHandlerContext loopbackCtx = loopbackChannel != null ? loopbackChannel.pipeline().context(Http2ConnectionHandler.class) : null;
                Integer loopbackStreamId = streamIds.loopbackStreamId(streamId);
                Http2Stream loopbackStream = loopbackStreamId != null ? loopbackConnection.stream(loopbackStreamId) : null;
                if (loopbackCtx != null && loopbackStream != null) {
                    // unpaired first, so the client is not sent a reset in answer to its own
                    streamIds.unpair(streamId);
                    ((Http2ConnectionHandler) loopbackCtx.handler()).resetStream(loopbackCtx, loopbackStreamId, errorCode, loopbackCtx.newPromise());
                    loopbackCtx.flush();
                }
            }
        };
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        loopbackChannel = ctx.channel();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpResponse && ((HttpResponse) msg).status().codeClass() != HttpStatusClass.INFORMATIONAL) {
            Integer streamId = ((HttpResponse) msg).headers().getInt(STREAM_ID.text());
            Integer clientStreamId = streamId != null ? streamIds.clientStreamId(streamId) : null;
            if (clientStreamId != null && proxyClientStreamGone(clientStreamId)) {
                // the client has already reset its stream, and a write to it would fail and close the client's connection
                ReferenceCountUtil.release(msg);
                return;
            }
        }
        ctx.fireChannelRead(msg);
    }

    private void recordLocalFailure(Http2Exception.StreamException streamException) {
        Http2Stream stream = loopbackConnection.stream(streamException.streamId());
        if (stream != null) {
            stream.setProperty(localFailureKey, streamException);
        }
    }

    private void loopbackStreamClosed(Http2Stream loopbackStream) {
        if (loopbackChannel == null || !loopbackChannel.isActive()) {
            // the loopback connection closed: LoopbackHttp2ConnectionCloseHandler answers the client's streams
            return;
        }
        Integer clientStreamId = streamIds.clientStreamId(loopbackStream.id());
        if (clientStreamId == null || streamIds.answered(clientStreamId)) {
            return;
        }
        Long remoteResetCode = loopbackStream.getProperty(remoteResetCodeKey);
        long errorCode;
        if (remoteResetCode != null) {
            errorCode = remoteResetCode;
        } else if (!loopbackStream.isHeadersSent() || (loopbackConnection.goAwayReceived() && loopbackStream.id() > loopbackConnection.local().lastStreamKnownByPeer())) {
            errorCode = Http2Error.REFUSED_STREAM.code();
        } else {
            errorCode = Http2Error.INTERNAL_ERROR.code();
        }
        resetProxyClientStream(clientStreamId, errorCode, remoteResetCode == null, loopbackStream.getProperty(localFailureKey));
    }

    private void resetProxyClientStream(int streamId, long errorCode, boolean relayFailure, Throwable cause) {
        ChannelHandlerContext clientCtx = proxyClientChannel.pipeline().context(Http2ConnectionHandler.class);
        if (clientCtx == null || !proxyClientChannel.isActive() || ((Http2ConnectionHandler) clientCtx.handler()).connection().stream(streamId) == null) {
            return;
        }
        // a reset MockServer chose is relayed as it is; it already logged its own action
        if (relayFailure && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("relayed stream {} to {} ended without a response, resetting the client's stream with error code {}")
                    .setArguments(streamId, proxyClientChannel.remoteAddress(), errorCode)
                    .setThrowable(cause)
            );
        }
        ((Http2ConnectionHandler) clientCtx.handler()).resetStream(clientCtx, streamId, errorCode, clientCtx.newPromise());
        clientCtx.flush();
    }

    private boolean proxyClientStreamGone(int streamId) {
        Http2ConnectionHandler clientHandler = proxyClientChannel.pipeline().get(Http2ConnectionHandler.class);
        return clientHandler != null && clientHandler.connection().stream(streamId) == null;
    }
}
