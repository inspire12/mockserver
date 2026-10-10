package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.MockServer;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * What MockServer logs when an HTTP/1.1 connection closes while a request's body is still arriving, on a connection
 * made straight to MockServer and on the client leg of a CONNECT tunnel: one {@code INFO} entry naming the request,
 * with no stack trace, whether the client closed the connection or reset it. HTTP/1.1 has no other way to give up on
 * an upload, so this is a client's choice and not MockServer's fault.
 */
public class Http1UploadCutShortLoggingIntegrationTest {

    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final String ENDED_WITH_CONNECTION = "HTTP/1.1 request from:{}ended with its connection before it was complete:{}";

    private static final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    private enum Route {
        DIRECT, CONNECT_TUNNEL
    }

    @BeforeClass
    public static void startServer() {
        // a clone: the event log clears the entry it is handed once it has copied it
        MockServerLogger.setGlobalLogEventListener(logEntry -> logged.add(logEntry.clone()));
        // the test JVM defaults to ERROR, which would drop the entries this class asserts on
        mockServer = new MockServer(configuration().logLevel("INFO"), 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServer() {
        try {
            stopQuietly(mockServerClient);
            stopQuietly(mockServer);
        } finally {
            MockServerLogger.setGlobalLogEventListener(null);
        }
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
        mockServerClient.when(request().withPath("/upload")).respond(response().withBody("uploaded"));
        logged.clear();
    }

    @Test
    public void shouldLogAnUploadItsClientClosesOnceAtInfoWithTheRequest() throws Exception {
        for (Route route : Route.values()) {
            int clientPort;
            try (Socket socket = connect(route)) {
                clientPort = socket.getLocalPort();
                write(socket, "POST /upload?part=1 HTTP/1.1\r\nHost: " + authority() + "\r\nContent-Length: 100\r\nX-Upload: first\r\n\r\nabcd");
            }

            assertLoggedOnceAtInfo(route, clientPort, awaitEntries(clientPort));
        }
        assertThat("no upload was dispatched", mockServerClient.retrieveRecordedRequests(request().withMethod("POST")), emptyArray());
    }

    @Test
    public void shouldLogAChunkedUploadItsClientClosesOnceAtInfoWithTheRequest() throws Exception {
        for (Route route : Route.values()) {
            int clientPort;
            try (Socket socket = connect(route)) {
                clientPort = socket.getLocalPort();
                write(socket, "POST /upload?part=1 HTTP/1.1\r\nHost: " + authority() + "\r\nTransfer-Encoding: chunked\r\nX-Upload: first\r\n\r\n4\r\nabcd\r\n");
            }

            assertLoggedOnceAtInfo(route, clientPort, awaitEntries(clientPort));
        }
        assertThat("no upload was dispatched", mockServerClient.retrieveRecordedRequests(request().withMethod("POST")), emptyArray());
    }

    /**
     * The transport reports a reset as its own exception before the connection closes; it counts as a close.
     */
    @Test
    public void shouldLogAnUploadItsClientResetsOnceAtInfoWithTheRequest() throws Exception {
        for (Route route : Route.values()) {
            int clientPort;
            try (Socket socket = connect(route)) {
                clientPort = socket.getLocalPort();
                // the 100 Continue says the request's head has been read, which a reset could otherwise discard
                write(socket, "POST /upload?part=1 HTTP/1.1\r\nHost: " + authority() + "\r\nContent-Length: 100\r\nExpect: 100-continue\r\nX-Upload: first\r\n\r\n");
                assertThat(route.name(), readResponseHead(socket.getInputStream()), startsWith("HTTP/1.1 100 "));
                write(socket, "abcd");
                socket.setSoLinger(true, 0);
            }

            assertLoggedOnceAtInfo(route, clientPort, awaitEntries(clientPort));
        }
        assertThat("no upload was dispatched", mockServerClient.retrieveRecordedRequests(request().withMethod("POST")), emptyArray());
    }

    @Test
    public void shouldLogNothingForAConnectionClosedOnceItsRequestIsComplete() throws Exception {
        for (Route route : Route.values()) {
            int clientPort;
            try (Socket socket = connect(route)) {
                clientPort = socket.getLocalPort();
                write(socket, "POST /upload HTTP/1.1\r\nHost: " + authority() + "\r\nContent-Length: 4\r\n\r\nabcd");
                assertThat(route.name(), readResponseHead(socket.getInputStream()), startsWith("HTTP/1.1 200 "));
            }
            // a request on another connection, answered once MockServer has handled the first one's close
            try (Socket socket = connect(Route.DIRECT)) {
                write(socket, "GET /upload HTTP/1.1\r\nHost: " + authority() + "\r\nContent-Length: 0\r\n\r\n");
                assertThat(route.name(), readResponseHead(socket.getInputStream()), startsWith("HTTP/1.1 200 "));
            }

            assertThat(route.name(), entries(clientPort), empty());
            assertThat(route.name(), warningsAndErrors(), empty());
        }
    }

    private static void assertLoggedOnceAtInfo(Route route, int clientPort, List<LogEntry> entries) {
        assertThat(route.name(), entries, hasSize(1));
        LogEntry entry = entries.get(0);
        assertThat(route.name(), entry.getLogLevel(), is(Level.INFO));
        assertThat(route.name(), entry.getMessageFormat(), is(ENDED_WITH_CONNECTION));
        assertThat(route.name(), entry.getThrowable(), is(nullValue()));
        HttpRequest request = (HttpRequest) entry.getHttpRequest();
        assertThat(route.name(), request.getMethod().getValue(), is("POST"));
        assertThat(route.name(), request.getPath().getValue(), is("/upload"));
        assertThat(route.name(), request.getFirstQueryStringParameter("part"), is("1"));
        assertThat(route.name(), request.getFirstHeader("X-Upload"), is("first"));
        assertThat(route.name() + ": from either leg of a tunnel", cutShortEntries(), hasSize(1));
        assertThat(route.name(), warningsAndErrors(), empty());
        logged.clear();
    }

    private static Socket connect(Route route) throws IOException {
        Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort());
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        socket.setTcpNoDelay(true);
        if (route == Route.CONNECT_TUNNEL) {
            write(socket, "CONNECT " + authority() + " HTTP/1.1\r\nHost: " + authority() + "\r\n\r\n");
            assertThat(readResponseHead(socket.getInputStream()), startsWith("HTTP/1.1 200 "));
        }
        return socket;
    }

    private static void write(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String authority() {
        return "127.0.0.1:" + mockServer.getLocalPort();
    }

    /**
     * The entries MockServer logged about an HTTP/1.1 request from a client's port.
     */
    private static List<LogEntry> entries(int clientPort) {
        Pattern address = Pattern.compile(":" + clientPort + "(?!\\d)");
        return cutShortEntries().stream()
            .filter(entry -> address.matcher(Arrays.toString(entry.getArguments())).find())
            .collect(Collectors.toList());
    }

    private static List<LogEntry> cutShortEntries() {
        return logged.stream()
            .filter(entry -> String.valueOf(entry.getMessageFormat()).contains("HTTP/1.1 request"))
            .collect(Collectors.toList());
    }

    private static List<LogEntry> awaitEntries(int clientPort) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (entries(clientPort).isEmpty() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        // long enough for a second entry, such as one from the tunnel's other leg, to have been logged
        TimeUnit.MILLISECONDS.sleep(200);
        return entries(clientPort);
    }

    private static List<LogEntry> warningsAndErrors() {
        return logged.stream()
            .filter(entry -> entry.getLogLevel() == Level.WARN || entry.getLogLevel() == Level.ERROR)
            .collect(Collectors.toList());
    }

    private static String readResponseHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (matched < end.length) {
            int read = in.read();
            if (read == -1) {
                break;
            }
            head.write(read);
            matched = read == end[matched] ? matched + 1 : (read == end[0] ? 1 : 0);
        }
        return new String(head.toByteArray(), StandardCharsets.US_ASCII);
    }
}
