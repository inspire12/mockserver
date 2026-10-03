package org.mockserver.netty.unification;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * HTTP/1.1 requests the codec cannot decode, written to a real socket of a MockServer with default configuration,
 * directly and through a cleartext {@code CONNECT} tunnel (whose client side has its own HTTP codec). Each is refused
 * with its status and {@code Connection: close}, and never reaches an expectation. The request line and the header
 * section are bounded by default: 64 KiB and 256 KiB.
 */
public class Http1RequestDecodeFailureIntegrationTest {

    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final int PIECE_BYTES = 1024;
    private static final int DEFAULT_MAX_INITIAL_LINE_LENGTH = 64 * 1024;
    private static final int DEFAULT_MAX_HEADER_SIZE = 256 * 1024;
    // far past either default, so a server that waits for the end of the line or header section never answers
    private static final int ENDLESS_BYTES = 4 * DEFAULT_MAX_HEADER_SIZE;

    // about 320 KB of headers, over the default maxHeaderSize, which limits only what clients send
    private static final int LARGE_RESPONSE_HEADERS = 40;

    private static MockServer mockServer;
    private static MockServerClient client;

    @BeforeClass
    public static void startServer() {
        mockServer = new MockServer(0);
        client = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        HttpResponse largeResponse = response().withStatusCode(200).withBody("large headers");
        for (int header = 0; header < LARGE_RESPONSE_HEADERS; header++) {
            largeResponse.withHeader("X-Large-" + header, "v".repeat(8000));
        }
        client
            .when(request().withPath("/large-response"))
            .respond(largeResponse);
        client
            .when(request().withPath("/decode/.*"))
            .respond(response().withStatusCode(200).withBody("served"));
    }

