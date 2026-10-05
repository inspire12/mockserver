package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.http3.Http3SettingsFrame;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.netty.MockServer;
import org.mockserver.testing.socket.Ipv4DatagramChannelFactory;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

/**
 * An HTTP/3 connection to MockServer that sends the field sections it is given, whatever limit the server
 * advertises, and reports the server's SETTINGS, each stream's response and how the server closed the connection.
 * Needs the QUIC native library.
 */
public final class Http3TestClient implements AutoCloseable {

    private static final long WAIT_SECONDS = 15;

    private final Channel datagramChannel;
    private final QuicChannel quicChannel;
    private final CompletableFuture<Http3SettingsFrame> serverSettings;
    private final CompletableFuture<QuicConnectionCloseEvent> closedByServer;

    private Http3TestClient(Channel datagramChannel, QuicChannel quicChannel, CompletableFuture<Http3SettingsFrame> serverSettings, CompletableFuture<QuicConnectionCloseEvent> closedByServer) {
        this.datagramChannel = datagramChannel;
        this.quicChannel = quicChannel;
        this.serverSettings = serverSettings;
        this.closedByServer = closedByServer;
    }

    public static Http3TestClient open(EventLoopGroup group, MockServer mockServer) throws Exception {
        assertThat("the HTTP/3 server started", mockServer.getHttp3Port(), greaterThan(0));
        QuicSslContext sslContext = QuicSslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .applicationProtocols(Http3.supportedApplicationProtocols())
            .build();
        Channel datagramChannel = new Bootstrap()
            .group(group)
            .channelFactory(Ipv4DatagramChannelFactory.INSTANCE)
            .handler(Http3.newQuicClientCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(30000, TimeUnit.MILLISECONDS)
                .initialMaxData(10000000)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .initialMaxStreamsBidirectional(100)
                .build())
            .bind(0)
            .sync()
            .channel();
        CompletableFuture<Http3SettingsFrame> serverSettings = new CompletableFuture<>();
        CompletableFuture<QuicConnectionCloseEvent> closedByServer = new CompletableFuture<>();
        ChannelInboundHandlerAdapter controlStreamHandler = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (msg instanceof Http3SettingsFrame) {
                    serverSettings.complete((Http3SettingsFrame) msg);
                }
                ReferenceCountUtil.release(msg);
            }

            @Override
            public boolean isSharable() {
                return true;
            }
        };
        QuicChannel quicChannel = QuicChannel.newBootstrap(datagramChannel)
            .handler(new ChannelInitializer<QuicChannel>() {
                @Override
                protected void initChannel(QuicChannel ch) {
                    ch.pipeline().addLast(new Http3ClientConnectionHandler(controlStreamHandler, null, null, null, true));
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                            if (evt instanceof QuicConnectionCloseEvent) {
                                closedByServer.complete((QuicConnectionCloseEvent) evt);
                            }
                            ctx.fireUserEventTriggered(evt);
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            // a connection the server closes is observed through closedByServer()
                        }
                    });
                }
            })
            .remoteAddress(new InetSocketAddress("127.0.0.1", mockServer.getHttp3Port()))
            .connect()
            .get(WAIT_SECONDS, TimeUnit.SECONDS);
        return new Http3TestClient(datagramChannel, quicChannel, serverSettings, closedByServer);
    }

    public int localPort() {
        return ((InetSocketAddress) datagramChannel.localAddress()).getPort();
    }

    public Http3SettingsFrame serverSettings() throws Exception {
        return serverSettings.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    public QuicConnectionCloseEvent closedByServer() throws Exception {
        return closedByServer.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Sends a request that is its header section alone.
     */
    public Exchange send(Http3Headers headers) throws Exception {
        Exchange exchange = new Exchange();
        QuicStreamChannel stream = requestStream(exchange);
        stream.writeAndFlush(new DefaultHttp3HeadersFrame(headers)).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        return exchange;
    }

    /**
     * Sends a request with a body and a trailer section: a second HEADERS frame, after the body.
     */
    public Exchange send(Http3Headers headers, String body, Http3Headers trailers) throws Exception {
        Exchange exchange = new Exchange();
        QuicStreamChannel stream = requestStream(exchange);
        stream.write(new DefaultHttp3HeadersFrame(headers));
        stream.write(new DefaultHttp3DataFrame(Unpooled.copiedBuffer(body, StandardCharsets.UTF_8)));
        stream.writeAndFlush(new DefaultHttp3HeadersFrame(trailers)).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        return exchange;
    }

    private QuicStreamChannel requestStream(Exchange exchange) throws Exception {
        return Http3.newRequestStream(quicChannel, new Http3RequestStreamInboundHandler() {
            private final ByteArrayOutputStream body = new ByteArrayOutputStream();

            @Override
            protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
                if (frame.headers().status() != null) {
                    exchange.status.complete(Integer.parseInt(frame.headers().status().toString()));
                }
            }

            @Override
            protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
                body.writeBytes(ByteBufUtil.getBytes(frame.content()));
                frame.release();
            }

            @Override
            protected void channelInputClosed(ChannelHandlerContext ctx) {
                exchange.body.complete(body.toString(StandardCharsets.UTF_8));
                ctx.close();
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                IllegalStateException closed = new IllegalStateException("stream closed without a response");
                exchange.status.completeExceptionally(closed);
                exchange.body.completeExceptionally(closed);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                exchange.status.completeExceptionally(cause);
                exchange.body.completeExceptionally(cause);
            }
        }).sync().getNow();
    }

    @Override
    public void close() {
        quicChannel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
        datagramChannel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
    }

    public static final class Exchange {
        private final CompletableFuture<Integer> status = new CompletableFuture<>();
        private final CompletableFuture<String> body = new CompletableFuture<>();

        public int status() throws Exception {
            return status.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        public String body() throws Exception {
            return body.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
