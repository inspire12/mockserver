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
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.ConnectionOptions;
import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.GrpcStreamResponse;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;
import org.mockserver.netty.integration.NettyBufferLeaks;
import org.mockserver.test.Http2FlowControlBodies;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.test.Http2FlowControlBodies.Size.OVER_WINDOW;

/**
 * A client that speaks HTTP/2 through MockServer as a CONNECT or SOCKS proxy is sent a streamed response as MockServer
 * writes it: the headers, then each event while the stream is still open, in order and byte for byte what a direct
 * connection is sent. Every other response is relayed as before. Each route is compared with a connection made
 * straight to MockServer in the same test.
 */
@RunWith(Parameterized.class)
public class Http2TunnelStreamedResponseIntegrationTest {

    private static final String TARGET_HOST = "localhost";
    private static final int EVENTS = 16;
    private static final int EVENT_GAP_MILLIS = 200;
    private static final long STREAM_MILLIS = (EVENTS - 1) * EVENT_GAP_MILLIS;
    private static final String GRPC_SERVICE = "com.example.grpc.GreetingService";
    private static final String GRPC_DESCRIPTOR = "../mockserver-core/src/test/resources/grpc/greeting.dsc";
    private static final AtomicInteger REQUEST_IDS = new AtomicInteger();

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static StreamingUpstream upstream;
    private static EventLoopGroup clientGroup;
    private static int leaksBefore;

    public enum Route {
        CONNECT_TLS(true), CONNECT_H2C(false), SOCKS5_TLS(true), SOCKS5_H2C(false);

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

    private Http2TestClient directClient;

