package org.mockserver.netty.integration.proxy.http;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * How a streamed upstream response's framing ends decides how the client's response ends. A body delimited by the
 * upstream closing its connection (HTTP/1.0 or HTTP/1.1 without chunking, plain or gzip, a full close or a half-close)
 * and a clean chunked body end with a terminating chunk; invalid chunk framing, plain or gzip, ends without one. The
 * upstream is a plain socket server so it can send any bytes.
 */
public class StreamedResponseFramingIntegrationTest {

    private static final String TERMINATING_CHUNK = "0\r\n\r\n";
    private static ServerSocket upstream;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServers() throws Exception {
        upstream = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(() -> {
            while (!upstream.isClosed()) {
                try {
                    Socket socket = upstream.accept();
                    new Thread(() -> serve(socket)).start();
                } catch (IOException closed) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        mockServer = new MockServer(configuration().streamingResponsesEnabled(true).streamIdleTimeoutSeconds(5));
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        mockServerClient.when(request().withPath("/.*")).forward(forward().withHost("127.0.0.1").withPort(upstream.getLocalPort()));
    }

    @AfterClass
    public static void stopServers() throws IOException {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        upstream.close();
    }

    @Test
    public void shouldCompleteACloseDelimitedHttp11Stream() throws IOException {
        assertComplete("/plain", 20);
    }

    @Test
    public void shouldCompleteACloseDelimitedHttp10Stream() throws IOException {
        assertComplete("/http10", 20);
    }

    @Test
    public void shouldCompleteACloseDelimitedGzipStream() throws IOException {
        assertComplete("/gzip", 20);
    }

    @Test
    public void shouldDropTheContentEncodingOfAStreamItDecoded() throws IOException {
        String received = readAll("/chunkedclean-gzip");
        assertThat(head(received), not(containsString("content-encoding")));
    }

    @Test
    public void shouldKeepTheContentEncodingOfAStreamItDoesNotDecode() throws IOException {
        String received = readAll("/chunkedclean-unknown-coding");
        assertThat(head(received), containsString("content-encoding: x-unknown\r\n"));
        assertThat("the bytes are the upstream's", dechunk(received), is(new String(events(20), StandardCharsets.US_ASCII)));
    }

    @Test
    public void shouldKeepTheContentEncodingOfACloseDelimitedStreamItDoesNotDecode() throws IOException {
        String received = readAll("/unknown-coding");
        assertThat(received, endsWith(TERMINATING_CHUNK));
        assertThat(head(received), containsString("content-encoding: x-unknown\r\n"));
        assertThat(dechunk(received), is(new String(events(20), StandardCharsets.US_ASCII)));
    }

    @Test
    public void shouldCompleteALargeCloseDelimitedGzipStream() throws IOException {
        assertComplete("/big-gzip", 50000);
    }

    @Test
    public void shouldCompleteALargeCloseDelimitedStream() throws IOException {
        assertComplete("/big", 50000);
    }

    @Test
    public void shouldCompleteAStreamEndedByAHalfClose() throws IOException {
        assertComplete("/halfclose", 20);
    }

    @Test
    public void shouldCompleteACleanChunkedStreamFollowedByAClose() throws IOException {
        assertComplete("/chunkedclean", 20);
    }

    @Test
    public void shouldCompleteACleanChunkedGzipStreamFollowedByAClose() throws IOException {
        assertComplete("/chunkedclean-gzip", 20);
    }

    @Test
    public void shouldEndAStreamWithInvalidChunkFramingIncomplete() throws IOException {
        String received = readAll("/badchunk");
        assertThat(received, containsString("data: event-0"));
        assertThat("invalid chunk framing is not a complete response", received, not(endsWith(TERMINATING_CHUNK)));
    }

    @Test
    public void shouldEndAGzipStreamWithInvalidChunkFramingIncomplete() throws IOException {
        String received = readAll("/badchunk-gzip");
        assertThat(received, containsString("200 OK"));
        assertThat("invalid chunk framing is not a complete response, whatever the coding", received, not(endsWith(TERMINATING_CHUNK)));
    }

    private static void assertComplete(String path, int events) throws IOException {
        String received = readAll(path);
        assertThat(path + " ends with a terminating chunk", received, endsWith(TERMINATING_CHUNK));
        assertThat(path, dechunk(received), is(new String(events(events), StandardCharsets.US_ASCII)));
    }

    private static byte[] events(int count) {
        StringBuilder events = new StringBuilder();
        for (int i = 0; i < count; i++) {
            events.append("data: event-").append(i).append("\n\n");
        }
        return events.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(plain);
        }
        return compressed.toByteArray();
    }

    /**
     * Answers by path: {@code big} 50,000 events (else 20), {@code http10} as HTTP/1.0, {@code gzip} compressed;
     * {@code chunkedclean} one chunk then the terminating chunk, {@code badchunk} one chunk then a size that is not
     * hex, otherwise the body unframed in several writes ending with a close, or {@code halfclose} a half-close.
     */
    private static void serve(Socket accepted) {
        try (Socket socket = accepted) {
            InputStream in = socket.getInputStream();
            StringBuilder head = new StringBuilder();
            for (int b; !head.toString().endsWith("\r\n\r\n") && (b = in.read()) != -1; ) {
                head.append((char) b);
            }
            String path = head.toString().split(" ")[1];
            OutputStream out = socket.getOutputStream();
            byte[] body = events(path.contains("big") ? 50000 : 20);
            String headers = (path.contains("http10") ? "HTTP/1.0" : "HTTP/1.1") + " 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n";
            if (path.contains("gzip")) {
                body = gzip(body);
                headers += "Content-Encoding: gzip\r\n";
            } else if (path.contains("unknown-coding")) {
                // a label alone: the bytes are what MockServer must pass on unchanged
                headers += "Content-Encoding: x-unknown\r\n";
            }
            if (path.contains("badchunk")) {
                byte[] first = path.contains("gzip") ? body : events(1);
                out.write((headers + "Transfer-Encoding: chunked\r\n\r\n" + Integer.toHexString(first.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(first);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread.sleep(100);
                out.write("zzzz\r\ngarbage\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                // stay open, so only the invalid framing can end the stream
                Thread.sleep(3000);
                return;
            }
            if (path.contains("chunkedclean")) {
                out.write((headers + "Transfer-Encoding: chunked\r\n\r\n" + Integer.toHexString(body.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            } else {
                out.write((headers + "\r\n").getBytes(StandardCharsets.US_ASCII));
                // in several writes, so the body spans several reads
                int step = Math.max(1, body.length / 7);
                for (int i = 0; i < body.length; i += step) {
                    out.write(body, i, Math.min(step, body.length - i));
                    out.flush();
                    Thread.sleep(20);
                }
            }
            out.flush();
            if (path.contains("halfclose")) {
                socket.shutdownOutput();
                Thread.sleep(200);
            }
        } catch (Exception ignored) {
            // the test asserts on what MockServer relays
        }
    }

    private static String readAll(String path) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort())) {
            socket.setSoTimeout(20000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[65536];
            try {
                for (int read; (read = in.read(buffer)) != -1; ) {
                    received.write(buffer, 0, read);
                    if (received.toString(StandardCharsets.ISO_8859_1).endsWith(TERMINATING_CHUNK)) {
                        break;
                    }
                }
            } catch (IOException reset) {
                // a reset ends the response as incomplete as a close does
            }
            return received.toString(StandardCharsets.ISO_8859_1);
        }
    }

    private static String head(String response) {
        return response.substring(0, Math.max(0, response.indexOf("\r\n\r\n") + 2)).toLowerCase(Locale.ROOT);
    }

    private static String dechunk(String response) {
        int index = response.indexOf("\r\n\r\n") + 4;
        StringBuilder body = new StringBuilder();
        while (index < response.length()) {
            int lineEnd = response.indexOf("\r\n", index);
            if (lineEnd < 0) {
                break;
            }
            int size = Integer.parseInt(response.substring(index, lineEnd).trim(), 16);
            if (size == 0) {
                break;
            }
            body.append(response, lineEnd + 2, Math.min(response.length(), lineEnd + 2 + size));
            index = lineEnd + 2 + size + 2;
        }
        return body.toString();
    }
}
