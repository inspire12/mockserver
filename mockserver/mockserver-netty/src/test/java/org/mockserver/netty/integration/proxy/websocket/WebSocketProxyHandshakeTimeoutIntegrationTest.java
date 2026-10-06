package org.mockserver.netty.integration.proxy.websocket;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * The WebSocket proxy relay waits for an upstream's handshake response as a forward waits for its response, at most
 * {@code maxSocketTimeout} and never longer than {@code maxFutureTimeout}: an upstream that accepts the connection and
 * never answers the upgrade, or stops part-way through its response head, fails the relay with a {@code 502} that says why, one WARN, and the upstream connection closed. A client that gives up first
 * has its upstream connection closed straight away. The client is a raw socket; the upstream is a server on 127.0.0.1
 * that records how each of its connections ended.
 */
public class WebSocketProxyHandshakeTimeoutIntegrationTest {

    private static final long TIMEOUT_MILLIS = 2_000;
    private static final String FAILURE = "WebSocket proxy passthrough failed";
    private static final String TIMED_OUT = "upstream WebSocket handshake response was not received within maxSocketTimeout (" + TIMEOUT_MILLIS + " ms)";
    private static final String FUTURE_TIMED_OUT = "upstream WebSocket handshake response was not received within maxFutureTimeout (" + TIMEOUT_MILLIS + " ms)";

    private static HandshakeUpstream upstream;
    private static MockServer bounded;
    private static MockServerClient boundedClient;
    private static MockServer patient;
    private static MockServerClient patientClient;
    private static Configuration futureBoundedConfiguration;
    private static MockServer futureBounded;
    private static MockServerClient futureBoundedClient;

    @BeforeClass
    public static void startServers() throws Exception {
        upstream = new HandshakeUpstream();
        bounded = new MockServer(relaying().maxSocketTimeoutInMillis(TIMEOUT_MILLIS), 0);
        boundedClient = new MockServerClient("127.0.0.1", bounded.getLocalPort());
        // longer than any wait in this class, so only the client's close can end the relay
        patient = new MockServer(relaying().maxSocketTimeoutInMillis(TimeUnit.MINUTES.toMillis(5)), 0);
        patientClient = new MockServerClient("127.0.0.1", patient.getLocalPort());
        futureBoundedConfiguration = relaying().maxFutureTimeoutInMillis(TIMEOUT_MILLIS);
        futureBounded = new MockServer(futureBoundedConfiguration, 0);
        futureBoundedClient = new MockServerClient("127.0.0.1", futureBounded.getLocalPort());
    }

    private static Configuration relaying() {
        // the test JVM defaults to ERROR, which would drop the WARN entries this class asserts on
        return configuration().logLevel("WARN").attemptToProxyIfNoMatchingExpectation(true);
    }

    @AfterClass
    public static void stopServers() throws Exception {
        stopQuietly(boundedClient);
        stopQuietly(patientClient);
        stopQuietly(futureBoundedClient);
        stopQuietly(bounded);
        stopQuietly(patient);
        stopQuietly(futureBounded);
        if (upstream != null) {
            upstream.close();
        }
    }

    @Before
    public void forgetEarlierTests() {
        upstream.events.clear();
        boundedClient.reset();
        patientClient.reset();
        futureBoundedClient.reset();
    }

    @Test
    public void shouldAnswer502WhenTheUpstreamNeverAnswersTheUpgrade() throws Exception {
        assertTimedOutWithOneWarning(bounded, boundedClient, "/silent/0", TIMED_OUT);
    }

    @Test
    public void shouldAnswer502WhenTheUpstreamStopsPartWayThroughItsResponseHead() throws Exception {
        assertTimedOutWithOneWarning(bounded, boundedClient, "/part-head/0", TIMED_OUT);
    }

