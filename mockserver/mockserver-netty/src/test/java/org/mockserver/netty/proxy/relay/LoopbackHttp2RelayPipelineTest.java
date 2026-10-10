package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameListenerDecorator;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapterBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.logging.MockServerLogger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.SCHEME;
import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_ID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * The relay's HTTP/2 loopback pipeline as {@code RelayConnectHandler} builds it, between a real proxy client connection
 * and a real HTTP/2 peer standing in for MockServer, read as the frames each leg writes: which resets cross the relay
 * when a stream ends, and that none does when an exchange completes. Run with {@link LoopbackHttp2StreamErrorHandler}
 * on either side of {@link LoopbackHttp2StreamIdRemapper}, since neither may depend on the other's position.
 */
@RunWith(Parameterized.class)
public class LoopbackHttp2RelayPipelineTest {

    @Parameterized.Parameters(name = "error handler first: {0}")
    public static Collection<Object[]> handlerOrders() {
        return Arrays.asList(new Object[]{true}, new Object[]{false});
    }

    private final boolean errorHandlerFirst;
    private EmbeddedChannel proxyClient;
    private Http2Connection proxyClientConnection;
    private EmbeddedChannel loopback;
    private EmbeddedChannel server;
    private LoopbackHttp2StreamIdRemapper remapper;
    private final WrittenFrames writtenToProxyClient = new WrittenFrames();
    private final WrittenFrames writtenToServer = new WrittenFrames();
    private final List<Integer> requestsAtServer = new ArrayList<>();
    private boolean serverAnswers = true;
    private int writesToAClosedLoopback;

    public LoopbackHttp2RelayPipelineTest(boolean errorHandlerFirst) {
        this.errorHandlerFirst = errorHandlerFirst;
    }

