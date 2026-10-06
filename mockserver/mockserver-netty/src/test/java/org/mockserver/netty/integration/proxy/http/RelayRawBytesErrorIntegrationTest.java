package org.mockserver.netty.integration.proxy.http;

import org.junit.After;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpError;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.MockServerCaTrustTestSupport;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.LogEntrySerializer;
import org.mockserver.server.initialize.ExpectationInitializer;
import org.slf4j.event.Level;

import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * An {@code error()} that writes raw bytes, over real sockets: a client behind an HTTP/1.1 CONNECT or SOCKS tunnel
 * must receive exactly the bytes a client on a direct connection receives, whether or not they are an HTTP response,
 * and its tunnel must then close as a direct connection does (with the bytes, or as idle).
 */
public class RelayRawBytesErrorIntegrationTest {

    private static final long IDLE_MILLIS = 400;
    private static final byte[] INCOMPLETE = "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nseven!!".getBytes(StandardCharsets.US_ASCII);
    // a status line and a header no encoder would write this way
    private static final byte[] WHOLE = "HTTP/1.1 200 Fine By Me\r\nX-Mixed-CASE:   spaced  \r\ncontent-LENGTH: 3\r\n\r\nraw".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NOT_HTTP = new byte[]{0, 1, 2, (byte) 0xff, 'n', 'o', 't', ' ', 'h', 't', 't', 'p', '\r', '\n', '\r', '\n', (byte) 0xfe};

    private MockServer mockServer;
    private int port;

    private void startServer(long idleTimeoutMillis) {
        startServer(idleTimeoutMillis, "");
    }

    private void startServer(long idleTimeoutMillis, String initializationClass) {
        // WARN: the entries asserted on below must reach the event log whatever level the build runs tests at
        mockServer = new MockServer(configuration()
            .logLevel("WARN")
            .inboundConnectionIdleTimeoutMillis(idleTimeoutMillis)
            .initializationClass(initializationClass)
            .startupWarmup(false)
            .proxySetup(false)
            .dynamicallyCreateCertificateAuthorityCertificate(false), 0);
        port = mockServer.getLocalPort();
    }

    /**
     * Answers {@code /raw} with an {@code error()} of no bytes. An empty array is left out of an expectation's JSON,
     * so the control plane cannot be sent one: the expectation is loaded in the server's own JVM.
     */
    public static class EmptyRawBytes implements ExpectationInitializer {
        @Override
        public Expectation[] initializeExpectations() {
            return new Expectation[]{new Expectation(request().withPath("/raw")).thenError(error().withResponseBytes(new byte[0]))};
        }
    }

    @After
    public void stopServer() {
        stopQuietly(mockServer);
    }

    @Test
    public void shouldRelayAnIncompleteRawResponseAndCloseTheTunnelAsIdle() throws Exception {
        relaysRawBytesAndClosesAsIdle(INCOMPLETE);
    }

    @Test
    public void shouldRelayRawBytesThatAreNotHttpAndCloseTheTunnelAsIdle() throws Exception {
        relaysRawBytesAndClosesAsIdle(NOT_HTTP);
    }

    @Test
    public void shouldRelayAWholeRawResponseByteForByteAndCloseTheTunnelAsIdle() throws Exception {
        relaysRawBytesAndClosesAsIdle(WHOLE);
    }

    @Test
    public void shouldRelayRawBytesLargerThanOneReadAndCloseTheTunnelAsIdle() throws Exception {
        relaysRawBytesAndClosesAsIdle(largeBytes(300_000));
    }

    @Test
    public void shouldCloseTheTunnelAsIdleAfterAnEmptyRawResponse() throws Exception {
        relaysRawBytesAndClosesAsIdle(new byte[0]);
    }

    @Test
    public void shouldRelayAnIncompleteRawResponseAndThenTheClose() throws Exception {
        relaysRawBytesAndThenTheClose(INCOMPLETE);
    }

    @Test
    public void shouldRelayRawBytesThatAreNotHttpAndThenTheClose() throws Exception {
        relaysRawBytesAndThenTheClose(NOT_HTTP);
    }

    @Test
    public void shouldRelayAWholeRawResponseAndThenTheClose() throws Exception {
        relaysRawBytesAndThenTheClose(WHOLE);
    }

    @Test
    public void shouldRelayRawBytesLargerThanOneReadAndThenTheClose() throws Exception {
        relaysRawBytesAndThenTheClose(largeBytes(300_000));
    }

