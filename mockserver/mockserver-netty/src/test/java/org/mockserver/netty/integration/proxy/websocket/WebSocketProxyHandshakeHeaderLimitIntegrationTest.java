package org.mockserver.netty.integration.proxy.websocket;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * The WebSocket proxy relay reads an upstream's handshake response headers up to {@code maxHeaderSize}, as the
 * forward client does for any other response; a larger one, or one it cannot decode for another reason, fails the
 * relay with a {@code 502} that says why, and one WARN. A client (a raw socket) sends the upgrade to MockServer, which
 * relays it to an upstream on 127.0.0.1 that writes its {@code 101} as bytes and then echoes text frames. The
 * client is answered with the upstream's response headers, less the hop-by-hop ones and the handshake's own.
 */
public class WebSocketProxyHandshakeHeaderLimitIntegrationTest {

    // above the 8,192 bytes Netty allows a response's headers when it is not told otherwise
    private static final int LIMIT = 32 * 1024;
    // below it
    private static final int SMALL_LIMIT = 4 * 1024;
    private static final String FAILURE = "WebSocket proxy passthrough failed";
    private static final String NOT_READ = "upstream WebSocket handshake response could not be read: ";
    private static final String CLOSED_EARLY = "upstream WebSocket connection closed before handshake completed";

    private static HandshakeUpstream upstream;
    private static MockServer limited;
    private static MockServerClient limitedClient;
    private static MockServer small;
    private static MockServerClient smallClient;
    private static MockServer defaults;
    private static MockServerClient defaultsClient;

    @BeforeClass
    public static void startServers() throws Exception {
        upstream = new HandshakeUpstream();
        limited = new MockServer(relaying().maxHeaderSize(LIMIT), 0);
        limitedClient = new MockServerClient("127.0.0.1", limited.getLocalPort());
        small = new MockServer(relaying().maxHeaderSize(SMALL_LIMIT), 0);
        smallClient = new MockServerClient("127.0.0.1", small.getLocalPort());
        defaults = new MockServer(relaying(), 0);
        defaultsClient = new MockServerClient("127.0.0.1", defaults.getLocalPort());
    }

    private static Configuration relaying() {
        // the test JVM defaults to ERROR, which would drop the WARN entries this class asserts on
        return configuration().logLevel("WARN").attemptToProxyIfNoMatchingExpectation(true);
    }

    @AfterClass
    public static void stopServers() throws Exception {
        for (MockServerClient client : Arrays.asList(limitedClient, smallClient, defaultsClient)) {
            stopQuietly(client);
        }
        for (MockServer mockServer : Arrays.asList(limited, small, defaults)) {
            stopQuietly(mockServer);
        }
        if (upstream != null) {
            upstream.close();
        }
    }

    @Before
    public void forgetEarlierTests() {
        upstream.events.clear();
        for (MockServerClient client : Arrays.asList(limitedClient, smallClient, defaultsClient)) {
            client.reset();
        }
    }

    @Test
    public void shouldRelayAHandshakeResponseWithHeadersOverNettysDefaultLimit() throws Exception {
        for (String order : new String[]{"first", "last", "paused"}) {
            Exchange exchange = upgrade(defaults, "/" + order + "/" + 20 * 1024);

            assertThat(order + " " + exchange, exchange.status, is(101));
            assertThat(order, exchange.echo, is("echo:hello"));
        }
    }

    @Test
    public void shouldRelayAHandshakeResponseWithHeadersOfExactlyMaxHeaderSizeAndFailLargerOnes() throws Exception {
        for (String path : new String[]{"/first/" + (LIMIT - 1), "/first/" + LIMIT, "/last/" + LIMIT, "/split/" + LIMIT}) {
            Exchange within = upgrade(limited, path);

            assertThat(path + " " + within, within.status, is(101));
            assertThat(path, within.echo, is("echo:hello"));
        }

        for (String order : new String[]{"first", "last", "paused", "split"}) {
            String path = "/" + order + "/" + (LIMIT + 1);
            limitedClient.reset();

            assertFailedWithOneWarning(limitedClient, path, upgrade(limited, path), "upstream WebSocket handshake response headers are larger than maxHeaderSize (" + LIMIT + " bytes)");
        }

        assertThat("the next upgrade is relayed", upgrade(limited, "/first/1000").echo, is("echo:hello"));
    }

