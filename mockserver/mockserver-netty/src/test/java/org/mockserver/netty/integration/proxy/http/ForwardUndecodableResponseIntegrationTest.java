package org.mockserver.netty.integration.proxy.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.ProxyPassMapping;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * An upstream HTTP/1.1 response the forward client cannot decode fails the forward with a {@code 502} that names why,
 * is logged once, and leaves its connection closed: nothing of it is relayed and the next forward is answered at once
 * on a new connection. A forward by expectation, by Host header and by proxy-pass mapping are answered alike.
 */
public class ForwardUndecodableResponseIntegrationTest {

    private static final String REASON = "response from the upstream could not be decoded: ";

    private static EventLoopGroup upstreamGroup;
    private static Channel upstream;
    private static final AtomicInteger UPSTREAM_CONNECTIONS = new AtomicInteger();
    private static MockServer mockServer;
    private static MockServerClient client;

    @BeforeClass
    public static void startServers() throws Exception {
        upstreamGroup = new NioEventLoopGroup(1);
        upstream = new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    UPSTREAM_CONNECTIONS.incrementAndGet();
                    ch.pipeline().addLast(new HttpRequestDecoder());
                    ch.pipeline().addLast(new HttpObjectAggregator(1024 * 1024));
                    ch.pipeline().addLast(new RawUpstream());
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        // the test JVM defaults to ERROR, which would drop the WARN entries this class asserts there are none of
        mockServer = new MockServer(configuration().logLevel("WARN").proxyPassMappings(List.of(ProxyPassMapping.proxyPass("/pass", "http://127.0.0.1:" + upstreamPort()))), 0);
        client = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(client);
        stopQuietly(mockServer);
        if (upstream != null) {
            upstream.close();
        }
        if (upstreamGroup != null) {
            upstreamGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Before
    public void forgetEarlierTests() {
        client.reset();
        client.when(request().withPath("/forward/.*")).forward(forward().withHost("127.0.0.1").withPort(upstreamPort()));
    }

    @Test
    public void shouldAnswer502ForAResponseWithAnInvalidHeaderAndForwardTheNextOnANewConnection() throws Exception {
        assertThat(get("/forward/ok").status, is(200));
        int before = UPSTREAM_CONNECTIONS.get();

        Response response = get("/forward/invalid-header");

        assertFailedOnce(response, "IllegalArgumentException: ");
        long started = System.nanoTime();
        Response next = get("/forward/ok");
        assertThat(next.status, is(200));
        assertThat(next.body, is("ok"));
        assertThat("answered at once, not after the read timeout", System.nanoTime() - started, lessThan(TimeUnit.SECONDS.toNanos(10)));
        assertThat("the connection the response arrived on was closed, not pooled", UPSTREAM_CONNECTIONS.get() - before, is(1));
    }

    @Test
    public void shouldAnswer502ForAStatusLineThatIsNotHttp() throws Exception {
        assertFailedOnce(get("/forward/not-http"), "IllegalArgumentException: ");
    }

    @Test
    public void shouldAnswer502ForAStatusLineLongerThanTheDecoderReads() throws Exception {
        assertFailedOnce(get("/forward/long-status"), "TooLongHttpLineException: An HTTP line is larger than 4096 bytes.");
    }

    @Test
    public void shouldAnswer502ForAChunkSizeThatIsNotANumber() throws Exception {
        assertFailedOnce(get("/forward/invalid-chunk"), "NumberFormatException: ");
    }

    @Test
    public void shouldAnswer502WhenProxyingByHostHeader() throws Exception {
        Response response = exchange("/proxied/invalid-header", "127.0.0.1:" + upstreamPort());

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, startsWith(REASON + "IllegalArgumentException: "));
        List<String> entries = decodingEntries();
        assertThat(entries.toString(), entries.size(), is(1));
        assertThat(entries.get(0), containsString("failed to proxy request"));
    }

    @Test
    public void shouldAnswer502AsTheForwardRouteDoesForAProxyPassMapping() throws Exception {
        Response response = get("/pass/invalid-header");

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, startsWith(REASON + "IllegalArgumentException: "));
        List<String> entries = decodingEntries();
        assertThat(entries.toString(), entries.size(), is(1));
        assertThat(entries.get(0), containsString("failed to proxy pass request"));
        assertThat(entries.get(0), containsString(response.body));
    }

    private static void assertFailedOnce(Response response, String cause) {
        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, startsWith(REASON + cause));
        List<String> entries = decodingEntries();
        assertThat(entries.toString(), entries.size(), is(1));
        assertThat(entries.get(0), containsString("failed to forward request"));
        assertThat(entries.get(0), containsString(response.body));
    }

    /**
     * Every entry about the failure, including the response mapper's "exception decoding response" that an
     * undecodable response used to be logged with as well as relayed.
     */
    private static List<String> decodingEntries() {
        return Arrays.stream(client.retrieveLogMessagesArray(null))
            .filter(entry -> entry.contains("could not be decoded") || entry.toLowerCase(Locale.ROOT).contains("exception"))
            .collect(Collectors.toList());
    }

    private static int upstreamPort() {
        return ((InetSocketAddress) upstream.localAddress()).getPort();
    }

    private static Response get(String path) throws Exception {
        return exchange(path, "127.0.0.1:" + mockServer.getLocalPort());
    }

    private static Response exchange(String path, String host) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort())) {
            socket.setSoTimeout(60_000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[16 * 1024];
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                received.write(buffer, 0, read);
            }
            return new Response(received.toString(StandardCharsets.ISO_8859_1.name()));
        }
    }

    private static final class Response {
        private final String raw;
        private final int status;
        private final String body;

        private Response(String raw) {
            this.raw = raw;
            int endOfHead = raw.indexOf("\r\n\r\n");
            status = raw.length() >= 12 && raw.startsWith("HTTP/1.1 ") ? Integer.parseInt(raw.substring(9, 12)) : -1;
            body = endOfHead < 0 ? "" : raw.substring(endOfHead + 4);
        }

        @Override
        public String toString() {
            return raw.length() > 500 ? raw.substring(0, 500) + "..." : raw;
        }
    }

    /**
     * Answers each request with the bytes its path names, as written, and leaves the connection open.
     */
    private static final class RawUpstream extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String path = request.uri().substring(request.uri().lastIndexOf('/') + 1);
            String response;
            switch (path) {
                case "invalid-header":
                    response = "HTTP/1.1 200 OK\r\nx-bad(header): value\r\ncontent-length: 2\r\n\r\nok";
                    break;
                case "not-http":
                    response = "some_random_bytes\r\n";
                    break;
                case "long-status":
                    response = "HTTP/1.1 200 " + "a".repeat(5000) + "\r\ncontent-length: 2\r\n\r\nok";
                    break;
                case "invalid-chunk":
                    response = "HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n\r\nzz\r\nok\r\n0\r\n\r\n";
                    break;
                default:
                    response = "HTTP/1.1 200 OK\r\ncontent-length: 2\r\n\r\nok";
            }
            ctx.writeAndFlush(Unpooled.copiedBuffer(response, StandardCharsets.ISO_8859_1));
        }
    }
}