    @Test
    public void shouldAnswerTheNextRequestAfterRawBytesAsOnADirectConnection() throws Exception {
        startServer(0);
        respondWithError("/raw", error().withResponseBytes(INCOMPLETE));
        respondWithError("/whole", error().withResponseBytes(WHOLE));
        respondWith("/simple", response().withBody("simple"));

        for (Route route : Route.values()) {
            try (Socket socket = route.open(port)) {
                socket.setSoTimeout(10_000);
                for (String rawPath : new String[]{"/raw", "/whole"}) {
                    byte[] expected = rawPath.equals("/raw") ? INCOMPLETE : WHOLE;
                    send(socket, rawPath);
                    assertThat(route + ": the raw bytes of " + rawPath, readBytes(socket.getInputStream(), expected.length), is(expected));
                    send(socket, "/simple");
                    String head = readHead(socket.getInputStream());
                    assertThat(route + ": the response after " + rawPath, head, containsString("200"));
                    assertThat(route + ": the response after " + rawPath, new String(readBytes(socket.getInputStream(), "simple".length()), StandardCharsets.US_ASCII), is("simple"));
                }
            }
        }
    }

    @Test
    public void shouldAnswerMoreThan128RawBytesResponsesOnOneConnection() throws Exception {
        startServer(0);
        respondWithError("/whole", error().withResponseBytes(WHOLE));

        for (Route route : Route.values()) {
            try (Socket socket = route.open(port)) {
                socket.setSoTimeout(10_000);
                // Netty's HttpServerCodec counts each as a request still awaiting its response
                for (int i = 0; i < 200; i++) {
                    send(socket, "/whole");
                    assertThat(route + ": raw response " + i, readBytes(socket.getInputStream(), WHOLE.length, route + ": raw response " + i), is(WHOLE));
                }
            }
        }
    }

    @Test
    public void shouldAnswerMoreThan128PipelinedRequestsOnOneConnection() throws Exception {
        startServer(0);
        respondWith("/simple", response().withBody("simple"));
        StringBuilder pipelined = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            pipelined.append(requestText("/simple"));
        }

