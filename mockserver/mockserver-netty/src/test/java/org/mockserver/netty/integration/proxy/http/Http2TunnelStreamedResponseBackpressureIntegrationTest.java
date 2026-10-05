package org.mockserver.netty.integration.proxy.http;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Headers;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.client.MockServerClient;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;
import org.mockserver.netty.integration.NettyBufferLeaks;
import org.mockserver.test.Http2FlowControlBodies;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.test.Http2FlowControlBodies.Size.OVER_WINDOW;

/**
 * A streamed response relayed through an HTTP/2 tunnel is paced by the client that reads it. One the client is not
 * taking holds back the upstream that produces it, and only that: the tunnel's other streams carry on, and nothing
 * accumulates in between. A client that takes none of it for {@code responseWriteStallTimeoutMillis} has the stream
 * reset and the upstream closed; one that takes it slowly, or is sent nothing for a while, is left alone, as is a
 * tunnel whose stream is silent for longer than {@code inboundConnectionIdleTimeoutMillis}.
 */
@RunWith(Parameterized.class)
public class Http2TunnelStreamedResponseBackpressureIntegrationTest {

    private static final String TARGET_HOST = "localhost";
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    // far more than the socket buffers between the upstream and the client can hold, on any platform
    private static final long FLOOD_BYTES = 32L * MAX_BODY_BYTES;
    private static final int CLIENT_STREAM_WINDOW = 16 * 1024;
    private static final long STALL_MILLIS = 2000;
    private static final long IDLE_MILLIS = 1500;
    private static final int GAP_MILLIS = 3500;
    private static final AtomicInteger REQUEST_IDS = new AtomicInteger();

    private static MockServer patientServer;
    private static MockServerClient patientClient;
    private static MockServer timingServer;
    private static MockServerClient timingClient;
    private static StreamingUpstream upstream;
    private static EventLoopGroup clientGroup;
    private static int leaksBefore;

    public enum Route {
        CONNECT_TLS(true), SOCKS5_H2C(false);

        private final boolean tls;

        Route(boolean tls) {
            this.tls = tls;
        }
    }

    @Parameterized.Parameters(name = "{0}")
    public static Object[] routes() {
        return Route.values();
    }

    @Parameterized.Parameter
    public Route route;

    @BeforeClass
    public static void startServers() throws Exception {
        leaksBefore = NettyBufferLeaks.recorded();
        upstream = new StreamingUpstream();
        clientGroup = new NioEventLoopGroup(2);
        // a response held whole would be refused at maxRequestBodySize, far short of the flood
        patientServer = new MockServer(configuration().logLevel("WARN").maxRequestBodySize(MAX_BODY_BYTES).streamIdleTimeoutSeconds(120), 0);
        patientClient = new MockServerClient("localhost", patientServer.getLocalPort());
        timingServer = new MockServer(configuration().logLevel("WARN").maxRequestBodySize(MAX_BODY_BYTES).streamIdleTimeoutSeconds(120)
            .responseWriteStallTimeoutMillis(STALL_MILLIS)
            .inboundConnectionIdleTimeoutMillis(IDLE_MILLIS), 0);
        timingClient = new MockServerClient("localhost", timingServer.getLocalPort());
    }

