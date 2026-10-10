package org.mockserver.netty.unification;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.codec.HttpChunkLineLimiter;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Chunked uploads written to a real socket of a MockServer with default configuration (no limit on the request line
 * or headers), both directly and through a cleartext {@code CONNECT} tunnel, whose client side has its own HTTP codec.
 */
public class Http1ChunkLineLimitIntegrationTest {

    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final int PIECE_BYTES = 1024;
    private static final int ENDLESS_EXTENSION_BYTES = 32 * HttpChunkLineLimiter.MAX_CHUNK_LINE_BYTES;

    private static MockServer mockServer;
    private static MockServerClient client;

    @BeforeClass
    public static void startServer() {
        mockServer = new MockServer(0);
        client = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        client
            .when(request().withMethod("POST").withPath("/upload").withBody("hello world"))
            .respond(response().withStatusCode(200).withBody("uploaded"));
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
    public void shouldAcceptAChunkedUploadWithChunkExtensions() throws Exception {
        try (Socket socket = direct()) {
            assertUploadAccepted(socket);
        }
    }

    @Test
    public void shouldAcceptAChunkedUploadWithChunkExtensionsThroughAConnectTunnel() throws Exception {
        try (Socket socket = tunnel()) {
            assertUploadAccepted(socket);
        }
    }

    @Test
    public void shouldRejectAnEndlessChunkExtensionBeforeItEnds() throws Exception {
        try (Socket socket = direct()) {
            assertEndlessChunkExtensionRejected(socket);
        }
        try (Socket socket = direct()) {
            assertUploadAccepted(socket);
        }
    }

    @Test
    public void shouldRejectAnEndlessChunkExtensionBeforeItEndsThroughAConnectTunnel() throws Exception {
        try (Socket socket = tunnel()) {
            assertEndlessChunkExtensionRejected(socket);
        }
        try (Socket socket = tunnel()) {
            assertUploadAccepted(socket);
        }
    }

    private static void assertUploadAccepted(Socket socket) throws IOException {
        OutputStream out = socket.getOutputStream();
        String request = head(socket)
            + "5;chunk-signature=" + "a".repeat(64) + "\r\nhello\r\n"
            + "6;note=" + "b".repeat(HttpChunkLineLimiter.MAX_CHUNK_LINE_BYTES / 2) + "\r\n world\r\n"
            + "0\r\nx-checksum: abc\r\n\r\n";
        // in pieces, so chunk-size lines span socket reads
        byte[] bytes = request.getBytes(StandardCharsets.US_ASCII);
        for (int offset = 0; offset < bytes.length; offset += PIECE_BYTES) {
            out.write(bytes, offset, Math.min(PIECE_BYTES, bytes.length - offset));
            out.flush();
        }

        String response = readResponseHead(socket.getInputStream());

        assertThat(response, startsWith("HTTP/1.1 200 OK\r\n"));
    }

    private static void assertEndlessChunkExtensionRejected(Socket socket) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write((head(socket) + "5;name=").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        byte[] piece = new byte[PIECE_BYTES];
        Arrays.fill(piece, (byte) 'a');
        try {
            for (int sent = 0; sent < ENDLESS_EXTENSION_BYTES; sent += PIECE_BYTES) {
                out.write(piece);
                out.flush();
            }
        } catch (IOException connectionAlreadyClosed) {
            // the response below is still expected
        }

        // the chunk-size line is never ended, so a response at all means the server did not wait for its end
        InputStream in = socket.getInputStream();
        String response = readResponseHead(in);

        assertThat(response, startsWith("HTTP/1.1 400 Bad Request\r\n"));
        assertThat(response.toLowerCase(), containsString("connection: close\r\n"));
        assertThat("connection closed after the response", in.read(), is(-1));
    }

    private static String head(Socket socket) {
        return "POST /upload HTTP/1.1\r\nHost: 127.0.0.1:" + mockServer.getLocalPort() + "\r\nTransfer-Encoding: chunked\r\n\r\n";
    }

    private static Socket direct() throws IOException {
        Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort());
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private static Socket tunnel() throws IOException {
        Socket socket = direct();
        String target = "127.0.0.1:" + mockServer.getLocalPort();
        socket.getOutputStream().write(("CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
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