    @Test
    public void shouldApplyAMaxHeaderSizeBelowNettysDefaultLimit() throws Exception {
        Exchange atLimit = upgrade(small, "/last/" + SMALL_LIMIT);

        assertThat(atLimit.toString(), atLimit.status, is(101));
        assertThat(atLimit.echo, is("echo:hello"));

        String path = "/last/" + (SMALL_LIMIT + 1);
        assertFailedWithOneWarning(smallClient, path, upgrade(small, path), "upstream WebSocket handshake response headers are larger than maxHeaderSize (" + SMALL_LIMIT + " bytes)");

        Exchange throughAnotherServer = upgrade(defaults, path);

        assertThat("each server applies its own limit: " + throughAnotherServer, throughAnotherServer.status, is(101));
        assertThat(throughAnotherServer.echo, is("echo:hello"));
    }

    @Test
    public void shouldLogAHandshakeTheUpstreamRefusesOnce() throws Exception {
        Exchange refused = upgrade(defaults, "/refused/0");

        assertThat(refused.toString(), refused.status, is(502));
        assertThat(refused.body, startsWith("upstream WebSocket handshake failed: "));
        upstream.awaitEvent("/refused/0 closed after 0 frame(s)");
        List<String> entries = failures(defaultsClient);
        assertThat(entries.toString(), entries.size(), is(1));
        assertThat(entries.get(0), containsString("upstream WebSocket handshake failed: "));
    }

    @Test
    public void shouldLogAnUpstreamThatResetsTheConnectionOnce() throws Exception {
        Exchange reset = upgrade(defaults, "/reset/0");

        assertThat(reset.toString(), reset.status, is(502));
        // a transport reports a reset as an error or as a closed connection
        assertThat(reset.body, anyOf(startsWith("upstream WebSocket error: "), is(CLOSED_EARLY)));
        upstream.awaitEvent("/reset/0 closed after 0 frame(s)");
        List<String> entries = failures(defaultsClient);
        assertThat(entries.toString(), entries.size(), is(1));
        assertThat(entries.get(0), containsString(reset.body));
    }

    /**
     * Netty hands on a response it stopped decoding with what it had read, here every header a handshake needs:
     * a header line ended by a bare line feed with the rest of the head and a frame in a second write, and a
     * connection closed part-way through the headers.
     */
    @Test
    public void shouldFailAHandshakeResponseItCannotDecodeAfterTheHandshakesOwnHeaders() throws Exception {
        for (String path : new String[]{"/bare-line-feed/0", "/closed-in-headers/0"}) {
            defaultsClient.reset();

            Exchange exchange = upgrade(defaults, path);

            assertThat(path + " " + exchange, exchange.status, is(502));
            assertThat(path, exchange.body, startsWith(NOT_READ));
            assertThat(path, exchange.body, not(endsWith("null")));
            upstream.awaitEvent(path + " closed after 0 frame(s)");
            List<String> entries = failures(defaultsClient);
            assertThat(path + " " + entries, entries.size(), is(1));
            assertThat(entries.get(0), containsString(exchange.body));
        }
    }

    @Test
    public void shouldKeepNettysLimitForTheStatusLineWhateverMaxHeaderSizeIs() throws Exception {
        Exchange atNettysLimit = upgrade(limited, "/status-line/4096");

        assertThat(atNettysLimit.toString(), atNettysLimit.status, is(101));
        assertThat(atNettysLimit.echo, is("echo:hello"));

        Exchange overNettysLimit = upgrade(limited, "/status-line/4097");

        assertThat(overNettysLimit.toString(), overNettysLimit.status, is(502));
        assertThat(overNettysLimit.body, is(NOT_READ + "An HTTP line is larger than 4096 bytes."));
    }

    @Test
    public void shouldReadMaxHeaderSizeForEachRelay() throws Exception {
        Configuration configuration = relaying();
        MockServer mockServer = new MockServer(configuration, 0);
        try {
            assertThat(upgrade(mockServer, "/last/" + (SMALL_LIMIT + 1)).status, is(101));

            configuration.maxHeaderSize(SMALL_LIMIT);

            Exchange afterLowering = upgrade(mockServer, "/last/" + (SMALL_LIMIT + 1));
            assertThat(afterLowering.toString(), afterLowering.status, is(502));
            assertThat(afterLowering.body, is("upstream WebSocket handshake response headers are larger than maxHeaderSize (" + SMALL_LIMIT + " bytes)"));

            configuration.maxHeaderSize(LIMIT);

            assertThat(upgrade(mockServer, "/last/" + (SMALL_LIMIT + 1)).status, is(101));
        } finally {
            stopQuietly(mockServer);
        }
    }