    @AfterClass
    public static void stopServers() throws Exception {
        stopQuietly(patientClient);
        stopQuietly(patientServer);
        stopQuietly(timingClient);
        stopQuietly(timingServer);
        if (upstream != null) {
            upstream.close();
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
        // what was waiting for a stream that was reset, for its window or for its socket, was released
        NettyBufferLeaks.assertNoneSince(leaksBefore);
    }

    @Before
    public void resetServers() {
        for (MockServerClient client : Arrays.asList(patientClient, timingClient)) {
            client.reset();
            client.when(request().withPath("/upstream/.*")).forward(forward().withHost("127.0.0.1").withPort(upstream.port()));
            client.when(request().withPath("/plain")).respond(response().withBody("served"));
        }
    }

    @Test
    public void shouldHoldBackOnlyTheUpstreamOfAStreamItsClientIsNotTaking() throws Exception {
        String whole = Http2FlowControlBodies.body(OVER_WINDOW, "whole-beside-a-stalled-stream-" + route);
        patientClient.when(request().withPath("/whole")).respond(response().withBody(whole));
        patientClient.upsert(new Expectation(request().withPath("/sse")).thenRespondWithSse(HttpSseResponse.sseResponse().withEvents(
            SseEvent.sseEvent().withData("one"), SseEvent.sseEvent().withData("two").withDelay(TimeUnit.MILLISECONDS, 200), SseEvent.sseEvent().withData("three").withDelay(TimeUnit.MILLISECONDS, 200))));
        String id = id();
        try (Http2TestClient tunnel = tunnel(patientServer)) {
            tunnel.streamWindow(CLIENT_STREAM_WINDOW);
            Http2TestClient.Exchange stalled = tunnel.sendReadingOnlyTheResponseHeaders(flood(id, FLOOD_BYTES), true);
            assertThat(stalled.status(), is(200));

            long held = writtenOnceStandingStill(id);
            assertThat("the upstream is held back, far short of the whole response", held, lessThan(FLOOD_BYTES));

            assertThat("a streamed response on the same tunnel", tunnel.send(headers(HttpMethod.GET, "/sse"), true).body(), is("data: one\n\ndata: two\n\ndata: three\n\n"));
            assertThat("a whole response on the same tunnel", tunnel.send(headers(HttpMethod.GET, "/whole"), true).body(), is(whole));
            assertThat("still held", upstream.written(id), lessThan(FLOOD_BYTES));
            assertThat(stalled.isReset(), is(false));
            assertThat(upstream.isOpen(id), is(true));

            stalled.readTheRest();

            assertCompleteWithin(stalled, 120);
            assertThat((long) stalled.receivedBytes(), is(FLOOD_BYTES));
            assertThat("every byte, in the order sent", MessageDigest.getInstance("SHA-256").digest(stalled.receivedByteArray()), is(StreamingUpstream.floodDigest(FLOOD_BYTES)));
        }
        assertThat(warningsAndErrors(patientClient), is(empty()));
    }

    @Test
    public void shouldResetAStreamedResponseItsClientStopsTakingAndCloseItsUpstream() throws Exception {
        String id = id();
        try (Http2TestClient tunnel = tunnel(timingServer)) {
            tunnel.streamWindow(CLIENT_STREAM_WINDOW);
            Http2TestClient.Exchange stalled = tunnel.sendReadingOnlyTheResponseHeaders(flood(id, FLOOD_BYTES), true);
            assertThat(stalled.status(), is(200));

            assertThat(stalled.resetErrorCode(), is(Http2Error.CANCEL.code()));

            assertThat("MockServer stops taking the upstream's response", upstream.closedWithin(id, 15), is(true));
            assertThat("the tunnel carries on", tunnel.send(headers(HttpMethod.GET, "/plain"), true).body(), is("served"));
            assertThat(tunnel.isOpen(), is(true));
        }
        assertThat(warningsAndErrors(timingClient), contains(containsString("because its client took none of the response waiting for it")));
    }

    @Test
    public void shouldNotResetAStreamedResponseItsClientTakesSlowly() throws Exception {
        long bytes = 4L * MAX_BODY_BYTES;
        String id = id();
        try (Http2TestClient tunnel = tunnel(timingServer)) {
            tunnel.streamWindow(CLIENT_STREAM_WINDOW);
            Http2TestClient.Exchange slow = tunnel.sendReadingOnlyTheResponseHeaders(flood(id, bytes), true);
            assertThat(slow.status(), is(200));

            // one frame, at most the stream's window, ten times a second, for three timeout periods
            long slowUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3 * STALL_MILLIS);
            while (System.nanoTime() < slowUntil) {
                slow.readOneFrame();
                Thread.sleep(100);
            }
            assertThat("not reset while it was being taken", slow.isReset(), is(false));
            assertThat("and still far from finished", (long) slow.receivedBytes(), lessThan(bytes));

            slow.readTheRest();

            assertCompleteWithin(slow, 60);
            assertThat(MessageDigest.getInstance("SHA-256").digest(slow.receivedByteArray()), is(StreamingUpstream.floodDigest(bytes)));
        }
        assertThat(warningsAndErrors(timingClient), is(empty()));
    }

