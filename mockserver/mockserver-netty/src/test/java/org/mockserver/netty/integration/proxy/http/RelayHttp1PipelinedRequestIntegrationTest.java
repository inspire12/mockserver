package org.mockserver.netty.integration.proxy.http;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.concurrent.EventExecutor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.netty.MockServer;
import org.mockserver.serialization.ExpectationSerializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A request pipelined on an HTTP/1.1 CONNECT tunnel, behind a response the client never reads, by a client that then
 * half-closes its connection, is received whole: the client's leg closes on the end of its input, which fails the
 * response still being written to it, and the tunnel's loopback must not be closed under the request.
 * <p>
 * MockServer's end of the loopback is held (its event loop is blocked) while the client sends the request and
 * half-closes, so the request is still being written to the loopback when the client's leg closes.
 */
public class RelayHttp1PipelinedRequestIntegrationTest {

    private static final int RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int UPLOAD_BYTES = 16 * 1024 * 1024;

    private WorkerGroupMockServer mockServer;
    private int port;

    private static final class WorkerGroupMockServer extends MockServer {
        WorkerGroupMockServer(Configuration configuration) {
            super(configuration, 0);
        }

        EventLoopGroup workers() {
            return getEventLoopGroup();
        }
    }

    @Before
    public void startServer() {
        mockServer = new WorkerGroupMockServer(configuration()
            .logLevel("WARN")
            .startupWarmup(false)
            .proxySetup(false)
            .nioEventLoopThreadCount(2)
            .maxRequestBodySize(2 * UPLOAD_BYTES));
        port = mockServer.getLocalPort();
        char[] large = new char[RESPONSE_BYTES];
        Arrays.fill(large, 'x');
        controlPlane("/mockserver/expectation", new ExpectationSerializer(new MockServerLogger()).serialize(
            new Expectation(request().withPath("/large")).thenRespond(response().withBody(new String(large))),
            new Expectation(request().withPath("/upload")).thenRespond(response().withBody("uploaded"))
        ));
    }

    @After
    public void stopServer() {
        stopQuietly(mockServer);
    }

    @Test
    public void shouldReceiveARequestStillBeingWrittenToTheLoopbackWhenAClientThatHalfClosesHasGone() throws Exception {
        try (Socket client = openTunnel()) {
            sendBehindAStalledResponseThenHalfClose(client);
        }
        await("every connection closed", () -> mockServer.getInboundConnectionCount() == 0);
    }

    private Socket openTunnel() throws IOException {
        Socket client = new Socket();
        // set before connecting so that the kernel does not grow it: the large response then stalls
        client.setReceiveBufferSize(4096);
        client.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
        client.setSoTimeout(10_000);
        client.getOutputStream().write(("CONNECT 127.0.0.1:" + port + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
        assertThat(readHead(client.getInputStream()), startsWith("HTTP/1.1 200"));
        return client;
    }

    private void sendBehindAStalledResponseThenHalfClose(Socket client) throws Exception {
        OutputStream output = client.getOutputStream();
        output.write("GET /large HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        output.flush();
        await("the first request was received", () -> received("/large"));
        awaitStalled(client.getInputStream());

        Runnable release = hold(eventLoopOfMockServersEndOfTheLoopback(client));
        try {
            output.write(("POST /upload HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: " + UPLOAD_BYTES + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            byte[] body = new byte[UPLOAD_BYTES];
            Arrays.fill(body, (byte) 'u');
            output.write(body);
            output.flush();
            client.shutdownOutput();
            // the client's leg closes on the end of its input, while the request waits for MockServer's end
            drainToTheEnd(client.getInputStream());
        } finally {
            release.run();
        }
        // the client keeps its socket open, half-closed
        await("the request sent before the half-close was received", () -> received("/upload"));
    }

    /**
     * The tunnel's client leg is the accepted connection from the client's port; the loopback's relay end shares its
     * event loop, and MockServer's end of the loopback is the accepted connection from the relay end's port.
     */
    private EventExecutor eventLoopOfMockServersEndOfTheLoopback(Socket client) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<EventExecutor> loops = new ArrayList<>();
            List<List<Channel>> channelsOfEachLoop = new ArrayList<>();
            for (EventExecutor loop : mockServer.workers()) {
                loops.add(loop);
                channelsOfEachLoop.add(loop.submit(() -> {
                    List<Channel> channels = new ArrayList<>();
                    ((SingleThreadEventLoop) loop).registeredChannelsIterator().forEachRemaining(channels::add);
                    return channels;
                }).get(10, TimeUnit.SECONDS));
            }
            EventExecutor clientLegLoop = null;
            Integer relayEndPort = null;
            for (int i = 0; i < loops.size(); i++) {
                for (Channel channel : channelsOfEachLoop.get(i)) {
                    if (portOf(channel.remoteAddress()) == client.getLocalPort()) {
                        clientLegLoop = loops.get(i);
                    }
                }
            }
            for (int i = 0; i < loops.size() && clientLegLoop != null; i++) {
                for (Channel channel : channelsOfEachLoop.get(i)) {
                    if (loops.get(i) == clientLegLoop && portOf(channel.remoteAddress()) == port && portOf(channel.localAddress()) != port) {
                        relayEndPort = portOf(channel.localAddress());
                    }
                }
            }
            for (int i = 0; i < loops.size() && relayEndPort != null; i++) {
                for (Channel channel : channelsOfEachLoop.get(i)) {
                    if (portOf(channel.remoteAddress()) == relayEndPort) {
                        assertThat("MockServer's end of the loopback runs on an event loop of its own", loops.get(i), not(sameInstance(clientLegLoop)));
                        return loops.get(i);
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the tunnel's connections were not found within 10 seconds");
    }

    private static int portOf(SocketAddress address) {
        return address instanceof InetSocketAddress ? ((InetSocketAddress) address).getPort() : -1;
    }

    private static Runnable hold(EventExecutor loop) throws InterruptedException {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        loop.execute(() -> {
            held.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat("the event loop was held within 10 seconds", held.await(10, TimeUnit.SECONDS), is(true));
        return release::countDown;
    }

    /**
     * Waits until the bytes the client has been sent stop growing: its receive buffer is full and MockServer has the
     * rest of the response still to write.
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
        throw new AssertionError("the response did not stall within 10 seconds");
    }

    /**
     * Reads, and drops, what the client was sent until its connection's input ends.
     */
    private static void drainToTheEnd(InputStream input) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (input.read(buffer) != -1) {
            assertThat("the client's leg closed within 10 seconds", System.nanoTime() < deadline, is(true));
        }
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

    /**
     * Verified rather than retrieved, so that a large body is not serialised back.
     */
    private boolean received(String path) {
        return controlPlane("/mockserver/verify", "{\"httpRequest\":{\"path\":\"" + path + "\"},\"times\":{\"atLeast\":1,\"atMost\":1}}") != null;
    }

    /**
     * @return the response body for a 2xx, or null for a 406 (a verification that failed)
     */
    private String controlPlane(String path, String json) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(30_000);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(("PUT " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = socket.getInputStream().read(buffer)) != -1; ) {
                response.write(buffer, 0, count);
            }
            String text = response.toString(StandardCharsets.UTF_8.name());
            if (text.startsWith("HTTP/1.1 406")) {
                return null;
            }
            assertThat(text, startsWith("HTTP/1.1 20"));
            return text.substring(text.indexOf("\r\n\r\n") + 4);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void await(String reason, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(reason, condition.getAsBoolean(), is(true));
    }
}
