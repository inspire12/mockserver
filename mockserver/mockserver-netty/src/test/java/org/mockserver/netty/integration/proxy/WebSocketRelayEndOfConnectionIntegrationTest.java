package org.mockserver.netty.integration.proxy;

import org.junit.After;
import org.junit.Test;
import org.mockserver.netty.MockServer;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * When one side of a relayed WebSocket leaves, the frames it sent just before reach the other side, followed by a close
 * frame: the one it sent, or 1001 (going away) for a side that left without one. What the other side sends from then
 * on is read (and dropped) rather than left unread.
 * <p>
 * The side that leaves reads nothing while the other floods it, so the relay stops reading the other side and holds
 * bytes from it unread. The other side reads slowly from a small receive buffer, so when the first leaves the relay
 * still has frames on their way to it. Closed with input unread, a socket sends a reset, and the kernel discards what
 * it had not yet sent.
 */
public class WebSocketRelayEndOfConnectionIntegrationTest {

    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int FRAMES = 512;
    private static final int FRAME_BYTES = 16 * 1024;
    private static final int SMALL_BUFFER = 16 * 1024;
    private static final int NORMAL_CLOSURE = 1000;
    private static final int GOING_AWAY = 1001;

    private MockServer mockServer;
    private ServerSocket upstream;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final AtomicLong flooded = new AtomicLong();
    private final AtomicLong floodedWhenLeaving = new AtomicLong();

    @After
    public void stop() throws IOException {
        stopQuietly(mockServer);
        for (Socket socket : sockets) {
            socket.close();
        }
        if (upstream != null) {
            upstream.close();
        }
    }

    @Test
    public void shouldDeliverAClientsFramesAndItsCloseFrameToASlowUpstream() throws Exception {
        assertThat(clientLeaves(true), is(new Received(FRAMES, NORMAL_CLOSURE, 0, true)));
    }

    @Test
    public void shouldDeliverAClientsFramesAndGoingAwayToASlowUpstreamWhenTheClientLeavesWithoutACloseFrame() throws Exception {
        assertThat(clientLeaves(false), is(new Received(FRAMES, GOING_AWAY, 0, true)));
    }

    @Test
    public void shouldDeliverAnUpstreamsFramesAndItsCloseFrameToASlowClient() throws Exception {
        assertThat(upstreamLeaves(true), is(new Received(FRAMES, NORMAL_CLOSURE, 0, true)));
    }

    @Test
    public void shouldDeliverAnUpstreamsFramesAndGoingAwayToASlowClientWhenTheUpstreamLeavesWithoutACloseFrame() throws Exception {
        assertThat(upstreamLeaves(false), is(new Received(FRAMES, GOING_AWAY, 0, true)));
    }

    @Test
    public void shouldCloseTheClientsConnectionWithinTheLingerLimitWhenTheUpstreamLeavesAndTheClientNeverReads() throws Exception {
        start();
        CompletableFuture<Socket> upstreamSocket = new CompletableFuture<>();
        daemon(() -> {
            Socket socket = acceptAndHandshake();
            upstreamSocket.complete(socket);
            daemon(() -> flood(socket, false));
            while (readFrame(socket.getInputStream()) != null) {
                // what the client sends is taken, so that nothing backs up towards the upstream
            }
        });
        Socket client = upgradedClient();
        awaitStalled(client.getInputStream());
        Socket upstreamLeaving = upstreamSocket.get(10, TimeUnit.SECONDS);
        upstreamLeaving.setSoLinger(true, 0);
        upstreamLeaving.close();

        // the client goes on sending, which shows the relay that the upstream has gone and then whether it still holds
        // the client's connection open; it never reads what is queued for it, the close frame included
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        boolean closed = false;
        while (!closed && System.nanoTime() < deadline) {
            try {
                client.getOutputStream().write(frame(0x2, new byte[16], true));
                client.getOutputStream().flush();
                Thread.sleep(20);
            } catch (IOException e) {
                closed = true;
            }
        }
        assertThat("the client's connection was closed within 20 seconds", closed, is(true));
    }

