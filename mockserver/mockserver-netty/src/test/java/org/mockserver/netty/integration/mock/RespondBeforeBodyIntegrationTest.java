package org.mockserver.netty.integration.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.KeyStoreFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertTrue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.ConnectionOptions.connectionOptions;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Reproduces the okhttp/okhttp#1001 scenario (issue #1831):
 * server responds to a POST with a large body before consuming the body, then closes the connection.
 */
public class RespondBeforeBodyIntegrationTest {

    private ClientAndServer mockServerClient;
    private int port;

    @Before
    public void startServer() {
        mockServerClient = startClientAndServer();
        port = mockServerClient.getPort();
    }

    @After
    public void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Test
    public void shouldRespondBeforeConsumingLargeRequestBody() throws Exception {
        // given
        mockServerClient
            .when(request()
                .withMethod("POST")
                .withPath("/upload")
                .withRespondBeforeBody(true)
            )
            .respond(response()
                .withStatusCode(403)
                .withBody("forbidden")
                .withConnectionOptions(connectionOptions().withCloseSocket(true))
            );

        long start = System.currentTimeMillis();
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Advertise a large body but only send headers + a tiny preamble.
            int advertisedContentLength = 50_000_000;
            String requestHead =
                "POST /upload HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: " + advertisedContentLength + "\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n";
            out.write(requestHead.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Send 1 KB of body
            byte[] preamble = new byte[1024];
            out.write(preamble);
            out.flush();

            // Now read the response
            byte[] buf = new byte[8192];
            int read = in.read(buf);
            long elapsed = System.currentTimeMillis() - start;

            assertTrue("expected response bytes, got " + read, read > 0);
            String responseText = new String(buf, 0, read, StandardCharsets.UTF_8);
            assertThat(responseText, containsString("403"));
            assertThat(responseText, containsString("forbidden"));

            // Response should arrive promptly, not after the full 50MB body is read
            assertThat("response should arrive quickly, elapsed=" + elapsed + "ms", elapsed, lessThan(5_000L));

            // Read should return -1 fairly soon (peer closed connection)
            int next = in.read();
            assertThat("server should close socket after early response", next, lessThanOrEqualTo(0));
        }
    }

    @Test
    public void shouldDeliverEarlyResponseToClientStillSendingBody() throws Exception {
        // given
        forbidUploadsBeforeTheirBody();

        for (int attempt = 0; attempt < 3; attempt++) {
            try (Socket socket = new Socket("localhost", port)) {
                // then
                assertEarlyResponseReadAfterWholeBodySent(socket, "attempt " + attempt);
            }
        }
    }

    @Test
    public void shouldDeliverEarlyResponseOverTlsToClientStillSendingBody() throws Exception {
        // given
        forbidUploadsBeforeTheirBody();

        try (Socket socket = startTls(new Socket("localhost", port))) {
            // then
            assertEarlyResponseReadAfterWholeBodySent(socket, "tls");
        }
    }

    private void forbidUploadsBeforeTheirBody() {
        mockServerClient
            .when(request()
                .withMethod("POST")
                .withPath("/upload")
                .withRespondBeforeBody(true)
            )
            .respond(response()
                .withStatusCode(403)
                .withBody("forbidden")
            );
    }

    /**
     * Sends a large body in full before reading the response, as okhttp does, so the server has answered and ended its
     * side of the connection while most of the body is still to arrive.
     */
    private static void assertEarlyResponseReadAfterWholeBodySent(Socket socket, String description) throws IOException {
        int bodyLength = 20_000_000;
        socket.setSoTimeout(10_000);
        OutputStream out = socket.getOutputStream();
        out.write((
            "POST /upload HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Length: " + bodyLength + "\r\n" +
            "\r\n"
        ).getBytes(StandardCharsets.US_ASCII));
        byte[] chunk = new byte[16 * 1024];
        try {
            for (int written = 0; written < bodyLength; written += chunk.length) {
                out.write(chunk, 0, Math.min(chunk.length, bodyLength - written));
            }
            out.flush();
        } catch (IOException e) {
            throw new AssertionError(description + ": connection failed while sending the body", e);
        }
        String responseText = readUntilEndOfStream(socket.getInputStream());
        assertThat(description, responseText, containsString("403"));
        assertThat(description, responseText, containsString("forbidden"));
    }