    /**
     * A {@code maxSocketTimeout} of {@code 0} turns the socket timeout off and one longer than
     * {@code maxFutureTimeout} does not apply, as for a forward; either way {@code maxFutureTimeout} ends the wait.
     */
    @Test
    public void shouldNeverWaitLongerThanMaxFutureTimeout() throws Exception {
        futureBoundedConfiguration.maxSocketTimeoutInMillis(0L);
        assertTimedOutWithOneWarning(futureBounded, futureBoundedClient, "/silent/2", FUTURE_TIMED_OUT);

        futureBoundedClient.reset();
        futureBoundedConfiguration.maxSocketTimeoutInMillis(TimeUnit.MINUTES.toMillis(5));
        assertTimedOutWithOneWarning(futureBounded, futureBoundedClient, "/silent/3", FUTURE_TIMED_OUT);
    }

    @Test
    public void shouldRelayAHandshakeAnsweredWithinTheTimeoutAndKeepRelayingAfterIt() throws Exception {
        Socket socket = connect(bounded);
        try {
            sendUpgrade(socket, "/slow/" + TIMEOUT_MILLIS / 4);
            assertThat(readHead(socket.getInputStream()), is("HTTP/1.1 101"));

            Thread.sleep(TIMEOUT_MILLIS * 2);

            assertThat("relayed after the timeout has passed", echo(socket, "hello"), is("echo:hello"));
            assertThat(failures(boundedClient), is(empty()));
        } finally {
            socket.close();
        }
    }

    @Test
    public void shouldCloseTheUpstreamConnectionWhenTheClientGivesUpWaiting() throws Exception {
        Socket socket = connect(patient);
        sendUpgrade(socket, "/silent/1");
        upstream.awaitEvent("/silent/1 received the upgrade");

        socket.close();

        upstream.awaitEvent("/silent/1 closed by MockServer");
        assertThat(failures(patientClient), is(empty()));
    }

    private static void assertTimedOutWithOneWarning(MockServer mockServer, MockServerClient client, String path, String reason) throws Exception {
        long start = System.nanoTime();
        int status;
        String body;
        try (Socket socket = connect(mockServer)) {
            sendUpgrade(socket, path);
            InputStream input = socket.getInputStream();
            String head = readHead(input);
            status = Integer.parseInt(head.substring(9, 12));
            body = readToEnd(input);
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(path + " " + body, status, is(502));
        assertThat(body, is(reason));
        assertThat("waited for the timeout", elapsedMillis, greaterThanOrEqualTo(TIMEOUT_MILLIS));
        upstream.awaitEvent(path + " closed by MockServer");
        List<String> entries = failures(client);
        assertThat(entries.toString(), entries.size(), is(1));
        assertThat(entries.get(0), containsString(reason));
    }

    private static List<String> failures(MockServerClient client) {
        return Arrays.stream(client.retrieveLogMessagesArray(null))
            .filter(entry -> entry.contains(FAILURE) || entry.toLowerCase(Locale.ROOT).contains("exception"))
            .collect(Collectors.toList());
    }

    private static Socket connect(MockServer mockServer) throws IOException {
        Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort());
        // every read ends within this, well past the timeout under test
        socket.setSoTimeout(30_000);
        return socket;
    }

    private static void sendUpgrade(Socket socket, String path) throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write(("GET " + path + " HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + upstream.port() + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
            + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    /**
     * Sends a masked text frame and returns the payload of the text frame that comes back.
     */
    private static String echo(Socket socket, String text) throws IOException {
        OutputStream output = socket.getOutputStream();
        InputStream input = socket.getInputStream();
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] mask = {1, 2, 3, 4};
        output.write(new byte[]{(byte) 0x81, (byte) (0x80 | payload.length)});
        output.write(mask);
        output.write(masked(payload, mask));
        output.flush();
        assertThat("a text frame", input.read(), is(0x81));
        return new String(readFully(input, input.read()), StandardCharsets.UTF_8);
    }

    private static byte[] masked(byte[] payload, byte[] mask) {
        byte[] result = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) {
            result[i] = (byte) (payload[i] ^ mask[i % 4]);
        }
        return result;
    }

    /**
     * Reads a response head and returns its protocol and status, for example {@code HTTP/1.1 101}.
     */
    private static String readHead(InputStream input) throws IOException {
        return readRawHead(input).substring(0, 12);
    }

