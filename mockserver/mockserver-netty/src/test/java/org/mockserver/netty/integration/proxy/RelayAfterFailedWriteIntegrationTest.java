package org.mockserver.netty.integration.proxy;

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * What a client sends to a relayed connection before it resets it, while a write to it is failing, still reaches the
 * upstream: over the binary (non-HTTP) relay and over the WebSocket relay.
 * <p>
 * The upstream sends more than the sockets' buffers hold and the client reads none of it, so MockServer has bytes
 * waiting to be written to the client. With MockServer's event loops held, the client sends its last message and resets
 * the connection, so that when the loops run again the message and the reset are both waiting: the write is tried
 * first, and fails.
 */
public class RelayAfterFailedWriteIntegrationTest {

    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final byte[] FLOOD = new byte[64 * 1024];

    private WorkerGroupMockServer mockServer;
    private Upstream upstream;

    private static final class WorkerGroupMockServer extends MockServer {
        WorkerGroupMockServer(Configuration configuration, Integer remotePort) {
            super(configuration, remotePort, "127.0.0.1", 0);
        }

        WorkerGroupMockServer(Configuration configuration) {
            super(configuration, 0);
        }

        EventLoopGroup workers() {
            return getEventLoopGroup();
        }
    }

    @After
    public void stop() throws IOException {
        stopQuietly(mockServer);
        if (upstream != null) {
            upstream.close();
        }
    }

    @Test
    public void shouldForwardABinaryMessageSentBeforeAResetOnOneUpstreamConnection() throws Exception {
        upstream = new Upstream(false);
        mockServer = new WorkerGroupMockServer(quiet(), upstream.port());
        try (Socket client = connect()) {
            send(client, "first\n".getBytes(StandardCharsets.US_ASCII));
            sendBehindAStalledWriteThenReset(client, "last\n".getBytes(StandardCharsets.US_ASCII));
        }
        await("the message sent before the reset reached the upstream", () -> upstream.receivedText().contains("last\n"));
        assertThat("one upstream connection", upstream.connections.get(), is(1));
    }