    private static SSLSocket startTls(Socket socket) throws Exception {
        KeyStore keyStore = new KeyStoreFactory(configuration(), new MockServerLogger()).loadOrCreateKeyStore();
        KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
        trusted.load(null, null);
        trusted.setCertificateEntry("mockserver-ca", keyStore.getCertificate(KeyStoreFactory.KEY_STORE_CA_ALIAS));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(socket, "localhost", socket.getPort(), true);
        tls.setUseClientMode(true);
        tls.startHandshake();
        return tls;
    }

    @Test
    public void shouldEndKeepAliveConnectionPromptlyAndSayItClosesAfterEarlyResponse() throws Exception {
        // given
        mockServerClient
            .when(request()
                .withMethod("POST")
                .withPath("/upload")
                .withRespondBeforeBody(true)
            )
            .respond(response()
                .withStatusCode(403)
            );

        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write((
                "POST /upload HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: 1024\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n"
            ).getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            // when
            long start = System.currentTimeMillis();
            String responseText = readUntilEndOfStream(socket.getInputStream());
            long elapsed = System.currentTimeMillis() - start;

            // then the server ends its side at once, not when it gives up waiting for the client
            assertThat("end of stream after " + elapsed + "ms", elapsed, lessThan(2_500L));
            assertThat(responseText, containsString("403"));
            assertThat(responseText.toLowerCase(), containsString("connection: close"));
            assertThat(responseText.toLowerCase(), not(containsString("keep-alive")));
        }
    }

    @Test
    public void shouldCloseConnectionOfClientThatKeepsItOpenAfterEarlyResponse() throws Exception {
        // given
        mockServerClient
            .when(request()
                .withMethod("POST")
                .withPath("/upload")
                .withRespondBeforeBody(true)
            )
            .respond(response()
                .withStatusCode(403)
            );

        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write((
                "POST /upload HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: 50000000\r\n" +
                "\r\n"
            ).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertThat(readUntilEndOfStream(socket.getInputStream()), containsString("403"));

            // when the client neither closes nor stops sending
            long start = System.currentTimeMillis();
            long deadline = start + 20_000;
            IOException closedByServer = null;
            while (closedByServer == null && System.currentTimeMillis() < deadline) {
                try {
                    out.write(new byte[1024]);
                    out.flush();
                    Thread.sleep(50);
                } catch (IOException e) {
                    closedByServer = e;
                }
            }
            long elapsed = System.currentTimeMillis() - start;

            // then within about its 5 s lingering limit, with headroom for a loaded machine
            assertTrue("server should end the connection within its lingering limit", closedByServer != null);
            assertThat("cut off after " + elapsed + "ms", elapsed, lessThan(10_000L));
        }
    }

    private static String readUntilEndOfStream(InputStream in) throws IOException {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            for (int read = in.read(buf); read >= 0; read = in.read(buf)) {
                received.write(buf, 0, read);
            }
        } catch (IOException e) {
            throw new AssertionError("connection failed after receiving " + received.size() + " bytes: [" + received.toString(StandardCharsets.UTF_8) + "]", e);
        }
        return received.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void shouldRejectExpectationWithRespondBeforeBodyAndBodyMatcher() throws IOException {
        // given a raw PUT to control plane with an invalid expectation
        String body = "{ \"httpRequest\":{ \"method\":\"POST\", \"path\":\"/upload\", \"respondBeforeBody\":true, \"body\":\"oops\" }," +
            "  \"httpResponse\":{ \"statusCode\":200 } }";

        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String requestHead =
                "PUT /mockserver/expectation HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body;
            out.write(requestHead.getBytes(StandardCharsets.UTF_8));
            out.flush();

            byte[] buf = new byte[8192];
            int read = in.read(buf);
            assertTrue(read > 0);
            String responseText = new String(buf, 0, read, StandardCharsets.UTF_8);
            assertThat(responseText, containsString("400"));
            assertThat(responseText, containsString("respondBeforeBody"));
        }
    }
}
