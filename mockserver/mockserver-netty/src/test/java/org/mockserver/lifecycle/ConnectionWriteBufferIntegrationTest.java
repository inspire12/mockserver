package org.mockserver.lifecycle;

import io.netty.bootstrap.ServerBootstrapConfig;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.util.internal.PlatformDependent;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Per-connection outbound buffering on a real server: the write-buffer water mark must reach accepted
 * connections, and a slow reader of a large response must not pin a direct-memory copy of the whole body.
 */
public class ConnectionWriteBufferIntegrationTest {

    private static final long MIB = 1024L * 1024;

    @Test
    public void shouldSetTheWriteBufferWaterMarkOnAcceptedConnectionsNotTheListeningSocket() {
        MockServer mockServer = new MockServer(0);
        try {
            ServerBootstrapConfig config = mockServer.serverServerBootstrap.config();

            WriteBufferWaterMark waterMark = (WriteBufferWaterMark) config.childOptions().get(ChannelOption.WRITE_BUFFER_WATER_MARK);
            assertThat(waterMark, notNullValue());
            assertThat(waterMark.low(), is(8 * 1024));
            assertThat(waterMark.high(), is(32 * 1024));
            assertThat(config.options().containsKey(ChannelOption.WRITE_BUFFER_WATER_MARK), is(false));
        } finally {
            mockServer.stop();
        }
    }

    @Test
    public void shouldNotHoldADirectCopyOfALargeBodyForEachSlowReader() throws Exception {
        int bodySize = 6 * 1024 * 1024;
        int slowReaders = 8;
        byte[] body = new byte[bodySize];
        for (int i = 0; i < bodySize; i++) {
            body[i] = (byte) ((i * 31 + i / 251) % 251);
        }
        MockServer mockServer = new MockServer(0);
        List<Socket> sockets = new ArrayList<>();
        try {
            new MockServerClient("127.0.0.1", mockServer.getLocalPort()).when(request().withPath("/large")).respond(response().withBody(binary(body)));
            long before = settledUsedDirectMemory();

            for (int i = 0; i < slowReaders; i++) {
                Socket socket = new Socket();
                socket.setReceiveBufferSize(4 * 1024);
                socket.connect(new InetSocketAddress("127.0.0.1", mockServer.getLocalPort()), 5000);
                socket.setSoTimeout(30000);
                socket.getOutputStream().write("GET /large HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                sockets.add(socket);
            }
            long held = settledUsedDirectMemory() - before;

            // without pacing each reader pins a direct copy of the whole body: slowReaders x 6 MiB, less what the kernel holds
            assertThat(held, lessThan(16 * MIB));
            assertThat(Arrays.equals(readBody(sockets.get(0).getInputStream()), body), is(true));
        } finally {
            for (Socket socket : sockets) {
                socket.close();
            }
            mockServer.stop();
        }
    }

    private static long settledUsedDirectMemory() throws InterruptedException {
        long previous = -1;
        long current = PlatformDependent.usedDirectMemory();
        for (int i = 0; i < 25 && current != previous; i++) {
            Thread.sleep(200);
            previous = current;
            current = PlatformDependent.usedDirectMemory();
        }
        return current;
    }

    private static byte[] readBody(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int c = in.read();
            if (c < 0) {
                throw new IOException("connection closed in the response head");
            }
            head.append((char) c);
        }
        int length = -1;
        for (String line : head.toString().split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                length = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        byte[] received = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(received, read, length - read);
            if (n < 0) {
                throw new IOException("connection closed after " + read + " of " + length + " body bytes");
            }
            read += n;
        }
        return received;
    }
}
