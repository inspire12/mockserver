package org.mockserver.netty.connection;

import org.junit.After;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.metrics.Metrics;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.MockServerCaTrustTestSupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpSseResponse.sseResponse;
import static org.mockserver.model.HttpWebSocketResponse.webSocketResponse;
import static org.mockserver.model.SseEvent.sseEvent;
import static org.mockserver.model.WebSocketMessage.webSocketMessage;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * inboundConnectionIdleTimeoutMillis over real sockets: a quiet connection is closed after the timeout,
 * while every kind of connection that is legitimately waiting - a delayed response, a streaming
 * response with long gaps, an open HTTP/2 stream, a WebSocket, a paused breakpoint - outlives it. A
 * CONNECT or SOCKS tunnel is treated the same way: both of its legs are closed once it has carried
 * nothing for the timeout with no exchange in progress, and a tunnel with one in progress outlives it.
 * Each "stays open" case waits for at least three times the timeout.
 */
public class InboundConnectionIdleTimeoutIntegrationTest {

    private static final long IDLE_MILLIS = 400;
    private static final long WAIT_MILLIS = 3 * IDLE_MILLIS;

    private MockServer mockServer;
    private MockServerClient mockServerClient;
    private int port;

    private void startServer(long idleTimeoutMillis) {
        mockServer = new MockServer(configuration()
            .inboundConnectionIdleTimeoutMillis(idleTimeoutMillis)
            .metricsEnabled(true)
            // no start-up warm-up connection, and the bundled CA the HTTP/2 client trusts
            .startupWarmup(false)
            .proxySetup(false)
            .dynamicallyCreateCertificateAuthorityCertificate(false), 0);
        port = mockServer.getLocalPort();
        mockServerClient = new MockServerClient("localhost", port);
    }

