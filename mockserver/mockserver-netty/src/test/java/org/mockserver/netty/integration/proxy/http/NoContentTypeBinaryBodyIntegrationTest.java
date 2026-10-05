package org.mockserver.netty.integration.proxy.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
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
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2SecurityUtil;
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
import org.mockserver.model.Body;
import org.mockserver.model.ClearType;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.LogEventRequestAndResponse;
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
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.netty.MockServerCaTrustTestSupport.caTrustingSslContext;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Bytes on the wire in must equal bytes on the wire out for a binary body sent with no Content-Type,
 * on every path MockServer carries a body: forwarded over HTTP/1.1 and HTTP/2, proxied (absolute URI
 * and CONNECT), streamed, and served from an expectation - and the recorded copies must not be corrupted.
 * The clients are the JDK's own, so neither end of the wire goes through MockServer's codecs.
 */
public class NoContentTypeBinaryBodyIntegrationTest {

    private static final byte[] REQUEST_BYTES = randomBytes(500_000, 17);
    private static final byte[] RESPONSE_BYTES = randomBytes(1_000_000, 29);
    private static final Map<String, byte[]> RECEIVED_UPSTREAM = new ConcurrentHashMap<>();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static EventLoopGroup upstreamGroup;
    private static Channel plainUpstreamChannel;
    private static Channel tlsUpstreamChannel;
    private static int plainUpstreamPort;
    private static int tlsUpstreamPort;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static int mockServerPort;