    @AfterClass
    public static void stopServer() {
        if (client != null) {
            client.close();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Test
    public void shouldRefuseABodyCutShortByAnInvalidChunkSize() throws Exception {
        try (Socket socket = direct()) {
            assertInvalidChunkSizeRefused(socket);
        }
    }

    @Test
    public void shouldRefuseABodyCutShortByAnInvalidChunkSizeThroughAConnectTunnel() throws Exception {
        try (Socket socket = tunnel()) {
            assertInvalidChunkSizeRefused(socket);
        }
    }

    @Test
    public void shouldRefuseACompressedBodyCutShortByAnInvalidChunkSize() throws Exception {
        // the decompressor would turn the failed end of the body into a successful one
        try (Socket socket = direct()) {
            String path = uniquePath();
            String request = "POST " + path + " HTTP/1.1\r\nHost: " + authority() + "\r\nContent-Encoding: gzip\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "zz\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            assertRefused(socket, "HTTP/1.1 400 Bad Request\r\n", path);
        }
    }

    @Test
    public void shouldRefuseAnEndlessRequestLineAtTheDefaultLimit() throws Exception {
        try (Socket socket = direct()) {
            assertEndlessRequestLineRefused(socket);
        }
        try (Socket socket = direct()) {
            assertLargeRequestServed(socket);
        }
    }

    @Test
    public void shouldRefuseAnEndlessRequestLineAtTheDefaultLimitThroughAConnectTunnel() throws Exception {
        try (Socket socket = tunnel()) {
            assertEndlessRequestLineRefused(socket);
        }
    }

    @Test
    public void shouldRefuseAnEndlessHeaderSectionAtTheDefaultLimit() throws Exception {
        try (Socket socket = direct()) {
            assertEndlessHeaderSectionRefused(socket);
        }
        try (Socket socket = direct()) {
            assertLargeRequestServed(socket);
        }
    }

    @Test
    public void shouldRefuseAnEndlessHeaderSectionAtTheDefaultLimitThroughAConnectTunnel() throws Exception {
        try (Socket socket = tunnel()) {
            assertEndlessHeaderSectionRefused(socket);
        }
    }

    @Test
    public void shouldServeARequestLineAndHeaderSectionJustUnderTheDefaultLimits() throws Exception {
        try (Socket socket = direct()) {
            assertLargeRequestServed(socket);
        }
        try (Socket socket = tunnel()) {
            assertLargeRequestServed(socket);
        }
    }

    @Test
    public void shouldRelayAResponseWithHeadersOverMaxHeaderSizeThroughAConnectTunnel() throws Exception {
        try (Socket socket = direct()) {
            assertLargeResponseIntact(socket);
        }
        try (Socket socket = tunnel()) {
            assertLargeResponseIntact(socket);
            // and the tunnel still carries the next exchange
            assertLargeResponseIntact(socket);
        }
    }

    private static void assertLargeResponseIntact(Socket socket) throws IOException {
        socket.getOutputStream().write(("GET /large-response HTTP/1.1\r\nHost: " + authority() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        InputStream in = socket.getInputStream();

        String head = readResponseHead(in);

        assertThat(head, startsWith("HTTP/1.1 200 OK\r\n"));
        assertThat(head.toLowerCase().split("\r\nx-large-", -1).length - 1, is(LARGE_RESPONSE_HEADERS));
        assertThat(head.toLowerCase(), containsString("content-length: 13\r\n"));
        assertThat(new String(in.readNBytes(13), StandardCharsets.US_ASCII), is("large headers"));
    }

    private static void assertInvalidChunkSizeRefused(Socket socket) throws IOException {
        String path = uniquePath();
        String request = "POST " + path + " HTTP/1.1\r\nHost: " + authority() + "\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "5\r\nhello\r\nzz\r\n world\r\n0\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();

        assertRefused(socket, "HTTP/1.1 400 Bad Request\r\n", path);
    }

    private static void assertEndlessRequestLineRefused(Socket socket) throws IOException {
        String path = uniquePath();
        OutputStream out = socket.getOutputStream();
        out.write(("GET " + path + "?filler=").getBytes(StandardCharsets.US_ASCII));
        writeEndlessly(out, 'a');

        assertRefused(socket, "HTTP/1.1 414 Request-URI Too Long\r\n", path);
    }

    private static void assertEndlessHeaderSectionRefused(Socket socket) throws IOException {
        String path = uniquePath();
        OutputStream out = socket.getOutputStream();
        out.write(("GET " + path + " HTTP/1.1\r\nHost: " + authority() + "\r\nX-Filler: ").getBytes(StandardCharsets.US_ASCII));
        writeEndlessly(out, 'b');

        assertRefused(socket, "HTTP/1.1 431 Request Header Fields Too Large\r\n", path);
    }

    private static void assertLargeRequestServed(Socket socket) throws IOException {
        String path = "/decode/" + "p".repeat(DEFAULT_MAX_INITIAL_LINE_LENGTH - 100);
        StringBuilder request = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\nHost: ").append(authority()).append("\r\n");
        String value = "v".repeat(8000);
        for (int header = 0; request.length() + value.length() < DEFAULT_MAX_INITIAL_LINE_LENGTH + DEFAULT_MAX_HEADER_SIZE - 20_000; header++) {
            request.append("X-Large-").append(header).append(": ").append(value).append("\r\n");
        }
        request.append("\r\n");
        socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();

        assertThat(readResponseHead(socket.getInputStream()), startsWith("HTTP/1.1 200 OK\r\n"));
    }

    private static void writeEndlessly(OutputStream out, char filler) {
        byte[] piece = String.valueOf(filler).repeat(PIECE_BYTES).getBytes(StandardCharsets.US_ASCII);
        try {
            for (int sent = 0; sent < ENDLESS_BYTES; sent += PIECE_BYTES) {
                out.write(piece);
                out.flush();
            }
        } catch (IOException connectionAlreadyClosed) {
            // the response is still expected
        }
    }

    private static void assertRefused(Socket socket, String statusLine, String path) throws IOException {
        InputStream in = socket.getInputStream();
        String response = readResponseHead(in);

        assertThat(response, startsWith(statusLine));
        assertThat(response.toLowerCase(), containsString("connection: close\r\n"));
        assertThat("connection closed after the response", in.read(), is(-1));
        assertThat("never dispatched", client.retrieveRecordedRequests(request().withPath(path)), emptyArray());
        // the codec reports an undecodable request line as a request for this path
        assertThat("never dispatched", client.retrieveRecordedRequests(request().withPath("/bad-request")), emptyArray());
        for (String logMessage : client.retrieveLogMessagesArray(null)) {
            // the aggregator's report of the request it held is expected after a rejection, and not logged
            assertThat(logMessage, allOf(not(containsString("web socket server caught exception")), not(containsString("exception caught by upstream relay handler"))));
        }
    }

    private static String uniquePath() {
        return "/decode/" + UUID.randomUUID();
    }

    private static String authority() {
        return "127.0.0.1:" + mockServer.getLocalPort();
    }

    private static Socket direct() throws IOException {
        Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort());
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private static Socket tunnel() throws IOException {
        Socket socket = direct();
        socket.getOutputStream().write(("CONNECT " + authority() + " HTTP/1.1\r\nHost: " + authority() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        assertThat(readResponseHead(socket.getInputStream()), startsWith("HTTP/1.1 200 "));
        return socket;
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
