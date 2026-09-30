package org.mockserver.netty.integration.mock;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.*;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpWebSocketResponse.webSocketResponse;
import static org.mockserver.model.WebSocketMessage.webSocketMessage;
import static org.mockserver.model.WebSocketMessageMatcher.webSocketMessageMatcher;

/**
 * Delayed WebSocket bidi reply sets are bounded per connection by pausing reads, and globally by refusing a whole
 * set with close status 1013; an admitted set always arrives whole and in order.
 */
public class WebSocketReplyBackpressureIntegrationTest {

    private static NioEventLoopGroup clientGroup;

    @BeforeClass
    public static void createClientGroup() {
        clientGroup = new NioEventLoopGroup(1);
    }

    @AfterClass
    public static void stopClientGroup() {
        clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test(timeout = 90_000)
    public void shouldPauseReadingForAFloodingClientAndDeliverEveryReplySetWholeAndInOrder() throws Exception {
        MockServer mockServer = new MockServer(configuration(), 0);
        try {
            int port = mockServer.getLocalPort();
            Scheduler scheduler = mockServer.getScheduler();
            new MockServerClient("localhost", port)
                .when(request().withPath("/ws/flood"))
                .respondWithWebSocket(webSocketResponse().withMatchers(webSocketMessageMatcher()
                    .withTextRegex("ping.*")
                    .withResponses(
                        webSocketMessage("r1").withDelay(TimeUnit.MILLISECONDS, 200),
                        webSocketMessage("r2").withDelay(TimeUnit.MILLISECONDS, 200),
                        webSocketMessage("r3").withDelay(TimeUnit.MILLISECONDS, 200))));

            int frames = 2_000;
            // ~1 KiB frames, so one socket read decodes tens of frames, not thousands
            String padding = String.join("", Collections.nCopies(1_000, "x"));
            AtomicInteger maxPendingFrames = new AtomicInteger();
            AtomicBoolean sampling = new AtomicBoolean(true);
            Thread sampler = new Thread(() -> {
                while (sampling.get()) {
                    maxPendingFrames.accumulateAndGet(scheduler.getPendingWebSocketReplyFrameCount(), Math::max);
                    Thread.onSpinWait();
                }
            });
            sampler.start();
            try (WebSocketTestClient client = WebSocketTestClient.connect(port, "/ws/flood")) {
                for (int i = 0; i < frames; i++) {
                    client.send("ping" + padding);
                }
                client.awaitReceived(frames * 3, 60);

                List<String> received = client.received;
                int r1 = 0;
                int r2 = 0;
                int r3 = 0;
                for (String frame : received) {
                    switch (frame) {
                        case "r1" -> r1++;
                        case "r2" -> r2++;
                        case "r3" -> r3++;
                        default -> throw new AssertionError("unexpected frame " + frame);
                    }
                    // each set is written in order, so no prefix can hold more of a later frame than an earlier one
                    assertThat(r1, greaterThanOrEqualTo(r2));
                    assertThat(r2, greaterThanOrEqualTo(r3));
                }
                assertThat(r1, is(frames));
                assertThat(r2, is(frames));
                assertThat(r3, is(frames));
                assertThat(client.closeStatus.isDone(), is(false));
            } finally {
                sampling.set(false);
                sampler.join();
            }
            assertThat("reads were paused", scheduler.getWebSocketReadPauseCount(), greaterThanOrEqualTo(1L));
            // the pause threshold plus the frames decoded from the socket read that crossed it
            assertThat(maxPendingFrames.get(), lessThanOrEqualTo((128 + 128) * 3));
            assertThat(scheduler.getOverloadRejectionCount(Scheduler.OverloadReason.WEBSOCKET_REPLIES), is(0L));
        } finally {
            mockServer.stop();
        }
    }

    @Test(timeout = 60_000)
    public void shouldCloseWith1013WhenAWholeReplySetIsRefusedAndStillDeliverAdmittedSets() throws Exception {
        MockServer mockServer = new MockServer(configuration().maxPendingDelayedResponses(1), 0);
        try {
            int port = mockServer.getLocalPort();
            Scheduler scheduler = mockServer.getScheduler();
            new MockServerClient("localhost", port)
                .when(request().withPath("/ws/slow"))
                .respondWithWebSocket(webSocketResponse().withMatchers(webSocketMessageMatcher()
                    .withText("go")
                    .withResponses(
                        webSocketMessage("now"),
                        webSocketMessage("later").withDelay(TimeUnit.MILLISECONDS, 2_000))));

            try (WebSocketTestClient admitted = WebSocketTestClient.connect(port, "/ws/slow");
                 WebSocketTestClient refused = WebSocketTestClient.connect(port, "/ws/slow")) {
                admitted.send("go");
                admitted.awaitReceived(1, 10);

                refused.send("go");
                assertThat(refused.closeStatus.get(10, TimeUnit.SECONDS), is(WebSocketCloseStatus.TRY_AGAIN_LATER.code()));
                assertThat("no part of the refused set was sent", refused.received, empty());

                admitted.awaitReceived(2, 10);
                assertThat(admitted.received, contains("now", "later"));
            }
            assertThat(scheduler.getOverloadRejectionCount(Scheduler.OverloadReason.WEBSOCKET_REPLIES), is(1L));
        } finally {
            mockServer.stop();
        }
    }

    private static final class WebSocketTestClient implements AutoCloseable {
        private final List<String> received = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Integer> closeStatus = new CompletableFuture<>();
        private Channel channel;

        static WebSocketTestClient connect(int port, String path) throws Exception {
            WebSocketTestClient client = new WebSocketTestClient();
            CompletableFuture<Boolean> handshakeComplete = new CompletableFuture<>();
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                new URI("ws://localhost:" + port + path), WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), Integer.MAX_VALUE);
            client.channel = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpClientCodec(), new HttpObjectAggregator(1 << 20), new SimpleChannelInboundHandler<Object>() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                handshaker.handshake(ctx.channel());
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                                if (!handshaker.isHandshakeComplete()) {
                                    handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) msg);
                                    handshakeComplete.complete(true);
                                } else if (msg instanceof TextWebSocketFrame) {
                                    client.received.add(((TextWebSocketFrame) msg).text());
                                } else if (msg instanceof CloseWebSocketFrame) {
                                    client.closeStatus.complete(((CloseWebSocketFrame) msg).statusCode());
                                }
                            }
                        });
                    }
                })
                .connect("localhost", port).sync().channel();
            handshakeComplete.get(5, TimeUnit.SECONDS);
            return client;
        }

        void send(String text) {
            channel.writeAndFlush(new TextWebSocketFrame(text));
        }

        void awaitReceived(int count, int timeoutSeconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (received.size() < count && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertThat(received.size(), greaterThanOrEqualTo(count));
        }

        @Override
        public void close() throws Exception {
            channel.close().sync();
        }
    }
}
