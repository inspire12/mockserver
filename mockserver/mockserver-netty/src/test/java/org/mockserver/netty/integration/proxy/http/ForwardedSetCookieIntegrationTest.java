package org.mockserver.netty.integration.proxy.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ReferenceCountUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpForward;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A forwarded response keeps its body and its {@code Set-Cookie} headers when one of them is not a cookie Netty can
 * decode (no name, or no {@code =}), over an HTTP/1.1 and an HTTP/2 upstream. A client (a raw socket) sends plain
 * HTTP/1.1 to MockServer, which forwards to upstreams on 127.0.0.1.
 */
public class ForwardedSetCookieIntegrationTest {

    // each is a response's Set-Cookie values, in the order the upstream sends them
    private static final List<List<String>> SET_COOKIE_HEADERS = Arrays.asList(
        Arrays.asList("aaaa"),
        Arrays.asList("flag; Path=/; HttpOnly"),
        Arrays.asList("=novalue"),
        Arrays.asList("flag; Path=/; HttpOnly", "session=abc; Path=/"),
        Arrays.asList("session=abc; Path=/", "=novalue")
    );

    private static EventLoopGroup upstreamGroup;
    private static Channel http1Upstream;
    private static Channel http2Upstream;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServers() throws Exception {
        upstreamGroup = new NioEventLoopGroup(2);
        http1Upstream = new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HttpRequestDecoder());
                    ch.pipeline().addLast(new HttpObjectAggregator(1024 * 1024));
                    ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                            StringBuilder response = new StringBuilder("HTTP/1.1 200 OK\r\ncontent-length: 2\r\n");
                            for (String setCookie : SET_COOKIE_HEADERS.get(caseIn(request.uri()))) {
                                response.append("set-cookie: ").append(setCookie).append("\r\n");
                            }
                            ctx.writeAndFlush(Unpooled.copiedBuffer(response + "\r\nok", StandardCharsets.ISO_8859_1));
                        }
                    });
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        SelfSignedCertificate certificate = new SelfSignedCertificate();
        SslContext sslContext = SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey())
            .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            .applicationProtocolConfig(new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                ApplicationProtocolNames.HTTP_2))
            .build();
        http2Upstream = new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
                    ch.pipeline().addLast(new ApplicationProtocolNegotiationHandler("") {
                        @Override
                        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                            ctx.pipeline().addLast(Http2FrameCodecBuilder.forServer().build());
                            ctx.pipeline().addLast(new Http2MultiplexHandler(new ChannelInboundHandlerAdapter() {
                                @Override
                                public boolean isSharable() {
                                    return true;
                                }

                                @Override
                                public void channelRead(ChannelHandlerContext stream, Object msg) {
                                    if (msg instanceof Http2HeadersFrame) {
                                        Http2Headers headers = new DefaultHttp2Headers().status("200");
                                        for (String setCookie : SET_COOKIE_HEADERS.get(caseIn(((Http2HeadersFrame) msg).headers().path().toString()))) {
                                            headers.add("set-cookie", setCookie);
                                        }
                                        stream.write(new DefaultHttp2HeadersFrame(headers, false));
                                        stream.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.copiedBuffer("ok", StandardCharsets.UTF_8), true));
                                    }
                                    ReferenceCountUtil.release(msg);
                                }
                            }));
                        }
                    });
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();

        mockServer = new MockServer(configuration().forwardProxyHttp2Upgrade(true), 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        mockServerClient.when(request().withPath("/http1/.*")).forward(forward().withHost("127.0.0.1").withPort(port(http1Upstream)));
        mockServerClient.when(request().withPath("/http2/.*")).forward(forward().withHost("127.0.0.1").withPort(port(http2Upstream)).withScheme(HttpForward.Scheme.HTTPS));
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        for (Channel upstream : Arrays.asList(http1Upstream, http2Upstream)) {
            if (upstream != null) {
                upstream.close();
            }
        }
        if (upstreamGroup != null) {
            upstreamGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Test
    public void shouldRelayTheBodyAndEverySetCookieHeaderFromAnHttp1Upstream() throws Exception {
        assertThat(notRelayedAsReceived("/http1/"), is(empty()));
    }

    @Test
    public void shouldRelayTheBodyAndEverySetCookieHeaderFromAnHttp2Upstream() throws Exception {
        assertThat(notRelayedAsReceived("/http2/"), is(empty()));
    }

    /**
     * @return a line for each case whose response did not reach the client with status 200, the body and the
     * upstream's Set-Cookie headers in order
     */
    private static List<String> notRelayedAsReceived(String pathPrefix) throws Exception {
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < SET_COOKIE_HEADERS.size(); i++) {
            String response = get(pathPrefix + i);
            int endOfHead = response.indexOf("\r\n\r\n");
            List<String> relayed = new ArrayList<>();
            for (String line : response.substring(0, Math.max(endOfHead, 0)).split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("set-cookie:")) {
                    relayed.add(line.substring("set-cookie:".length()).trim());
                }
            }
            if (!response.startsWith("HTTP/1.1 200 ") || endOfHead < 0 || !response.substring(endOfHead + 4).equals("ok") || !relayed.equals(SET_COOKIE_HEADERS.get(i))) {
                failures.add(SET_COOKIE_HEADERS.get(i) + " answered " + response.replace("\r\n", "|"));
            }
        }
        return failures;
    }

    private static String get(String path) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort())) {
            socket.setSoTimeout(30_000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + mockServer.getLocalPort() + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[16 * 1024];
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                received.write(buffer, 0, read);
            }
            return received.toString(StandardCharsets.ISO_8859_1.name());
        }
    }

    private static int caseIn(String path) {
        return Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
    }

    private static int port(Channel listener) {
        return ((InetSocketAddress) listener.localAddress()).getPort();
    }
}
