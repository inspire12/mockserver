package org.mockserver.netty.integration.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.MockServer;
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
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A request whose body is over {@code maxRequestBodySize} is answered 413 before its body is read. When the
 * connection then ends (the request is not keep-alive, or its body had started arriving) a client still sending
 * that body, as okhttp and curl do before reading, must receive the 413 and a clean end of stream, not a reset.
 */
public class OversizedRequestBodyIntegrationTest {

    private static final int MAX_REQUEST_BODY_SIZE = 64 * 1024;
    private static final int BODY_LENGTH = 20_000_000;

    private MockServer mockServer;

    @Before
    public void startServer() {
        mockServer = new MockServer(configuration().maxRequestBodySize(MAX_REQUEST_BODY_SIZE), 0);
    }

    @After
    public void stopServer() {
        stopQuietly(mockServer);
    }

    @Test
    public void shouldDeliverTooLargeToClientStillSendingBodyOfConnectionCloseRequest() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Socket socket = new Socket("localhost", mockServer.getLocalPort())) {
                sendWholeBody(socket, "POST /upload HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n", "attempt " + attempt);
                assertTooLargeThenEndOfStream(socket, "attempt " + attempt);
            }
        }
    }

    @Test
    public void shouldDeliverTooLargeToClientStillSendingBodyOfHttp10Request() throws Exception {
        try (Socket socket = new Socket("localhost", mockServer.getLocalPort())) {
            sendWholeBody(socket, "POST /upload HTTP/1.0\r\nHost: localhost\r\n", "http/1.0");
            assertTooLargeThenEndOfStream(socket, "http/1.0");
        }
    }

    @Test
    public void shouldDeliverTooLargeOverTlsToClientStillSendingBody() throws Exception {
        try (Socket socket = startTls(new Socket("localhost", mockServer.getLocalPort()))) {
            sendWholeBody(socket, "POST /upload HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n", "tls");
            assertTooLargeThenEndOfStream(socket, "tls");
        }
    }

    @Test
    public void shouldDeliverTooLargeToClientStillSendingChunkedBodyPastTheLimit() throws Exception {
        // no Content-Length, so the limit is only passed part-way through the body: the connection ends even though
        // the request is keep-alive, because the rest of the body cannot be told from the next request
        try (Socket socket = new Socket("localhost", mockServer.getLocalPort())) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write("POST /upload HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = new byte[16 * 1024];
            byte[] chunkHead = (Integer.toHexString(chunk.length) + "\r\n").getBytes(StandardCharsets.US_ASCII);
            byte[] crlf = "\r\n".getBytes(StandardCharsets.US_ASCII);
            try {
                for (int written = 0; written < BODY_LENGTH; written += chunk.length) {
                    out.write(chunkHead);
                    out.write(chunk);
                    out.write(crlf);
                }
                out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException e) {
                throw new AssertionError("chunked: connection failed while sending the body", e);
            }
            assertTooLargeThenEndOfStream(socket, "chunked");
        }
    }

    @Test
    public void shouldKeepKeepAliveConnectionOpenAfterTooLarge() throws Exception {
        try (Socket socket = new Socket("localhost", mockServer.getLocalPort())) {
            sendWholeBody(socket, "POST /upload HTTP/1.1\r\nHost: localhost\r\n", "keep-alive");
            OutputStream out = socket.getOutputStream();
            out.write(("GET /after HTTP/1.1\r\nHost: localhost:" + mockServer.getLocalPort() + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String responses = readUntilEndOfStream(socket.getInputStream(), "keep-alive");
            assertThat(responses, startsWith("HTTP/1.1 413 "));
            assertThat("the next request on the connection is still answered", responses, containsString("HTTP/1.1 404 "));
        }
    }

    /**
     * Sends a request head with a {@code Content-Length} over the limit, then the whole body before reading anything.
     */
    private static void sendWholeBody(Socket socket, String requestLineAndHeaders, String description) throws IOException {
        socket.setSoTimeout(10_000);
        OutputStream out = socket.getOutputStream();
        out.write((requestLineAndHeaders + "Content-Length: " + BODY_LENGTH + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        byte[] chunk = new byte[16 * 1024];
        try {
            for (int written = 0; written < BODY_LENGTH; written += chunk.length) {
                out.write(chunk, 0, Math.min(chunk.length, BODY_LENGTH - written));
            }
            out.flush();
        } catch (IOException e) {
            throw new AssertionError(description + ": connection failed while sending the body", e);
        }
    }

    private static void assertTooLargeThenEndOfStream(Socket socket, String description) throws IOException {
        String response = readUntilEndOfStream(socket.getInputStream(), description);
        assertThat(description, response, startsWith("HTTP/1.1 413 "));
        assertThat(description, response.toLowerCase(), containsString("connection: close"));
    }

    private static String readUntilEndOfStream(InputStream in, String description) {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            for (int read = in.read(buf); read >= 0; read = in.read(buf)) {
                received.write(buf, 0, read);
            }
        } catch (IOException e) {
            throw new AssertionError(description + ": connection failed after receiving " + received.size() + " bytes: [" + received.toString(StandardCharsets.UTF_8) + "]", e);
        }
        return received.toString(StandardCharsets.UTF_8);
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
}
