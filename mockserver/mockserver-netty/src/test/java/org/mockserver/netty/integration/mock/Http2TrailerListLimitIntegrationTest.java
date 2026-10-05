package org.mockserver.netty.integration.mock;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A header block that follows an HTTP/2 request's headers, its trailers, is limited by {@code maxHeaderSize} as the
 * headers are, on a connection made straight to MockServer and in a CONNECT or SOCKS tunnel. Trailers over the limit
 * reset their stream and nothing else: the request is not dispatched, a response already being sent stops, the
 * connection and its other streams carry on, and one WARN says why. Only a tunnel answers {@code 431} first, and only
 * while no response has started.
 */
@RunWith(Parameterized.class)
public class Http2TrailerListLimitIntegrationTest {

    // above the 8,192 bytes Netty limits a header list to when it is not told otherwise
    private static final int LIMIT = 32 * 1024;
    private static final int FIELD_OVERHEAD = 32;
    private static final String TRAILER = "x-trailer";
    private static final String TARGET_HOST = "localhost";
    private static final int TARGET_PORT = 443;
    private static final String TRAILERS_OVER_THE_LIMIT = "because the request's trailers are larger than maxHeaderSize";
    private static final int SSE_EVENTS = 30;
    private static final int SSE_INTERVAL_MILLIS = 100;

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static EventLoopGroup clientGroup;
    private static int leaksBefore;

    public enum Route {
        H2C(false), TLS(false), CONNECT(true), SOCKS5(true);

        private final boolean tunnel;

        Route(boolean tunnel) {
            this.tunnel = tunnel;
        }
    }

    @Parameterized.Parameters(name = "{0}")
    public static Object[] routes() {
        return Route.values();
    }

    @Parameterized.Parameter
    public Route route;

