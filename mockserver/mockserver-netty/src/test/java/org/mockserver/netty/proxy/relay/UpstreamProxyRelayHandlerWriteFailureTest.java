package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandlerBuilder;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_ID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;

/**
 * A request {@link UpstreamProxyRelayHandler} cannot write to the relay's loopback resets only its own client stream
 * when the failure is that stream's (an HTTP/2 stream error), and the loopback stays open; any other failure, or one on
 * an HTTP/1.1 tunnel, closes the loopback as before.
 */
public class UpstreamProxyRelayHandlerWriteFailureTest {

    private static final int STREAM_ID_VALUE = 3;

    // only read for its TLS attribute
    private final EmbeddedChannel proxyClientParent = new EmbeddedChannel();
    private EmbeddedChannel proxyClientChannel;
    private EmbeddedChannel loopbackChannel;
    private Http2Stream clientStream;
    private boolean failLater;
    private ChannelPromise pendingWrite;

    @After
    public void releaseAll() {
        proxyClientChannel.finishAndReleaseAll();
        loopbackChannel.finishAndReleaseAll();
        proxyClientParent.finishAndReleaseAll();
    }

    @Test
    public void shouldRefuseOnlyTheStreamWhenTheLoopbackRefusesToOpenIt() throws Exception {
        relayOverHttp2(Http2Exception.streamError(STREAM_ID_VALUE, Http2Error.REFUSED_STREAM, "Cannot create stream greater than Last-Stream-ID from GOAWAY."));

        assertThat(resetsWrittenToClient(), contains(Http2Error.REFUSED_STREAM.code()));
        assertThat(clientStream.isResetSent(), is(true));
        assertThat("the loopback carries on with the other streams", loopbackChannel.isActive(), is(true));
    }

    @Test
    public void shouldResetOnlyTheStreamWithInternalErrorWhenItsLoopbackStreamFails() throws Exception {
        relayOverHttp2(Http2Exception.streamError(STREAM_ID_VALUE, Http2Error.STREAM_CLOSED, "Stream closed before write could take place"));

        assertThat(resetsWrittenToClient(), contains(Http2Error.INTERNAL_ERROR.code()));
        assertThat(loopbackChannel.isActive(), is(true));
    }

    @Test
    public void shouldNotAnswerAStreamTheClientHasAlreadyEnded() throws Exception {
        Throwable failure = Http2Exception.streamError(STREAM_ID_VALUE, Http2Error.STREAM_CLOSED, "Stream closed before write could take place");
        failLater = true;
        relayOverHttp2(failure);
        // the queued write fails while the client's own RST_STREAM is being read, which closes the stream after that
        pendingWrite.setFailure(failure);
        clientStream.close();
        proxyClientChannel.runPendingTasks();

        assertThat(resetsWrittenToClient(), is(empty()));
        assertThat(loopbackChannel.isActive(), is(true));
    }

    @Test
    public void shouldCloseTheLoopbackWhenTheFailureIsNotAStreamError() throws Exception {
        relayOverHttp2(new IOException("Broken pipe"));

        assertThat(resetsWrittenToClient(), is(empty()));
        assertThat(loopbackChannel.isActive(), is(false));
    }

    @Test
    public void shouldCloseTheLoopbackOfAnHttp1Tunnel() {
        loopbackChannel = failingLoopback(Http2Exception.streamError(STREAM_ID_VALUE, Http2Error.REFUSED_STREAM, "refused"));
        proxyClientChannel = new EmbeddedChannel(relayHandler());
        proxyClientChannel.writeInbound(request());
        proxyClientChannel.runPendingTasks();

        assertThat(loopbackChannel.isActive(), is(false));
    }

    private Http2Stream relayOverHttp2(Throwable failure) throws Exception {
        loopbackChannel = failingLoopback(failure);
        Http2ConnectionHandler clientFacing = new HttpToHttp2ConnectionHandlerBuilder().server(true).frameListener(new Http2FrameAdapter()).build();
        // an EmbeddedChannel runs its pending tasks on every read, which a real channel does not
        proxyClientChannel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void read(ChannelHandlerContext ctx) {
            }
        }, clientFacing, relayHandler());
        Http2Connection connection = clientFacing.connection();
        clientStream = connection.remote().createStream(STREAM_ID_VALUE, true);
        // the server's preface SETTINGS
        releaseOutbound();
        proxyClientChannel.writeInbound(request());
        proxyClientChannel.runPendingTasks();
        return clientStream;
    }

    private UpstreamProxyRelayHandler relayHandler() {
        return new UpstreamProxyRelayHandler(new MockServerLogger(), proxyClientParent, loopbackChannel, "localhost", 443, 0);
    }

    private EmbeddedChannel failingLoopback(Throwable failure) {
        return new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                if (failLater) {
                    pendingWrite = promise;
                } else {
                    promise.setFailure(failure);
                }
            }
        });
    }

    private static DefaultFullHttpRequest request() {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", Unpooled.copiedBuffer(new byte[]{1, 2, 3}));
        request.headers().setInt(STREAM_ID.text(), STREAM_ID_VALUE);
        return request;
    }

    private List<Long> resetsWrittenToClient() throws Exception {
        List<Long> resets = new ArrayList<>();
        try (DefaultHttp2FrameReader reader = new DefaultHttp2FrameReader()) {
            ByteBuf written;
            while ((written = proxyClientChannel.readOutbound()) != null) {
                try {
                    reader.readFrame(mock(ChannelHandlerContext.class), written, new Http2FrameAdapter() {
                        @Override
                        public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
                            assertThat(streamId, is(STREAM_ID_VALUE));
                            resets.add(errorCode);
                        }
                    });
                } finally {
                    written.release();
                }
            }
        }
        return resets;
    }

    private void releaseOutbound() {
        Object written;
        while ((written = proxyClientChannel.readOutbound()) != null) {
            ReferenceCountUtil.release(written);
        }
    }
}
