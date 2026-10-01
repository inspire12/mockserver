package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
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
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.MockServer;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A {@code Content-Encoding: gzip} request body over real HTTP/3 (QUIC) is decompressed as HTTP/1.1 and HTTP/2
 * decompress it: matched on its decompressed content, forwarded upstream exactly as an HTTP/1.1 request with the same
 * body is, answered with 413 when it decompresses past {@code maxRequestBodySize}, and its stream closed without a
 * response when it is corrupt. QUIC-gated like the other HTTP/3 integration tests.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3RequestDecompressionIntegrationTest {

    private static final int MAX_BODY = 1024 * 1024;
    private static final String JSON = "{\"name\":\"value\",\"text\":\"héllo wörld\"}";

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static CapturingUpstream upstream;
    private static int http3Port;

    private NioEventLoopGroup clientGroup;

    @BeforeClass
    public static void startServer() throws IOException {
        assumeQuicAvailable();
        upstream = new CapturingUpstream();
        int udpPort = org.mockserver.testing.socket.TestPortFactory.findFreeUdpPort();
        mockServer = new MockServer(configuration()
            .http3Port(udpPort)
            .http3MaxIdleTimeout(30000L)
            .maxRequestBodySize(MAX_BODY), 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        http3Port = mockServer.getHttp3Port();
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
        assumeQuicAvailable();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);
        mockServerClient.reset();
        upstream.captured.clear();
    }

    @After
    public void tearDown() {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
            clientGroup = null;
        }
    }

    @Test
    public void shouldMatchAGzipBodyOnItsDecompressedContentOverHttp3() throws Exception {
        mockServerClient
            .when(request().withPath("/h3_gzip").withBody(json(JSON)))
            .respond(response().withBody("matched_gzip"));
        byte[] compressed = gzip(JSON.getBytes(StandardCharsets.UTF_8));

        Http3Result result = sendHttp3Request("/h3_gzip", "gzip", compressed);

        assertThat("body received over http3: <" + result.body + ">", result.body, is("matched_gzip"));
        HttpRequest recorded = mockServerClient.retrieveRecordedRequests(request().withPath("/h3_gzip"))[0];
        assertThat(recorded.getFirstHeader("content-encoding"), is("gzip"));
        assertThat(recorded.getFirstHeader("content-length"), is(String.valueOf(JSON.getBytes(StandardCharsets.UTF_8).length)));
        assertThat(recorded.getOriginalBody(), is(compressed));
    }

    @Test
    public void shouldForwardAGzipBodyUpstreamAsHttp1Does() throws Exception {
        mockServerClient
            .when(request().withPath("/h3_gzip_forward"))
            .forward(forward().withHost("127.0.0.1").withPort(upstream.port()));
        byte[] compressed = gzip(JSON.getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> viaHttp1 = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
            .send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mockServer.getLocalPort() + "/h3_gzip_forward"))
                .header("content-type", "application/json")
                .header("content-encoding", "gzip")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(compressed))
                .build(), HttpResponse.BodyHandlers.ofString());
        CapturedRequest forwardedFromHttp1 = upstream.captured.poll(10, TimeUnit.SECONDS);
        Http3Result viaHttp3 = sendHttp3Request("/h3_gzip_forward", "gzip", compressed);
        CapturedRequest forwardedFromHttp3 = upstream.captured.poll(10, TimeUnit.SECONDS);

        assertThat(viaHttp1.statusCode(), is(200));
        assertThat(viaHttp3.status, is("200"));
        assertThat(forwardedFromHttp1, notNullValue());
        assertThat(forwardedFromHttp3, notNullValue());
        assertThat(forwardedFromHttp3.headers.get("content-encoding"), is(forwardedFromHttp1.headers.get("content-encoding")));
        assertThat(forwardedFromHttp3.headers.get("content-length"), is(forwardedFromHttp1.headers.get("content-length")));
        assertThat(forwardedFromHttp3.body, is(forwardedFromHttp1.body));
        // compressed once, not twice
        assertThat(new String(gunzip(forwardedFromHttp3.body), StandardCharsets.UTF_8), is(JSON));
    }

    @Test
    public void shouldAnswerADecompressionBombWith413OverHttp3() throws Exception {
        mockServerClient
            .when(request().withPath("/h3_gzip_bomb"))
            .respond(response().withBody("should_not_match"));
        byte[] bomb = gzip(new byte[16 * MAX_BODY]);
        assertThat("compressed body is under the limit", bomb.length < MAX_BODY, is(true));

        Http3Result result = sendHttp3Request("/h3_gzip_bomb", "gzip", bomb);

        assertThat(result.status, is("413"));
    }

    @Test
    public void shouldCloseTheStreamWithoutAResponseForACorruptGzipBodyOverHttp3() throws Exception {
        mockServerClient
            .when(request().withPath("/h3_gzip_corrupt"))
            .respond(response().withBody("should_not_match"));

        Http3Result corrupt = sendHttp3Request("/h3_gzip_corrupt", "gzip", "this is not gzip".getBytes(StandardCharsets.UTF_8));
        Http3Result next = sendHttp3Request("/h3_gzip_corrupt", null, "plain".getBytes(StandardCharsets.UTF_8));

        assertThat("no response headers for a corrupt body", corrupt.receivedHeaders, is(false));
        assertThat(corrupt.resetOrClosedWithoutResponse(), is(true));
        assertThat("the server keeps serving", next.body, is("should_not_match"));
    }

    // ---- HTTP/3 client ----

    private static class Http3Result {
        volatile String status = "null";
        volatile String body = "";
        volatile boolean receivedHeaders = false;
        volatile boolean inputClosed = false;
        volatile boolean exceptionRaised = false;

        boolean resetOrClosedWithoutResponse() {
            return !receivedHeaders && (exceptionRaised || inputClosed);
        }
    }

    private Http3Result sendHttp3Request(String path, String contentEncoding, byte[] requestBody) throws Exception {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
        }
        clientGroup = new NioEventLoopGroup(1);

        QuicSslContext clientSslContext = QuicSslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .applicationProtocols(Http3.supportedApplicationProtocols())
            .build();

        Channel clientChannel = new Bootstrap()
            .group(clientGroup)
            .channel(NioDatagramChannel.class)
            .handler(Http3.newQuicClientCodecBuilder()
                .sslContext(clientSslContext)
                .maxIdleTimeout(30000, TimeUnit.MILLISECONDS)
                .initialMaxData(10000000)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .initialMaxStreamsBidirectional(100)
                .build())
            .bind(0)
            .sync()
            .channel();

        QuicChannel quicChannel = QuicChannel.newBootstrap(clientChannel)
            .handler(new Http3ClientConnectionHandler())
            .remoteAddress(new InetSocketAddress("127.0.0.1", http3Port))
            .connect()
            .get(15, TimeUnit.SECONDS);

        Http3Result result = new Http3Result();
        StringBuilder collected = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);

        QuicStreamChannel requestStream = Http3.newRequestStream(
            quicChannel,
            new Http3RequestStreamInboundHandler() {
                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
                    result.receivedHeaders = true;
                    CharSequence status = headersFrame.headers().status();
                    if (status != null) {
                        result.status = status.toString();
                    }
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
                    ByteBuf content = dataFrame.content();
                    collected.append(content.toString(StandardCharsets.UTF_8));
                    content.release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    result.inputClosed = true;
                    done.countDown();
                    ctx.close();
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    result.exceptionRaised = true;
                    done.countDown();
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) {
                    done.countDown();
                }
            }
        ).sync().getNow();

        DefaultHttp3HeadersFrame requestHeaders = new DefaultHttp3HeadersFrame();
        requestHeaders.headers().method("POST");
        requestHeaders.headers().path(path);
        requestHeaders.headers().scheme("https");
        requestHeaders.headers().authority("127.0.0.1:" + http3Port);
        requestHeaders.headers().add("content-type", "application/json");
        if (contentEncoding != null) {
            requestHeaders.headers().add("content-encoding", contentEncoding);
        }
        requestHeaders.headers().addInt("content-length", requestBody.length);

        requestStream.write(requestHeaders).sync();
        requestStream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(requestBody)))
            .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
            .sync();

        done.await(20, TimeUnit.SECONDS);
        result.body = collected.toString();

        quicChannel.close().sync();
        clientChannel.close().sync();

        return result;
    }

    // ---- an upstream that records the exact HTTP/1.1 request bytes it receives ----

    private static final class CapturedRequest {
        final Map<String, String> headers;
        final byte[] body;

        CapturedRequest(Map<String, String> headers, byte[] body) {
            this.headers = headers;
            this.body = body;
        }
    }

    private static final class CapturingUpstream {
        final BlockingQueue<CapturedRequest> captured = new LinkedBlockingQueue<>();
        private final ServerSocket serverSocket;

        CapturingUpstream() throws IOException {
            serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        Socket socket = serverSocket.accept();
                        Thread connection = new Thread(() -> serve(socket), "h3-decompression-upstream-connection");
                        connection.setDaemon(true);
                        connection.start();
                    } catch (IOException closed) {
                        return;
                    }
                }
            }, "h3-decompression-upstream");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void serve(Socket socket) {
            try (Socket s = socket; InputStream in = new BufferedInputStream(s.getInputStream()); OutputStream out = s.getOutputStream()) {
                Map<String, String> headers = new HashMap<>();
                String line = readLine(in);
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                }
                int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                byte[] body = in.readNBytes(length);
                captured.add(new CapturedRequest(headers, body));
                out.write("HTTP/1.1 200 OK\r\ncontent-length: 8\r\nconnection: close\r\n\r\ncaptured".getBytes(StandardCharsets.US_ASCII));
                out.flush();
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

    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(plain);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
            return gzip.readAllBytes();
        }
    }

    private static void assumeQuicAvailable() {
        try {
            Assume.assumeTrue(
                "native QUIC transport not available on this platform -- skipping HTTP/3 decompression test",
                io.netty.handler.codec.quic.Quic.isAvailable()
            );
        } catch (Throwable t) {
            Assume.assumeNoException("native QUIC transport failed to load -- skipping HTTP/3 decompression test", t);
        }
    }
}
