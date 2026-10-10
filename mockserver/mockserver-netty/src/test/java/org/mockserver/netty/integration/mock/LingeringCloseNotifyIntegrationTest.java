package org.mockserver.netty.integration.mock;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslCloseCompletionEvent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A connection ended with a lingering close over TLS must send a {@code close_notify} before its output ends. The JDK's
 * {@code SSLSocket} accepts an end of stream without one, so this uses a Netty {@code SslHandler}, which reports a
 * {@code close_notify} as {@link SslCloseCompletionEvent#SUCCESS} and an end of stream without one as a failure.
 */
public class LingeringCloseNotifyIntegrationTest {

    private static final int MAX_REQUEST_BODY_SIZE = 64 * 1024;
    private static final long WAIT_SECONDS = 30;

    private static ClientAndServer mockServerClient;
    private static EventLoopGroup clientGroup;
    private static SslContext clientSslContext;

    @BeforeClass
    public static void startServerAndClient() throws Exception {
        mockServerClient = startClientAndServer(configuration().maxRequestBodySize(MAX_REQUEST_BODY_SIZE));
        mockServerClient
            .when(request()
                .withMethod("POST")
                .withPath("/early")
                .withRespondBeforeBody(true)
            )
            .respond(response()
                .withStatusCode(403)
                .withBody("forbidden")
            );
        clientGroup = new NioEventLoopGroup(1);
        clientSslContext = SslContextBuilder.forClient()
            .sslProvider(SslProvider.JDK)
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .build();
    }

    @AfterClass
    public static void stopServerAndClient() {
        stopQuietly(mockServerClient);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void shouldSendCloseNotifyAfterTooLargeForConnectionCloseRequest() throws Exception {
        TlsExchange exchange = send("POST /upload HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: 20000000\r\n\r\n");

        assertThat(exchange.received(), startsWith("HTTP/1.1 413 "));
        assertThat(exchange.received().toLowerCase(), containsString("connection: close"));
        assertThat("close_notify received before the end of stream: " + exchange.closeEvent, exchange.closeEvent.isSuccess(), is(true));
    }

    @Test
    public void shouldSendCloseNotifyAfterEarlyResponse() throws Exception {
        TlsExchange exchange = send("POST /early HTTP/1.1\r\nHost: localhost\r\nContent-Length: 20000000\r\n\r\n");

        assertThat(exchange.received(), startsWith("HTTP/1.1 403 "));
        assertThat(exchange.received(), containsString("forbidden"));
        assertThat("close_notify received before the end of stream: " + exchange.closeEvent, exchange.closeEvent.isSuccess(), is(true));
    }

    /**
     * Sends a request head and the first part of its body once the handshake completes, then waits for the server to
     * end the TLS session, by a {@code close_notify} or by ending the connection without one.
     */
    private TlsExchange send(String requestHead) throws Exception {
        TlsExchange exchange = new TlsExchange();
        byte[] head = requestHead.getBytes(StandardCharsets.US_ASCII);
        Channel channel = new Bootstrap()
            .group(clientGroup)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(clientSslContext.newHandler(ch.alloc(), "localhost", mockServerClient.getPort()));
                    ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                        @Override
                        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                            if (evt instanceof SslHandshakeCompletionEvent) {
                                if (((SslHandshakeCompletionEvent) evt).isSuccess()) {
                                    ctx.write(Unpooled.wrappedBuffer(head));
                                    ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[16 * 1024]));
                                } else {
                                    exchange.closed.completeExceptionally(((SslHandshakeCompletionEvent) evt).cause());
                                }
                            } else if (evt instanceof SslCloseCompletionEvent) {
                                exchange.closed.complete((SslCloseCompletionEvent) evt);
                            }
                            ctx.fireUserEventTriggered(evt);
                        }

                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                            exchange.append(msg.toString(StandardCharsets.UTF_8));
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            ctx.close();
                        }
                    });
                }
            })
            .connect("localhost", mockServerClient.getPort())
            .sync()
            .channel();
        try {
            exchange.closeEvent = exchange.closed.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            channel.close().await(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        return exchange;
    }

    private static final class TlsExchange {
        private final StringBuffer received = new StringBuffer();
        private final CompletableFuture<SslCloseCompletionEvent> closed = new CompletableFuture<>();
        private SslCloseCompletionEvent closeEvent;

        void append(String text) {
            received.append(text);
        }

        String received() {
            return received.toString();
        }
    }
}
