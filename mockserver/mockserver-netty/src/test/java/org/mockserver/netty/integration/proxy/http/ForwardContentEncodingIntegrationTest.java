package org.mockserver.netty.integration.proxy.http;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.compression.SnappyFrameEncoder;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.compression.ZstdDecoder;
import io.netty.handler.codec.compression.ZstdEncoder;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.codec.BodyContentEncodingEncoder;
import org.mockserver.metrics.remotewrite.RemoteWriteV1Encoder;
import org.mockserver.metrics.remotewrite.SnappyBlock;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.MockServer;
import org.mockserver.testing.socket.Ipv4DatagramChannelFactory;
import org.xerial.snappy.Snappy;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpOverrideForwardedRequest.forwardOverriddenRequest;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.MockServerCaTrustTestSupport.caTrustingSslContext;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A request body sent with a {@code Content-Encoding}, over HTTP/1.1, HTTP/2 and HTTP/3, is forwarded upstream as the
 * exact bytes the client sent, whatever the coding (an upstream socket compares the bytes); a body changed by an
 * override is encoded in its coding again; and a Prometheus remote-write request (raw-block Snappy) is matched on its
 * decoded protobuf and answered. The HTTP/3 tests are QUIC-gated like the other HTTP/3 integration tests.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class ForwardContentEncodingIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final byte[] PLAIN = plain();

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static CapturingUpstream upstream;
    private static int http3Port;

    private enum Transport { HTTP_1_1, HTTP_2, HTTP_3, CONNECT_HTTP_1_1, CONNECT_HTTP_2, SOCKS5_HTTP_1_1 }

    @BeforeClass
    public static void startServer() throws IOException {
        upstream = new CapturingUpstream();
        mockServer = new MockServer(configuration()
            .http3Port(quicAvailable() ? org.mockserver.testing.socket.TestPortFactory.findFreeUdpPort() : null)
            .http3MaxIdleTimeout(30000L), 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        http3Port = quicAvailable() ? mockServer.getHttp3Port() : 0;
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (upstream != null) {
            upstream.close();
        }
    }

    @Before
    public void reset() {
        mockServerClient.reset();
        upstream.captured.clear();
    }

    @Test
    public void shouldForwardEveryCodingByteIdenticalOverHttp1() throws Exception {
        assertEveryCodingForwardedByteIdentical(Transport.HTTP_1_1);
    }

    @Test
    public void shouldForwardEveryCodingByteIdenticalOverHttp2() throws Exception {
        assertEveryCodingForwardedByteIdentical(Transport.HTTP_2);
    }

    @Test
    public void shouldForwardEveryCodingByteIdenticalOverHttp3() throws Exception {
        assumeHttp3();
        assertEveryCodingForwardedByteIdentical(Transport.HTTP_3);
    }

    @Test
    public void shouldForwardEveryCodingByteIdenticalThroughAConnectTunnelOverHttp1() throws Exception {
        assertEveryCodingForwardedByteIdentical(Transport.CONNECT_HTTP_1_1);
    }

    @Test
    public void shouldForwardEveryCodingByteIdenticalThroughAConnectTunnelOverHttp2() throws Exception {
        assertEveryCodingForwardedByteIdentical(Transport.CONNECT_HTTP_2);
    }

    @Test
    public void shouldForwardEveryCodingByteIdenticalThroughASocks5Tunnel() throws Exception {
        assertEveryCodingForwardedByteIdentical(Transport.SOCKS5_HTTP_1_1);
    }

    @Test
    public void shouldReencodeAChangedBodyOverHttp1() throws Exception {
        assertChangedBodyReencoded(Transport.HTTP_1_1);
    }

    @Test
    public void shouldReencodeAChangedBodyOverHttp2() throws Exception {
        assertChangedBodyReencoded(Transport.HTTP_2);
    }

    @Test
    public void shouldReencodeAChangedBodyOverHttp3() throws Exception {
        assumeHttp3();
        assertChangedBodyReencoded(Transport.HTTP_3);
    }

    @Test
    public void shouldReencodeAChangedBodyThroughAConnectTunnelOverHttp1() throws Exception {
        assertChangedBodyReencoded(Transport.CONNECT_HTTP_1_1);
    }

    @Test
    public void shouldReencodeAChangedBodyThroughAConnectTunnelOverHttp2() throws Exception {
        assertChangedBodyReencoded(Transport.CONNECT_HTTP_2);
    }

    @Test
    public void shouldMockAPrometheusRemoteWriteReceiverOverHttp1() throws Exception {
        assertRemoteWriteMocked(Transport.HTTP_1_1);
    }

    @Test
    public void shouldMockAPrometheusRemoteWriteReceiverOverHttp2() throws Exception {
        assertRemoteWriteMocked(Transport.HTTP_2);
    }

    @Test
    public void shouldMockAPrometheusRemoteWriteReceiverOverHttp3() throws Exception {
        assumeHttp3();
        assertRemoteWriteMocked(Transport.HTTP_3);
    }

    @Test
    public void shouldMockAPrometheusRemoteWriteReceiverThroughAConnectTunnelOverHttp1() throws Exception {
        assertRemoteWriteMocked(Transport.CONNECT_HTTP_1_1);
    }

    @Test
    public void shouldMockAPrometheusRemoteWriteReceiverThroughAConnectTunnelOverHttp2() throws Exception {
        assertRemoteWriteMocked(Transport.CONNECT_HTTP_2);
    }

    private void assertEveryCodingForwardedByteIdentical(Transport transport) throws Exception {
        mockServerClient
            .when(request().withPath("/forward_coding"))
            .forward(forward().withHost("127.0.0.1").withPort(upstream.port()));
        assertThat("zstd-jni reaches this module's classpath, as it does the shaded jar", Zstd.isAvailable(), is(true));

        Map<String, byte[]> codings = new LinkedHashMap<>();
        codings.put("gzip", gzipBestCompression(PLAIN));
        codings.put("x-gzip", gzipBestCompression(PLAIN));
        codings.put("deflate", zlibBestCompression(PLAIN));
        codings.put("snappy", SnappyBlock.compress(PLAIN));
        codings.put("SNAPPY", encode(new SnappyFrameEncoder(), PLAIN));
        codings.put("zstd", encode(new ZstdEncoder(), PLAIN));
        // never decoded: a coding list, and br without Brotli on the classpath
        codings.put("gzip, br", gzipBestCompression(PLAIN));
        codings.put("br", "not really brotli".getBytes(StandardCharsets.UTF_8));

        for (Map.Entry<String, byte[]> coding : codings.entrySet()) {
            String contentEncoding = coding.getKey();
            byte[] wire = coding.getValue();
            if (contentEncoding.equalsIgnoreCase("gzip") || contentEncoding.equalsIgnoreCase("deflate")) {
                assertThat("the client's " + contentEncoding + " differs from MockServer's own, so re-encoding would show",
                    wire, not(BodyContentEncodingEncoder.encodeBody(PLAIN, contentEncoding)));
            }

            int status = send(transport, "/forward_coding", headers("application/json", contentEncoding), wire);
            CapturedRequest forwarded = upstream.captured.poll(TIMEOUT.getSeconds(), TimeUnit.SECONDS);

            assertThat(transport + " " + contentEncoding + " status", status, is(200));
            assertThat(transport + " " + contentEncoding + " reached the upstream", forwarded, notNullValue());
            assertThat(transport + " " + contentEncoding + " forwarded byte-identical", forwarded.body, is(wire));
            assertThat(forwarded.headers.get("content-encoding"), is(contentEncoding));
            assertThat(forwarded.headers.get("content-length"), is(String.valueOf(wire.length)));
        }
    }

    private void assertChangedBodyReencoded(Transport transport) throws Exception {
        byte[] changed = "{\"changed\":\"by an override\"}".getBytes(StandardCharsets.UTF_8);
        mockServerClient
            .when(request().withPath("/forward_changed"))
            .forward(forwardOverriddenRequest(
                request()
                    .withHeader("Host", "127.0.0.1:" + upstream.port())
                    .withSecure(false)
                    .withBody(binary(changed))
            ));

        send(transport, "/forward_changed", headers("application/json", "gzip"), gzipBestCompression(PLAIN));
        CapturedRequest gzipped = upstream.captured.poll(TIMEOUT.getSeconds(), TimeUnit.SECONDS);
        send(transport, "/forward_changed", headers("application/x-protobuf", "snappy"), SnappyBlock.compress(PLAIN));
        CapturedRequest snappy = upstream.captured.poll(TIMEOUT.getSeconds(), TimeUnit.SECONDS);
        send(transport, "/forward_changed", headers("application/json", "zstd"), encode(new ZstdEncoder(), PLAIN));
        CapturedRequest zstd = upstream.captured.poll(TIMEOUT.getSeconds(), TimeUnit.SECONDS);

        assertThat(gzipped, notNullValue());
        assertThat(gzipped.headers.get("content-encoding"), is("gzip"));
        assertThat(gunzip(gzipped.body), is(changed));
        assertThat(snappy, notNullValue());
        assertThat(snappy.headers.get("content-encoding"), is("snappy"));
        assertThat(Snappy.uncompress(snappy.body), is(changed));
        assertThat(zstd, notNullValue());
        assertThat(zstd.headers.get("content-encoding"), is("zstd"));
        assertThat(unzstd(zstd.body), is(changed));
    }

    private void assertRemoteWriteMocked(Transport transport) throws Exception {
        PrometheusRegistry registry = new PrometheusRegistry();
        Counter.builder().name("remote_write_test").help("a remote-write test counter").labelNames("transport").register(registry)
            .labelValues(transport.name()).inc(7);
        byte[] writeRequest = new RemoteWriteV1Encoder().encode(registry.scrape(), System.currentTimeMillis());
        byte[] wire = SnappyBlock.compress(writeRequest);
        mockServerClient
            .when(request()
                .withMethod("POST")
                .withPath("/api/v1/write")
                .withHeader("content-encoding", "snappy")
                .withHeader("x-prometheus-remote-write-version", "0.1.0")
                .withBody(binary(writeRequest)))
            .respond(response().withStatusCode(204));

        Map<String, String> headers = headers("application/x-protobuf", "snappy");
        headers.put("x-prometheus-remote-write-version", "0.1.0");
        headers.put("user-agent", "Prometheus/3.0.0");
        int status = send(transport, "/api/v1/write", headers, wire);

        assertThat(transport + " remote-write answered by the expectation", status, is(204));
        HttpRequest recorded = mockServerClient.retrieveRecordedRequests(request().withPath("/api/v1/write"))[0];
        assertThat(recorded.getBodyAsRawBytes(), is(writeRequest));
        assertThat(recorded.getOriginalBody(), is(wire));
        assertThat(new String(recorded.getBodyAsRawBytes(), StandardCharsets.ISO_8859_1), containsString("remote_write_test_total"));
    }

    private static Map<String, String> headers(String contentType, String contentEncoding) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", contentType);
        headers.put("content-encoding", contentEncoding);
        return headers;
    }

    private int send(Transport transport, String path, Map<String, String> headers, byte[] body) throws Exception {
        switch (transport) {
            case HTTP_1_1:
                return sendWithJdkClient(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT).build(), "http", path, headers, body, HttpClient.Version.HTTP_1_1);
            case HTTP_2:
                return sendWithJdkClient(HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).sslContext(caTrustingSslContext()).connectTimeout(TIMEOUT).build(), "https", path, headers, body, HttpClient.Version.HTTP_2);
            case CONNECT_HTTP_1_1:
            case CONNECT_HTTP_2:
                // https through MockServer as a proxy: the JDK client opens a CONNECT tunnel that MockServer terminates
                HttpClient.Version version = transport == Transport.CONNECT_HTTP_2 ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1;
                HttpClient tunnelled = HttpClient.newBuilder()
                    .version(version)
                    .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", mockServer.getLocalPort())))
                    .sslContext(caTrustingSslContext())
                    .connectTimeout(TIMEOUT)
                    .build();
                return sendWithJdkClient(tunnelled, URI.create("https://127.0.0.1:" + upstream.port() + path), headers, body, version);
            case SOCKS5_HTTP_1_1:
                return sendThroughSocks5(path, headers, body);
            default:
                return sendHttp3(path, headers, body);
        }
    }

    private int sendWithJdkClient(HttpClient client, String scheme, String path, Map<String, String> headers, byte[] body, HttpClient.Version expected) throws Exception {
        return sendWithJdkClient(client, URI.create(scheme + "://localhost:" + mockServer.getLocalPort() + path), headers, body, expected);
    }

    private int sendWithJdkClient(HttpClient client, URI uri, Map<String, String> headers, byte[] body, HttpClient.Version expected) throws Exception {
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.version(), is(expected));
        return response.statusCode();
    }

    // HTTPS inside a SOCKS5 tunnel, which MockServer terminates and relays as it does a CONNECT tunnel
    private int sendThroughSocks5(String path, Map<String, String> headers, byte[] body) throws Exception {
        try (Socket tunnel = new Socket(new java.net.Proxy(java.net.Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", mockServer.getLocalPort())))) {
            tunnel.setSoTimeout((int) TIMEOUT.toMillis());
            tunnel.connect(new InetSocketAddress("127.0.0.1", upstream.port()));
            try (Socket tls = caTrustingSslContext().getSocketFactory().createSocket(tunnel, "127.0.0.1", upstream.port(), false)) {
                StringBuilder head = new StringBuilder("POST " + path + " HTTP/1.1\r\nhost: 127.0.0.1:" + upstream.port() + "\r\n");
                headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
                head.append("content-length: ").append(body.length).append("\r\nconnection: close\r\n\r\n");
                OutputStream out = tls.getOutputStream();
                out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
                String statusLine = CapturingUpstream.readLine(new BufferedInputStream(tls.getInputStream()));
                assertThat(statusLine, notNullValue());
                return Integer.parseInt(statusLine.split(" ")[1]);
            }
        }
    }

    private int sendHttp3(String path, Map<String, String> headers, byte[] body) throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
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
            QuicChannel quicChannel = QuicChannel.newBootstrap(datagramChannel)
                .handler(new Http3ClientConnectionHandler())
                .remoteAddress(new InetSocketAddress("127.0.0.1", http3Port))
                .connect()
                .get(15, TimeUnit.SECONDS);
            int[] status = {-1};
            CountDownLatch done = new CountDownLatch(1);
            QuicStreamChannel stream = Http3.newRequestStream(quicChannel, new Http3RequestStreamInboundHandler() {
                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
                    if (frame.headers().status() != null) {
                        status[0] = Integer.parseInt(frame.headers().status().toString());
                    }
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
                    frame.release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    done.countDown();
                    ctx.close();
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    done.countDown();
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) {
                    done.countDown();
                }
            }).sync().getNow();
            DefaultHttp3HeadersFrame requestHeaders = new DefaultHttp3HeadersFrame();
            requestHeaders.headers().method("POST").path(path).scheme("https").authority("127.0.0.1:" + http3Port);
            headers.forEach((name, value) -> requestHeaders.headers().add(name, value));
            requestHeaders.headers().addInt("content-length", body.length);
            stream.writeAndFlush(requestHeaders).sync();
            stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(body))).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT).sync();
            done.await(TIMEOUT.getSeconds(), TimeUnit.SECONDS);
            quicChannel.close().sync();
            datagramChannel.close().sync();
            return status[0];
        } finally {
            group.shutdownGracefully();
        }
    }

    private static final class CapturedRequest {
        final Map<String, String> headers;
        final byte[] body;

        CapturedRequest(Map<String, String> headers, byte[] body) {
            this.headers = headers;
            this.body = body;
        }
    }

    // records the exact HTTP/1.1 request bytes it receives, so nothing between MockServer and the assertion decodes them
    private static final class CapturingUpstream {
        final BlockingQueue<CapturedRequest> captured = new LinkedBlockingQueue<>();
        private final ServerSocket serverSocket;

        CapturingUpstream() throws IOException {
            serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        Socket socket = serverSocket.accept();
                        Thread connection = new Thread(() -> serve(socket), "forward-content-encoding-upstream-connection");
                        connection.setDaemon(true);
                        connection.start();
                    } catch (IOException closed) {
                        return;
                    }
                }
            }, "forward-content-encoding-upstream");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void serve(Socket socket) {
            try (Socket s = socket; InputStream in = new BufferedInputStream(s.getInputStream()); OutputStream out = s.getOutputStream()) {
                while (true) {
                    String requestLine = readLine(in);
                    if (requestLine == null || requestLine.isEmpty()) {
                        return;
                    }
                    Map<String, String> headers = new HashMap<>();
                    String line;
                    while ((line = readLine(in)) != null && !line.isEmpty()) {
                        int colon = line.indexOf(':');
                        headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                    }
                    byte[] body = in.readNBytes(Integer.parseInt(headers.getOrDefault("content-length", "0")));
                    captured.add(new CapturedRequest(headers, body));
                    out.write("HTTP/1.1 200 OK\r\ncontent-length: 8\r\n\r\ncaptured".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
            } catch (IOException | RuntimeException ignored) {
                // the test fails on the missing capture
            }
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    String text = line.toString(StandardCharsets.ISO_8859_1);
                    return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
                }
                line.write(b);
            }
            return line.size() > 0 ? line.toString(StandardCharsets.ISO_8859_1) : null;
        }

        void close() {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    private static byte[] plain() {
        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 2000; i++) {
            json.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append(",\"name\":\"item ").append(i).append(" – é\"}");
        }
        return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzipBestCompression(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out) {
            {
                def.setLevel(Deflater.BEST_COMPRESSION);
            }
        }) {
            gzip.write(plain);
        }
        return out.toByteArray();
    }

    private static byte[] zlibBestCompression(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(out, new Deflater(Deflater.BEST_COMPRESSION))) {
            deflate.write(plain);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gzip.readAllBytes();
        }
    }

    private static byte[] encode(ChannelHandler encoder, byte[] plain) {
        EmbeddedChannel channel = new EmbeddedChannel(encoder);
        try {
            channel.writeOutbound(Unpooled.wrappedBuffer(plain));
            channel.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<ByteBuf> pieces = new ArrayList<>();
            ByteBuf piece;
            while ((piece = channel.readOutbound()) != null) {
                pieces.add(piece);
            }
            for (ByteBuf each : pieces) {
                byte[] bytes = new byte[each.readableBytes()];
                each.readBytes(bytes);
                out.writeBytes(bytes);
                each.release();
            }
            return out.toByteArray();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static byte[] unzstd(byte[] compressed) {
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdDecoder());
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(compressed));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuf piece;
            while ((piece = channel.readInbound()) != null) {
                byte[] bytes = new byte[piece.readableBytes()];
                piece.readBytes(bytes);
                out.writeBytes(bytes);
                piece.release();
            }
            return out.toByteArray();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static boolean quicAvailable() {
        try {
            return io.netty.handler.codec.quic.Quic.isAvailable();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static void assumeHttp3() {
        Assume.assumeTrue("native QUIC transport not available on this platform -- skipping HTTP/3", quicAvailable());
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);
    }
}