    /**
     * The upstream floods the client and reads its frames slowly; the client reads nothing, sends its frames and ends
     * its output.
     */
    private Received clientLeaves(boolean withCloseFrame) throws Exception {
        start();
        CompletableFuture<Received> received = new CompletableFuture<>();
        daemon(() -> {
            try {
                Socket socket = acceptAndHandshake();
                daemon(() -> flood(socket, false));
                received.complete(readUntilClosed(paced(socket.getInputStream())));
            } catch (Throwable e) {
                received.completeExceptionally(e);
            }
        });
        Socket client = upgradedClient();
        awaitStalled(client.getInputStream());
        leave(client, true, withCloseFrame);
        return received.get(60, TimeUnit.SECONDS);
    }

    /**
     * The client floods the upstream and reads its frames slowly; the upstream reads nothing, sends its frames and ends
     * its output.
     */
    private Received upstreamLeaves(boolean withCloseFrame) throws Exception {
        start();
        daemon(() -> {
            try {
                Socket socket = acceptAndHandshake();
                awaitStalled(socket.getInputStream());
                leave(socket, false, withCloseFrame);
            } catch (Exception e) {
                // the test fails on what the client received
            }
        });
        Socket client = upgradedClient();
        daemon(() -> flood(client, true));
        return readUntilClosed(paced(client.getInputStream()));
    }

