package org.mockserver.netty.integration.proxy.http;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A binary message forwarded on an upstream connection of its own, to an upstream that accepts the connection and
 * closes it without answering, is logged once: with the server at WARN, the message's own record (received requests
 * are recorded at any level) is followed by one entry, which names the failure and shows at most the first
 * maxLoggedBodyBytes bytes of the message.
 */
public class BinaryForwardFailedOnceIntegrationTest {

    private static final int LENGTH = 1_000;
    private static final int LOGGED = 16;

    private static ServerSocket upstream;
    private static Thread closesEachConnection;
    private static MockServer mockServer;
    private static MockServerClient client;

    @BeforeClass
    public static void startServers() throws Exception {
        upstream = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        closesEachConnection = new Thread(() -> {
            while (!upstream.isClosed()) {
                try {
                    upstream.accept().close();
                } catch (IOException closed) {
                    // the test has finished
                }
            }
        }, BinaryForwardFailedOnceIntegrationTest.class.getSimpleName() + "-upstream");
        closesEachConnection.setDaemon(true);
        closesEachConnection.start();
        mockServer = new MockServer(
            configuration()
                // the test JVM defaults to ERROR, which would drop the WARN entry this class asserts on
                .logLevel("WARN")
                .forwardBinaryRequestsUseSingleConnection(false)
                .maxLoggedBodyBytes(LOGGED),
            upstream.getLocalPort(), "127.0.0.1", 0
        );
        client = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServers() throws Exception {
        stopQuietly(client);
        stopQuietly(mockServer);
        if (upstream != null) {
            upstream.close();
        }
    }

    @Test
    public void shouldLogAForwardThatTheUpstreamClosedWithoutAnAnswerOnce() throws Exception {
        byte[] message = new byte[LENGTH];
        Arrays.fill(message, (byte) 0xA5);

        try (Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort())) {
            socket.setSoTimeout(15_000);
            socket.getOutputStream().write(message);
            socket.getOutputStream().flush();
            InputStream input = socket.getInputStream();
            try {
                assertThat("closed with no answer", input.read(), is(-1));
            } catch (java.net.SocketException reset) {
                // closed all the same
            }
        }

        List<String> entries = entriesAfterTheMessagesRecord();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (entries.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
            entries = entriesAfterTheMessagesRecord();
        }
        // a second entry, had there been one, is logged before the connection is closed
        assertThat(entries.toString(), entries.size(), is(1));
        assertThat(entries.get(0), containsString("closing connection"));
        assertThat(entries.get(0), containsString("a5".repeat(LOGGED) + "...(" + LENGTH + " bytes, only the first " + LOGGED + " logged, maxLoggedBodyBytes)"));
        assertThat(entries.get(0), not(containsString("a5".repeat(LOGGED + 1))));
    }

    private static List<String> entriesAfterTheMessagesRecord() {
        List<String> logged = Arrays.asList(client.retrieveLogMessagesArray(null));
        int lastReceived = -1;
        for (int i = 0; i < logged.size(); i++) {
            if (logged.get(i).contains("received binary request")) {
                lastReceived = i;
            }
        }
        assertThat(logged.toString(), lastReceived, not(is(-1)));
        return logged.subList(lastReceived + 1, logged.size());
    }
}