    @Before
    public void connect() {
        MockServerLogger mockServerLogger = new MockServerLogger();
        proxyClient = new EmbeddedChannel();
        Http2Connection loopbackConnection = new DefaultHttp2Connection(false);
        remapper = new LoopbackHttp2StreamIdRemapper(mockServerLogger, loopbackConnection, proxyClient);
        LoopbackHttp2StreamErrorHandler errorHandler = new LoopbackHttp2StreamErrorHandler(mockServerLogger, loopbackConnection, remapper, proxyClient);
        proxyClientConnection = new DefaultHttp2Connection(true);
        proxyClient.pipeline().addLast(new HttpToHttp2ConnectionHandlerBuilder()
            .frameListener(errorHandler.proxyClientFrameListener(proxyClientConnection, new Http2FrameAdapter()))
            .connection(proxyClientConnection)
            .build());
        loopback = new EmbeddedChannel(
            new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    if (!ctx.channel().isActive()) {
                        writesToAClosedLoopback++;
                    }
                    ctx.write(msg, promise);
                }
            },
            new HttpToHttp2ConnectionHandlerBuilder()
                .frameListener(errorHandler.frameListener(new InboundHttp2ToHttpAdapterBuilder(loopbackConnection).maxContentLength(1024 * 1024).build()))
                .connection(loopbackConnection)
                .build(),
            errorHandlerFirst ? errorHandler : remapper,
            errorHandlerFirst ? remapper : errorHandler,
            new LoopbackHttp2ConnectionCloseHandler(mockServerLogger, loopbackConnection, proxyClient, remapper),
            new DownstreamProxyRelayHandler(mockServerLogger, proxyClient)
        );
        Http2Connection serverConnection = new DefaultHttp2Connection(true);
        server = new EmbeddedChannel(
            new HttpToHttp2ConnectionHandlerBuilder()
                .frameListener(ignoringResets(new InboundHttp2ToHttpAdapterBuilder(serverConnection).maxContentLength(1024 * 1024).build()))
                .connection(serverConnection)
                .build(),
            new SimpleChannelInboundHandler<FullHttpRequest>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                    int streamId = request.headers().getInt(STREAM_ID.text());
                    requestsAtServer.add(streamId);
                    if (serverAnswers) {
                        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer("answer for " + request.uri(), StandardCharsets.UTF_8));
                        response.headers().setInt(STREAM_ID.text(), streamId);
                        ctx.writeAndFlush(response);
                    }
                }
            }
        );
        pump();
    }

    @After
    public void close() {
        loopback.finishAndReleaseAll();
        server.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
        writtenToProxyClient.close();
        writtenToServer.close();
    }

    @Test
    public void shouldRelayAWholeExchangeWithoutResettingEitherStream() throws Exception {
        relay(3, "/first");
        relay(5, "/second");

        assertThat(writtenToProxyClient.bodies.get(3), is("answer for /first"));
        assertThat(writtenToProxyClient.bodies.get(5), is("answer for /second"));
        assertThat("the response ended the client's stream", proxyClientConnection.stream(3), nullValue());
        assertThat(writtenToProxyClient.resets, is(empty()));
        assertThat(writtenToServer.resets, is(empty()));
        assertThat(remapper.mappedStreams(), is(0));
        assertTunnelOpen();
    }

    @Test
    public void shouldResetTheLoopbackStreamWhenTheRelayResetsTheClientsStream() throws Exception {
        serverAnswers = false;
        relay(3, "/held");
        assertThat(requestsAtServer, contains(1));

        // as the client-facing Http2ConnectionHandler ends a stream whose request breaks the protocol
        resetProxyClientStream(3, Http2Error.PROTOCOL_ERROR);

        assertThat(writtenToServer.resets, contains("1:" + Http2Error.CANCEL.code()));
        assertThat(writtenToProxyClient.resets, contains("3:" + Http2Error.PROTOCOL_ERROR.code()));
        assertThat(remapper.loopbackStreamId(3), nullValue());
        assertThat(remapper.mappedStreams(), is(0));
        serverAnswers = true;
        relay(5, "/after");
        assertThat(writtenToProxyClient.bodies.get(5), is("answer for /after"));
        assertTunnelOpen();
    }

    @Test
    public void shouldRelayThePeersResetWithoutAnsweringItWithAReset() throws Exception {
        serverAnswers = false;
        relay(3, "/reset");
        HttpToHttp2ConnectionHandler serverHandler = server.pipeline().get(HttpToHttp2ConnectionHandler.class);
        ChannelHandlerContext serverCtx = server.pipeline().context(serverHandler);

        serverHandler.resetStream(serverCtx, 1, Http2Error.CANCEL.code(), serverCtx.newPromise());
        serverCtx.flush();
        pump();

        assertThat(writtenToProxyClient.resets, contains("3:" + Http2Error.CANCEL.code()));
        assertThat(writtenToServer.resets, is(empty()));
        assertThat(remapper.mappedStreams(), is(0));
        assertTunnelOpen();
    }

    @Test
    public void shouldRelayTheClientsOwnResetOnce() throws Exception {
        serverAnswers = false;
        relay(3, "/cancelled");
        Http2ConnectionHandler clientHandler = proxyClient.pipeline().get(Http2ConnectionHandler.class);

        // as the client-facing decoder handles a RST_STREAM frame: the frame listener, then the stream's close
        clientHandler.decoder().frameListener().onRstStreamRead(proxyClient.pipeline().context(clientHandler), 3, Http2Error.STREAM_CLOSED.code());
        proxyClientConnection.stream(3).close();
        pump();

        assertThat(writtenToServer.resets, contains("1:" + Http2Error.STREAM_CLOSED.code()));
        assertThat(writtenToProxyClient.resets, is(empty()));
        assertTunnelOpen();
    }

    @Test
    public void shouldLeaveTheLoopbackStreamsToTheTunnelsCloseWhenTheClientConnectionCloses() throws Exception {
        serverAnswers = false;
        relay(3, "/held");

        // the connection lost, not closed gracefully: every client stream closes with it
        proxyClient.unsafe().close(proxyClient.voidPromise());
        pump();

        assertThat(proxyClientConnection.numActiveStreams(), is(0));
        assertThat(writtenToServer.resets, is(empty()));
    }

    @Test
    public void shouldWriteNothingToALoopbackWhoseCloseResetsTheClientsStreams() throws Exception {
        serverAnswers = false;
        relay(3, "/held");

        loopback.unsafe().close(loopback.voidPromise());
        pump();

        assertThat(writtenToProxyClient.resets, contains("3:" + Http2Error.INTERNAL_ERROR.code()));
        assertThat(writesToAClosedLoopback, is(0));
    }

    private void relay(int clientStreamId, String uri) throws Http2Exception {
        // half closed, as a client's stream is once its whole request has arrived
        proxyClientConnection.remote().createStream(clientStreamId, true);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, Unpooled.copiedBuffer("upload for " + uri, StandardCharsets.UTF_8));
        request.headers().setInt(STREAM_ID.text(), clientStreamId);
        request.headers().set("host", "localhost");
        request.headers().set(SCHEME.text(), "https");
        loopback.writeAndFlush(request);
        pump();
    }

    private void resetProxyClientStream(int streamId, Http2Error error) {
        Http2ConnectionHandler clientHandler = proxyClient.pipeline().get(Http2ConnectionHandler.class);
        ChannelHandlerContext clientCtx = proxyClient.pipeline().context(clientHandler);
        clientHandler.resetStream(clientCtx, streamId, error.code(), clientCtx.newPromise());
        clientCtx.flush();
        pump();
    }

    private void assertTunnelOpen() {
        assertThat(proxyClient.isActive(), is(true));
        assertThat(loopback.isActive(), is(true));
    }

    private void pump() {
        boolean moved;
        do {
            moved = false;
            ByteBuf bytes;
            while ((bytes = loopback.readOutbound()) != null) {
                writtenToServer.read(bytes);
                server.writeInbound(bytes);
                moved = true;
            }
            while ((bytes = server.readOutbound()) != null) {
                loopback.writeInbound(bytes);
                moved = true;
            }
            while ((bytes = proxyClient.readOutbound()) != null) {
                writtenToProxyClient.read(bytes);
                bytes.release();
                moved = true;
            }
            proxyClient.runPendingTasks();
            loopback.runPendingTasks();
        } while (moved);
    }

    /**
     * The peer's adapter reports a reset as an exception; the stream is closed either way.
     */
    private static Http2FrameListener ignoringResets(Http2FrameListener delegate) {
        return new Http2FrameListenerDecorator(delegate) {
            @Override
            public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
            }
        };
    }

    /**
     * The resets and response bodies among the frames one leg of the relay has written.
     */
    private static final class WrittenFrames extends Http2FrameAdapter {
        private final DefaultHttp2FrameReader reader = new DefaultHttp2FrameReader();
        // the reader allocates a header block's buffer from its context
        private final EmbeddedChannel readerChannel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        private final ByteBuf unread = Unpooled.buffer();
        private final List<String> resets = new ArrayList<>();
        private final Map<Integer, String> bodies = new LinkedHashMap<>();

        void read(ByteBuf written) {
            ByteBuf preface = Http2CodecUtil.connectionPrefaceBuf();
            if (!written.equals(preface)) {
                unread.writeBytes(written, written.readerIndex(), written.readableBytes());
                try {
                    reader.readFrame(readerChannel.pipeline().firstContext(), unread, this);
                } catch (Http2Exception unreadable) {
                    throw new AssertionError(unreadable);
                }
            }
        }

        @Override
        public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
            resets.add(streamId + ":" + errorCode);
        }

        @Override
        public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) {
            bodies.merge(streamId, data.toString(StandardCharsets.UTF_8), String::concat);
            return data.readableBytes() + padding;
        }

        void close() {
            reader.close();
            unread.release();
            readerChannel.finishAndReleaseAll();
        }
    }
}