    /**
     * The upstream answers {@code 403} and then, in the same write, with a valid {@code 101}.
     */
    @Test
    public void shouldNotRelayAnAcceptanceThatFollowsARefusal() throws Exception {
        Exchange refused = upgrade(defaults, "/refused-then-accepted/0");

        assertThat(refused.toString(), refused.status, is(502));
        assertThat(refused.body, startsWith("upstream WebSocket handshake failed: "));
        upstream.awaitEvent("/refused-then-accepted/0 closed after 0 frame(s)");
        List<String> entries = failures(defaultsClient);
        assertThat(entries.toString(), entries.size(), is(1));
    }

    @Test
    public void shouldPassTheUpstreamsHandshakeResponseHeadersToTheClientButNotItsHopByHopOrHandshakeHeaders() throws Exception {
        Exchange exchange = upgrade(defaults, "/headers/0", "Sec-WebSocket-Protocol: chat\r\nSec-WebSocket-Extensions: permessage-deflate\r\n");

        assertThat(exchange.toString(), exchange.status, is(101));
        assertThat("the handshake still holds", exchange.echo, is("echo:hello"));
        assertThat(exchange.head, headerValues(exchange.head, "set-cookie"), contains("session=abc; Path=/; HttpOnly", "theme=dark"));
        assertThat(exchange.head, headerValues(exchange.head, "x-custom"), contains("one"));
        assertThat(exchange.head, headerValues(exchange.head, "sec-websocket-protocol"), contains("chat"));
        // the upstream's own was for MockServer's key, not the client's
        assertThat(exchange.head, headerValues(exchange.head, "sec-websocket-accept"), contains(UpgradeKey.ACCEPT));
        assertThat(exchange.head, headerValues(exchange.head, "upgrade"), contains("websocket"));
        assertThat(exchange.head, headerValues(exchange.head, "connection"), contains("upgrade"));
        for (String notRelayed : new String[]{"sec-websocket-extensions", "x-hop", "keep-alive", "proxy-connection", "proxy-authenticate", "transfer-encoding", "te", "trailer", "content-length"}) {
            assertThat(notRelayed + " in " + exchange.head, headerValues(exchange.head, notRelayed), empty());
        }

        HttpResponse recorded = awaitRecordedUpgrade(defaultsClient, "/headers/0");
        assertThat(recorded.toString(), recorded.getHeader("set-cookie"), contains("session=abc; Path=/; HttpOnly", "theme=dark"));
        assertThat(recorded.toString(), recorded.getHeader("x-custom"), contains("one"));
        assertThat(recorded.toString(), recorded.containsHeader("keep-alive"), is(false));
        // the relay's own recording headers keep the relay's values alone
        assertThat(recorded.toString(), recorded.getHeader("x-mockserver-websocket-frames"), contains(not("999")));
        assertThat(recorded.toString(), recorded.getHeader("x-mockserver-websocket-transcript-truncated"), contains("false"));
    }

    @Test
    public void shouldPassAHandshakeResponseHeaderOfUpToMaxHeaderSizeToTheClient() throws Exception {
        Exchange exchange = upgrade(limited, "/last/" + LIMIT);

        assertThat(exchange.toString(), exchange.status, is(101));
        assertThat(headerValues(exchange.head, "set-cookie"), contains("session=" + "a".repeat(LIMIT - 124)));
        assertThat(headerValues(exchange.head, "x-served-by"), contains("test"));
    }

    private static List<String> headerValues(String head, String name) {
        return Arrays.stream(head.split("\r\n"))
            .skip(1)
            .filter(line -> line.toLowerCase(Locale.ROOT).startsWith(name + ":"))
            .map(line -> line.substring(line.indexOf(':') + 1).trim())
            .collect(Collectors.toList());
    }

