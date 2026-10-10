package org.mockserver.netty.integration.proxy.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.netty.MockServer;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.LogEntrySerializer;
import org.mockserver.socket.tls.KeyStoreFactory;
import org.slf4j.event.Level;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A whole request sent as the first bytes of a CONNECT or SOCKS tunnel, or of a direct connection, in one write, by a
 * client that closes its connection as soon as it has written it, over real sockets. MockServer must receive the
 * request, both of a tunnel's legs must close, and a client that simply leaves is not worth a warning.
 * <p>
 * The protocol is only known from these bytes, so the HTTP/2 handlers are installed while they are being read; and
 * on HTTP/2 both the relay and MockServer answer the client's preface while its request is still arriving. Those
 * answers wait for the end of the read, so a client that resets its connection does not lose its request; and they
 * are not held back from a client that waits for the server's {@code SETTINGS}.
 */
public class RelayTunnelFirstBytesIntegrationTest {

    private static final byte[] HTTP2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int HTTP2_MAX_FRAME_SIZE = 16_384;
    private static final int HTTP2_SETTINGS = 0x4;
    private static final int ATTEMPTS = 10;

    private MockServer mockServer;
    private int port;

    @Before
    public void startServer() {
        // WARN: the entries asserted on below must reach the event log whatever level the build runs tests at
        mockServer = new MockServer(configuration().logLevel("WARN").startupWarmup(false).proxySetup(false), 0);
        port = mockServer.getLocalPort();
        controlPlane("/mockserver/expectation", new ExpectationSerializer(new MockServerLogger()).serialize(
            new Expectation(request().withPath("/first")).thenRespond(response().withBody("answered"))
        ));
    }

    @After
    public void stopServer() {
        stopQuietly(mockServer);
    }

    @Test
    public void shouldReceiveAnHttp2RequestSentAsTheFirstBytesOfAConnectTunnelByAClientThatLeavesAtOnce() throws Exception {
        // one DATA frame, and well under the 65,535 byte window: the client needs nothing from MockServer to send it
        sendAsFirstBytesAndLeave(this::openConnectTunnel, http2Request(16_000));
    }

    @Test
    public void shouldReceiveAnHttp2RequestSentAsTheFirstBytesOfASocksTunnelByAClientThatLeavesAtOnce() throws Exception {
        // several DATA frames, still under the window
        sendAsFirstBytesAndLeave(this::openSocks5Tunnel, http2Request(60_000));
    }

    @Test
    public void shouldReceiveAnHttp1RequestSentAsTheFirstBytesOfAConnectTunnelByAClientThatLeavesAtOnce() throws Exception {
        sendAsFirstBytesAndLeave(this::openConnectTunnel, http1Request(16_000));
    }

    @Test
    public void shouldReceiveAnHttp1RequestSentAsTheFirstBytesOfASocksTunnelByAClientThatLeavesAtOnce() throws Exception {
        sendAsFirstBytesAndLeave(this::openSocks5Tunnel, http1Request(16_000));
    }

    @Test
    public void shouldReceiveAnHttp2RequestWithNoBodySentOnADirectConnectionByAClientThatResets() throws Exception {
        sendAsFirstBytesAndResetEachTime(this::openDirect, 1, () -> http2Request(0));
    }

    @Test
    public void shouldReceiveAnHttp2RequestSentOnADirectConnectionByAClientThatResets() throws Exception {
        sendAsFirstBytesAndResetEachTime(this::openDirect, 1, () -> http2Request(16_000));
    }

    @Test
    public void shouldReceiveAnHttp2RequestWithSeveralDataFramesSentOnADirectConnectionByAClientThatResets() throws Exception {
        sendAsFirstBytesAndResetEachTime(this::openDirect, 1, () -> http2Request(60_000));
    }

    @Test
    public void shouldReceiveAnHttp2RequestWithNoBodySentThroughAConnectTunnelByAClientThatResets() throws Exception {
        sendAsFirstBytesAndResetEachTime(this::openConnectTunnel, 2, () -> http2Request(0));
    }

    @Test
    public void shouldReceiveAnHttp2RequestSentThroughAConnectTunnelByAClientThatResets() throws Exception {
        sendAsFirstBytesAndResetEachTime(this::openConnectTunnel, 2, () -> http2Request(16_000));
    }