    /**
     * Nothing is written for longer than both timeouts, with nothing waiting to be written: the stream is neither
     * stalled nor is its tunnel idle.
     */
    @Test
    public void shouldKeepATunnelAndItsStreamThroughASilenceLongerThanTheIdleAndStallTimeouts() throws Exception {
        timingClient.upsert(new Expectation(request().withPath("/sse/gap")).thenRespondWithSse(HttpSseResponse.sseResponse().withEvents(
            SseEvent.sseEvent().withData("before"), SseEvent.sseEvent().withData("after").withDelay(TimeUnit.MILLISECONDS, GAP_MILLIS))));
        try (Http2TestClient tunnel = tunnel(timingServer)) {
            Http2TestClient.Exchange quiet = tunnel.send(headers(HttpMethod.GET, "/sse/gap"), true);

            assertThat(quiet.receivedWithin("data: before\n\n", 15), is(true));
            assertThat(quiet.body(), is("data: before\n\ndata: after\n\n"));
            assertThat("the first event arrived before the silence, not after it", quiet.millisFromFirstDataToEnd(), greaterThanOrEqualTo((long) GAP_MILLIS - 500));
            assertThat(tunnel.isOpen(), is(true));
            assertThat("the tunnel carries on", tunnel.send(headers(HttpMethod.GET, "/plain"), true).body(), is("served"));
        }
        assertThat(warningsAndErrors(timingClient), is(empty()));
    }

    /**
     * A mocked response with a 1xx status is the whole response. Through a tunnel it ends the client's stream, as it
     * did before streamed responses were relayed as they are written, so the tunnel is then idle and closed as idle.
     */
    @Test
    public void shouldEndTheStreamOfAMockedInformationalResponseAndCloseItsTunnelAsIdle() throws Exception {
        timingClient.when(request().withPath("/processing")).respond(response().withStatusCode(102));
        timingClient.when(request().withPath("/hints")).respond(response().withStatusCode(103).withHeader("link", "</style.css>; rel=preload"));
        for (String path : Arrays.asList("/processing", "/hints")) {
            try (Http2TestClient tunnel = tunnel(timingServer)) {
                Http2TestClient.Exchange informational = tunnel.send(headers(HttpMethod.GET, path), true);

                assertThat(path, informational.interimStatus(), is(path.equals("/hints") ? 103 : 102));
                assertCompleteWithin(informational, 10);
                assertThat(path + ": the tunnel is closed as idle", tunnel.closedWithin(15), is(true));
            }
        }
    }

    /**
     * A client that closes its connection, and with it its TLS session, in the middle of a response streamed as fast
     * as it is taken has only left. Writes already on their way to it fail, and none of that is logged as an error.
     */
    @Test
    public void shouldLogNothingForAClientThatLeavesInTheMiddleOfAStreamedResponse() throws Exception {
        for (int i = 0; i < 8; i++) {
            String id = id();
            try (Http2TestClient tunnel = tunnel(patientServer)) {
                Http2TestClient.Exchange flood = tunnel.send(flood(id, FLOOD_BYTES), true);
                assertThat(flood.status(), is(200));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (flood.receivedBytes() < 256 * 1024) {
                    assertThat("the response is arriving", System.nanoTime() < deadline, is(true));
                    Thread.sleep(5);
                }
            }
            assertThat("the upstream is closed", upstream.closedWithin(id, 15), is(true));
        }
        // on the same server, so everything the last close set off has been logged by the time this is answered
        try (Http2TestClient next = tunnel(patientServer)) {
            assertThat(next.send(headers(HttpMethod.GET, "/plain"), true).body(), is("served"));
        }
        assertThat("a client that leaves is not an error", warningsAndErrors(patientClient), is(empty()));
    }

