package org.mockserver.netty.integration.proxy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.RawHttp1Connection;
import org.mockserver.netty.integration.RawHttp1Connection.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * An upstream response sent as one-byte chunks passes the forward client's component limit (1,024 at the 64 KiB
 * {@code maxResponseBodySize} used here) sixty-four times; the forward client's aggregator merges only what arrived
 * since its last merge. Responses must still be forwarded unchanged, one after another on the same pooled upstream
 * connection (so on the same aggregator), and a response one byte over the limit must still fail the forward.
 */
public class ForwardTinyChunkResponseIntegrationTest {

    private static final int MAX_RESPONSE_BODY_SIZE = 64 * 1024;

    private TinyChunkUpstream upstream;
    private MockServer mockServer;
    private MockServerClient mockServerClient;

    @Before
    public void startServers() throws IOException {
        upstream = new TinyChunkUpstream();
        mockServer = new MockServer(configuration().maxResponseBodySize(MAX_RESPONSE_BODY_SIZE), 0);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        mockServerClient
            .when(request().withPath("/upstream/.*"))
            .forward(forward().withHost("127.0.0.1").withPort(upstream.getPort()));
    }

    @After
    public void stopServers() {
        try {
            stopQuietly(mockServerClient);
            stopQuietly(mockServer);
        } finally {
            upstream.close();
        }
    }

    @Test(timeout = 120_000)
    public void shouldForwardResponsesOfOneByteChunksUnchangedOnOnePooledUpstreamConnection() throws Exception {
        try (RawHttp1Connection connection = new RawHttp1Connection(mockServer.getLocalPort())) {
            for (int i = 0; i < 3; i++) {
                String path = "/upstream/tiny/" + i;
                connection.sendGet(path);
                Response response = connection.readResponse();
                assertThat(path + " " + response.head, response.status, is(200));
                assertThat(path, response.body.equals(TinyChunkUpstream.body(path, MAX_RESPONSE_BODY_SIZE)), is(true));
            }
        }
        assertThat("the upstream connection is pooled, so one forward client aggregator received every response",
            upstream.connections.get(), is(1));
    }

    @Test(timeout = 120_000)
    public void shouldForwardAResponseAtTheLimitAndFailOneByteOver() throws Exception {
        try (RawHttp1Connection connection = new RawHttp1Connection(mockServer.getLocalPort())) {
            connection.sendGet("/upstream/tiny/at-limit");
            Response atLimit = connection.readResponse();
            assertThat(atLimit.head, atLimit.status, is(200));
            assertThat(atLimit.body.equals(TinyChunkUpstream.body("/upstream/tiny/at-limit", MAX_RESPONSE_BODY_SIZE)), is(true));

            connection.sendGet("/upstream/over");
            Response over = connection.readResponse();
            assertThat(over.head, over.status, is(502));

            connection.sendGet("/upstream/tiny/after");
            Response after = connection.readResponse();
            assertThat(after.head, after.status, is(200));
            assertThat(after.body.equals(TinyChunkUpstream.body("/upstream/tiny/after", MAX_RESPONSE_BODY_SIZE)), is(true));
        }
    }

    /**
     * A raw HTTP/1.1 upstream that keeps its connections open and answers each request with a body of one-byte
     * chunks, {@code maxResponseBodySize} long, or one byte longer for {@code /upstream/over}.
     */
    private static final class TinyChunkUpstream implements AutoCloseable {

        private final ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        private final AtomicInteger connections = new AtomicInteger();
        private volatile boolean running = true;

        TinyChunkUpstream() throws IOException {
            Thread acceptThread = new Thread(this::acceptLoop, "tiny-chunk-upstream");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        static String body(String path, int length) {
            StringBuilder body = new StringBuilder(length).append(path);
            int offset = path.hashCode() & 0xff;
            for (int i = body.length(); i < length; i++) {
                body.append((char) ('a' + (i + offset) % 26));
            }
            return body.substring(0, length);
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    connections.incrementAndGet();
                    Thread connection = new Thread(() -> serve(socket), "tiny-chunk-upstream-connection");
                    connection.setDaemon(true);
                    connection.start();
                } catch (IOException closed) {
                    // closed during shutdown
                }
            }
        }

        private void serve(Socket socket) {
            try (socket) {
                socket.setSoTimeout(60_000);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                String requestLine;
                while ((requestLine = readRequestHeadAndReturnRequestLine(in)) != null) {
                    String path = requestLine.split(" ")[1];
                    int length = path.equals("/upstream/over") ? MAX_RESPONSE_BODY_SIZE + 1 : MAX_RESPONSE_BODY_SIZE;
                    ByteArrayOutputStream wire = new ByteArrayOutputStream();
                    wire.writeBytes("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    RawHttp1Connection.writeChunks(wire, body(path, length).getBytes(StandardCharsets.US_ASCII), new int[]{1}, chunks -> {
                        out.write(chunks.toByteArray());
                        chunks.reset();
                    });
                    out.write(wire.toByteArray());
                    out.flush();
                }
            } catch (IOException closed) {
                // the forward client closed the connection, as it does for a response over the limit
            }
        }

        private static String readRequestHeadAndReturnRequestLine(InputStream in) throws IOException {
            StringBuilder head = new StringBuilder();
            int character;
            while ((character = in.read()) != -1) {
                head.append((char) character);
                if (head.length() >= 4 && head.lastIndexOf("\r\n\r\n") == head.length() - 4) {
                    return head.substring(0, head.indexOf("\r\n"));
                }
            }
            return null;
        }

        @Override
        public void close() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException ignore) {
                // already closed
            }
        }
    }
}