    @Test
    public void shouldReceiveAnHttp2RequestWithSeveralDataFramesSentThroughAConnectTunnelByAClientThatResets() throws Exception {
        sendAsFirstBytesAndResetEachTime(this::openConnectTunnel, 2, () -> http2Request(60_000));
    }

    @Test
    public void shouldReceiveAnHttp2RequestWithNoBodySentThroughASocksTunnelByAClientThatResets() throws Exception {
        sendAsFirstBytesAndResetEachTime(this::openSocks5Tunnel, 2, () -> http2Request(0));
    }

    @Test
    public void shouldReceiveAnHttp2RequestSentThroughASocksTunnelByAClientThatResets() throws Exception {
        sendAsFirstBytesAndResetEachTime(this::openSocks5Tunnel, 2, () -> http2Request(16_000));
    }

    @Test
    public void shouldReceiveAnHttp2RequestWithSeveralDataFramesSentThroughASocksTunnelByAClientThatResets() throws Exception {
        sendAsFirstBytesAndResetEachTime(this::openSocks5Tunnel, 2, () -> http2Request(60_000));
    }

    @Test
    public void shouldReceiveAnHttp1RequestSentOnADirectConnectionByAClientThatResets() throws Exception {
        sendAsFirstBytesAndResetEachTime(this::openDirect, 1, () -> http1Request(16_000));
    }

    @Test
    public void shouldReceiveAnHttp1RequestSentThroughAConnectTunnelByAClientThatResets() throws Exception {
        sendAsFirstBytesAndResetEachTime(this::openConnectTunnel, 2, () -> http1Request(16_000));
    }

    @Test
    public void shouldReceiveAnHttp1RequestSentThroughASocksTunnelByAClientThatResets() throws Exception {
        sendAsFirstBytesAndResetEachTime(this::openSocks5Tunnel, 2, () -> http1Request(16_000));
    }