    @Test
    public void shouldRelayAWebSocketFrameSentBeforeAReset() throws Exception {
        upstream = new Upstream(true);
        mockServer = new WorkerGroupMockServer(quiet().attemptToProxyIfNoMatchingExpectation(true));
        try (Socket client = connect()) {
            send(client, ("GET /relay HTTP/1.1\r\nHost: 127.0.0.1:" + upstream.port() + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            assertThat(readHead(client.getInputStream()), startsWith("HTTP/1.1 101"));
            send(client, maskedTextFrame("first"));
            sendBehindAStalledWriteThenReset(client, maskedTextFrame("last"));
        }
        await("the frame sent before the reset reached the upstream", () -> upstream.textFrames.contains("last"));
        assertThat(upstream.textFrames, hasItem("first"));
    }

    private static Configuration quiet() {
        return configuration().logLevel("WARN").startupWarmup(false).proxySetup(false);
    }

    /**
     * A client socket with a small receive buffer, set before it connects so that the kernel does not grow it.
     */
    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(4096);
        socket.connect(new InetSocketAddress("127.0.0.1", mockServer.getLocalPort()), 10_000);
        socket.setSoTimeout(10_000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private void sendBehindAStalledWriteThenReset(Socket client, byte[] last) throws Exception {
        await("the upstream is flooding the client", () -> upstream.flooding.getCount() == 0);
        awaitStalled(client.getInputStream());
        Runnable release = holdEventLoops();
        try {
            send(client, last);
            client.setSoLinger(true, 0);
            client.close();
            // loopback delivers the message and the reset before the loops run again
            Thread.sleep(300);
        } finally {
            release.run();
        }
    }

    private static void send(Socket socket, byte[] bytes) throws IOException {
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    /**
     * Waits until the bytes the client has been sent stop growing: its receive buffer is full and MockServer has more
     * still to write.
     */
    private static void awaitStalled(InputStream input) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int before = -1;
        while (System.nanoTime() < deadline) {
            Thread.sleep(250);
            int now = input.available();
            if (now > 0 && now == before) {
                return;
            }
            before = now;
        }
        throw new AssertionError("the client's connection did not stall within 10 seconds");
    }

    private Runnable holdEventLoops() throws InterruptedException {
        List<EventExecutor> loops = new ArrayList<>();
        mockServer.workers().forEach(loops::add);
        CountDownLatch held = new CountDownLatch(loops.size());
        CountDownLatch release = new CountDownLatch(1);
        for (EventExecutor loop : loops) {
            loop.execute(() -> {
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        if (!held.await(10, TimeUnit.SECONDS)) {
            release.countDown();
            throw new AssertionError("the event loops were not held within 10 seconds");
        }
        return release::countDown;
    }

    private static String readHead(InputStream input) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.US_ASCII.name()).endsWith("\r\n\r\n")) {
            int next = input.read();
            if (next == -1) {
                throw new IOException("connection closed after: " + head.toString(StandardCharsets.US_ASCII.name()));
            }
            head.write(next);
        }
        return head.toString(StandardCharsets.US_ASCII.name());
    }

    private static byte[] maskedTextFrame(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] mask = {1, 2, 3, 4};
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        // FIN, text; masked, a length under 126
        frame.write(0x81);
        frame.write(0x80 | payload.length);
        frame.write(mask, 0, mask.length);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i % 4]);
        }
        return frame.toByteArray();
    }

    private static void await(String reason, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(reason, condition.getAsBoolean(), is(true));
    }

    /**
     * Answers each connection's first bytes (or, for a WebSocket, its handshake) by sending more than the sockets'
     * buffers hold, and records what it receives: the bytes themselves, or a WebSocket's text frames.
     */
    private static final class Upstream implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final boolean webSocket;
        private final AtomicInteger connections = new AtomicInteger();
        private final CountDownLatch flooding = new CountDownLatch(1);
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private final List<String> textFrames = new CopyOnWriteArrayList<>();
        private final List<Socket> accepted = new CopyOnWriteArrayList<>();

        Upstream(boolean webSocket) throws IOException {
            this.webSocket = webSocket;
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            daemon(() -> {
                try {
                    while (true) {
                        Socket socket = serverSocket.accept();
                        accepted.add(socket);
                        connections.incrementAndGet();
                        daemon(() -> serve(socket));
                    }
                } catch (IOException closed) {
                    // stopped
                }
            });
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        String receivedText() {
            synchronized (received) {
                return new String(received.toByteArray(), StandardCharsets.US_ASCII);
            }
        }

        private void serve(Socket socket) {
            try {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                if (webSocket) {
                    String head = readHead(input);
                    String key = head.replaceAll("(?is).*sec-websocket-key:\\s*([^\\r\\n]+).*", "$1").trim();
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII)));
                    output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    textFrames.add(readTextFrame(input));
                    daemon(() -> flood(output, true));
                    for (String text; (text = readTextFrame(input)) != null; ) {
                        textFrames.add(text);
                    }
                } else {
                    byte[] buffer = new byte[8192];
                    boolean first = true;
                    for (int count; (count = input.read(buffer)) != -1; ) {
                        synchronized (received) {
                            received.write(buffer, 0, count);
                        }
                        if (first) {
                            first = false;
                            daemon(() -> flood(output, false));
                        }
                    }
                }
            } catch (Exception closed) {
                // the connection ended
            }
        }

        /**
         * Writes until the connection fails or 64 MB have been written, which no socket buffers hold.
         */
        private void flood(OutputStream output, boolean asWebSocketFrames) {
            flooding.countDown();
            try {
                for (int i = 0; i < 1024; i++) {
                    if (asWebSocketFrames) {
                        // FIN, binary; unmasked, a 16-bit length
                        output.write(new byte[]{(byte) 0x82, 126, (byte) 0xff, (byte) 0xff});
                        output.write(FLOOD, 0, 0xffff);
                    } else {
                        output.write(FLOOD);
                    }
                }
            } catch (IOException closed) {
                // the relay closed the connection
            }
        }

        /**
         * A client's frame, which is masked; null at the end of the connection.
         */
        private static String readTextFrame(InputStream input) throws IOException {
            int first = input.read();
            if (first == -1) {
                return null;
            }
            int second = input.read();
            long length = second & 0x7f;
            if (length == 126) {
                length = (input.read() << 8) | input.read();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | input.read();
                }
            }
            byte[] mask = readFully(input, (second & 0x80) != 0 ? 4 : 0);
            byte[] payload = readFully(input, (int) length);
            for (int i = 0; i < payload.length && mask.length == 4; i++) {
                payload[i] ^= mask[i % 4];
            }
            return (first & 0x0f) == 0x1 ? new String(payload, StandardCharsets.UTF_8) : "opcode " + (first & 0x0f);
        }

        private static byte[] readFully(InputStream input, int length) throws IOException {
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

        private static void daemon(ThrowingRunnable runnable) {
            Thread thread = new Thread(() -> {
                try {
                    runnable.run();
                } catch (Exception e) {
                    // the connection ended
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            for (Socket socket : accepted) {
                socket.close();
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
