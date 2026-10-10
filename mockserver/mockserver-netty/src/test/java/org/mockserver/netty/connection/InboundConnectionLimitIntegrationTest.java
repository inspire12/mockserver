package org.mockserver.netty.connection;

import org.junit.After;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.metrics.Metrics;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * maxInboundConnections over real sockets: the connection beyond the limit is closed at once rather than
 * left waiting, the server recovers as soon as a slot frees, and a CONNECT tunnel whose internal
 * loopback leg is refused fails promptly instead of hanging.
 */
public class InboundConnectionLimitIntegrationTest {

    private MockServer mockServer;
    private int port;

    private void startServer(int maxInboundConnections) {
        // startupWarmup off: its loopback keep-alive connection would otherwise hold a slot for ~5 seconds
        mockServer = new MockServer(configuration()
            .maxInboundConnections(maxInboundConnections)
            .metricsEnabled(true)
            .startupWarmup(false)
            // the test JVM defaults to ERROR, which would drop the WARN this class asserts on
            .logLevel("WARN"), 0);
        port = mockServer.getLocalPort();
    }

    @After
    public void stopServer() {
        stopQuietly(mockServer);
    }

    @Test
    public void shouldRefuseConnectionBeyondLimitAndRecoverWhenOneCloses() throws Exception {
        startServer(2);
        long rejectedBefore = Metrics.getInboundConnectionsRejectedCount();
        List<Socket> admitted = new ArrayList<>();
        try {
            admitted.add(openAndExchange());
            admitted.add(openAndExchange());
            assertThat(mockServer.getInboundConnectionCount(), is(2));

            try (Socket refused = new Socket("localhost", port)) {
                refused.setSoTimeout(10_000);
                long start = System.nanoTime();
                assertClosedByServer(refused);
                assertThat("refused at once, not accepted and left hanging",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), lessThan(5_000L));
            }
            assertThat(mockServer.getInboundConnectionCount(), is(2));
            assertThat(Metrics.getInboundConnectionsRejectedCount() - rejectedBefore, is(1L));

            admitted.remove(0).close();
            awaitTrue(() -> mockServer.getInboundConnectionCount() == 1);

            admitted.add(openAndExchange());
            assertThat(mockServer.getInboundConnectionCount(), is(2));
        } finally {
            for (Socket socket : admitted) {
                socket.close();
            }
        }
    }

    @Test
    public void shouldLogRefusal() throws Exception {
        // two slots: the Java client below uses one connection for its version check and another for the retrieve
        startServer(2);
        try (Socket first = openAndExchange(); Socket second = openAndExchange()) {
            try (Socket refused = new Socket("localhost", port)) {
                refused.setSoTimeout(10_000);
                assertClosedByServer(refused);
            }
            first.close();
            second.close();
            awaitTrue(() -> mockServer.getInboundConnectionCount() == 0);

            MockServerClient mockServerClient = new MockServerClient("localhost", port);
            String logs = mockServerClient.retrieveLogMessages(null);
            assertThat(logs, containsString("refused inbound connection"));
            assertThat(logs, containsString("maxInboundConnections"));
        }
    }

    @Test
    public void shouldFailConnectTunnelPromptlyWhenItsLoopbackLegIsRefused() throws Exception {
        startServer(1);
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream output = socket.getOutputStream();
            output.write(("CONNECT localhost:" + port + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            // the tunnel's loopback leg to MockServer itself is the second connection, so it is refused
            assertThat(readHead(socket.getInputStream()), containsString("502"));
        }
    }

    @Test
    public void shouldNotLimitConnectionsByDefault() throws Exception {
        startServer(0);
        List<Socket> sockets = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                sockets.add(openAndExchange());
            }
            assertThat(mockServer.getInboundConnectionCount(), is(20));
        } finally {
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }

    private Socket openAndExchange() throws IOException {
        Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(10_000);
        OutputStream output = socket.getOutputStream();
        output.write(("PUT /mockserver/status HTTP/1.1\r\nHost: localhost:" + port + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
        String head = readHead(socket.getInputStream());
        assertThat(head, containsString("200"));
        int contentLength = 0;
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        socket.getInputStream().readNBytes(contentLength);
        return socket;
    }

    private static void assertClosedByServer(Socket socket) throws IOException {
        try {
            OutputStream output = socket.getOutputStream();
            output.write("PUT /mockserver/status HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            int read = socket.getInputStream().read();
            assertThat("expected the refused connection to be closed, but it answered", read, is(-1));
        } catch (SocketException reset) {
            // connection reset: refused
        }
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

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("condition not met within 10 seconds");
            }
            Thread.sleep(10);
        }
    }
}