    @Test
    public void shouldSendSettingsToAnH2cClientWaitingForThemOnADirectConnection() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            sendPrefaceThenAwaitServerSettings(socket);
        }
    }

    @Test
    public void shouldSendSettingsToAnH2cClientWaitingForThemThroughAConnectTunnel() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            openConnectTunnel(socket);
            sendPrefaceThenAwaitServerSettings(socket);
        }
    }

    @Test
    public void shouldSendSettingsToAnH2cClientWaitingForThemThroughASocksTunnel() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            openSocks5Tunnel(socket);
            sendPrefaceThenAwaitServerSettings(socket);
        }
    }

    @Test
    public void shouldSendSettingsToAnH2ClientWaitingForThemOnADirectTlsConnection() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port); SSLSocket tls = startTlsWithH2(socket)) {
            sendPrefaceThenAwaitServerSettings(tls);
        }
    }

    @Test
    public void shouldSendSettingsWhenTheTlsHandshakeOfAConnectTunnelCompletesBeforeTheClientSendsAnything() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            openConnectTunnel(socket);
            try (SSLSocket tls = startTlsWithH2(socket)) {
                // the tunnel's HTTP/2 handler is added as the handshake completes, before any request bytes
                awaitServerSettings(tls.getInputStream());
            }
        }
    }

    private void sendPrefaceThenAwaitServerSettings(Socket socket) throws IOException {
        ByteArrayOutputStream preface = new ByteArrayOutputStream();
        write(preface, HTTP2_PREFACE);
        writeFrame(preface, HTTP2_SETTINGS, 0x0, 0, new byte[0]);
        socket.getOutputStream().write(preface.toByteArray());
        socket.getOutputStream().flush();
        // nothing more is sent: the server's SETTINGS must not wait for another read
        awaitServerSettings(socket.getInputStream());
    }

    /**
     * Reads frames until a SETTINGS frame that is not an acknowledgement, or fails on the socket's read timeout.
     */
    private static void awaitServerSettings(InputStream input) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            byte[] header = readBytes(input, 9);
            int length = (header[0] & 0xff) << 16 | (header[1] & 0xff) << 8 | header[2] & 0xff;
            readBytes(input, length);
            if (header[3] == HTTP2_SETTINGS && (header[4] & 0x1) == 0) {
                return;
            }
        }
        throw new AssertionError("no SETTINGS from the server within 10 seconds");
    }

    private SSLSocket startTlsWithH2(Socket socket) throws IOException {
        socket.setSoTimeout(10_000);
        SSLSocket tls = (SSLSocket) new KeyStoreFactory(configuration(), new MockServerLogger()).sslContext().getSocketFactory()
            .createSocket(socket, "127.0.0.1", port, true);
        tls.setUseClientMode(true);
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setApplicationProtocols(new String[]{"h2"});
        tls.setSSLParameters(parameters);
        tls.startHandshake();
        assertThat("ALPN", tls.getApplicationProtocol(), is("h2"));
        return tls;
    }

    /**
     * Each attempt on a connection of its own, which the client closes with {@code SO_LINGER} 0, so its kernel resets
     * the connection rather than sending a FIN. The request has been written whole before then.
     */
    private void sendAsFirstBytesAndResetEachTime(TunnelOpener opener, int connectionsOpen, Supplier<byte[]> request) throws Exception {
        List<String> loggedBefore = loggedAtWarnOrAbove();
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(10_000);
                opener.open(socket);
                await("the connection is open, with its tunnel's loopback if it has one", () -> mockServer.getInboundConnectionCount() == connectionsOpen);

                socket.getOutputStream().write(request.get());
                socket.getOutputStream().flush();
                socket.setSoLinger(true, 0);
            }
            await("every leg closed", () -> mockServer.getInboundConnectionCount() == 0);
        }

        // waited for without failing, so that a failure reports how many were received
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (requestsReceived() < ATTEMPTS && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat("requests received of " + ATTEMPTS, requestsReceived(), is(ATTEMPTS));
        assertThat("nothing logged at WARN or above for a client that reset", loggedAtWarnOrAbove(), is(loggedBefore));
    }

    private void openDirect(Socket socket) {
        // nothing comes before the request
    }

    private void sendAsFirstBytesAndLeave(TunnelOpener tunnelOpener, byte[] request) throws Exception {
        List<String> loggedBefore = loggedAtWarnOrAbove();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            tunnelOpener.open(socket);
            await("only the client's leg and the loopback leg are open", () -> mockServer.getInboundConnectionCount() == 2);

            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            // closed with nothing unread, so the client sends a FIN and not a reset
        }

        await("the request reached MockServer", this::requestReceived);
        await("both legs closed", () -> mockServer.getInboundConnectionCount() == 0);
        assertThat("nothing logged at WARN or above for a client that left", loggedAtWarnOrAbove(), is(loggedBefore));
    }

    private byte[] http1Request(int bodyBytes) {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        write(request, ("POST /first HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nContent-Length: " + bodyBytes + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        write(request, body(bodyBytes));
        return request.toByteArray();
    }

    /**
     * The client preface, an empty SETTINGS frame, and one POST on stream 1: HEADERS, then DATA frames ending the stream.
     */
    private byte[] http2Request(int bodyBytes) {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        write(request, HTTP2_PREFACE);
        writeFrame(request, 0x4, 0x0, 0, new byte[0]);
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        // indexed fields of the HPACK static table: ":method: POST" and ":scheme: http"
        headers.write(0x83);
        headers.write(0x86);
        writeLiteralHeader(headers, 0x04, "/first");
        writeLiteralHeader(headers, 0x01, "127.0.0.1:" + port);
        // END_HEADERS, and END_STREAM when there is no body
        writeFrame(request, 0x1, bodyBytes == 0 ? 0x5 : 0x4, 1, headers.toByteArray());
        for (int sent = 0; sent < bodyBytes; ) {
            int length = Math.min(HTTP2_MAX_FRAME_SIZE, bodyBytes - sent);
            sent += length;
            // END_STREAM on the last
            writeFrame(request, 0x0, sent == bodyBytes ? 0x1 : 0x0, 1, body(length));
        }
        return request.toByteArray();
    }

    /**
     * A header field not added to the dynamic table, its name taken from the static table and its value not Huffman coded.
     */
    private static void writeLiteralHeader(ByteArrayOutputStream headers, int staticTableNameIndex, String value) {
        headers.write(staticTableNameIndex);
        headers.write(value.length());
        write(headers, value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeFrame(ByteArrayOutputStream out, int type, int flags, int streamId, byte[] payload) {
        out.write(payload.length >> 16);
        out.write(payload.length >> 8);
        out.write(payload.length);
        out.write(type);
        out.write(flags);
        out.write(streamId >> 24);
        out.write(streamId >> 16);
        out.write(streamId >> 8);
        out.write(streamId);
        write(out, payload);
    }

    private static byte[] body(int length) {
        // printable: the event log's JSON spends six characters on a zero byte, in each of its copies of the body
        byte[] body = new byte[length];
        Arrays.fill(body, (byte) 'x');
        return body;
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    private interface TunnelOpener {
        void open(Socket socket) throws IOException;
    }

    private void openConnectTunnel(Socket socket) throws IOException {
        socket.getOutputStream().write(("CONNECT 127.0.0.1:" + port + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.US_ASCII.name()).endsWith("\r\n\r\n")) {
            head.write(readBytes(socket.getInputStream(), 1), 0, 1);
        }
        assertThat(head.toString(StandardCharsets.US_ASCII.name()), containsString("200"));
    }

    /**
     * Reads the whole of each reply: closing a socket with anything unread resets the connection, and this client is to
     * leave with a FIN.
     */
    private void openSocks5Tunnel(Socket socket) throws IOException {
        OutputStream output = socket.getOutputStream();
        // version 5, one method offered: no authentication
        output.write(new byte[]{5, 1, 0});
        output.flush();
        assertThat(readBytes(socket.getInputStream(), 2), is(new byte[]{5, 0}));
        // version 5, CONNECT, reserved, IPv4 127.0.0.1, port
        output.write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port});
        output.flush();
        // version, status, reserved, address type, and the first byte of the address
        byte[] reply = readBytes(socket.getInputStream(), 5);
        assertThat("SOCKS5 success", reply[1], is((byte) 0));
        int restOfAddress = reply[3] == 3 ? reply[4] : reply[3] == 1 ? 3 : 15;
        readBytes(socket.getInputStream(), restOfAddress + 2);
    }

    private static byte[] readBytes(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        for (int read = 0; read < length; ) {
            int count = input.read(bytes, read, length - read);
            if (count == -1) {
                throw new IOException("connection closed after " + read + " of " + length + " bytes");
            }
            read += count;
        }
        return bytes;
    }

    private boolean requestReceived() {
        return controlPlane("/mockserver/retrieve?type=REQUESTS&format=JSON", "{\"path\":\"/first\"}").contains("\"/first\"");
    }

    private int requestsReceived() {
        String received = controlPlane("/mockserver/retrieve?type=REQUESTS&format=JSON", "{\"path\":\"/first\"}");
        try {
            return received.trim().isEmpty() ? 0 : new ObjectMapper().readTree(received).size();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private List<String> loggedAtWarnOrAbove() {
        List<String> messages = new ArrayList<>();
        for (LogEntry entry : new LogEntrySerializer(new MockServerLogger()).deserializeArray(controlPlane("/mockserver/retrieve?type=LOGS&format=LOG_ENTRIES", ""))) {
            if (entry.getLogLevel() != null && entry.getLogLevel().toInt() >= Level.WARN.toInt()) {
                messages.add(entry.getLogLevel() + " " + entry.getMessageFormat());
            }
        }
        return messages;
    }

    /**
     * One control-plane call on a connection of its own, closed with its response, so that the server's open connections
     * are soon the tunnel's alone.
     */
    private String controlPlane(String pathAndQuery, String json) {
        try (Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort())) {
            socket.setSoTimeout(10_000);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(("PUT " + pathAndQuery + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = socket.getInputStream().read(buffer)) != -1; ) {
                response.write(buffer, 0, count);
            }
            String text = response.toString(StandardCharsets.UTF_8.name());
            assertThat(text, startsWith("HTTP/1.1 20"));
            return text.substring(text.indexOf("\r\n\r\n") + 4);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void await(String reason, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(reason, condition.getAsBoolean(), is(true));
    }
}