    @BeforeClass
    public static void startServers() throws Exception {
        leaksBefore = NettyBufferLeaks.recorded();
        // the test JVM defaults to ERROR, which would hide a WARN logged for a client that only leaves
        mockServer = new MockServer(configuration().logLevel("WARN"), 0);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        upstream = new StreamingUpstream();
        clientGroup = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopServers() throws Exception {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (upstream != null) {
            upstream.close();
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
        // parts dropped for a stream reset, a tunnel closed and an upstream lost mid-response were all released
        NettyBufferLeaks.assertNoneSince(leaksBefore);
    }

    @Before
    public void resetServer() throws Exception {
        mockServerClient.reset();
        mockServerClient.uploadGrpcDescriptor(Files.readAllBytes(Paths.get(GRPC_DESCRIPTOR)));
        mockServerClient.when(request().withPath("/upstream/.*")).forward(forward().withHost("127.0.0.1").withPort(upstream.port()));
        mockServerClient.when(request().withPath("/plain")).respond(response().withBody("served"));
        mockServerClient.upsert(new Expectation(request().withPath("/sse")).thenRespondWithSse(HttpSseResponse.sseResponse().withEvents(events("tick", EVENTS, EVENT_GAP_MILLIS))));
    }

    @Test
    public void shouldRelayServerSentEventsAsTheyAreSent() throws Exception {
        try (Http2TestClient direct = direct(); Http2TestClient tunnel = tunnel()) {
            Http2TestClient.Exchange expected = direct.send(headers(direct, HttpMethod.GET, "/sse"), true);
            Http2TestClient.Exchange streamed = tunnel.send(headers(tunnel, HttpMethod.GET, "/sse"), true);

            assertStreamedAsOnADirectConnection(streamed, expected);
            assertThat(streamed.body(), containsString("tick_" + (EVENTS - 1)));
            assertThat(streamed.header("content-type"), is(expected.header("content-type")));
            assertThat("a stream's length is not known", streamed.header("content-length"), is(nullValue()));
        }
        assertThat(warningsAndErrors(), is(empty()));
    }

    @Test
    public void shouldRelayAForwardedTokenStreamAsTheUpstreamSendsIt() throws Exception {
        try (Http2TestClient direct = direct(); Http2TestClient tunnel = tunnel()) {
            Http2TestClient.Exchange expected = direct.send(headers(direct, HttpMethod.POST, tokens(EVENTS, EVENT_GAP_MILLIS)).add("content-type", "application/json"), false)
                .data("{\"stream\": true}", true);
            Http2TestClient.Exchange streamed = tunnel.send(headers(tunnel, HttpMethod.POST, tokens(EVENTS, EVENT_GAP_MILLIS)).add("content-type", "application/json"), false)
                .data("{\"stream\": true}", true);

            assertStreamedAsOnADirectConnection(streamed, expected);
            assertThat("every token, in the order sent", streamed.body(), is(StreamingUpstream.tokens(EVENTS)));
        }
        assertThat(warningsAndErrors(), is(empty()));
    }

    @Test
    public void shouldRelayAStreamedResponseThatFollowsAnInterimResponse() throws Exception {
        try (Http2TestClient tunnel = tunnel()) {
            Http2TestClient.Exchange streamed = tunnel.send(headers(tunnel, HttpMethod.POST, "/sse").add("expect", "100-continue"), false);
            assertThat(streamed.interimStatus(), is(100));

            streamed.data("upload", true);

            assertThat(streamed.status(), is(200));
            assertThat(streamed.millisFromFirstDataToEnd(), greaterThanOrEqualTo(STREAM_MILLIS / 2));
            assertThat(streamed.body(), containsString("tick_" + (EVENTS - 1)));
        }
    }

    @Test
    public void shouldRelayAChunkedResponseOfUndeclaredLengthWhole() throws Exception {
        String body = Http2FlowControlBodies.body(OVER_WINDOW, "chunked-through-" + route);
        mockServerClient.when(request().withPath("/chunked")).respond(response().withBody(body).withConnectionOptions(ConnectionOptions.connectionOptions().withChunkSize(4096)));
        try (Http2TestClient direct = direct(); Http2TestClient tunnel = tunnel()) {
            Http2TestClient.Exchange expected = direct.send(headers(direct, HttpMethod.GET, "/chunked"), true);
            Http2TestClient.Exchange chunked = tunnel.send(headers(tunnel, HttpMethod.GET, "/chunked"), true);

            assertThat(chunked.status(), is(200));
            assertThat(chunked.body(), is(body));
            assertThat(expected.body(), is(body));
            assertThat("no length declared, as on a direct connection", chunked.header("content-length"), is(expected.header("content-length")));
        }
    }

    @Test
    public void shouldRelayAResponseOfDeclaredLengthAsBefore() throws Exception {
        String body = Http2FlowControlBodies.body(OVER_WINDOW, "declared-through-" + route);
        mockServerClient.when(request().withPath("/declared")).respond(response().withBody(body).withHeader("x-kind", "declared"));
        try (Http2TestClient direct = direct(); Http2TestClient tunnel = tunnel()) {
            Http2TestClient.Exchange expected = direct.send(headers(direct, HttpMethod.GET, "/declared"), true);
            Http2TestClient.Exchange declared = tunnel.send(headers(tunnel, HttpMethod.GET, "/declared"), true);

            assertThat(declared.status(), is(200));
            assertThat(declared.body(), is(body));
            assertThat(declared.header("x-kind"), is("declared"));
            assertThat(declared.header("content-length"), is(String.valueOf(body.getBytes(StandardCharsets.UTF_8).length)));
            assertThat(declared.header("content-length"), is(expected.header("content-length")));
        }
    }

    /**
     * A tunnel decodes a compressed response, as it always has. One of declared length is still relayed whole, with
     * the length it decodes to.
     */
    @Test
    public void shouldRelayACompressedResponseOfDeclaredLengthAsBefore() throws Exception {
        String text = Http2FlowControlBodies.body(OVER_WINDOW, "compressed-through-" + route);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        mockServerClient.when(request().withPath("/compressed")).respond(response().withHeader("content-encoding", "gzip").withBody(binary(compressed.toByteArray())));
        try (Http2TestClient tunnel = tunnel()) {
            Http2TestClient.Exchange decoded = tunnel.send(headers(tunnel, HttpMethod.GET, "/compressed"), true);

            assertThat(decoded.status(), is(200));
            assertThat(decoded.body(), is(text));
            assertThat(decoded.header("content-length"), is(String.valueOf(text.getBytes(StandardCharsets.UTF_8).length)));
            assertThat(decoded.header("content-encoding"), is(nullValue()));
        }
    }

    @Test
    public void shouldRelayResponsesWithNoBodyAsBefore() throws Exception {
        mockServerClient.when(request().withPath("/no-content")).respond(response().withStatusCode(204));
        mockServerClient.when(request().withPath("/not-modified")).respond(response().withStatusCode(304).withHeader("etag", "\"v1\""));
        mockServerClient.when(request().withMethod("HEAD").withPath("/head")).respond(response().withHeader("content-length", "6"));
        try (Http2TestClient direct = direct(); Http2TestClient tunnel = tunnel()) {
            for (String path : Arrays.asList("/no-content", "/not-modified")) {
                Http2TestClient.Exchange expected = direct.send(headers(direct, HttpMethod.GET, path), true);
                Http2TestClient.Exchange empty = tunnel.send(headers(tunnel, HttpMethod.GET, path), true);

                assertThat(path, empty.status(), is(expected.status()));
                assertThat(path, empty.body(), is(""));
                assertThat(path, expected.body(), is(""));
                assertThat(path, empty.header("etag"), is(expected.header("etag")));
                assertThat(path, empty.header("content-length"), is(expected.header("content-length")));
            }
            Http2TestClient.Exchange expectedHead = direct.send(headers(direct, HttpMethod.HEAD, "/head"), true);
            Http2TestClient.Exchange head = tunnel.send(headers(tunnel, HttpMethod.HEAD, "/head"), true);
            assertThat(head.status(), is(200));
            assertThat(head.body(), is(""));
            assertThat(expectedHead.body(), is(""));
            assertThat("the tunnel carries on", tunnel.send(headers(tunnel, HttpMethod.GET, "/plain"), true).body(), is("served"));
        }
    }

    @Test
    public void shouldRelayTrailersAfterTheBody() throws Exception {
        String body = Http2FlowControlBodies.body(OVER_WINDOW, "trailers-through-" + route);
        mockServerClient.when(request().withPath("/trailers")).respond(response().withBody(body).withTrailer("x-checksum", "abc123"));
        try (Http2TestClient direct = direct(); Http2TestClient tunnel = tunnel()) {
            Http2TestClient.Exchange expected = direct.send(headers(direct, HttpMethod.GET, "/trailers"), true);
            Http2TestClient.Exchange trailed = tunnel.send(headers(tunnel, HttpMethod.GET, "/trailers"), true);

            assertThat(trailed.status(), is(200));
            assertThat(trailed.body(), is(body));
            assertThat(trailed.trailer("x-checksum"), is("abc123"));
            assertThat(expected.trailer("x-checksum"), is("abc123"));
        }
    }

    @Test
    public void shouldInterleaveStreamedAndWholeResponsesOnOneTunnel() throws Exception {
        String whole = Http2FlowControlBodies.body(OVER_WINDOW, "whole-through-" + route);
        mockServerClient.when(request().withPath("/whole")).respond(response().withBody(whole));
        mockServerClient.upsert(new Expectation(request().withPath("/sse/quick")).thenRespondWithSse(HttpSseResponse.sseResponse().withEvents(events("quick", EVENTS, 110))));
        try (Http2TestClient tunnel = tunnel()) {
            Http2TestClient.Exchange slowStream = tunnel.send(headers(tunnel, HttpMethod.GET, "/sse"), true);
            Http2TestClient.Exchange forwarded = tunnel.send(headers(tunnel, HttpMethod.GET, tokens(EVENTS, 150)).add("accept", "text/event-stream"), true);
            Http2TestClient.Exchange quickStream = tunnel.send(headers(tunnel, HttpMethod.GET, "/sse/quick"), true);
            assertThat(slowStream.receivedWithin("tick_1", 15), is(true));
            List<Http2TestClient.Exchange> wholes = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                wholes.add(tunnel.send(headers(tunnel, HttpMethod.GET, "/whole"), true));
            }

            for (Http2TestClient.Exchange exchange : wholes) {
                assertThat(exchange.body(), is(whole));
            }
            assertThat(quickStream.body(), is(sseBody("quick", EVENTS)));
            assertThat(forwarded.body(), is(StreamingUpstream.tokens(EVENTS)));
            assertThat(slowStream.body(), is(sseBody("tick", EVENTS)));
            assertThat(quickStream.millisFromFirstDataToEnd(), greaterThanOrEqualTo(700L));
            assertThat(forwarded.millisFromFirstDataToEnd(), greaterThanOrEqualTo(1000L));
        }
        assertThat(warningsAndErrors(), is(empty()));
    }

    /**
     * A client that gives up on one stream stops MockServer producing it, as on a direct connection: the upstream's
     * connection closes long before the upstream would have finished. The tunnel's other streams carry on.
     */
    @Test
    public void shouldStopTheUpstreamWhenTheClientResetsAStreamedResponse() throws Exception {
        String id = id();
        try (Http2TestClient tunnel = tunnel()) {
            Http2TestClient.Exchange other = tunnel.send(headers(tunnel, HttpMethod.GET, "/sse"), true);
            Http2TestClient.Exchange abandoned = tunnel.send(headers(tunnel, HttpMethod.GET, "/upstream/tokens?count=400&gap=100&id=" + id).add("accept", "text/event-stream"), true);
            assertThat(abandoned.receivedWithin(StreamingUpstream.token(2), 15), is(true));

            abandoned.reset(Http2Error.CANCEL);

            assertThat("the upstream is closed, not left to send its remaining tokens", upstream.closedWithin(id, 10), is(true));
            assertThat(other.body(), is(sseBody("tick", EVENTS)));
            assertThat("the tunnel carries on", tunnel.send(headers(tunnel, HttpMethod.GET, "/plain"), true).body(), is("served"));
            assertThat(tunnel.isOpen(), is(true));
        }
        assertThat("a client that gives up is not an error", warningsAndErrors(), is(empty()));
    }

    @Test
    public void shouldStopTheUpstreamWhenTheTunnelClosesDuringAStreamedResponse() throws Exception {
        String id = id();
        try (Http2TestClient tunnel = tunnel()) {
            Http2TestClient.Exchange abandoned = tunnel.send(headers(tunnel, HttpMethod.GET, "/upstream/tokens?count=400&gap=100&id=" + id).add("accept", "text/event-stream"), true);
            assertThat(abandoned.receivedWithin(StreamingUpstream.token(2), 15), is(true));
        }

        assertThat(upstream.closedWithin(id, 10), is(true));
        // on the same server, so everything the close set off has been logged by the time this is answered
        try (Http2TestClient next = tunnel()) {
            assertThat(next.send(headers(next, HttpMethod.GET, "/plain"), true).body(), is("served"));
        }
        assertThat("a client that leaves is not an error", warningsAndErrors(), is(empty()));
    }

    /**
     * The upstream's connection is lost part way through. The client is told the same way on a tunnel as on a
     * direct connection, having been sent the same tokens first.
     */
    @Test
    public void shouldEndAStreamedResponseWhoseUpstreamIsLostAsADirectConnectionDoes() throws Exception {
        try (Http2TestClient direct = direct(); Http2TestClient tunnel = tunnel()) {
            Http2TestClient.Exchange expected = direct.send(headers(direct, HttpMethod.GET, "/upstream/dies?id=" + id()).add("accept", "text/event-stream"), true);
            Http2TestClient.Exchange cut = tunnel.send(headers(tunnel, HttpMethod.GET, "/upstream/dies?id=" + id()).add("accept", "text/event-stream"), true);

            assertThat(outcome(cut), is(outcome(expected)));
            assertThat(cut.received(), is(StreamingUpstream.tokens(3)));
            assertThat("the tunnel carries on", tunnel.send(headers(tunnel, HttpMethod.GET, "/plain"), true).body(), is("served"));
        }
    }

    @Test
    public void shouldRelayAGrpcServerStreamMessageByMessage() throws Exception {
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get(GRPC_DESCRIPTOR));
        com.google.protobuf.Descriptors.MethodDescriptor greeting = store.getMethod(GRPC_SERVICE, "Greeting");
        String path = "/" + GRPC_SERVICE + "/Greeting";
        GrpcStreamResponse stream = GrpcStreamResponse.grpcStreamResponse().withStatusName("OK");
        for (int i = 0; i < 6; i++) {
            stream.withMessage(GrpcStreamMessage.grpcStreamMessage("{\"greeting\": \"Hello " + i + "\"}").withDelay(TimeUnit.MILLISECONDS, i == 0 ? 0 : 300));
        }
        mockServerClient.when(request().withMethod("POST").withPath(path).withHeader("x-call", "stream")).respondWithGrpcStream(stream);
        mockServerClient.when(request().withMethod("POST").withPath(path).withHeader("x-call", "unary"))
            .respondWithGrpcStream(GrpcStreamResponse.grpcStreamResponse().withStatusName("OK").withMessage("{\"greeting\": \"Hello once\"}"));
        byte[] call = GrpcFrameCodec.encode(store.getConverter().toProtobuf("{\"name\":\"Tom\"}", greeting.getInputType()));
        try (Http2TestClient tunnel = tunnel()) {
            Http2TestClient.Exchange streamed = tunnel.send(headers(tunnel, HttpMethod.POST, path).add("content-type", "application/grpc").add("te", "trailers").add("x-call", "stream"), false).data(call, true);
            Http2TestClient.Exchange unary = tunnel.send(headers(tunnel, HttpMethod.POST, path).add("content-type", "application/grpc").add("te", "trailers").add("x-call", "unary"), false).data(call, true);

            assertThat(unary.status(), is(200));
            assertThat(unary.trailer("grpc-status"), is("0"));
            List<byte[]> unaryMessages = GrpcFrameCodec.decode(unary.receivedByteArray());
            assertThat(unaryMessages, hasSize(1));
            assertThat(store.getConverter().toJson(unaryMessages.get(0), greeting.getOutputType()), containsString("Hello once"));

            assertThat(streamed.status(), is(200));
            assertThat(streamed.trailer("grpc-status"), is("0"));
            assertThat("the first message long before the last", streamed.millisFromFirstDataToEnd(), greaterThanOrEqualTo(750L));
            List<byte[]> messages = GrpcFrameCodec.decode(streamed.receivedByteArray());
            assertThat(messages, hasSize(6));
            for (int i = 0; i < 6; i++) {
                assertThat(store.getConverter().toJson(messages.get(i), greeting.getOutputType()), containsString("Hello " + i));
            }
        }
    }

