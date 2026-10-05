package org.mockserver.netty.integration;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2SettingsFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.util.ReferenceCountUtil;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.netty.MockServerCaTrustTestSupport.caCertificate;

/**
 * An HTTP/2 connection to MockServer that sends the header lists it is given, whatever limit the server advertises,
 * reads response headers of any size, and reports the server's SETTINGS, each stream's response and reset, and the
 * connection's GOAWAY.
 */
public final class Http2TestClient implements AutoCloseable {

    private static final long WAIT_SECONDS = 15;

    private final Channel connection;
    private final CompletableFuture<Http2Settings> serverSettings = new CompletableFuture<>();
    private final CompletableFuture<Long> goAway = new CompletableFuture<>();

    private Http2TestClient(Channel connection) {
        this.connection = connection;
    }

    /**
     * Cleartext HTTP/2 with prior knowledge.
     */
    public static Http2TestClient h2c(EventLoopGroup group, int port) throws Exception {
        Channel channel = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    // filled in once the client exists
                }
            })
            .connect("127.0.0.1", port).sync().channel();
        Http2TestClient client = new Http2TestClient(channel);
        client.addHttp2Handlers();
        return client;
    }

    /**
     * HTTP/2 negotiated by ALPN on a TLS connection made straight to MockServer.
     */
    public static Http2TestClient tls(EventLoopGroup group, int port) throws Exception {
        Channel channel = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    // filled in once the client exists
                }
            })
            .connect("127.0.0.1", port).sync().channel();
        return overTls(channel, "localhost", port);
    }

    /**
     * HTTP/2 through MockServer's CONNECT proxy: CONNECT over HTTP/1.1, then TLS with ALPN {@code h2}.
     */
    public static Http2TestClient throughConnect(EventLoopGroup group, int port, String targetHost, int targetPort) throws Exception {
        CompletableFuture<Integer> connectStatus = new CompletableFuture<>();
        Channel channel = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast("http-codec", new HttpClientCodec());
                    ch.pipeline().addLast("http-aggregator", new HttpObjectAggregator(64 * 1024));
                    ch.pipeline().addLast("connect-response", new SimpleChannelInboundHandler<FullHttpResponse>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
                            ctx.pipeline().remove("http-codec");
                            ctx.pipeline().remove("http-aggregator");
                            ctx.pipeline().remove(this);
                            connectStatus.complete(response.status().code());
                        }
                    });
                }
            })
            .connect("127.0.0.1", port).sync().channel();
        String target = targetHost + ":" + targetPort;
        DefaultFullHttpRequest connectRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, target);
        connectRequest.headers().set(HttpHeaderNames.HOST, target);
        channel.writeAndFlush(connectRequest);
        assertThat(connectStatus.get(WAIT_SECONDS, TimeUnit.SECONDS), is(200));
        return overTls(channel, targetHost, targetPort);
    }

    private static Http2TestClient overTls(Channel channel, String host, int port) throws Exception {
        SslContext sslContext = SslContextBuilder.forClient()
            .trustManager(caCertificate())
            .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            .applicationProtocolConfig(new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                ApplicationProtocolNames.HTTP_2))
            .build();
        SslHandler sslHandler = sslContext.newHandler(channel.alloc(), host, port);
        Http2TestClient client = new Http2TestClient(channel);
        // h2 is the only protocol offered, and the preface waits in the TLS handler until the handshake is done
        channel.pipeline().addLast(sslHandler);
        client.addHttp2Handlers();
        sslHandler.handshakeFuture().sync();
        assertThat(sslHandler.applicationProtocol(), is(ApplicationProtocolNames.HTTP_2));
        return client;
    }

    private void addHttp2Handlers() {
        connection.pipeline().addLast(Http2FrameCodecBuilder.forClient()
            // or the encoder would apply the server's limit itself, and an over-limit request would never be sent
            .encoderIgnoreMaxHeaderListSize(true)
            // response headers of any size are read
            .initialSettings(Http2Settings.defaultSettings().maxHeaderListSize(Http2CodecUtil.MAX_HEADER_LIST_SIZE))
            .build());
        connection.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel pushedStream) {
                // MockServer does not push
            }
        }));
        connection.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                try {
                    if (msg instanceof Http2SettingsFrame) {
                        serverSettings.complete(((Http2SettingsFrame) msg).settings());
                    } else if (msg instanceof Http2GoAwayFrame) {
                        goAway.complete(((Http2GoAwayFrame) msg).errorCode());
                    }
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                // a connection the server closes is observed through closed() and goAwayErrorCode()
            }
        });
    }

    /**
     * @return the first SETTINGS frame the server sent
     */
    public Http2Settings serverSettings() throws Exception {
        return serverSettings.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * @return the error code of the GOAWAY the server sent
     */
    public long goAwayErrorCode() throws Exception {
        return goAway.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    public boolean closedWithin(long seconds) throws InterruptedException {
        return connection.closeFuture().await(seconds, TimeUnit.SECONDS);
    }

    public boolean isOpen() {
        return connection.isActive();
    }

    /**
     * Sends a request's headers on a new stream, with END_STREAM unless a request body is to follow. A stream left
     * open is how a reset sent after the response is observed: one for a stream both sides have ended is ignored.
     */
    public Exchange send(Http2Headers headers, boolean endStream) throws Exception {
        Exchange exchange = new Exchange();
        Http2StreamChannel stream = new Http2StreamChannelBootstrap(connection)
            .handler(new ChannelInboundHandlerAdapter() {
                private final ByteArrayOutputStream body = new ByteArrayOutputStream();

                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    try {
                        if (msg instanceof Http2HeadersFrame) {
                            Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
                            if (headersFrame.headers().status() != null) {
                                exchange.headers.complete(headersFrame.headers());
                                exchange.status.complete(Integer.parseInt(headersFrame.headers().status().toString()));
                            }
                            if (headersFrame.isEndStream()) {
                                exchange.body.complete(body.toString(StandardCharsets.UTF_8));
                            }
                        } else if (msg instanceof Http2DataFrame) {
                            Http2DataFrame data = (Http2DataFrame) msg;
                            body.writeBytes(ByteBufUtil.getBytes(data.content()));
                            if (data.isEndStream()) {
                                exchange.body.complete(body.toString(StandardCharsets.UTF_8));
                            }
                        } else if (msg instanceof Http2ResetFrame) {
                            reset(((Http2ResetFrame) msg).errorCode());
                        }
                    } finally {
                        ReferenceCountUtil.release(msg);
                    }
                }

                @Override
                public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                    if (evt instanceof Http2ResetFrame) {
                        reset(((Http2ResetFrame) evt).errorCode());
                    }
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) {
                    IllegalStateException closed = new IllegalStateException("stream closed");
                    exchange.headers.completeExceptionally(closed);
                    exchange.status.completeExceptionally(closed);
                    exchange.body.completeExceptionally(closed);
                    exchange.reset.completeExceptionally(closed);
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    exchange.headers.completeExceptionally(cause);
                    exchange.status.completeExceptionally(cause);
                    exchange.body.completeExceptionally(cause);
                }

                private void reset(long errorCode) {
                    exchange.reset.complete(errorCode);
                    IllegalStateException reset = new IllegalStateException("stream reset with error code " + errorCode);
                    exchange.headers.completeExceptionally(reset);
                    exchange.status.completeExceptionally(reset);
                    exchange.body.completeExceptionally(reset);
                }
            })
            .open().sync().getNow();
        stream.writeAndFlush(new DefaultHttp2HeadersFrame(headers, endStream)).sync();
        return exchange;
    }

    @Override
    public void close() {
        connection.close().syncUninterruptibly();
    }

    public static final class Exchange {
        private final CompletableFuture<Http2Headers> headers = new CompletableFuture<>();
        private final CompletableFuture<Integer> status = new CompletableFuture<>();
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final CompletableFuture<Long> reset = new CompletableFuture<>();

        /**
         * @return the value of a response header, or null if the response has none of that name
         */
        public String header(String name) throws Exception {
            CharSequence value = headers.get(WAIT_SECONDS, TimeUnit.SECONDS).get(name);
            return value != null ? value.toString() : null;
        }

        public int status() throws Exception {
            return status.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        public String body() throws Exception {
            return body.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        public long resetErrorCode() throws Exception {
            return reset.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