    /**
     * The client leaves with a stream it was not taking: MockServer is waiting for window on the loopback, and the
     * relay holds what it was sent. Both legs and the upstream close at once, well inside the 5 s the relay gives a
     * loopback whose other end has not closed.
     */
    @Test
    public void shouldCloseBothLegsAndTheUpstreamWhenAClientLeavesAStreamItWasNotTaking() throws Exception {
        MockServer server = new MockServer(configuration().logLevel("WARN").startupWarmup(false).proxySetup(false).proxySetupLogging(false).streamIdleTimeoutSeconds(120), 0);
        try {
            // on a connection of its own, closed with its response: the Java client keeps its connections for a while
            controlPlane(server, "/mockserver/expectation", "{\"httpRequest\":{\"path\":\"/upstream/.*\"},\"httpForward\":{\"host\":\"127.0.0.1\",\"port\":" + upstream.port() + ",\"scheme\":\"HTTP\"}}");
            String id = id();
            Http2TestClient tunnel = tunnel(server);
            try {
                tunnel.streamWindow(CLIENT_STREAM_WINDOW);
                Http2TestClient.Exchange stalled = tunnel.sendReadingOnlyTheResponseHeaders(flood(id, FLOOD_BYTES), true);
                assertThat(stalled.status(), is(200));
                assertThat("held back", writtenOnceStandingStill(id), lessThan(FLOOD_BYTES));
                assertThat("the client's leg and the loopback", openConnectionsBecome(server, 2), is(true));
            } finally {
                tunnel.close();
            }
            long leftNanos = System.nanoTime();

            assertThat("the upstream is closed", upstream.closedWithin(id, 15), is(true));
            assertThat("both legs are closed", openConnectionsBecome(server, 0), is(true));
            assertThat("at once, not when the wait for the loopback's other end runs out", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - leftNanos), lessThan(4000L));
            // this server's log was never reset, so it still has the notices it logs when it first forwards or intercepts TLS
            assertThat("a client that leaves is not an error", warningsAndErrors(new MockServerClient("localhost", server.getLocalPort())), everyItem(anyOf(startsWith("Forward proxy is configured to trust ALL"), startsWith("MockServer proxy setup"))));
        } finally {
            stopQuietly(server);
        }
    }

    private static boolean openConnectionsBecome(MockServer server, int connections) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (server.getInboundConnectionCount() != connections) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    private static void controlPlane(MockServer server, String path, String json) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.getLocalPort())) {
            socket.setSoTimeout(10_000);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(("PUT " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
            assertThat(new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8), startsWith("HTTP/1.1 201"));
        }
    }

    /**
     * @return the bytes the upstream has had taken once that count has not moved for a second and a half
     */
    private static long writtenOnceStandingStill(String id) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        long last = -1;
        long lastMovedNanos = System.nanoTime();
        while (true) {
            long written = upstream.written(id);
            if (written != last) {
                last = written;
                lastMovedNanos = System.nanoTime();
            } else if (written > 0 && System.nanoTime() - lastMovedNanos > TimeUnit.MILLISECONDS.toNanos(1500)) {
                return written;
            }
            assertThat("the upstream stops being read within 30s (written so far: " + written + ")", System.nanoTime() < deadline, is(true));
            Thread.sleep(50);
        }
    }

    private static void assertCompleteWithin(Http2TestClient.Exchange exchange, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!exchange.isComplete()) {
            assertThat("not reset", exchange.isReset(), is(false));
            assertThat("complete within " + seconds + "s (received so far: " + exchange.receivedBytes() + ")", System.nanoTime() < deadline, is(true));
            Thread.sleep(20);
        }
    }

    private Http2Headers flood(String id, long bytes) {
        return headers(HttpMethod.GET, "/upstream/flood?bytes=" + bytes + "&id=" + id).add("accept", "text/event-stream");
    }

    private static String id() {
        return "backpressure-" + REQUEST_IDS.incrementAndGet();
    }

    private Http2TestClient tunnel(MockServer server) throws Exception {
        return route == Route.CONNECT_TLS
            ? Http2TestClient.throughConnect(clientGroup, server.getLocalPort(), TARGET_HOST, 443, true)
            : Http2TestClient.throughSocks5(clientGroup, server.getLocalPort(), TARGET_HOST, 80, false);
    }

    private Http2Headers headers(HttpMethod method, String path) {
        return new DefaultHttp2Headers()
            .method(method.asciiName())
            .scheme(route.tls ? "https" : "http")
            .authority(TARGET_HOST + ":" + (route.tls ? 443 : 80))
            .path(path);
    }

    /**
     * What MockServer logged since the last reset, other than the requests it received and the responses it returned.
     */
    private static List<String> warningsAndErrors(MockServerClient client) {
        return Arrays.stream(client.retrieveLogMessagesArray(null))
            .map(message -> message.substring(message.indexOf(" - ") + " - ".length()))
            .filter(message -> !message.startsWith("received request") && !message.startsWith("returning ") && !message.startsWith("forwarded request"))
            .collect(Collectors.toList());
    }
}
