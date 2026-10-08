package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2ChannelDuplexHandler;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapterBuilder;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.LogEntrySerializer;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * A relayed response whose write back to the client fails while the client's connection is still open is logged at
 * ERROR; its Netty text lists every header, so the credentials must be masked when redactSecretsInLog is on. The first
 * failure ends the relay: both legs are asked to close, the loopback stops reading, and the writes failing after it are
 * not logged. A write that fails because the client's connection has closed ends the relay without a log entry.
 */
public class DownstreamProxyRelayHandlerWriteFailureTest {

    private static final String SESSION = "SESSION-COOKIE-SECRET-123";
    private static final String BEARER = "BEARER-TOKEN-SECRET-456";

    private static List<LogEntry> relayWithFailingWrite(HttpObject message) {
        List<LogEntry> logged = new CopyOnWriteArrayList<>();
        MockServerLogger logger = new MockServerLogger(DownstreamProxyRelayHandlerWriteFailureTest.class) {
            @Override
            public void logEvent(LogEntry logEntry) {
                logged.add(logEntry);
            }
        };
        EmbeddedChannel upstream = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                promise.setFailure(new IOException("Broken pipe"));
            }
        });
        EmbeddedChannel downstream = new EmbeddedChannel(new DownstreamProxyRelayHandler(logger, upstream));
        downstream.writeInbound(message);
        upstream.runPendingTasks();
        downstream.runPendingTasks();
        assertThat("the failed upstream channel is closed", upstream.isOpen(), is(false));
        downstream.finishAndReleaseAll();
        return logged;
    }

    @Test
    public void shouldMaskTheRelayedResponseHeadersWhenTheWriteFails() {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer("body", StandardCharsets.UTF_8));
        response.headers().add("Set-Cookie", "session=" + SESSION + "; Path=/").add("Authorization", "Bearer " + BEARER);

        List<LogEntry> logged = relayWithFailingWrite(response);

        assertThat(response.refCnt(), is(0));
        assertThat(logged, hasSize(1));
        LogEntry entry = logged.get(0);
        for (String output : new String[]{
            entry.getMessage(configuration().redactSecretsInLog(true)),
            entry.getCompactMessage(configuration().redactSecretsInLog(true)),
            new LogEntrySerializer(new MockServerLogger(), configuration().redactSecretsInLog(true)).serialize(entry)
        }) {
            assertThat(output, containsString("exception while returning writing"));
            assertThat(output, not(containsString(SESSION)));
            assertThat(output, not(containsString(BEARER)));
        }
        assertThat("unchanged with redaction off", entry.getMessage(configuration().redactSecretsInLog(false)), containsString(BEARER));
    }

    @Test
    public void shouldMaskARelayedBodyChunkWhole() {
        DefaultHttpContent chunk = new DefaultHttpContent(Unpooled.copiedBuffer("chunk", StandardCharsets.UTF_8));

        List<LogEntry> logged = relayWithFailingWrite(chunk);

        assertThat(chunk.refCnt(), is(0));
        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getMessage(configuration().redactSecretsInLog(true)), containsString("***REDACTED***"));
        assertThat(logged.get(0).getMessage(configuration().redactSecretsInLog(false)), containsString("DefaultHttpContent"));
    }

    @Test
    public void shouldEndTheRelayAndLogOnceWhenTheProxyClientRefusesWritesWhileStayingOpen() {
        List<LogEntry> logged = new CopyOnWriteArrayList<>();
        MockServerLogger logger = new MockServerLogger(DownstreamProxyRelayHandlerWriteFailureTest.class) {
            @Override
            public void logEvent(LogEntry logEntry) {
                logged.add(logEntry);
            }
        };
        List<ChannelPromise> pendingWrites = new ArrayList<>();
        AtomicInteger closesAsked = new AtomicInteger();
        // as a TLS handler whose close_notify waits behind bytes its client has not taken: open, refusing every write
        EmbeddedChannel proxyClient = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                pendingWrites.add(promise);
            }

            @Override
            public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
                closesAsked.incrementAndGet();
            }
        });
        EmbeddedChannel loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(logger, proxyClient));
        List<HttpContent> chunks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            DefaultHttpContent chunk = new DefaultHttpContent(Unpooled.copiedBuffer("chunk " + i, StandardCharsets.UTF_8));
            chunks.add(chunk);
            loopback.writeInbound(chunk);
        }
        assertThat(pendingWrites, hasSize(50));

        // closing the loopback asks the proxy client to flush, which adds a write of its own
        for (ChannelPromise write : new ArrayList<>(pendingWrites)) {
            write.setFailure(new SSLException("SSLEngine closed already"));
        }

        assertThat("one failed write logged, not one per chunk", logged, hasSize(1));
        assertThat(logged.get(0).getMessage(configuration().redactSecretsInLog(false)), containsString("exception while returning writing"));
        assertThat("the loopback was closed", loopback.isOpen(), is(false));
        assertThat("the loopback stopped reading", loopback.config().isAutoRead(), is(false));
        assertThat("the proxy client was asked to close", closesAsked.get(), greaterThan(0));
        for (HttpContent chunk : chunks) {
            assertThat(chunk.refCnt(), is(0));
        }
        loopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    @Test
    public void shouldLeaveTheRelayOpenWhenOnlyOneHttp2StreamOfTheProxyClientHasGone() {
        List<LogEntry> logged = new CopyOnWriteArrayList<>();
        MockServerLogger logger = new MockServerLogger(DownstreamProxyRelayHandlerWriteFailureTest.class) {
            @Override
            public void logEvent(LogEntry logEntry) {
                logged.add(logEntry);
            }
        };
        List<Object> written = new ArrayList<>();
        // as HttpToHttp2ConnectionHandler fails the queued DATA of a stream reset while it waited for window
        EmbeddedChannel proxyClient = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                Integer streamId = msg instanceof HttpMessage ? ((HttpMessage) msg).headers().getInt(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text()) : null;
                if (streamId != null && streamId == 3) {
                    ReferenceCountUtil.release(msg);
                    promise.setFailure(Http2Exception.streamError(3, Http2Error.STREAM_CLOSED, "Stream closed before write could take place"));
                } else {
                    written.add(msg);
                    promise.setSuccess();
                }
            }
        });
        EmbeddedChannel loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(logger, proxyClient));

        loopback.writeInbound(responseOnStream(3));
        loopback.writeInbound(responseOnStream(5));

        assertThat("nothing logged above DEBUG", logged.stream().filter(entry -> entry.getLogLevel().toInt() > Level.DEBUG.toInt()).count(), is(0L));
        assertThat("the loopback is still open", loopback.isOpen(), is(true));
        assertThat("the loopback is still reading", loopback.config().isAutoRead(), is(true));
        assertThat("the proxy client is still open", proxyClient.isOpen(), is(true));
        assertThat("the other stream's response was relayed", written, hasSize(1));
        written.forEach(ReferenceCountUtil::release);
        loopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    @Test
    public void shouldEndTheRelayWhenAStreamFailureHasNoStreamOfItsOwn() {
        EmbeddedChannel proxyClient = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                promise.setFailure(Http2Exception.streamError(3, Http2Error.STREAM_CLOSED, "stream closed"));
            }
        });
        EmbeddedChannel loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(new MockServerLogger(), proxyClient));

        // an HTTP/1.1 response carries no stream id, so the failure cannot be one stream's
        loopback.writeInbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer("body", StandardCharsets.UTF_8)));

        assertThat(loopback.isOpen(), is(false));
        assertThat(proxyClient.isOpen(), is(false));
        loopback.finishAndReleaseAll();
    }

    @Test
    public void shouldEndTheRelayWithoutLoggingWhenTheProxyClientsConnectionClosesWithResponseDataQueued() {
        List<LogEntry> logged = new CopyOnWriteArrayList<>();
        MockServerLogger logger = new MockServerLogger(DownstreamProxyRelayHandlerWriteFailureTest.class) {
            @Override
            public void logEvent(LogEntry logEntry) {
                logged.add(logEntry);
            }
        };
        Http2ProxyClient proxyClient = new Http2ProxyClient();
        EmbeddedChannel loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(logger, proxyClient.channel));
        proxyClient.channel.pipeline().addLast(new UpstreamProxyRelayHandler(logger, proxyClient.channel, loopback, "localhost", 80, 1024));
        int streamId = proxyClient.request();
        loopback.writeInbound(responseOnStream(streamId, 16 * Http2ProxyClient.STREAM_WINDOW));
        proxyClient.exchange();
        assertThat("the rest of the response waits for window", proxyClient.client.dataBytes, is(Http2ProxyClient.STREAM_WINDOW));

        // the socket closing fails the queued data with the stream's own error, not a closed-channel one
        proxyClient.channel.unsafe().close(proxyClient.channel.unsafe().voidPromise());
        proxyClient.channel.runPendingTasks();

        assertThat("nothing logged above DEBUG", logged.stream().filter(entry -> entry.getLogLevel().toInt() > Level.DEBUG.toInt()).count(), is(0L));
        assertThat("the loopback was closed, by the client's leg", loopback.isOpen(), is(false));
        loopback.finishAndReleaseAll();
        proxyClient.release();
    }

    @Test
    public void shouldEndTheRelayWhenAStreamFailsOnAProxyClientWhoseConnectionHasClosed() {
        List<LogEntry> logged = new CopyOnWriteArrayList<>();
        MockServerLogger logger = new MockServerLogger(DownstreamProxyRelayHandlerWriteFailureTest.class) {
            @Override
            public void logEvent(LogEntry logEntry) {
                logged.add(logEntry);
            }
        };
        EmbeddedChannel proxyClient = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                ctx.channel().unsafe().close(ctx.channel().unsafe().voidPromise());
                promise.setFailure(Http2Exception.streamError(3, Http2Error.STREAM_CLOSED, "Stream closed before write could take place"));
            }
        });
        EmbeddedChannel loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(logger, proxyClient));
        proxyClient.pipeline().addLast(new UpstreamProxyRelayHandler(logger, proxyClient, loopback, "localhost", 80, 1024));

        // one stream's failure by its cause and stream id, but every stream of a closed connection fails this way
        loopback.writeInbound(responseOnStream(3));
        proxyClient.runPendingTasks();

        assertThat("the loopback was closed, by the client's leg", loopback.isOpen(), is(false));
        assertThat("nothing logged above DEBUG", logged.stream().filter(entry -> entry.getLogLevel().toInt() > Level.DEBUG.toInt()).count(), is(0L));
        loopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    @Test
    public void shouldLeaveTheLoopbackOpenForARequestStillBeingWrittenWhenAWriteFailsToAProxyClientThatHasGone() {
        List<LogEntry> logged = new CopyOnWriteArrayList<>();
        MockServerLogger logger = new MockServerLogger(DownstreamProxyRelayHandlerWriteFailureTest.class) {
            @Override
            public void logEvent(LogEntry logEntry) {
                logged.add(logEntry);
            }
        };
        EmbeddedChannel proxyClient = new EmbeddedChannel();
        SlowSocket loopbackSocket = new SlowSocket();
        EmbeddedChannel loopback = new EmbeddedChannel(loopbackSocket, new DownstreamProxyRelayHandler(logger, proxyClient));
        proxyClient.pipeline().addLast(new UpstreamProxyRelayHandler(logger, proxyClient, loopback, "localhost", 80, 1024));
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/pipelined", Unpooled.copiedBuffer("upload", StandardCharsets.UTF_8));
        proxyClient.writeInbound(request);
        assertThat("the request is still being written", loopbackSocket.messages, hasItem(sameInstance(request)));
        proxyClient.close();
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer("body", StandardCharsets.UTF_8));

        loopback.writeInbound(response);

        assertThat(response.refCnt(), is(0));
        assertThat("left open for the request still being written", loopback.isOpen(), is(true));
        assertThat("and still read", loopback.config().isAutoRead(), is(true));

        loopbackSocket.drain();
        loopback.runPendingTasks();

        assertThat("the request was written", new ArrayList<>(loopback.outboundMessages()), hasItem(sameInstance(request)));
        assertThat("and the loopback then closed", loopback.isOpen(), is(false));
        // closed through its pipeline once flushed, an HTTP/1.1 loopback would reset MockServer's end before it had read
        // the request, if a response were then unread
        assertThat("by ending its output, not through its pipeline", loopbackSocket.closesThroughThePipeline, is(0));
        assertThat("nothing logged above DEBUG", logged.stream().filter(entry -> entry.getLogLevel().toInt() > Level.DEBUG.toInt()).count(), is(0L));
        loopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    @Test
    public void shouldLeaveTheLoopbackToTheClientsLegWhenAWriteFailsToAProxyClientThatHasGoneWithNoRequestBeingWritten() {
        List<LogEntry> logged = new CopyOnWriteArrayList<>();
        MockServerLogger logger = new MockServerLogger(DownstreamProxyRelayHandlerWriteFailureTest.class) {
            @Override
            public void logEvent(LogEntry logEntry) {
                logged.add(logEntry);
            }
        };
        AtomicBoolean socketClosed = new AtomicBoolean();
        // a socket that has closed, whose channelInactive has not yet run: writes to it fail
        EmbeddedChannel proxyClient = new EmbeddedChannel() {
            @Override
            public boolean isActive() {
                return !socketClosed.get() && super.isActive();
            }
        };
        proxyClient.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (socketClosed.get()) {
                    ReferenceCountUtil.release(msg);
                    promise.setFailure(new ClosedChannelException());
                } else {
                    ctx.write(msg, promise);
                }
            }
        });
        EmbeddedChannel loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(logger, proxyClient));
        proxyClient.pipeline().addLast(new UpstreamProxyRelayHandler(logger, proxyClient, loopback, "localhost", 80, 1024));
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/pipelined", Unpooled.copiedBuffer("upload", StandardCharsets.UTF_8));
        proxyClient.writeInbound(request);
        assertThat("the request was written", new ArrayList<>(loopback.outboundMessages()), hasItem(sameInstance(request)));
        socketClosed.set(true);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer("body", StandardCharsets.UTF_8));

        loopback.writeInbound(response);

        assertThat(response.refCnt(), is(0));
        // MockServer may not yet have read the request, which a close with the response unread would reset
        assertThat("left open for the client's leg to end", loopback.isOpen(), is(true));
        assertThat("and still read", loopback.config().isAutoRead(), is(true));

        socketClosed.set(false);
        proxyClient.unsafe().close(proxyClient.unsafe().voidPromise());
        proxyClient.runPendingTasks();

        assertThat("ended by the client's leg", loopback.isOpen(), is(false));
        assertThat("nothing logged above DEBUG", logged.stream().filter(entry -> entry.getLogLevel().toInt() > Level.DEBUG.toInt()).count(), is(0L));
        loopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    @Test
    public void shouldCloseTheLoopbackAtOnceWithARequestStillBeingWrittenWhenAWriteFailsToAnOpenProxyClient() {
        // every write fails, and a close is carried out: the client's leg was open when the write failed
        EmbeddedChannel proxyClient = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                promise.setFailure(new SSLException("SSLEngine closed already"));
            }
        });
        SlowSocket loopbackSocket = new SlowSocket();
        EmbeddedChannel loopback = new EmbeddedChannel(loopbackSocket, new DownstreamProxyRelayHandler(new MockServerLogger(), proxyClient));
        proxyClient.pipeline().addLast(new UpstreamProxyRelayHandler(new MockServerLogger(), proxyClient, loopback, "localhost", 80, 1024));
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/pipelined", Unpooled.copiedBuffer("upload", StandardCharsets.UTF_8));
        proxyClient.writeInbound(request);
        assertThat("the request is still being written", loopbackSocket.messages, hasItem(sameInstance(request)));

        loopback.writeInbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer("body", StandardCharsets.UTF_8)));

        assertThat("the proxy client was closed", proxyClient.isOpen(), is(false));
        assertThat("the loopback was closed at once", loopback.isOpen(), is(false));
        assertThat("the loopback stopped reading", loopback.config().isAutoRead(), is(false));
        loopbackSocket.drain();
        assertThat("the request was not written", new ArrayList<>(loopback.outboundMessages()), not(hasItem(sameInstance(request))));
        assertThat(request.refCnt(), is(0));
        loopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    /**
     * A socket that is taking nothing: every write waits, in order, until it drains.
     */
    private static final class SlowSocket extends ChannelOutboundHandlerAdapter {
        private final List<Object> messages = new ArrayList<>();
        private final List<ChannelPromise> promises = new ArrayList<>();
        private ChannelHandlerContext ctx;
        private boolean drained;
        private int closesThroughThePipeline;

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
            closesThroughThePipeline++;
            ctx.close(promise);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (drained) {
                ctx.write(msg, promise);
                return;
            }
            this.ctx = ctx;
            messages.add(msg);
            promises.add(promise);
        }

        void drain() {
            drained = true;
            for (int i = 0; i < messages.size(); i++) {
                ctx.write(messages.get(i), promises.get(i));
            }
            ctx.flush();
        }
    }

    private static FullHttpResponse responseOnStream(int streamId) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer("body " + streamId, StandardCharsets.UTF_8));
        response.headers().setInt(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(), streamId);
        return response;
    }

    private static FullHttpResponse responseOnStream(int streamId, int bodyBytes) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(new byte[bodyBytes]));
        response.headers().setInt(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(), streamId);
        return response;
    }

    /**
     * The relay's client-facing HTTP/2 leg, an {@code HttpToHttp2ConnectionHandler}, joined to a client that grants each
     * stream {@link #STREAM_WINDOW} bytes and no more, so the rest of a larger response waits in the flow controller.
     */
    private static final class Http2ProxyClient {
        private static final int STREAM_WINDOW = 1024;

        private final EmbeddedChannel channel;
        private final Client client = new Client();
        private final EmbeddedChannel clientChannel;

        Http2ProxyClient() {
            Http2Connection connection = new DefaultHttp2Connection(true);
            channel = new EmbeddedChannel(
                new HttpToHttp2ConnectionHandlerBuilder()
                    .connection(connection)
                    .frameListener(new InboundHttp2ToHttpAdapterBuilder(connection).maxContentLength(1024 * 1024).build())
                    .build(),
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ReferenceCountUtil.release(msg);
                    }
                }
            );
            clientChannel = new EmbeddedChannel(
                Http2FrameCodecBuilder.forClient().initialSettings(Http2Settings.defaultSettings().initialWindowSize(STREAM_WINDOW)).build(),
                client
            );
            clientChannel.flush();
            exchange();
        }

        int request() {
            Http2FrameStream stream = client.request();
            exchange();
            return stream.id();
        }

        void exchange() {
            boolean moved = true;
            while (moved) {
                moved = move(clientChannel, channel) | move(channel, clientChannel);
            }
        }

        private static boolean move(EmbeddedChannel from, EmbeddedChannel to) {
            boolean moved = false;
            for (Object msg; (msg = from.readOutbound()) != null; ) {
                if (to.isOpen()) {
                    to.writeInbound(msg);
                } else {
                    ReferenceCountUtil.release(msg);
                }
                moved = true;
            }
            to.runPendingTasks();
            return moved;
        }

        void release() {
            clientChannel.finishAndReleaseAll();
            channel.finishAndReleaseAll();
        }
    }

    private static final class Client extends Http2ChannelDuplexHandler {
        private ChannelHandlerContext ctx;
        private int dataBytes;

        @Override
        protected void handlerAdded0(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        Http2FrameStream request() {
            Http2FrameStream stream = newStream();
            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().method("GET").scheme("http").authority("localhost").path("/"), true).stream(stream));
            return stream;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof Http2DataFrame) {
                    dataBytes += ((Http2DataFrame) msg).content().readableBytes();
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
    }
}
