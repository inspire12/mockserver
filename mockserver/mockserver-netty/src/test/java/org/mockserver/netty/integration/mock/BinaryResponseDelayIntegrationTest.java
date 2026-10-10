package org.mockserver.netty.integration.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.mock.Expectation;
import org.mockserver.socket.PortFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.BinaryRequestDefinition.binaryRequest;
import static org.mockserver.model.BinaryResponse.binaryResponse;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A binary expectation's delay, sent to MockServer over its REST API, holds back the reply on a real connection, and
 * the reply to a later message on that connection waits behind it.
 */
public class BinaryResponseDelayIntegrationTest {

    private static final long DELAY_MILLIS = 400;

    private ClientAndServer mockServer;

    @Before
    public void startMockServer() {
        mockServer = startClientAndServer(PortFactory.findFreePort());
        mockServer.upsert(new Expectation(binaryRequest(bytes("slow"))).thenRespondWithBinary(binaryResponse(bytes("SLOW")).withDelay(MILLISECONDS, DELAY_MILLIS)));
        mockServer.upsert(new Expectation(binaryRequest(bytes("fast"))).thenRespondWithBinary(binaryResponse(bytes("FAST"))));
    }

    @After
    public void stopMockServer() {
        stopQuietly(mockServer);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String read(InputStream in, int length) throws IOException {
        byte[] read = in.readNBytes(length);
        return new String(read, StandardCharsets.UTF_8);
    }

    @Test
    public void shouldDelayTheReplyAndHoldTheNextReplyBehindIt() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", mockServer.getPort())) {
            socket.setSoTimeout(10_000);
            socket.setTcpNoDelay(true);

            long sent = System.nanoTime();
            socket.getOutputStream().write(bytes("slow"));
            socket.getOutputStream().flush();
            // a message of its own, not joined to the first in one read
            MILLISECONDS.sleep(100);
            socket.getOutputStream().write(bytes("fast"));
            socket.getOutputStream().flush();

            InputStream in = socket.getInputStream();
            assertThat(read(in, 4), is("SLOW"));
            assertThat("written no sooner than its delay", NANOSECONDS.toMillis(System.nanoTime() - sent), greaterThanOrEqualTo(DELAY_MILLIS));
            assertThat("the later reply comes after it", read(in, 4), is("FAST"));
        }
    }

    @Test
    public void shouldAnswerAtOnceWithoutADelay() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", mockServer.getPort())) {
            socket.setSoTimeout(10_000);

            socket.getOutputStream().write(bytes("fast"));
            socket.getOutputStream().flush();

            assertThat(read(socket.getInputStream(), 4), is("FAST"));
        }
    }
}
