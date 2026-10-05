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
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * An HTTP/2 request sent through a CONNECT tunnel is limited by {@code maxHeaderSize} as one sent straight to
 * MockServer is: the tunnel advertises the limit, answers a header list over it {@code 431} and resets its stream
 * while the tunnel carries on, and closes the tunnel for a header block more than a quarter over. A SOCKS tunnel is
 * served by the same handler.
 */
public class ConnectRelayHeaderListLimitIntegrationTest {

    // above the 8,192 bytes Netty limits a header list to when it is not told otherwise
    private static final int LIMIT = 32 * 1024;
    private static final int DEFAULT_LIMIT = 256 * 1024;
    private static final String FILLER = "x-filler";
    private static final int FIELD_OVERHEAD = 32;
    private static final String TARGET_HOST = "localhost";
    private static final int TARGET_PORT = 443;

    private static MockServer limited;
    private static MockServerClient limitedClient;
    private static MockServer defaults;
    private static MockServerClient defaultsClient;
    private static EventLoopGroup clientGroup;

    @BeforeClass
    public static void startServers() {
        // the test JVM defaults to ERROR, which would drop the WARN entries this class asserts on
        limited = new MockServer(configuration().maxHeaderSize(LIMIT).logLevel("WARN"), 0);
        limitedClient = new MockServerClient("localhost", limited.getLocalPort());
        defaults = new MockServer(configuration().logLevel("WARN"), 0);
        defaultsClient = new MockServerClient("localhost", defaults.getLocalPort());
        clientGroup = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(limitedClient);
        stopQuietly(limited);
        stopQuietly(defaultsClient);
        stopQuietly(defaults);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Before
    public void resetServers() {
        limitedClient.reset();
        limitedClient.when(request().withPath("/limit")).respond(response().withBody("served"));
        defaultsClient.reset();
        defaultsClient.when(request().withPath("/limit")).respond(response().withBody("served"));
    }

    @Test
    public void shouldAdvertiseMaxHeaderSizeAsTheHeaderListLimit() throws Exception {
        try (Http2TestClient client = throughTunnel(limited)) {
            assertThat(client.serverSettings().maxHeaderListSize(), is((long) LIMIT));
        }
        try (Http2TestClient client = throughTunnel(defaults)) {
            assertThat(client.serverSettings().maxHeaderListSize(), is((long) DEFAULT_LIMIT));
        }
    }

    @Test
    public void shouldServeAHeaderListOfExactlyMaxHeaderSize() throws Exception {
        try (Http2TestClient client = throughTunnel(limited)) {
            // twice: the relay's loopback learns the limit MockServer advertises to it only after its first request
            for (int request = 0; request < 2; request++) {
                Http2TestClient.Exchange exchange = client.send(headersOfSize(LIMIT), true);

                assertThat(exchange.status(), is(200));
                assertThat(exchange.body(), is("served"));
            }
        }
        assertThat(limitedClient.retrieveRecordedRequests(request().withPath("/limit")).length, is(2));
        assertThat(warnings(limitedClient, "maxHeaderSize"), is(0L));
    }

    @Test
    public void shouldAnswerAHeaderListOneByteOverWith431AndKeepTheTunnel() throws Exception {
        try (Http2TestClient client = throughTunnel(limited)) {
            // left open, so the reset that follows the 431 is not for a stream both sides have ended
            Http2TestClient.Exchange refused = client.send(headersOfSize(HttpMethod.POST, LIMIT + 1), false);

            assertThat(refused.status(), is(431));
            assertThat(refused.resetErrorCode(), is(Http2Error.PROTOCOL_ERROR.code()));
            assertThat("never dispatched", limitedClient.retrieveRecordedRequests(request().withPath("/limit")), emptyArray());

            Http2TestClient.Exchange next = client.send(headersOfSize(LIMIT), true);
            assertThat("the tunnel carries on", next.status(), is(200));
            assertThat(next.body(), is("served"));
        }
        assertThat(warnings(limitedClient, "because its header list is larger than maxHeaderSize"), is(1L));
    }

    @Test
    public void shouldCloseTheTunnelForAHeaderBlockMoreThanAQuarterOver() throws Exception {
        try (Http2TestClient client = throughTunnel(limited)) {
            // 'a' takes 5 bits in HPACK's Huffman code, so this block is about 1.9 times the limit
            client.send(headersOfSize(3 * LIMIT), true);

            assertThat(client.goAwayErrorCode(), is(Http2Error.PROTOCOL_ERROR.code()));
            assertThat(client.closedWithin(10), is(true));
        }
        assertThat("never dispatched", limitedClient.retrieveRecordedRequests(request().withPath("/limit")), emptyArray());
        assertThat(warnings(limitedClient, "because a request's header block is more than a quarter larger than maxHeaderSize"), is(1L));
    }

    @Test
    public void shouldServeAHeaderListOverNettysDefaultLimitByDefault() throws Exception {
        try (Http2TestClient client = throughTunnel(defaults)) {
            Http2TestClient.Exchange exchange = client.send(headersOfSize(100 * 1024), true);

            assertThat(exchange.status(), is(200));
            assertThat(exchange.body(), is("served"));

            Http2TestClient.Exchange refused = client.send(headersOfSize(DEFAULT_LIMIT + 1), true);
            assertThat(refused.status(), is(431));
        }
    }

    /**
     * One 4 KiB field, sent once and then referred to 20,000 times from HPACK's dynamic table at a byte a time:
     * about 23 KB on the wire and 80 MB decoded, over 300 times the default limit.
     */
    @Test
    public void shouldRefuseAHeaderBlockThatDecodesFarOverTheLimit() throws Exception {
        try (Http2TestClient client = throughTunnel(defaults)) {
            Http2Headers bomb = pseudoHeaders(HttpMethod.POST);
            String value = "a".repeat(4000);
            for (int i = 0; i < 20_000; i++) {
                bomb.add(FILLER, value);
            }

            Http2TestClient.Exchange refused = client.send(bomb, false);

            assertThat(refused.status(), is(431));
            assertThat(refused.resetErrorCode(), is(Http2Error.PROTOCOL_ERROR.code()));
            assertThat("the tunnel carries on", client.send(pseudoHeaders(), true).status(), is(200));
        }
        assertThat("only the request after it was dispatched", defaultsClient.retrieveRecordedRequests(request().withPath("/limit")).length, is(1));
        assertThat(warnings(defaultsClient, "because its header list is larger than maxHeaderSize"), is(1L));
    }

    /**
     * {@code maxHeaderSize} limits requests. A mocked response's headers are MockServer's own, and reach the client
     * whatever their size: 9,000 bytes is over the 8,192 that the relay's loopback read a header list up to, and
     * 20,000 bytes made a header block that closed the loopback.
     */
    @Test
    public void shouldRelayAResponseWithHeadersLargerThanTheRequestLimit() throws Exception {
        for (int headerLength : new int[]{9_000, 20_000, 2 * LIMIT}) {
            String value = "b".repeat(headerLength);
            limitedClient.when(request().withPath("/large-response-" + headerLength)).respond(response().withHeader("x-large", value).withBody("served"));
            try (Http2TestClient client = throughTunnel(limited)) {
                Http2TestClient.Exchange exchange = client.send(pseudoHeaders().path("/large-response-" + headerLength), true);

                assertThat("a " + headerLength + " byte response header", exchange.status(), is(200));
                assertThat(exchange.header("x-large"), is(value));
                assertThat(exchange.body(), is("served"));
            }
        }
    }

    private static Http2TestClient throughTunnel(MockServer mockServer) throws Exception {
        return Http2TestClient.throughConnect(clientGroup, mockServer.getLocalPort(), TARGET_HOST, TARGET_PORT);
    }

    private static Http2Headers pseudoHeaders() {
        return pseudoHeaders(HttpMethod.GET);
    }

    private static Http2Headers pseudoHeaders(HttpMethod method) {
        return new DefaultHttp2Headers()
            .method(method.asciiName())
            .scheme("https")
            .authority(TARGET_HOST + ":" + TARGET_PORT)
            .path("/limit");
    }

    /**
     * A request whose header list is exactly {@code size} bytes as RFC 9113 counts it, made up with one filler field.
     */
    private static Http2Headers headersOfSize(int size) {
        return headersOfSize(HttpMethod.GET, size);
    }

    private static Http2Headers headersOfSize(HttpMethod method, int size) {
        Http2Headers headers = pseudoHeaders(method);
        long fillerValueLength = size - headerListSize(headers) - FILLER.length() - FIELD_OVERHEAD;
        headers.add(FILLER, "a".repeat((int) fillerValueLength));
        assertThat(headerListSize(headers), is((long) size));
        return headers;
    }

    private static long headerListSize(Http2Headers headers) {
        long size = 0;
        for (Map.Entry<CharSequence, CharSequence> field : headers) {
            size += field.getKey().length() + field.getValue().length() + FIELD_OVERHEAD;
        }
        return size;
    }

    private static long warnings(MockServerClient client, String text) {
        return Arrays.stream(client.retrieveLogMessagesArray(null)).filter(message -> message.contains(text)).count();
    }
}