        for (Route route : Route.values()) {
            try (Socket socket = route.open(port)) {
                socket.setSoTimeout(10_000);
                OutputStream output = socket.getOutputStream();
                output.write(pipelined.toString().getBytes(StandardCharsets.US_ASCII));
                output.flush();
                for (int i = 0; i < 200; i++) {
                    assertThat(route + ": response " + i, new String(readBytes(socket.getInputStream(), 1, route + ": response " + i), StandardCharsets.US_ASCII) + readHead(socket.getInputStream()), containsString("200"));
                    assertThat(route + ": response " + i, new String(readBytes(socket.getInputStream(), "simple".length()), StandardCharsets.US_ASCII), is("simple"));
                }
            }
        }
    }

    @Test
    public void shouldNotEndAPipelinedExchangeEarlyWhenRawBytesSpanSeveralReads() throws Exception {
        startServer(IDLE_MILLIS);
        byte[] large = largeBytes(300_000);
        respondWithError("/raw", error().withResponseBytes(large));
        respondWith("/slow", response().withBody("slow but sure").withDelay(TimeUnit.MILLISECONDS, 3 * IDLE_MILLIS));

        for (Route route : Route.values()) {
            try (Socket socket = route.open(port)) {
                socket.setSoTimeout(10_000);
                OutputStream output = socket.getOutputStream();
                output.write((requestText("/raw") + requestText("/slow")).getBytes(StandardCharsets.US_ASCII));
                output.flush();

                assertThat(route + ": the raw bytes", Arrays.equals(readBytes(socket.getInputStream(), large.length), large), is(true));
                // the second exchange is still in progress for three idle periods: ended early, the tunnel would be closed
                assertThat(route + ": the delayed response", readHead(socket.getInputStream()), containsString("200"));
                assertThat(route + ": the delayed response", new String(readBytes(socket.getInputStream(), "slow but sure".length()), StandardCharsets.US_ASCII), is("slow but sure"));
            }
        }
    }

    private void relaysRawBytesAndClosesAsIdle(byte[] bytes) throws Exception {
        if (bytes.length == 0) {
            startServer(IDLE_MILLIS, EmptyRawBytes.class.getName());
        } else {
            startServer(IDLE_MILLIS);
            respondWithError("/raw", error().withResponseBytes(bytes));
        }
        List<String> loggedBefore = loggedAtWarnOrAbove();

        for (Route route : Route.values()) {
            try (Socket socket = route.open(port)) {
                socket.setSoTimeout(10_000);
                long sent = System.nanoTime();
                send(socket, "/raw");

                Received received = readUntilClosed(socket);

                assertThat(route + ": closed by MockServer, as an idle direct connection is", received.closedByPeer, is(true));
                assertThat(route + ": the bytes a direct client receives", describe(received.bytes), is(describe(bytes)));
                assertThat(route + ": exactly the bytes a direct client receives", Arrays.equals(received.bytes, bytes), is(true));
                assertThat(route + ": not closed before it had been idle", millisSince(sent), greaterThanOrEqualTo(IDLE_MILLIS - 50));
            }
        }
        awaitNoInboundConnections();
        assertThat("nothing logged at WARN or above", loggedAtWarnOrAbove(), is(loggedBefore));
    }

    private void relaysRawBytesAndThenTheClose(byte[] bytes) throws Exception {
        // no idle timeout: only the error's own drop closes the connection
        startServer(0);
        HttpError rawBytesThenDrop = error().withResponseBytes(bytes).withDropConnection(true);
        respondWithError("/raw", rawBytesThenDrop);
        List<String> loggedBefore = loggedAtWarnOrAbove();

        for (Route route : Route.values()) {
            try (Socket socket = route.open(port)) {
                socket.setSoTimeout(10_000);
                long sent = System.nanoTime();
                send(socket, "/raw");

                Received received = readUntilClosed(socket);

                assertThat(route + ": closed by MockServer", received.closedByPeer, is(true));
                assertThat(route + ": the bytes a direct client receives", describe(received.bytes), is(describe(bytes)));
                assertThat(route + ": exactly the bytes a direct client receives", Arrays.equals(received.bytes, bytes), is(true));
                assertThat(route + ": closed with the bytes", millisSince(sent), lessThan(5_000L));
            }
        }
        awaitNoInboundConnections();
        assertThat("nothing logged at WARN or above", loggedAtWarnOrAbove(), is(loggedBefore));
    }

    private enum Route {
        DIRECT(false) {
            @Override
            void tunnel(Socket socket, int port) {
            }
        },
        DIRECT_TLS(true) {
            @Override
            void tunnel(Socket socket, int port) {
            }
        },
        CONNECT(false) {
            @Override
            void tunnel(Socket socket, int port) throws IOException {
                openConnectTunnel(socket, port);
            }
        },
        CONNECT_TLS(true) {
            @Override
            void tunnel(Socket socket, int port) throws IOException {
                openConnectTunnel(socket, port);
            }
        },
        SOCKS4(false) {
            @Override
            void tunnel(Socket socket, int port) throws IOException {
                openSocks4Tunnel(socket, port);
            }
        },
        SOCKS4_TLS(true) {
            @Override
            void tunnel(Socket socket, int port) throws IOException {
                openSocks4Tunnel(socket, port);
            }
        },
        SOCKS5(false) {
            @Override
            void tunnel(Socket socket, int port) throws IOException {
                openSocks5Tunnel(socket, port);
            }
        },
        SOCKS5_TLS(true) {
            @Override
            void tunnel(Socket socket, int port) throws IOException {
                openSocks5Tunnel(socket, port);
            }
        };

        private final boolean tls;

        Route(boolean tls) {
            this.tls = tls;
        }

        abstract void tunnel(Socket socket, int port) throws IOException;

        Socket open(int port) throws Exception {
            Socket socket = new Socket("127.0.0.1", port);
            try {
                socket.setSoTimeout(10_000);
                tunnel(socket, port);
                if (!tls) {
                    return socket;
                }
                // HTTP/1.1: no ALPN protocol is offered
                SSLSocket tlsSocket = (SSLSocket) MockServerCaTrustTestSupport.caTrustingSslContext().getSocketFactory().createSocket(socket, "localhost", port, true);
                tlsSocket.startHandshake();
                return tlsSocket;
            } catch (Exception e) {
                socket.close();
                throw e;
            }
        }
    }

    private static void openConnectTunnel(Socket socket, int port) throws IOException {
        socket.getOutputStream().write(("CONNECT localhost:" + port + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        assertThat(readHead(socket.getInputStream()), containsString("200"));
    }

    private static void openSocks4Tunnel(Socket socket, int port) throws IOException {
        OutputStream output = socket.getOutputStream();
        // version 4, CONNECT, port, IPv4 127.0.0.1, empty user id
        output.write(new byte[]{4, 1, (byte) (port >> 8), (byte) port, 127, 0, 0, 1, 0});
        output.flush();
        byte[] reply = readBytes(socket.getInputStream(), 8);
        assertThat("SOCKS4 request granted", reply[1], is((byte) 90));
    }

    private static void openSocks5Tunnel(Socket socket, int port) throws IOException {
        OutputStream output = socket.getOutputStream();
        // version 5, one method offered: no authentication
        output.write(new byte[]{5, 1, 0});
        output.flush();
        assertThat(readBytes(socket.getInputStream(), 2), is(new byte[]{5, 0}));
        // version 5, CONNECT, reserved, IPv4 127.0.0.1, port
        output.write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port});
        output.flush();
        // version, status, reserved, address type, and the first byte of the address
        byte[] reply = readBytes(socket.getInputStream(), 5);
        assertThat("SOCKS5 success", reply[1], is((byte) 0));
        int restOfAddress = reply[3] == 3 ? reply[4] : reply[3] == 1 ? 3 : 15;
        readBytes(socket.getInputStream(), restOfAddress + 2);
    }

    private String requestText(String path) {
        return "GET " + path + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n";
    }

    private void send(Socket socket, String path) throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write(requestText(path).getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static final class Received {
        private final byte[] bytes;
        private final boolean closedByPeer;

        private Received(byte[] bytes, boolean closedByPeer) {
            this.bytes = bytes;
            this.closedByPeer = closedByPeer;
        }
    }

    /**
     * Everything the connection delivers until its peer closes it, or until nothing has arrived for the socket's timeout.
     */
    private static Received readUntilClosed(Socket socket) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            for (int count; (count = socket.getInputStream().read(buffer)) != -1; ) {
                all.write(buffer, 0, count);
            }
            return new Received(all.toByteArray(), true);
        } catch (SocketTimeoutException stillOpen) {
            return new Received(all.toByteArray(), false);
        }
    }

    private static String readHead(InputStream input) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            head.write(readBytes(input, 1), 0, 1);
        }
        return head.toString(StandardCharsets.US_ASCII);
    }

    private static byte[] readBytes(InputStream input, int length, String what) throws IOException {
        try {
            return readBytes(input, length);
        } catch (IOException e) {
            throw new IOException(what + ": " + e.getMessage(), e);
        }
    }

    private static byte[] readBytes(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        for (int read = 0; read < length; ) {
            int count = input.read(bytes, read, length - read);
            if (count == -1) {
                throw new IOException("connection closed after " + read + " of " + length + " bytes");
            }
            read += count;
        }
        return bytes;
    }

    // short enough to read in a failure: the length, a hash and how the bytes start
    private static String describe(byte[] bytes) {
        String start = new String(bytes, 0, Math.min(bytes.length, 120), StandardCharsets.ISO_8859_1).replace("\r", "\\r").replace("\n", "\\n");
        return bytes.length + " bytes, hash " + Arrays.hashCode(bytes) + ", starting: " + start;
    }

    private static byte[] largeBytes(int length) {
        // a different value at every offset a read could end on, so a slice relayed out of place is seen
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 31 + (i >> 8));
        }
        return bytes;
    }

    private void respondWithError(String path, HttpError httpError) {
        controlPlane("/mockserver/expectation", new ExpectationSerializer(new MockServerLogger()).serialize(new Expectation(request().withPath(path)).thenError(httpError)));
    }

    private void respondWith(String path, HttpResponse httpResponse) {
        controlPlane("/mockserver/expectation", new ExpectationSerializer(new MockServerLogger()).serialize(new Expectation(request().withPath(path)).thenRespond(httpResponse)));
    }

    /**
     * One control-plane call on a connection of its own, closed with its response, so that the server's open connections
     * are soon the tunnel's alone.
     */
    private String controlPlane(String pathAndQuery, String json) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(("PUT " + pathAndQuery + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
            String text = new String(readUntilClosed(socket).bytes, StandardCharsets.UTF_8);
            assertThat(text, startsWith("HTTP/1.1 20"));
            return text.substring(text.indexOf("\r\n\r\n") + 4);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private List<String> loggedAtWarnOrAbove() {
        List<String> messages = new ArrayList<>();
        for (LogEntry entry : new LogEntrySerializer(new MockServerLogger()).deserializeArray(controlPlane("/mockserver/retrieve?type=LOGS&format=LOG_ENTRIES", ""))) {
            if (entry.getLogLevel() != null && entry.getLogLevel().toInt() >= Level.WARN.toInt()) {
                messages.add(entry.getLogLevel() + " " + entry.getMessageFormat());
            }
        }
        return messages;
    }

    private void awaitNoInboundConnections() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (mockServer.getInboundConnectionCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat("every tunnel's legs are closed", mockServer.getInboundConnectionCount(), is(0));
    }

    private static long millisSince(long nanoTime) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanoTime);
    }
}