    private static String readRawHead(InputStream input) throws IOException {
        StringBuilder head = new StringBuilder();
        while (head.indexOf("\r\n\r\n") < 0) {
            int read = input.read();
            if (read == -1) {
                throw new EOFException("connection closed after: " + head);
            }
            head.append((char) read);
        }
        return head.toString();
    }

    private static String readToEnd(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int read = input.read(); read != -1; read = input.read()) {
            body.write(read);
        }
        return body.toString(StandardCharsets.UTF_8.name());
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        for (int offset = 0; offset < length; ) {
            int read = input.read(bytes, offset, length - offset);
            if (read == -1) {
                throw new EOFException("connection closed " + (length - offset) + " bytes early");
            }
            offset += read;
        }
        return bytes;
    }

    /**
     * Reads the upgrade, then for {@code /silent/...} answers nothing, for {@code /part-head/...} sends the start of
     * a {@code 101} head and nothing more, and for {@code /slow/<millis>} waits that long, answers {@code 101} and
     * echoes text frames. It records when it has read the upgrade and whether MockServer closed the connection or
     * this server gave up waiting after 30 seconds.
     */
    private static final class HandshakeUpstream {
        private final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final List<String> events = new CopyOnWriteArrayList<>();

        private HandshakeUpstream() throws IOException {
            daemon(() -> {
                while (!listener.isClosed()) {
                    Socket socket = listener.accept();
                    sockets.add(socket);
                    daemon(() -> serve(socket));
                }
            });
        }

        private int port() {
            return listener.getLocalPort();
        }

        private void serve(Socket socket) throws IOException {
            socket.setSoTimeout(30_000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            String head = readRawHead(input);
            String path = head.substring("GET ".length(), head.indexOf(" HTTP/1.1"));
            events.add(path + " received the upgrade");
            try {
                if (path.startsWith("/part-head/")) {
                    output.write("HTTP/1.1 101 Switching Protocols\r\nupgrade: websocket\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    output.flush();
                } else if (path.startsWith("/slow/")) {
                    pause(Long.parseLong(path.substring("/slow/".length())));
                    output.write(("HTTP/1.1 101 Switching Protocols\r\nupgrade: websocket\r\nconnection: upgrade\r\nsec-websocket-accept: " + accept(head) + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    output.flush();
                }
                // each frame from MockServer is masked and, here, shorter than 126 bytes
                for (int opcode = input.read(); opcode != -1; opcode = input.read()) {
                    int length = input.read() & 0x7F;
                    byte[] mask = readFully(input, 4);
                    byte[] payload = masked(readFully(input, length), mask);
                    if (opcode == 0x88) {
                        output.write(new byte[]{(byte) 0x88, 0});
                        output.flush();
                        break;
                    }
                    byte[] echo = ("echo:" + new String(payload, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                    output.write(new byte[]{(byte) 0x81, (byte) echo.length});
                    output.write(echo);
                    output.flush();
                }
                events.add(path + " closed by MockServer");
            } catch (SocketTimeoutException timedOut) {
                events.add(path + " gave up waiting");
            } finally {
                socket.close();
            }
        }

        private static void pause(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private static String accept(String head) {
            String key = Arrays.stream(head.split("\r\n"))
                .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:"))
                .map(line -> line.substring(line.indexOf(':') + 1).trim())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no key in: " + head));
            try {
                byte[] digest = MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1));
                return Base64.getEncoder().encodeToString(digest);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        private void awaitEvent(String event) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!events.contains(event)) {
                assertThat("upstream saw " + events + " and not " + event, System.nanoTime(), lessThan(deadline));
                Thread.sleep(20);
            }
        }

        private static void daemon(IoTask task) {
            Thread thread = new Thread(() -> {
                try {
                    task.run();
                } catch (IOException closed) {
                    // the listener or a connection was closed
                }
            }, "handshake-upstream");
            thread.setDaemon(true);
            thread.start();
        }

        private void close() throws IOException {
            listener.close();
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }

    private interface IoTask {
        void run() throws IOException;
    }
}