    private static HttpResponse awaitRecordedUpgrade(MockServerClient client, String path) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            LogEventRequestAndResponse[] recorded = client.retrieveRecordedRequestsAndResponses(request().withPath(path));
            if (recorded.length > 0) {
                return recorded[0].getHttpResponse();
            }
            assertThat("the upgrade to " + path + " is recorded", System.nanoTime(), lessThan(deadline));
            Thread.sleep(20);
        }
    }

    private static void assertFailedWithOneWarning(MockServerClient client, String path, Exchange exchange, String reason) throws InterruptedException {
        assertThat(path + " " + exchange, exchange.status, is(502));
        assertThat(exchange.body, is(reason));
        // MockServer has closed the upstream connection, so anything it logs for that is in the log by now
        upstream.awaitEvent(path + " closed after 0 frame(s)");
        List<String> entries = failures(client);
        assertThat(entries.toString(), entries.size(), is(1));
        assertThat(entries.get(0), containsString(reason));
    }

    private static List<String> failures(MockServerClient client) {
        return Arrays.stream(client.retrieveLogMessagesArray(null))
            .filter(entry -> entry.contains(FAILURE) || entry.toLowerCase(Locale.ROOT).contains("exception"))
            .collect(Collectors.toList());
    }

    /**
     * Sends the upgrade for {@code path} to the upstream through {@code mockServer}. After a {@code 101} it sends
     * the text frame {@code hello}, reads one text frame back and closes; otherwise it reads the body to the end of
     * the connection. Every read ends within 15 seconds.
     */
    private static Exchange upgrade(MockServer mockServer, String path) throws Exception {
        return upgrade(mockServer, path, "");
    }

    private static Exchange upgrade(MockServer mockServer, String path, String extraHeaders) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort())) {
            socket.setSoTimeout(15_000);
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();
            output.write(("GET " + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + upstream.port() + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + UpgradeKey.KEY + "\r\n"
                + extraHeaders
                + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            String head = readHead(input);
            int status = Integer.parseInt(head.substring(9, 12));
            if (status != 101) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                for (int read = input.read(); read != -1; read = input.read()) {
                    body.write(read);
                }
                return new Exchange(status, head, body.toString(StandardCharsets.UTF_8.name()), "");
            }
            // a client's frames are masked: FIN and text, the mask bit and the length, the key, the payload
            byte[] hello = "hello".getBytes(StandardCharsets.UTF_8);
            byte[] mask = {1, 2, 3, 4};
            output.write(new byte[]{(byte) 0x81, (byte) (0x80 | hello.length)});
            output.write(mask);
            output.write(masked(hello, mask));
            output.flush();
            assertThat("a text frame", input.read(), is(0x81));
            String echo = new String(readFully(input, input.read()), StandardCharsets.UTF_8);
            // a masked close frame with no payload
            output.write(new byte[]{(byte) 0x88, (byte) 0x80});
            output.write(mask);
            output.flush();
            return new Exchange(status, head, "", echo);
        }
    }

    private static byte[] masked(byte[] payload, byte[] mask) {
        byte[] result = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) {
            result[i] = (byte) (payload[i] ^ mask[i % 4]);
        }
        return result;
    }

    private static String readHead(InputStream input) throws IOException {
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

    private static final class UpgradeKey {
        private static final String KEY = "dGhlIHNhbXBsZSBub25jZQ==";
        // RFC 6455's worked example: the accept value for that key
        private static final String ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
    }

    private static final class Exchange {
        private final int status;
        private final String head;
        private final String body;
        private final String echo;

        private Exchange(int status, String head, String body, String echo) {
            this.status = status;
            this.head = head;
            this.body = body;
            this.echo = echo;
        }

        @Override
        public String toString() {
            return "status " + status + ", body " + body + ", echo " + echo;
        }
    }

    /**
     * Answers the upgrade for {@code /first/<size>}, {@code /last/<size>}, {@code /paused/<size>} or {@code /split/<size>} with a
     * {@code 101} whose header section is {@code size} bytes as Netty's HTTP/1.1 decoder counts it (the header lines
     * without their line ends), the large header before or after the handshake's own, then echoes text frames.
     * {@code /paused/} is {@code /last/} written in two parts, {@code /split/} is {@code /last/} with its last header
     * line's LF written apart from its CR, and {@code /status-line/<size>} has a status line of
     * that size instead. {@code /refused/...} is answered {@code 403}, {@code /reset/...} with a connection reset,
     * and {@code /bare-line-feed/...} and {@code /closed-in-headers/...} with a head Netty cannot decode. It records
     * how each connection ended.
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
            String head = readHead(input);
            String path = head.substring("GET ".length(), head.indexOf(" HTTP/1.1"));
            int frames = 0;
            try {
                if (path.startsWith("/reset/")) {
                    // closing then resets the connection, which MockServer reads as an error
                    socket.setSoLinger(true, 0);
                    return;
                }
                if (path.startsWith("/bare-line-feed/") || path.startsWith("/closed-in-headers/")) {
                    String handshake = "HTTP/1.1 101 Switching Protocols\r\nupgrade: websocket\r\nconnection: upgrade\r\nsec-websocket-accept: " + accept(head) + "\r\nx-served-by: test\r\n";
                    if (path.startsWith("/closed-in-headers/")) {
                        output.write((handshake + "x-last: 1\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        output.flush();
                        return;
                    }
                    output.write((handshake + "x-bare: 1\n").getBytes(StandardCharsets.ISO_8859_1));
                    output.flush();
                    pause();
                    // the end of the head, then the text frame "hello"
                    output.write("x-last: 1\r\n\r\n\u0081\u0005hello".getBytes(StandardCharsets.ISO_8859_1));
                } else if (path.startsWith("/headers/")) {
                    // the handshake's own headers, with a hop-by-hop one named by Connection in another case, the subprotocol asked
                    // for, an extension nobody offered it, end-to-end headers and hop-by-hop ones
                    String protocol = Arrays.stream(head.split("\r\n"))
                        .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-protocol:"))
                        .map(line -> "sec-websocket-protocol: " + line.substring(line.indexOf(':') + 1).trim() + "\r\n")
                        .findFirst()
                        .orElse("");
                    output.write(("HTTP/1.1 101 Switching Protocols\r\n"
                        + "upgrade: websocket\r\n"
                        + "connection: upgrade, X-Hop\r\n"
                        + "sec-websocket-accept: " + accept(head) + "\r\n"
                        + protocol
                        + "Sec-WebSocket-Extensions: permessage-deflate\r\n"
                        + "set-cookie: session=abc; Path=/; HttpOnly\r\n"
                        + "set-cookie: theme=dark\r\n"
                        + "x-custom: one\r\n"
                        + "X-MockServer-WebSocket-Frames: 999\r\n"
                        + "x-mockserver-websocket-transcript-truncated: true\r\n"
                        + "x-hop: 1\r\n"
                        + "Keep-Alive: timeout=5\r\n"
                        + "proxy-connection: keep-alive\r\n"
                        + "Proxy-Authenticate: Basic realm=\"upstream\"\r\n"
                        + "transfer-encoding: gzip\r\n"
                        + "te: trailers\r\n"
                        + "trailer: x-checksum\r\n"
                        + "content-length: 0\r\n"
                        + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                } else if (path.startsWith("/refused")) {
                    String accepted = path.startsWith("/refused-then-accepted/") ? "HTTP/1.1 101 Switching Protocols\r\nupgrade: websocket\r\nconnection: upgrade\r\nsec-websocket-accept: " + accept(head) + "\r\n\r\n" : "";
                    output.write(("HTTP/1.1 403 Forbidden\r\ncontent-length: 0\r\n\r\n" + accepted).getBytes(StandardCharsets.ISO_8859_1));
                } else {
                    // Netty adds a header once it has read the line after it, so one follows the handshake's own
                    String handshake = "upgrade: websocket\r\nconnection: upgrade\r\nsec-websocket-accept: " + accept(head) + "\r\nx-served-by: test\r\n";
                    // those four lines are 104 bytes and "set-cookie: session=" 20
                    String large = "set-cookie: session=" + "a".repeat(Integer.parseInt(path.substring(path.lastIndexOf('/') + 1)) - 124) + "\r\n";
                    String headers = path.startsWith("/first/") ? large + handshake : handshake + large;
                    String statusLine = "HTTP/1.1 101 Switching Protocols";
                    if (path.startsWith("/status-line/")) {
                        // a status line of the size asked for, with headers of a few bytes
                        statusLine += "s".repeat(Integer.parseInt(path.substring(path.lastIndexOf('/') + 1)) - statusLine.length());
                        headers = handshake;
                    }
                    byte[] response = (statusLine + "\r\n" + headers + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
                    if (path.startsWith("/paused/")) {
                        // the rest arrives after MockServer has acted on the first part
                        output.write(response, 0, 12 * 1024);
                        output.flush();
                        pause();
                        output.write(response, 12 * 1024, response.length - 12 * 1024);
                    } else if (path.startsWith("/split/")) {
                        // the last header line's LF, and the blank line, arrive after its CR
                        int afterLastCr = response.length - 3;
                        output.write(response, 0, afterLastCr);
                        output.flush();
                        pause();
                        output.write(response, afterLastCr, 3);
                    } else {
                        output.write(response);
                    }
                }
                output.flush();
                // each frame from MockServer is masked and, here, shorter than 126 bytes
                for (int opcode = input.read(); opcode != -1; opcode = input.read()) {
                    int length = input.read() & 0x7F;
                    byte[] mask = readFully(input, 4);
                    byte[] payload = masked(readFully(input, length), mask);
                    frames++;
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
            } finally {
                events.add(path + " closed after " + frames + " frame(s)");
                socket.close();
            }
        }

        private static void pause() {
            try {
                Thread.sleep(300);
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
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
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
