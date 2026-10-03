package org.mockserver.netty.connection;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2ChannelDuplexHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.metrics.Metrics;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Drives {@link Http2StreamWriteStallHandler} through a real HTTP/2 client and server codec joined in memory, so the
 * connection's socket writability can be set directly: a stream whose own window is open waits only for the socket,
 * which is the connection watcher's to time, while a stream whose window is closed waits for its client.
 */
public class Http2StreamWriteStallHandlerTest {

    private static final long TIMEOUT_MILLIS = 200;
    private static final int RESPONSE_BYTES = 16 * 1024;
    private static final int SOCKET_WRITABILITY = 1;

    private EmbeddedChannel server;
    private EmbeddedChannel client;
    private Socket socket;
    private Http2FrameCodec serverCodec;
    private Client clientHandler;

    @Before
    public void enableMetrics() {
        Metrics.resetAdditionalMetricsForTesting();
        new Metrics(configuration().metricsEnabled(true));
    }

    @After
    public void close() {
        if (client != null) {
            client.finishAndReleaseAll();
        }
        if (server != null) {
            server.finishAndReleaseAll();
        }
        Metrics.resetAdditionalMetricsForTesting();
    }

    @Test
    public void shouldNotResetAStreamWithAnOpenWindowWaitingOnlyForTheSocket() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        socketWritable(false);

        clientHandler.request();
        waitThrough(4 * TIMEOUT_MILLIS);

        assertThat("the stream was not reset", clientHandler.resetErrorCode, is(nullValue()));
        assertThat("the stream is still open", serverCodec.connection().numActiveStreams(), is(1));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(0L));

        socketWritable(true);
        exchange();
        assertThat("the response was sent once the socket was writable", clientHandler.dataBytes, is(RESPONSE_BYTES));
        assertThat(clientHandler.endStream, is(true));
    }

    @Test
    public void shouldResetAStreamWithAnOpenWindowBehindAnUnwritableSocketWhenNoConnectionWatcherIsPresent() throws Exception {
        // a connection accepted while the timeout was 0 has no connection watcher to cut a socket stall
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE, false);
        socketWritable(false);

        clientHandler.request();
        waitThrough(4 * TIMEOUT_MILLIS);

        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetAStreamWhoseClientGrantsItNoWindow() throws Exception {
        connect(0);

        clientHandler.request();
        waitThrough(4 * TIMEOUT_MILLIS);

        assertThat("the stream was reset", clientHandler.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(serverCodec.connection().numActiveStreams(), is(0));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetAStreamWhoseClientGrantsItNoWindowWhileTheSocketIsNotWritableOnce() throws Exception {
        connect(0);
        socketWritable(false);

        clientHandler.request();
        waitThrough(4 * TIMEOUT_MILLIS);

        // the stream stays active until its RST_STREAM gets past the socket, and is not reset again meanwhile
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
        assertThat(serverCodec.connection().numActiveStreams(), is(1));
        serverCodec.connection().forEachActiveStream(stream -> {
            assertThat("the stream was reset", stream.isResetSent(), is(true));
            return true;
        });

        socketWritable(true);
        exchange();
        assertThat("the reset reached the client once the socket was writable", clientHandler.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(serverCodec.connection().numActiveStreams(), is(0));
    }

    private void connect(int clientInitialWindowSize) {
        connect(clientInitialWindowSize, true);
    }

    private void connect(int clientInitialWindowSize, boolean connectionWatcher) {
        serverCodec = Http2FrameCodecBuilder.forServer().build();
        socket = new Socket();
        server = new EmbeddedChannel(socket, serverCodec, new Http2StreamWriteStallHandler(TIMEOUT_MILLIS, null), new Responder());
        if (connectionWatcher) {
            // its timer never arms here, since the embedded channel's outbound buffer is always emptied by a flush
            server.pipeline().addFirst(new WriteStallTimeoutHandler(60_000, null));
        }
        clientHandler = new Client();
        client = new EmbeddedChannel(
            Http2FrameCodecBuilder.forClient().initialSettings(Http2Settings.defaultSettings().initialWindowSize(clientInitialWindowSize)).build(),
            clientHandler
        );
        client.flush();
        exchange();
    }

    private void socketWritable(boolean writable) {
        socket.hold(!writable);
        server.unsafe().outboundBuffer().setUserDefinedWritability(SOCKET_WRITABILITY, writable);
        server.runPendingTasks();
        assertThat(server.isWritable(), is(writable));
    }

    private void waitThrough(long millis) throws InterruptedException {
        long started = System.nanoTime();
        while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < millis) {
            TimeUnit.MILLISECONDS.sleep(WriteStallTimeoutHandler.checkIntervalMillis(TIMEOUT_MILLIS));
            server.runScheduledPendingTasks();
            exchange();
        }
    }

    private void exchange() {
        boolean moved = true;
        while (moved) {
            moved = move(client, server) | move(server, client);
        }
    }

    private static boolean move(EmbeddedChannel from, EmbeddedChannel to) {
        boolean moved = false;
        for (Object msg; (msg = from.readOutbound()) != null; ) {
            to.writeInbound(msg);
            moved = true;
        }
        to.runPendingTasks();
        return moved;
    }

    /**
     * Stands for the connection's socket: while it is not writable it takes nothing, so writes stay incomplete, as they
     * do behind a client that is not reading.
     */
    private static final class Socket extends ChannelOutboundHandlerAdapter {
        private final Queue<Object> heldMessages = new ArrayDeque<>();
        private final Queue<ChannelPromise> heldPromises = new ArrayDeque<>();
        private ChannelHandlerContext ctx;
        private boolean holding;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (holding) {
                heldMessages.add(msg);
                heldPromises.add(promise);
            } else {
                ctx.write(msg, promise);
            }
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            if (!holding) {
                ctx.flush();
            }
        }

        void hold(boolean hold) {
            holding = hold;
            if (!hold) {
                while (!heldMessages.isEmpty()) {
                    ctx.write(heldMessages.poll(), heldPromises.poll());
                }
                ctx.flush();
            }
        }
    }

    /**
     * Answers every request with a body the size of a few frames.
     */
    private static final class Responder extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof Http2HeadersFrame) {
                    Http2HeadersFrame request = (Http2HeadersFrame) msg;
                    ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200")).stream(request.stream()));
                    ctx.write(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(new byte[RESPONSE_BYTES]), true).stream(request.stream()));
                    ctx.flush();
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    private static final class Client extends Http2ChannelDuplexHandler {
        private ChannelHandlerContext ctx;
        private Long resetErrorCode;
        private int dataBytes;
        private boolean endStream;

        @Override
        protected void handlerAdded0(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        void request() {
            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().method("GET").scheme("http").authority("localhost").path("/"), true).stream(newStream()));
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof Http2DataFrame) {
                    dataBytes += ((Http2DataFrame) msg).content().readableBytes();
                    endStream |= ((Http2DataFrame) msg).isEndStream();
                } else if (msg instanceof Http2ResetFrame) {
                    resetErrorCode = ((Http2ResetFrame) msg).errorCode();
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
    }
}