    @BeforeClass
    public static void startServer() {
        leaksBefore = NettyBufferLeaks.recorded();
        // the test JVM defaults to ERROR, which would drop the WARN entries this class asserts on
        mockServer = new MockServer(configuration().maxHeaderSize(LIMIT).logLevel("WARN"), 0);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        clientGroup = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopServer() throws Exception {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
        // the part of a request held when its stream was reset, and of a response not yet written, were released
        NettyBufferLeaks.assertNoneSince(leaksBefore);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
        mockServerClient.when(request().withPath("/upload")).respond(response().withBody("served"));
    }

    @Test
    public void shouldServeARequestWhoseTrailersAreExactlyMaxHeaderSize() throws Exception {
        try (Http2TestClient client = connect()) {
            Http2TestClient.Exchange exchange = client.send(pseudoHeaders(HttpMethod.POST, "/upload"), false)
                .data("request body", false)
                .trailers(trailersOfSize(LIMIT));

            assertThat(exchange.status(), is(200));
            assertThat(exchange.body(), is("served"));
        }
        assertThat(uploads(), contains("request body"));
        assertThat(warningsAndErrors(), is(empty()));
    }

    @Test
    public void shouldResetAStreamWhoseTrailersAreOverTheLimitBeforeAnyResponse() throws Exception {
        try (Http2TestClient client = connect()) {
            Http2TestClient.Exchange other = client.send(pseudoHeaders(HttpMethod.POST, "/upload"), false)
                .data("other ", false);

            Http2TestClient.Exchange refused = client.send(pseudoHeaders(HttpMethod.POST, "/upload"), false)
                .data("request body", false)
                .trailers(trailersOfSize(LIMIT + 1));

            assertAnswered431OnlyInATunnel(refused);
            assertThat("never dispatched", mockServerClient.retrieveRecordedRequests(request().withPath("/upload")), emptyArray());

            other.data("stream", true);
            assertThat("a stream still uploading carries on", other.status(), is(200));
            assertThat(other.body(), is("served"));
            assertThat("the connection carries on", client.send(pseudoHeaders(HttpMethod.GET, "/upload"), true).status(), is(200));
            assertThat(client.isOpen(), is(true));
        }
        assertThat(uploads(), contains("other stream", ""));
        assertThat(warningsAndErrors(), contains(containsString(TRAILERS_OVER_THE_LIMIT)));
    }

    @Test
    public void shouldResetAStreamWhoseTrailersAreOverTheLimitAfterAnInterimResponse() throws Exception {
        try (Http2TestClient client = connect()) {
            Http2TestClient.Exchange refused = client.send(pseudoHeaders(HttpMethod.POST, "/upload").add("expect", "100-continue"), false);
            assertThat(refused.interimStatus(), is(100));

            refused.data("request body", false).trailers(trailersOfSize(LIMIT + 1));

            assertAnswered431OnlyInATunnel(refused);
            assertThat("never dispatched", mockServerClient.retrieveRecordedRequests(request().withPath("/upload")), emptyArray());
            assertThat("the connection carries on", client.send(pseudoHeaders(HttpMethod.GET, "/upload"), true).status(), is(200));
        }
        assertThat(warningsAndErrors(), contains(containsString(TRAILERS_OVER_THE_LIMIT)));
    }

    /**
     * A response has started and cannot finish: its body is larger than the stream's window and the client takes
     * none of it. No well-behaved client sends a header block then, having ended its request, but one that does is
     * limited before anything else is checked.
     */
    @Test
    public void shouldResetAStreamSentAHeaderBlockOverTheLimitWhileItsResponseIsBeingWritten() throws Exception {
        String largeBody = "b".repeat(256 * 1024);
        mockServerClient.when(request().withPath("/large")).respond(response().withBody(largeBody));
        try (Http2TestClient client = connect()) {
            client.streamWindow(1024);
            Http2TestClient.Exchange started = client.sendReadingOnlyTheResponseHeaders(pseudoHeaders(HttpMethod.GET, "/large"), true);
            assertThat(started.status(), is(200));

            started.headersWhateverTheStreamState(trailersOfSize(LIMIT + 1));

            assertThat(started.resetErrorCode(), is(Http2Error.PROTOCOL_ERROR.code()));
            Http2TestClient.Exchange next = client.send(pseudoHeaders(HttpMethod.GET, "/upload"), true);
            assertThat("the connection carries on", next.status(), is(200));
            assertThat(next.body(), is("served"));
            assertThat(client.isOpen(), is(true));
        }
        assertThat(warningsAndErrors(), contains(containsString(TRAILERS_OVER_THE_LIMIT)));
    }

    /**
     * The events reach the client as MockServer writes them, through a tunnel as on a direct connection, so the
     * response has started on every route and the stream is only reset.
     */
    @Test
    public void shouldResetAStreamSentAHeaderBlockOverTheLimitWhileItsResponseIsStreamed() throws Exception {
        List<SseEvent> events = new ArrayList<>();
        for (int i = 0; i < SSE_EVENTS; i++) {
            events.add(SseEvent.sseEvent().withData("tick_" + i + ";").withDelay(TimeUnit.MILLISECONDS, i == 0 ? 0 : SSE_INTERVAL_MILLIS));
        }
        mockServerClient.upsert(new Expectation(request().withPath("/sse")).thenRespondWithSse(HttpSseResponse.sseResponse().withEvents(events)));
        String lastEvent = "tick_" + (SSE_EVENTS - 1) + ";";
        try (Http2TestClient client = connect()) {
            Http2TestClient.Exchange streamed = client.send(pseudoHeaders(HttpMethod.GET, "/sse"), true);
            Http2TestClient.Exchange other = client.send(pseudoHeaders(HttpMethod.GET, "/sse"), true);
            assertThat(streamed.status(), is(200));
            assertThat(streamed.receivedWithin("tick_1;", 15), is(true));
            assertThat(other.receivedWithin("tick_1;", 15), is(true));

            streamed.headersWhateverTheStreamState(trailersOfSize(LIMIT + 1));

            assertThat(streamed.resetErrorCode(), is(Http2Error.PROTOCOL_ERROR.code()));
            String streamedWhenReset = streamed.received();
            assertThat("cut short", streamedWhenReset.contains(lastEvent), is(false));
            // the rest of the other stream's events take long enough for MockServer to try to write to the reset one
            assertThat("the other stream carries on", other.body(), containsString(lastEvent));
            assertThat(other.status(), is(200));
            assertThat("nothing more on the reset stream", streamed.received(), is(streamedWhenReset));
            assertThat("the connection carries on", client.send(pseudoHeaders(HttpMethod.GET, "/upload"), true).status(), is(200));
            assertThat(client.isOpen(), is(true));
        }
        assertThat(warningsAndErrors(), contains(containsString(TRAILERS_OVER_THE_LIMIT)));
    }

    /**
     * Only an upload cut short by the trailers' refusal goes unreported. One the client gives up on is still logged,
     * as it is today: at ERROR, by the first handler after the aggregator. A tunnel holds the upload in the relay.
     */
    @Test
    public void shouldStillReportAnUploadTheClientResets() throws Exception {
        try (Http2TestClient client = connect()) {
            client.send(pseudoHeaders(HttpMethod.POST, "/upload"), false)
                .data("request body", false)
                .reset(Http2Error.CANCEL);

            // on the same connection, so MockServer has read the reset by the time this is answered
            assertThat(client.send(pseudoHeaders(HttpMethod.GET, "/upload"), true).status(), is(200));
        }
        assertThat("the upload was never dispatched", uploads(), contains(""));
        if (route.tunnel) {
            assertThat(warningsAndErrors(), is(empty()));
        } else {
            assertThat(warningsAndErrors(), contains(containsString("web socket server caught exception")));
        }
    }

    /**
     * A tunnel answers trailers refused before any response 431, which ends the stream the client has ended too, so
     * the reset that follows is for a stream that is closed. A direct connection only resets the stream.
     */
    private void assertAnswered431OnlyInATunnel(Http2TestClient.Exchange refused) throws Exception {
        if (route.tunnel) {
            assertThat(refused.status(), is(431));
            assertThat(refused.body(), is(""));
        } else {
            assertThat(refused.resetErrorCode(), is(Http2Error.PROTOCOL_ERROR.code()));
            assertThrows("no response on a direct connection", ExecutionException.class, refused::status);
        }
    }

    private Http2TestClient connect() throws Exception {
        int port = mockServer.getLocalPort();
        switch (route) {
            case H2C:
                return Http2TestClient.h2c(clientGroup, port);
            case TLS:
                return Http2TestClient.tls(clientGroup, port);
            case CONNECT:
                return Http2TestClient.throughConnect(clientGroup, port, TARGET_HOST, TARGET_PORT);
            default:
                return Http2TestClient.throughSocks5(clientGroup, port, TARGET_HOST, TARGET_PORT, true);
        }
    }

    private Http2Headers pseudoHeaders(HttpMethod method, String path) {
        return new DefaultHttp2Headers()
            .method(method.asciiName())
            .scheme(route == Route.H2C ? "http" : "https")
            .authority(route.tunnel ? TARGET_HOST + ":" + TARGET_PORT : "localhost:" + mockServer.getLocalPort())
            .path(path);
    }

    /**
     * Trailers whose header list is exactly {@code size} bytes as RFC 9113 counts it.
     */
    private static Http2Headers trailersOfSize(int size) {
        return new DefaultHttp2Headers().add(TRAILER, "a".repeat(size - TRAILER.length() - FIELD_OVERHEAD));
    }

    private static List<String> uploads() {
        return Arrays.stream(mockServerClient.retrieveRecordedRequests(request().withPath("/upload")))
            .map(recorded -> recorded.getBodyAsString() == null ? "" : recorded.getBodyAsString())
            .collect(Collectors.toList());
    }

    /**
     * What MockServer logged since the last reset, other than the requests it received and the responses it returned.
     */
    private static List<String> warningsAndErrors() {
        return Arrays.stream(mockServerClient.retrieveLogMessagesArray(null))
            .map(message -> message.substring(message.indexOf(" - ") + " - ".length()))
            .filter(message -> !message.startsWith("received request") && !message.startsWith("returning "))
            .collect(Collectors.toList());
    }
}
