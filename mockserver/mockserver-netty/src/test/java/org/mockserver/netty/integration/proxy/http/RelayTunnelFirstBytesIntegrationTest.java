package org.mockserver.netty.integration.proxy.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.netty.MockServer;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.LogEntrySerializer;
import org.slf4j.event.Level;

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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A whole request sent as the first bytes of a CONNECT or SOCKS tunnel, in one write, by a client that closes its
 * connection as soon as it has written it, over real sockets. MockServer must receive the request, as it does on a
 * direct connection, both of the tunnel's legs must close, and a client that simply leaves is not worth a warning.
 * <p>
 * The tunnel's protocol is only known from these bytes, so the relay's handlers are installed while they are being
 * read; and on HTTP/2 both the relay and MockServer answer the client's preface while its request is still arriving.
 */
public class RelayTunnelFirstBytesIntegrationTest {

    private static final byte[] HTTP2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int HTTP2_MAX_FRAME_SIZE = 16_384;

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
        // END_HEADERS
        writeFrame(request, 0x1, 0x4, 1, headers.toByteArray());
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