    @After
    public void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Test
    public void shouldCloseConnectionThatNeverSendsAnything() throws Exception {
        startServer(IDLE_MILLIS);
        long idleClosedBefore = Metrics.getInboundConnectionsIdleClosedCount();
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            long start = System.nanoTime();

            assertThat(socket.getInputStream().read(), is(-1));

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(elapsedMillis, greaterThanOrEqualTo(IDLE_MILLIS - 50));
            assertThat(elapsedMillis, lessThan(IDLE_MILLIS + 5_000));
        }
        assertThat(Metrics.getInboundConnectionsIdleClosedCount() - idleClosedBefore, greaterThanOrEqualTo(1L));
    }

    @Test
    public void shouldCloseKeepAliveConnectionOnceIdleAfterItsResponse() throws Exception {
        startServer(IDLE_MILLIS);
        mockServerClient.when(request().withPath("/simple")).respond(response().withBody("simple"));
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);

            String firstResponse = exchange(socket, "/simple");
            assertThat(firstResponse, containsString("200"));
            assertThat("keep-alive is honoured while the client is active", exchange(socket, "/simple"), containsString("simple"));
            long answered = System.nanoTime();

            assertThat(socket.getInputStream().read(), is(-1));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - answered), greaterThanOrEqualTo(IDLE_MILLIS - 50));
        }
    }

    @Test
    public void shouldCloseKeepAliveConnectionOnceIdleAfterARawBytesResponse() throws Exception {
        startServer(IDLE_MILLIS);
        mockServerClient.when(request().withPath("/raw")).error(error()
            .withResponseBytes("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nraw".getBytes(StandardCharsets.UTF_8)));
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);

            assertThat(exchange(socket, "/raw"), containsString("raw"));
            long answered = System.nanoTime();

            assertThat("the raw-bytes exchange no longer keeps the connection busy forever", socket.getInputStream().read(), is(-1));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - answered), greaterThanOrEqualTo(IDLE_MILLIS - 50));
        }
    }

    @Test
    public void shouldNotCloseConnectionWhenTimeoutDisabled() throws Exception {
        startServer(0);
        try (Socket socket = new Socket("localhost", port)) {
            assertStillOpenAfter(socket, WAIT_MILLIS);
        }
    }

    @Test
    public void shouldNotCloseConnectionWhileResponseIsDelayed() throws Exception {
        startServer(IDLE_MILLIS);
        mockServerClient.when(request().withPath("/slow")).respond(response().withBody("slow but sure").withDelay(TimeUnit.MILLISECONDS, WAIT_MILLIS));
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);

            assertThat(exchange(socket, "/slow"), containsString("slow but sure"));
        }
    }

    @Test
    public void shouldNotCloseConnectionWhileBreakpointHoldsTheExchange() throws Exception {
        startServer(IDLE_MILLIS);
        mockServerClient.when(request().withPath("/paused")).respond(response().withBody("released"));
        mockServerClient.addBreakpoint(request().withPath("/paused"), pausedRequest -> {
            try {
                Thread.sleep(WAIT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return pausedRequest;
        });
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);

            assertThat("both the paused exchange and the client's breakpoint WebSocket outlive the timeout",
                exchange(socket, "/paused"), containsString("released"));
        }
    }

    @Test
    public void shouldNotCloseSlowReaderOfLargePacedBodyWhileItStopsReading() throws Exception {
        startServer(IDLE_MILLIS);
        int bodyBytes = 4 * 1024 * 1024;
        mockServerClient.when(request().withPath("/large")).respond(response().withBody(new String(new char[bodyBytes]).replace('\0', 'x')));
        try (Socket socket = new Socket()) {
            // a small receive window so most of the body stays queued on the server while the client pauses
            socket.setReceiveBufferSize(16 * 1024);
            socket.connect(new java.net.InetSocketAddress("localhost", port));
            socket.setSoTimeout(10_000);
            send(socket, "GET /large HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n");
            InputStream input = socket.getInputStream();
            String head = readHead(input);
            assertThat(head, containsString("200"));
            byte[] first = readBytes(input, 64 * 1024);

            // the client sends and reads nothing, and the server cannot write: idle on both sides
            Thread.sleep(WAIT_MILLIS);

            byte[] rest = readBytes(input, bodyBytes - first.length);
            assertThat("the whole paced body arrives after the pause", first.length + rest.length, is(bodyBytes));
            assertThat(rest[rest.length - 1], is((byte) 'x'));
        }
    }

    @Test
    public void shouldNotCloseStreamingResponseWithGapsLongerThanTimeout() throws Exception {
        startServer(IDLE_MILLIS);
        mockServerClient.when(request().withPath("/sse")).respondWithSse(
            sseResponse()
                .withEvent(sseEvent().withData("first"))
                .withEvent(sseEvent().withData("second").withDelay(TimeUnit.MILLISECONDS, WAIT_MILLIS))
                .withEvent(sseEvent().withData("third").withDelay(TimeUnit.MILLISECONDS, WAIT_MILLIS))
                .withCloseConnection(true)
        );
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            send(socket, "GET /sse HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n");

            String stream = readUntilClosed(socket);

            assertThat(stream, containsString("data: first"));
            assertThat(stream, containsString("data: second"));
            assertThat(stream, containsString("data: third"));
        }
    }

    @Test
    public void shouldNotCloseWebSocketThatIsQuietForLongerThanTimeout() throws Exception {
        startServer(IDLE_MILLIS);
        mockServerClient.when(request().withPath("/ws")).respondWithWebSocket(
            webSocketResponse().withMessage(webSocketMessage("after the quiet").withDelay(TimeUnit.MILLISECONDS, WAIT_MILLIS))
        );
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            send(socket, "GET /ws HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n");
            String handshake = readHead(socket.getInputStream());
            assertThat(handshake, containsString("101"));

            byte[] frame = readBytes(socket.getInputStream(), 2 + "after the quiet".length());

            assertThat(new String(frame, 2, frame.length - 2, StandardCharsets.UTF_8), is("after the quiet"));
        }
    }

    @Test
    public void shouldCloseBothLegsOfAConnectTunnelOnceIdleAfterItsResponse() throws Exception {
        startServer(IDLE_MILLIS);
        mockServerClient.when(request().withPath("/through-tunnel")).respond(response().withBody("tunnelled"));
        long idleClosedBefore = Metrics.getInboundConnectionsIdleClosedCount();
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            openConnectTunnel(socket);
            assertThat(exchange(socket, "/through-tunnel"), containsString("tunnelled"));
            // taken before the tunnel's last activity, so the bound holds however long the client takes to read it
            long beforeLastExchange = System.nanoTime();
            assertThat("the tunnel is kept alive between requests", exchange(socket, "/through-tunnel"), containsString("tunnelled"));

            assertThat(socket.getInputStream().read(), is(-1));

            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beforeLastExchange), greaterThanOrEqualTo(IDLE_MILLIS - 50));
            awaitNoInboundConnections("the tunnel's loopback leg closes with it");
        }
        assertThat(Metrics.getInboundConnectionsIdleClosedCount() - idleClosedBefore, greaterThanOrEqualTo(1L));
    }

    @Test
    public void shouldCloseBothLegsOfAConnectTunnelThatNeverCarriesAnything() throws Exception {
        startServer(IDLE_MILLIS);
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            long beforeOpening = System.nanoTime();
            openConnectTunnel(socket);
            assertThat("the client's leg and the loopback leg", mockServer.getInboundConnectionCount(), is(2));

            assertThat(socket.getInputStream().read(), is(-1));

            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beforeOpening), greaterThanOrEqualTo(IDLE_MILLIS - 50));
            awaitNoInboundConnections("the tunnel's loopback leg closes with it");
        }
    }

    @Test
    public void shouldCloseBothLegsOfASocksTunnelOnceIdleAfterItsResponse() throws Exception {
        startServer(IDLE_MILLIS);
        mockServerClient.when(request().withPath("/through-tunnel")).respond(response().withBody("tunnelled"));
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            openSocks5Tunnel(socket);
            long beforeLastExchange = System.nanoTime();
            assertThat(exchange(socket, "/through-tunnel"), containsString("tunnelled"));

            assertThat(socket.getInputStream().read(), is(-1));

            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beforeLastExchange), greaterThanOrEqualTo(IDLE_MILLIS - 50));
            awaitNoInboundConnections("the tunnel's loopback leg closes with it");
        }
    }

    @Test
    public void shouldCloseTheLoopbackLegOfATunnelWhoseClientLeavesBeforeSendingAnything() throws Exception {
        startServer(0);
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            openSocks5Tunnel(socket);
            assertThat("the client's leg and the loopback leg", mockServer.getInboundConnectionCount(), is(2));
        }
        awaitNoInboundConnections("a SOCKS tunnel's loopback leg closes when its client leaves");
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            openConnectTunnel(socket);
            assertThat("the client's leg and the loopback leg", mockServer.getInboundConnectionCount(), is(2));
        }
        awaitNoInboundConnections("a CONNECT tunnel's loopback leg closes when its client leaves");
    }

    @Test
    public void shouldNotCloseConnectTunnelWhenTimeoutDisabled() throws Exception {
        startServer(0);
        mockServerClient.when(request().withPath("/through-tunnel")).respond(response().withBody("tunnelled"));
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            openConnectTunnel(socket);

            Thread.sleep(WAIT_MILLIS);

            assertThat("both tunnel legs survive the silence", exchange(socket, "/through-tunnel"), containsString("tunnelled"));
        }
    }

    @Test
    public void shouldNotCloseConnectTunnelWhileItsResponseIsDelayed() throws Exception {
        startServer(IDLE_MILLIS);
        mockServerClient.when(request().withPath("/slow")).respond(response().withBody("slow but sure").withDelay(TimeUnit.MILLISECONDS, WAIT_MILLIS));
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            openConnectTunnel(socket);

            assertThat(exchange(socket, "/slow"), containsString("slow but sure"));
        }
    }

    @Test
    public void shouldNotCloseConnectTunnelWhileItsRequestIsNeverAnswered() throws Exception {
        startServer(IDLE_MILLIS);
        // answered with nothing, and the connection left open
        mockServerClient.when(request().withPath("/held")).error(error());
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            openConnectTunnel(socket);
            send(socket, "GET /held HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n");

            assertStillOpenAfter(socket, WAIT_MILLIS);
        }
    }

    @Test
    public void shouldNotCloseConnectTunnelThatHasSwitchedProtocols() throws Exception {
        startServer(IDLE_MILLIS);
        mockServerClient.when(request().withPath("/upgrade")).respond(response().withStatusCode(101).withHeader("Upgrade", "custom").withHeader("Connection", "Upgrade"));
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            openConnectTunnel(socket);
            send(socket, "GET /upgrade HTTP/1.1\r\nHost: localhost:" + port + "\r\nConnection: Upgrade\r\nUpgrade: custom\r\n\r\n");
            assertThat(readHead(socket.getInputStream()), containsString("101"));

            assertStillOpenAfter(socket, WAIT_MILLIS);
        }
    }

    @Test
    public void shouldNotCloseConnectTunnelWhileItsRequestIsStillBeingUploaded() throws Exception {
        startServer(IDLE_MILLIS);
        mockServerClient.when(request().withPath("/upload").withBody("first half, second half")).respond(response().withBody("uploaded"));
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            openConnectTunnel(socket);
            send(socket, "POST /upload HTTP/1.1\r\nHost: localhost:" + port + "\r\nContent-Length: 23\r\n\r\nfirst half, ");

            // nothing crosses either leg: the relay holds the request until its body is complete
            Thread.sleep(WAIT_MILLIS);
            send(socket, "second half");

            String head = readHead(socket.getInputStream());
            assertThat(head, containsString("200"));
            assertThat(new String(readBytes(socket.getInputStream(), "uploaded".length()), StandardCharsets.UTF_8), is("uploaded"));
        }
    }

    @Test
    public void shouldNotCloseConnectTunnelWhileAStreamedResponseHasGapsLongerThanTimeout() throws Exception {
        startServer(IDLE_MILLIS);
        mockServerClient.when(request().withPath("/sse")).respondWithSse(
            sseResponse()
                .withEvent(sseEvent().withData("first"))
                .withEvent(sseEvent().withData("second").withDelay(TimeUnit.MILLISECONDS, WAIT_MILLIS))
                .withEvent(sseEvent().withData("third").withDelay(TimeUnit.MILLISECONDS, WAIT_MILLIS))
                .withCloseConnection(true)
        );
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            openConnectTunnel(socket);
            send(socket, "GET /sse HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n");

            String stream = readUntilClosed(socket);

            assertThat(stream, containsString("data: first"));
            assertThat(stream, containsString("data: second"));
            assertThat(stream, containsString("data: third"));
        }
    }

    @Test
    public void shouldNotCloseConnectTunnelWhileBytesKeepArrivingWithNoExchangeInProgress() throws Exception {
        long idleMillis = 1_000;
        startServer(idleMillis);
        mockServerClient.when(request().withPath("/through-tunnel")).respond(response().withBody("tunnelled"));
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            openConnectTunnel(socket);
            String request = "GET /through-tunnel HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n";
            long started = System.nanoTime();

            // a request head arriving a few bytes at a time: no request has been decoded, so only the bytes keep it open
            for (int sent = 0; sent < request.length(); sent += 4) {
                send(socket, request.substring(sent, Math.min(request.length(), sent + 4)));
                Thread.sleep(idleMillis / 5);
            }

            assertThat("the head took longer than the timeout to arrive", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), greaterThanOrEqualTo(2 * idleMillis));
            String head = readHead(socket.getInputStream());
            assertThat(head, containsString("200"));
            assertThat(new String(readBytes(socket.getInputStream(), "tunnelled".length()), StandardCharsets.UTF_8), is("tunnelled"));
        }
    }

    @Test
    public void shouldNotCloseHttp2ConnectTunnelWhileStreamIsOpenAndCloseItOnceIdle() throws Exception {
        startServer(IDLE_MILLIS);
        mockServerClient.when(request().withPath("/h2-slow")).respond(response().withBody("h2 slow").withDelay(TimeUnit.MILLISECONDS, WAIT_MILLIS));
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .proxy(ProxySelector.of(new InetSocketAddress("localhost", port)))
            .sslContext(MockServerCaTrustTestSupport.caTrustingSslContext())
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        HttpResponse<String> response = httpClient.send(
            java.net.http.HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/h2-slow")).timeout(Duration.ofSeconds(10)).build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.version(), is(HttpClient.Version.HTTP_2));
        assertThat(response.body(), is("h2 slow"));
        // the client keeps its tunnel pooled, so only the idle timeout closes it
        awaitNoInboundConnections("the idle HTTP/2 tunnel and its loopback leg are closed");
    }

    @Test
    public void shouldNotCloseHttp2ConnectionWhileStreamIsOpen() throws Exception {
        startServer(IDLE_MILLIS);
        mockServerClient.when(request().withPath("/h2-slow")).respond(response().withBody("h2 slow").withDelay(TimeUnit.MILLISECONDS, WAIT_MILLIS));
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .sslContext(MockServerCaTrustTestSupport.caTrustingSslContext())
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        HttpResponse<String> response = httpClient.send(
            java.net.http.HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/h2-slow")).timeout(Duration.ofSeconds(10)).build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.version(), is(HttpClient.Version.HTTP_2));
        assertThat(response.body(), is("h2 slow"));
    }

    private void openConnectTunnel(Socket socket) throws IOException {
        send(socket, "CONNECT localhost:" + port + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n");
        assertThat(readHead(socket.getInputStream()), containsString("200"));
    }

    private void openSocks5Tunnel(Socket socket) throws IOException {
        OutputStream output = socket.getOutputStream();
        // version 5, one method offered: no authentication
        output.write(new byte[]{5, 1, 0});
        output.flush();
        assertThat(readBytes(socket.getInputStream(), 2), is(new byte[]{5, 0}));
        // version 5, CONNECT, reserved, IPv4 127.0.0.1, port
        output.write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port});
        output.flush();
        byte[] reply = readBytes(socket.getInputStream(), 10);
        assertThat("SOCKS5 success", reply[1], is((byte) 0));
    }

    private void awaitNoInboundConnections(String reason) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (mockServer.getInboundConnectionCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(reason, mockServer.getInboundConnectionCount(), is(0));
    }

    private static void send(Socket socket, String request) throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write(request.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private String exchange(Socket socket, String path) throws IOException {
        send(socket, "GET " + path + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n");
        InputStream input = socket.getInputStream();
        String head = readHead(input);
        int contentLength = 0;
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        return head + new String(readBytes(input, contentLength), StandardCharsets.UTF_8);
    }

    private static String readHead(InputStream input) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        byte[] terminator = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        while (matched < terminator.length) {
            int next = input.read();
            if (next == -1) {
                throw new IOException("connection closed while reading response head: " + head);
            }
            head.write(next);
            matched = next == terminator[matched] ? matched + 1 : (next == terminator[0] ? 1 : 0);
        }
        return head.toString(StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int read = 0;
        while (read < length) {
            int count = input.read(bytes, read, length - read);
            if (count == -1) {
                throw new IOException("connection closed after " + read + " of " + length + " bytes");
            }
            read += count;
        }
        return bytes;
    }

    private static String readUntilClosed(Socket socket) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = socket.getInputStream().read(buffer)) != -1) {
            all.write(buffer, 0, count);
        }
        return all.toString(StandardCharsets.UTF_8);
    }

    private static void assertStillOpenAfter(Socket socket, long millis) throws IOException {
        socket.setSoTimeout((int) millis);
        try {
            int read = socket.getInputStream().read();
            fail("expected the connection to stay open and silent, but read " + read);
        } catch (SocketTimeoutException expected) {
            // still open
        }
    }
}