    /**
     * The first event reaches a tunnel's client long before the response ends, as it does a direct connection's; the
     * whole body is then the same bytes.
     */
    private static void assertStreamedAsOnADirectConnection(Http2TestClient.Exchange streamed, Http2TestClient.Exchange direct) throws Exception {
        assertThat(streamed.status(), is(200));
        assertThat(direct.status(), is(200));
        assertThat("the first event arrives while the stream is open", streamed.millisFromFirstDataToEnd(), greaterThanOrEqualTo(STREAM_MILLIS / 2));
        assertThat("as on a direct connection", direct.millisFromFirstDataToEnd(), greaterThanOrEqualTo(STREAM_MILLIS / 2));
        assertThat(streamed.body(), is(direct.body()));
    }

    private static String outcome(Http2TestClient.Exchange exchange) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!exchange.isReset() && !exchange.isComplete()) {
            assertThat("the response ends or is reset within 15s", System.nanoTime() < deadline, is(true));
            Thread.sleep(10);
        }
        return exchange.isReset() ? "reset with " + exchange.resetErrorCode() : "ended";
    }

    private static List<SseEvent> events(String name, int count, int gapMillis) {
        List<SseEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(SseEvent.sseEvent().withData(name + "_" + i).withDelay(TimeUnit.MILLISECONDS, i == 0 ? 0 : gapMillis));
        }
        return events;
    }

    private static String sseBody(String name, int count) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < count; i++) {
            body.append("data: ").append(name).append('_').append(i).append("\n\n");
        }
        return body.toString();
    }

    private static String tokens(int count, int gapMillis) {
        return "/upstream/tokens?count=" + count + "&gap=" + gapMillis + "&id=" + id();
    }

    private static String id() {
        return "request-" + REQUEST_IDS.incrementAndGet();
    }

    private Http2TestClient direct() throws Exception {
        directClient = route.tls ? Http2TestClient.tls(clientGroup, mockServer.getLocalPort()) : Http2TestClient.h2c(clientGroup, mockServer.getLocalPort());
        return directClient;
    }

    private Http2TestClient tunnel() throws Exception {
        int port = mockServer.getLocalPort();
        switch (route) {
            case CONNECT_TLS:
            case CONNECT_H2C:
                return Http2TestClient.throughConnect(clientGroup, port, TARGET_HOST, targetPort(), route.tls);
            default:
                return Http2TestClient.throughSocks5(clientGroup, port, TARGET_HOST, targetPort(), route.tls);
        }
    }

    private int targetPort() {
        return route.tls ? 443 : 80;
    }

    private Http2Headers headers(Http2TestClient client, HttpMethod method, String path) {
        return new DefaultHttp2Headers()
            .method(method.asciiName())
            .scheme(route.tls ? "https" : "http")
            .authority(client == directClient ? "localhost:" + mockServer.getLocalPort() : TARGET_HOST + ":" + targetPort())
            .path(path);
    }

    /**
     * What MockServer logged since the last reset, other than the requests it received and the responses it returned.
     */
    private static List<String> warningsAndErrors() {
        return Arrays.stream(mockServerClient.retrieveLogMessagesArray(null))
            .map(message -> message.substring(message.indexOf(" - ") + " - ".length()))
            .filter(message -> !message.startsWith("received request") && !message.startsWith("returning ") && !message.startsWith("forwarded request"))
            .collect(Collectors.toList());
    }
}