    private void start() throws IOException {
        upstream = new ServerSocket();
        // set before binding so that accepted sockets get it and the kernel does not grow it
        upstream.setReceiveBufferSize(SMALL_BUFFER);
        upstream.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0));
        mockServer = new MockServer(configuration().logLevel("WARN").startupWarmup(false).proxySetup(false).attemptToProxyIfNoMatchingExpectation(true), 0);
    }

    private Socket acceptAndHandshake() throws Exception {
        Socket socket = upstream.accept();
        sockets.add(socket);
        socket.setSoTimeout(30_000);
        String head = readHead(socket.getInputStream());
        String key = head.replaceAll("(?is).*sec-websocket-key:\\s*([^\\r\\n]+).*", "$1").trim();
        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII)));
        socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    private Socket upgradedClient() throws IOException {
        Socket client = new Socket();
        sockets.add(client);
        // set before connecting so that the kernel does not grow it
        client.setReceiveBufferSize(SMALL_BUFFER);
        client.connect(new InetSocketAddress("127.0.0.1", mockServer.getLocalPort()), 10_000);
        client.setSoTimeout(30_000);
        client.getOutputStream().write(("GET /relay HTTP/1.1\r\nHost: 127.0.0.1:" + upstream.getLocalPort() + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
        String head = readHead(client.getInputStream());
        if (!head.startsWith("HTTP/1.1 101")) {
            throw new AssertionError("not upgraded: " + head);
        }
        return client;
    }

    /**
     * Sends binary frames, each starting with its sequence number, and if asked a close frame and one more frame, then
     * ends the output; the socket stays open, with what it was sent unread.
     */
    private void leave(Socket socket, boolean masked, boolean withCloseFrame) throws IOException {
        floodedWhenLeaving.set(flooded.get());
        OutputStream output = socket.getOutputStream();
        for (int sequence = 0; sequence < FRAMES; sequence++) {
            byte[] payload = new byte[FRAME_BYTES];
            ByteBuffer.wrap(payload).putInt(sequence);
            output.write(frame(0x2, payload, masked));
        }
        if (withCloseFrame) {
            output.write(frame(0x8, new byte[]{(byte) (NORMAL_CLOSURE >> 8), (byte) NORMAL_CLOSURE}, masked));
            output.write(frame(0x2, new byte[FRAME_BYTES], masked));
        }
        output.flush();
        socket.shutdownOutput();
    }

    /**
     * Writes text frames until the connection fails or 64 MB have been written, which no socket buffers hold.
     */
    private void flood(Socket socket, boolean masked) {
        byte[] frame = frame(0x1, "x".repeat(0xffff).getBytes(StandardCharsets.US_ASCII), masked);
        try {
            OutputStream output = socket.getOutputStream();
            for (int i = 0; i < 1024; i++) {
                output.write(frame);
                flooded.addAndGet(frame.length);
            }
        } catch (IOException closed) {
            // the connection ended
        }
    }

    private static byte[] frame(int opcode, byte[] payload, boolean masked) {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 8);
        frame.write(0x80 | opcode);
        int maskBit = masked ? 0x80 : 0;
        if (payload.length < 126) {
            frame.write(maskBit | payload.length);
        } else {
            frame.write(maskBit | 126);
            frame.write(payload.length >> 8);
            frame.write(payload.length & 0xff);
        }
        byte[] mask = {1, 2, 3, 4};
        if (masked) {
            frame.write(mask, 0, mask.length);
        }
        for (int i = 0; i < payload.length; i++) {
            frame.write(masked ? payload[i] ^ mask[i % 4] : payload[i]);
        }
        return frame.toByteArray();
    }

    /**
     * Reads frames until a close frame or the end of the connection, checking that the binary frames arrive in order;
     * then counts the frames that follow up to the end of the connection, and waits for this side's own flood to be
     * taken further than when the other side left.
     */
    private Received readUntilClosed(InputStream input) throws Exception {
        int[] frames = {0};
        Integer closeCode;
        try {
            closeCode = readFrames(input, frames);
        } catch (IOException e) {
            throw new IOException("after " + frames[0] + " of " + FRAMES + " frames: " + e, e);
        }
        int framesAfterClose = 0;
        if (closeCode != null) {
            while (readFrame(input) != null) {
                framesAfterClose++;
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (flooded.get() < floodedWhenLeaving.get() + 1024 * 1024 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return new Received(frames[0], closeCode, framesAfterClose, flooded.get() >= floodedWhenLeaving.get() + 1024 * 1024);
    }

    /**
     * @return the close frame's code, or null at the end of the connection
     */
    private static Integer readFrames(InputStream input, int[] received) throws IOException {
        while (true) {
            byte[][] frame = readFrame(input);
            if (frame == null) {
                return null;
            }
            int opcode = frame[0][0];
            byte[] payload = frame[1];
            if (opcode == 0x8) {
                return payload.length >= 2 ? ((payload[0] & 0xff) << 8) | (payload[1] & 0xff) : null;
            }
            int sequence = ByteBuffer.wrap(payload).getInt();
            if (opcode != 0x2 || payload.length != FRAME_BYTES || sequence != received[0]) {
                throw new IOException("frame " + received[0] + " arrived as opcode " + opcode + ", " + payload.length + " bytes, sequence " + sequence);
            }
            received[0]++;
        }
    }

    /**
     * @return the frame's opcode and its unmasked payload, or null at the end of the connection
     */
    private static byte[][] readFrame(InputStream input) throws IOException {
        int first = input.read();
        if (first == -1) {
            return null;
        }
        int second = input.read();
        int length = second & 0x7f;
        if (length == 126) {
            length = (input.read() << 8) | input.read();
        } else if (length == 127) {
            throw new IOException("unexpected 64-bit frame length");
        }
        byte[] mask = readFully(input, (second & 0x80) != 0 ? 4 : 0);
        byte[] payload = readFully(input, length);
        for (int i = 0; i < payload.length && mask.length == 4; i++) {
            payload[i] ^= mask[i % 4];
        }
        return new byte[][]{{(byte) (first & 0x0f)}, payload};
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        for (int read = 0; read < length; ) {
            int count = input.read(bytes, read, length - read);
            if (count == -1) {
                throw new IOException("connection ended part-way through a frame (" + read + " of " + length + " bytes)");
            }
            read += count;
        }
        return bytes;
    }

    /**
     * Reads at most a small buffer's worth at a time with a pause before each read, so that the relay writing to this
     * end falls behind.
     */
    private static InputStream paced(InputStream input) {
        return new BufferedInputStream(new FilterInputStream(input) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
                return super.read(bytes, offset, Math.min(length, SMALL_BUFFER));
            }
        }, SMALL_BUFFER);
    }

    /**
     * Waits until the bytes waiting on this end stop growing: its receive buffer is full and the relay has stopped
     * reading the other side.
     */
    private static void awaitStalled(InputStream input) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        int before = -1;
        while (System.nanoTime() < deadline) {
            Thread.sleep(250);
            int now = input.available();
            if (now > 0 && now == before) {
                return;
            }
            before = now;
        }
        throw new AssertionError("the connection did not stall within 20 seconds");
    }

    private static String readHead(InputStream input) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            int next = input.read();
            if (next == -1) {
                throw new IOException("connection closed after: " + head.toString(StandardCharsets.US_ASCII));
            }
            head.write(next);
        }
        return head.toString(StandardCharsets.US_ASCII);
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

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record Received(int frames, Integer closeCode, int framesAfterClose, boolean readOnAfterLeaving) {
    }
}
