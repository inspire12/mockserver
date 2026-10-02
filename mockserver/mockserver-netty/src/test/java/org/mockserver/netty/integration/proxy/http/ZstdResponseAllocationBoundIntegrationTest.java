package org.mockserver.netty.integration.proxy.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpForward;
import org.mockserver.netty.MockServer;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.MockServerCaTrustTestSupport.caTrustingSslContext;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A {@code zstd} response cannot make MockServer allocate the content size its frame header declares, on every path
 * that decodes a response: the forward client over HTTP/1.1 and HTTP/2 (aggregated and streamed), and the CONNECT
 * relay's loopback over HTTP/1.1 and HTTP/2. The upstream is a plain Netty server, independent of MockServer.
 * <p>
 * The hostile body declares 2 GiB - 1 bytes. No JVM can allocate that as one array ("Requested array size exceeds VM
 * limit"), so a decoder that allocates the declared size fails here whatever the test JVM's heap size.
 */
public class ZstdResponseAllocationBoundIntegrationTest {

    // magic, frame header (8-byte content size, not single-segment), 1 KiB window, content size 2^31 - 1, one empty last raw block
    private static final byte[] DECLARES_TWO_GIB = {
        0x28, (byte) 0xB5, 0x2F, (byte) 0xFD,
        (byte) 0xC0,
        0x00,
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x00
    };
    // that frame, then a second holding an event: a streamed response that fails mid-body still ends as a 200 with an
    // empty body, so only the decoded event shows the first frame was decoded (a frame's own content must match its size)
    private static final byte[] EVENT = "data: hello\n\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DECLARES_TWO_GIB_THEN_AN_EVENT = concat(DECLARES_TWO_GIB, com.github.luben.zstd.Zstd.compress(EVENT, 3));
    private static final byte[] PLAIN = plain();
    private static final byte[] PLAIN_ZSTD = com.github.luben.zstd.Zstd.compress(PLAIN, 3);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Map<String, String> UPSTREAM_PROTOCOL = new ConcurrentHashMap<>();

    private static EventLoopGroup upstreamGroup;
    private static Channel plainUpstreamChannel;
    private static Channel tlsUpstreamChannel;
    private static int plainUpstreamPort;
    private static int tlsUpstreamPort;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static int mockServerPort;

    @BeforeClass
    public static void startServers() throws Exception {
        assertThat("zstd-jni reaches this module's classpath, as it does the shaded jar", Zstd.isAvailable(), is(true));
        assertThat(DECLARES_TWO_GIB.length, is(17));
        assertThat(com.github.luben.zstd.Zstd.getFrameContentSize(DECLARES_TWO_GIB), is((long) Integer.MAX_VALUE));

        upstreamGroup = new NioEventLoopGroup(2);
        ZstdUpstreamHandler handler = new ZstdUpstreamHandler();

        plainUpstreamChannel = new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(1024 * 1024), handler);
                }
            })
            .bind(0).sync().channel();
        plainUpstreamPort = ((InetSocketAddress) plainUpstreamChannel.localAddress()).getPort();

        SelfSignedCertificate certificate = new SelfSignedCertificate();
        SslContext tlsUpstreamSslContext = SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey())
            .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            .applicationProtocolConfig(new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                ApplicationProtocolNames.HTTP_2,
                ApplicationProtocolNames.HTTP_1_1))
            .build();
        tlsUpstreamChannel = new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(tlsUpstreamSslContext.newHandler(ch.alloc()));
                    ch.pipeline().addLast(new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
                        @Override
                        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                            if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                                ctx.pipeline().addLast(Http2FrameCodecBuilder.forServer().build());
                                ctx.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                    @Override
                                    protected void initChannel(Channel streamChannel) {
                                        streamChannel.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(true), new HttpObjectAggregator(1024 * 1024), handler);
                                    }
                                }));
                            } else {
                                ctx.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(1024 * 1024), handler);
                            }
                        }
                    });
                }
            })
            .bind(0).sync().channel();
        tlsUpstreamPort = ((InetSocketAddress) tlsUpstreamChannel.localAddress()).getPort();

        mockServer = new MockServer(configuration()
            .streamingResponsesEnabled(true)
            .forwardProxyHttp2Upgrade(true));
        mockServerPort = mockServer.getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (plainUpstreamChannel != null) {
            plainUpstreamChannel.close();
        }
        if (tlsUpstreamChannel != null) {
            tlsUpstreamChannel.close();
        }
        if (upstreamGroup != null) {
            upstreamGroup.shutdownGracefully();
        }
    }

    @Before
    public void resetExpectations() {
        mockServerClient.reset();
        UPSTREAM_PROTOCOL.clear();
        mockServerClient.when(request().withPath("/forward-h1/.*")).forward(forward().withHost("127.0.0.1").withPort(plainUpstreamPort));
        mockServerClient.when(request().withPath("/forward-h2/.*")).forward(forward().withHost("127.0.0.1").withPort(tlsUpstreamPort).withScheme(HttpForward.Scheme.HTTPS));
        // served by MockServer itself, so through a CONNECT tunnel the relay's loopback leg is what decodes it
        mockServerClient.when(request().withPath("/mock/declared")).respond(response().withHeader("content-encoding", "zstd").withBody(binary(DECLARES_TWO_GIB)));
        mockServerClient.when(request().withPath("/mock/legitimate")).respond(response().withHeader("content-encoding", "zstd").withBody(binary(PLAIN_ZSTD)));
    }

    @Test
    public void shouldBoundADeclaredSizeForwardedOverHttp1() throws Exception {
        assertDecodedToNothing(direct(HttpClient.Version.HTTP_1_1, "/forward-h1/declared"));
        assertThat(UPSTREAM_PROTOCOL.get("/forward-h1/declared"), is("HTTP/1.1"));
    }

    @Test
    public void shouldDecodeALegitimateResponseForwardedOverHttp1() throws Exception {
        assertDecodedByteForByte(direct(HttpClient.Version.HTTP_1_1, "/forward-h1/legitimate"));
    }

    @Test
    public void shouldBoundADeclaredSizeStreamedOverHttp1() throws Exception {
        assertDecodedToTheEvent(direct(HttpClient.Version.HTTP_1_1, "/forward-h1/stream/declared-event"));
    }

    @Test
    public void shouldDecodeALegitimateResponseStreamedOverHttp1() throws Exception {
        assertDecodedByteForByte(direct(HttpClient.Version.HTTP_1_1, "/forward-h1/stream/legitimate"));
    }

    @Test
    public void shouldBoundADeclaredSizeForwardedOverHttp2() throws Exception {
        assertDecodedToNothing(direct(HttpClient.Version.HTTP_1_1, "/forward-h2/declared"));
        assertThat("the forward client negotiated HTTP/2 with the upstream", UPSTREAM_PROTOCOL.get("/forward-h2/declared"), is("HTTP/2"));
    }

    @Test
    public void shouldDecodeALegitimateResponseForwardedOverHttp2() throws Exception {
        assertDecodedByteForByte(direct(HttpClient.Version.HTTP_1_1, "/forward-h2/legitimate"));
        assertThat(UPSTREAM_PROTOCOL.get("/forward-h2/legitimate"), is("HTTP/2"));
    }

    @Test
    public void shouldBoundADeclaredSizeStreamedOverHttp2() throws Exception {
        assertDecodedToTheEvent(direct(HttpClient.Version.HTTP_1_1, "/forward-h2/stream/declared-event"));
        assertThat(UPSTREAM_PROTOCOL.get("/forward-h2/stream/declared-event"), is("HTTP/2"));
    }

    @Test
    public void shouldDecodeALegitimateResponseStreamedOverHttp2() throws Exception {
        assertDecodedByteForByte(direct(HttpClient.Version.HTTP_1_1, "/forward-h2/stream/legitimate"));
        assertThat(UPSTREAM_PROTOCOL.get("/forward-h2/stream/legitimate"), is("HTTP/2"));
    }

    @Test
    public void shouldBoundADeclaredSizeDecodedByTheConnectRelayOverHttp1() throws Exception {
        assertDecodedToNothing(throughConnect(HttpClient.Version.HTTP_1_1, "/mock/declared"));
    }

    @Test
    public void shouldDecodeALegitimateResponseInTheConnectRelayOverHttp1() throws Exception {
        assertDecodedByteForByte(throughConnect(HttpClient.Version.HTTP_1_1, "/mock/legitimate"));
    }

    @Test
    public void shouldBoundADeclaredSizeDecodedByTheConnectRelayOverHttp2() throws Exception {
        assertDecodedToNothing(throughConnect(HttpClient.Version.HTTP_2, "/mock/declared"));
    }

    @Test
    public void shouldDecodeALegitimateResponseInTheConnectRelayOverHttp2() throws Exception {
        assertDecodedByteForByte(throughConnect(HttpClient.Version.HTTP_2, "/mock/legitimate"));
    }

    @Test
    public void shouldBoundADeclaredSizeFromAnUpstreamProxiedThroughAConnectTunnel() throws Exception {
        // no expectation matches, so MockServer forwards to the hostile upstream, then relays the response back
        assertDecodedToNothing(throughConnect(HttpClient.Version.HTTP_1_1, "/connect/declared"));
        assertDecodedToNothing(throughConnect(HttpClient.Version.HTTP_2, "/connect/h2/declared"));
        // forwardProxyHttp2Upgrade lets the forward client negotiate HTTP/2 with the TLS upstream whatever the tunnel carries
        assertThat(UPSTREAM_PROTOCOL.get("/connect/declared"), is("HTTP/2"));
        assertThat(UPSTREAM_PROTOCOL.get("/connect/h2/declared"), is("HTTP/2"));
    }

    private static HttpResponse<byte[]> direct(HttpClient.Version version, String path) throws Exception {
        HttpClient client = HttpClient.newBuilder().version(version).connectTimeout(TIMEOUT).build();
        return client.send(java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + mockServerPort + path)).timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpResponse<byte[]> throughConnect(HttpClient.Version version, String path) throws Exception {
        // https through MockServer as a proxy: the JDK client opens a CONNECT tunnel that MockServer terminates
        HttpClient client = HttpClient.newBuilder()
            .version(version)
            .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", mockServerPort)))
            .sslContext(caTrustingSslContext())
            .connectTimeout(TIMEOUT)
            .build();
        HttpResponse<byte[]> response = client.send(java.net.http.HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + tlsUpstreamPort + path)).timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat("the tunnel carried " + version, response.version(), is(version));
        return response;
    }

    private static void assertDecodedToNothing(HttpResponse<byte[]> response) {
        // the frame holds one empty block, so decoding it without allocating its declared size gives an empty body
        assertThat(response.statusCode(), is(200));
        assertThat(response.body().length, is(0));
        assertThat(response.headers().firstValue("content-encoding").isPresent(), is(false));
    }

    private static void assertDecodedToTheEvent(HttpResponse<byte[]> response) {
        assertThat(response.statusCode(), is(200));
        assertThat(new String(response.body(), StandardCharsets.US_ASCII), is(new String(EVENT, StandardCharsets.US_ASCII)));
    }

    private static void assertDecodedByteForByte(HttpResponse<byte[]> response) {
        assertThat(response.statusCode(), is(200));
        assertThat(response.body().length, is(PLAIN.length));
        assertThat(Arrays.equals(response.body(), PLAIN), is(true));
        assertThat(response.headers().firstValue("content-encoding").isPresent(), is(false));
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] both = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    private static byte[] plain() {
        byte[] plain = new byte[300 * 1024];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) ('a' + (i * 7 + i / 1024) % 26);
        }
        return plain;
    }

    /**
     * Answers {@code .../declared} with {@link #DECLARES_TWO_GIB}, {@code .../declared-event} with
     * {@link #DECLARES_TWO_GIB_THEN_AN_EVENT} and {@code .../legitimate} with {@link #PLAIN} in
     * zstd, as {@code text/event-stream} for a path containing {@code /stream/} so MockServer relays it unaggregated.
     */
    @ChannelHandler.Sharable
    private static final class ZstdUpstreamHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String path = new QueryStringDecoder(request.uri()).path();
            UPSTREAM_PROTOCOL.put(path, ctx.channel() instanceof Http2StreamChannel ? "HTTP/2" : "HTTP/1.1");
            byte[] body = path.endsWith("/declared") ? DECLARES_TWO_GIB : path.endsWith("/declared-event") ? DECLARES_TWO_GIB_THEN_AN_EVENT : PLAIN_ZSTD;
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, path.contains("/stream/") ? "text/event-stream" : "application/octet-stream");
            response.headers().set(HttpHeaderNames.CONTENT_ENCODING, "zstd");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            ctx.writeAndFlush(response);
        }
    }
}