    private static byte[] randomBytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    @BeforeClass
    public static void startServers() throws Exception {
        upstreamGroup = new NioEventLoopGroup(2);
        RecordingUpstreamHandler recordingHandler = new RecordingUpstreamHandler();

        plainUpstreamChannel = new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(16 * 1024 * 1024), recordingHandler);
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
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
                                        streamChannel.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(true), new HttpObjectAggregator(16 * 1024 * 1024), recordingHandler);
                                    }
                                }));
                            } else {
                                ctx.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(16 * 1024 * 1024), recordingHandler);
                            }
                        }
                    });
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        tlsUpstreamPort = ((InetSocketAddress) tlsUpstreamChannel.localAddress()).getPort();

        mockServer = new MockServer(configuration()
            .streamingResponsesEnabled(true)
            .forwardProxyHttp2Upgrade(true)
            .maxRequestBodySize(16 * 1024 * 1024)
            .maxResponseBodySize(16 * 1024 * 1024));
        mockServerPort = mockServer.getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
        mockServerClient.when(request().withPath("/forward-h1")).forward(forward().withHost("127.0.0.1").withPort(plainUpstreamPort));
        mockServerClient.when(request().withPath("/forward-h2")).forward(forward().withHost("127.0.0.1").withPort(tlsUpstreamPort).withScheme(HttpForward.Scheme.HTTPS));
        mockServerClient.when(request().withPath("/stream-h1")).forward(forward().withHost("127.0.0.1").withPort(plainUpstreamPort));
        mockServerClient.when(request().withPath("/mock-binary")).respond(response().withBody(binary(RESPONSE_BYTES)));
        mockServerClient.when(request().withPath("/mock-json").withBody(json("{\"id\":1}"))).respond(response().withBody("json matched"));
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
    public void clearRecords() {
        RECEIVED_UPSTREAM.clear();
        mockServerClient.clear(request(), ClearType.LOG);
    }

    /**
     * Records the body the upstream received and answers with {@link #RESPONSE_BYTES} and no
     * Content-Type, as one fixed-length response or, for {@code /stream*}, as a chunked stream.
     */
    @ChannelHandler.Sharable
    private static final class RecordingUpstreamHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String path = new QueryStringDecoder(request.uri()).path();
            RECEIVED_UPSTREAM.put(path, ByteBufUtil.getBytes(request.content()));
            if (path.startsWith("/stream")) {
                DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                HttpUtil.setTransferEncodingChunked(head, true);
                ctx.write(head);
                for (int offset = 0; offset < RESPONSE_BYTES.length; offset += 65536) {
                    ctx.write(new DefaultHttpContent(Unpooled.wrappedBuffer(RESPONSE_BYTES, offset, Math.min(65536, RESPONSE_BYTES.length - offset))));
                }
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            } else {
                DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(RESPONSE_BYTES));
                HttpUtil.setContentLength(response, RESPONSE_BYTES.length);
                ctx.writeAndFlush(response);
            }
        }
    }

    private static HttpClient http1Client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT).build();
    }

    private static java.net.http.HttpRequest.Builder post(String uri, byte[] body) {
        // no Content-Type header: the JDK client only sends one when told to
        return java.net.http.HttpRequest.newBuilder(URI.create(uri))
            .timeout(TIMEOUT)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body));
    }

    private static void assertByteIdentical(String description, byte[] actual, byte[] expected) {
        assertThat(description + " length", actual.length, is(expected.length));
        assertThat(description + " bytes", Arrays.equals(actual, expected), is(true));
    }

    private static void assertRecordedByteIdentical(String path) {
        LogEventRequestAndResponse[] recorded = mockServerClient.retrieveRecordedRequestsAndResponses(request().withPath(path));
        assertThat("recorded exchanges for " + path, recorded.length, is(1));
        assertByteIdentical("recorded request body", recorded[0].getHttpRequest().getBodyAsRawBytes(), REQUEST_BYTES);
        assertByteIdentical("recorded response body", recorded[0].getHttpResponse().getBodyAsRawBytes(), RESPONSE_BYTES);
        HttpRequest[] recordedRequests = mockServerClient.retrieveRecordedRequests(request().withPath(path));
        assertByteIdentical("retrieved request body", recordedRequests[0].getBodyAsRawBytes(), REQUEST_BYTES);
    }

    @Test
    public void shouldForwardOverHttp1ByteIdentical() throws Exception {
        HttpResponse<byte[]> response = http1Client().send(post("http://localhost:" + mockServerPort + "/forward-h1", REQUEST_BYTES).build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode(), is(200));
        assertByteIdentical("request received upstream", RECEIVED_UPSTREAM.get("/forward-h1"), REQUEST_BYTES);
        assertByteIdentical("response received by client", response.body(), RESPONSE_BYTES);
        assertThat(response.headers().firstValue("content-type").isPresent(), is(false));
        assertRecordedByteIdentical("/forward-h1");
    }

    @Test
    public void shouldForwardOverHttp2BothLegsByteIdentical() throws Exception {
        // HTTP/2 from the client to MockServer, and HTTP/2 (ALPN) from MockServer to the upstream
        HttpClient http2Client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).sslContext(caTrustingSslContext()).connectTimeout(TIMEOUT).build();

        HttpResponse<byte[]> response = http2Client.send(post("https://localhost:" + mockServerPort + "/forward-h2", REQUEST_BYTES).build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.version(), is(HttpClient.Version.HTTP_2));
        assertThat(response.statusCode(), is(200));
        assertByteIdentical("request received upstream", RECEIVED_UPSTREAM.get("/forward-h2"), REQUEST_BYTES);
        assertByteIdentical("response received by client", response.body(), RESPONSE_BYTES);
        assertRecordedByteIdentical("/forward-h2");
    }

    @Test
    public void shouldProxyAbsoluteUriRequestByteIdentical() throws Exception {
        HttpClient proxyClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", mockServerPort))).connectTimeout(TIMEOUT).build();

        HttpResponse<byte[]> response = proxyClient.send(post("http://127.0.0.1:" + plainUpstreamPort + "/proxy-h1", REQUEST_BYTES).build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode(), is(200));
        assertByteIdentical("request received upstream", RECEIVED_UPSTREAM.get("/proxy-h1"), REQUEST_BYTES);
        assertByteIdentical("response received by client", response.body(), RESPONSE_BYTES);
        assertRecordedByteIdentical("/proxy-h1");
    }

    @Test
    public void shouldProxyThroughConnectTunnelByteIdentical() throws Exception {
        // https through a proxy makes the JDK client open a CONNECT tunnel, which MockServer terminates
        HttpClient connectClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", mockServerPort))).sslContext(caTrustingSslContext()).connectTimeout(TIMEOUT).build();

        HttpResponse<byte[]> response = connectClient.send(post("https://127.0.0.1:" + tlsUpstreamPort + "/connect-tls", REQUEST_BYTES).build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode(), is(200));
        assertByteIdentical("request received upstream", RECEIVED_UPSTREAM.get("/connect-tls"), REQUEST_BYTES);
        assertByteIdentical("response received by client", response.body(), RESPONSE_BYTES);
        assertRecordedByteIdentical("/connect-tls");
    }

    @Test
    public void shouldStreamForwardedResponseByteIdentical() throws Exception {
        HttpResponse<byte[]> response = http1Client().send(post("http://localhost:" + mockServerPort + "/stream-h1", REQUEST_BYTES).header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode(), is(200));
        assertByteIdentical("request received upstream", RECEIVED_UPSTREAM.get("/stream-h1"), REQUEST_BYTES);
        assertByteIdentical("response received by client", response.body(), RESPONSE_BYTES);
        // a streamed response is recorded only up to maxStreamingCaptureBytes, so compare that prefix
        LogEventRequestAndResponse[] recorded = mockServerClient.retrieveRecordedRequestsAndResponses(request().withPath("/stream-h1"));
        assertByteIdentical("recorded request body", recorded[0].getHttpRequest().getBodyAsRawBytes(), REQUEST_BYTES);
        byte[] recordedResponse = recorded[0].getHttpResponse().getBodyAsRawBytes();
        assertThat("streamed response recorded as a truncated capture", recordedResponse.length, lessThan(RESPONSE_BYTES.length));
        assertByteIdentical("recorded response prefix", recordedResponse, Arrays.copyOf(RESPONSE_BYTES, recordedResponse.length));
    }

    @Test
    public void shouldServeExpectationBinaryResponseAndRecordRequestByteIdentical() throws Exception {
        HttpResponse<byte[]> response = http1Client().send(post("http://localhost:" + mockServerPort + "/mock-binary", REQUEST_BYTES).build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode(), is(200));
        assertByteIdentical("response received by client", response.body(), RESPONSE_BYTES);
        assertThat(response.headers().firstValue("content-type").isPresent(), is(false));
        HttpRequest[] recordedRequests = mockServerClient.retrieveRecordedRequests(request().withPath("/mock-binary"));
        assertThat(recordedRequests[0].getBody().getType(), is(Body.Type.BINARY));
        assertByteIdentical("retrieved request body", recordedRequests[0].getBodyAsRawBytes(), REQUEST_BYTES);
    }

    @Test
    public void shouldAcceptControlPlaneBodiesWithANonUtf8ByteAndNoContentType() throws Exception {
        // the é is a lone ISO-8859-1 byte, so each body is kept as binary; the control plane must still
        // read it as the UTF-8 text it always did rather than as base64
        HttpResponse<String> expectation = http1Client().send(java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + mockServerPort + "/mockserver/expectation"))
            .timeout(TIMEOUT)
            .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray("{\"httpRequest\":{\"path\":\"/control-plane-latin1\"},\"httpResponse\":{\"body\":\"José\"}}".getBytes(StandardCharsets.ISO_8859_1)))
            .build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> graphql = http1Client().send(java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + mockServerPort + "/mockserver/graphql?path=/graphql-latin1"))
            .timeout(TIMEOUT)
            .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray("# café\ntype Query { hello: String }".getBytes(StandardCharsets.ISO_8859_1)))
            .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(expectation.body(), expectation.statusCode(), is(201));
        assertThat(graphql.body(), graphql.statusCode(), is(201));
        HttpResponse<String> served = http1Client().send(java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + mockServerPort + "/control-plane-latin1")).timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(served.body(), is("Jos\uFFFD"));
    }

    @Test
    public void shouldStillMatchJsonSentWithoutContentType() throws Exception {
        HttpResponse<String> utf8 = http1Client().send(post("http://localhost:" + mockServerPort + "/mock-json", "{\"name\":\"şarəs\",\"id\":1}".getBytes(StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> latin1 = http1Client().send(post("http://localhost:" + mockServerPort + "/mock-json", "{\"name\":\"José\",\"id\":1}".getBytes(StandardCharsets.ISO_8859_1)).build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> other = http1Client().send(post("http://localhost:" + mockServerPort + "/mock-json", "{\"id\":2}".getBytes(StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(utf8.body(), is("json matched"));
        assertThat(latin1.body(), is("json matched"));
        assertThat(other.statusCode(), is(404));
    }
}
