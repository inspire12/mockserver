package org.mockserver.netty.integration.proxy.socks;

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
 * An HTTP/2 request sent through a SOCKS5 or SOCKS4 tunnel, over TLS or in cleartext, is limited by
 * {@code maxHeaderSize} as one sent through a CONNECT tunnel is ({@code ConnectRelayHeaderListLimitIntegrationTest}):
 * the tunnel advertises the limit, answers a header list over it {@code 431} and resets its stream while the tunnel
 * carries on, and closes the tunnel for a header block more than a quarter over.
 */
@RunWith(Parameterized.class)
public class SocksRelayHeaderListLimitIntegrationTest {

    // above the 8,192 bytes Netty limits a header list to when it is not told otherwise
    private static final int LIMIT = 32 * 1024;
    private static final int DEFAULT_LIMIT = 256 * 1024;
    private static final String FILLER = "x-filler";
    private static final int FIELD_OVERHEAD = 32;

    private static MockServer limited;
    private static MockServerClient limitedClient;
    private static MockServer defaults;
    private static MockServerClient defaultsClient;
    private static EventLoopGroup clientGroup;

    public enum Tunnel {
        SOCKS5_TLS(true, true), SOCKS5_CLEARTEXT(true, false), SOCKS4_TLS(false, true), SOCKS4_CLEARTEXT(false, false);

        private final boolean socks5;
        private final boolean tls;

        Tunnel(boolean socks5, boolean tls) {
            this.socks5 = socks5;
            this.tls = tls;
        }

        // SOCKS4 takes an address, not a name
        private String targetHost() {
            return socks5 ? "localhost" : "127.0.0.1";
        }

        private int targetPort() {
            return tls ? 443 : 80;
        }
    }

    @Parameterized.Parameters(name = "{0}")
    public static Object[] tunnels() {
        return Tunnel.values();
    }

    @Parameterized.Parameter
    public Tunnel tunnel;

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
     * {@code maxHeaderSize} limits requests. A mocked response's headers are MockServer's own, and reach the client
     * whatever their size: over the 8,192 bytes Netty reads a header list up to by default, and over the request limit.
     */
    @Test
    public void shouldRelayAResponseWithHeadersLargerThanTheRequestLimit() throws Exception {
        for (int headerLength : new int[]{9_000, 20_000, 2 * LIMIT}) {
            String value = "b".repeat(headerLength);
            limitedClient.when(request().withPath("/large-response-" + headerLength)).respond(response().withHeader("x-large", value).withBody("served"));
            try (Http2TestClient client = throughTunnel(limited)) {
                Http2TestClient.Exchange exchange = client.send(pseudoHeaders(HttpMethod.GET).path("/large-response-" + headerLength), true);

                assertThat("a " + headerLength + " byte response header", exchange.status(), is(200));
                assertThat(exchange.header("x-large"), is(value));
                assertThat(exchange.body(), is("served"));
            }
        }
    }

    private Http2TestClient throughTunnel(MockServer mockServer) throws Exception {
        return tunnel.socks5
            ? Http2TestClient.throughSocks5(clientGroup, mockServer.getLocalPort(), tunnel.targetHost(), tunnel.targetPort(), tunnel.tls)
            : Http2TestClient.throughSocks4(clientGroup, mockServer.getLocalPort(), tunnel.targetHost(), tunnel.targetPort(), tunnel.tls);
    }

    private Http2Headers pseudoHeaders(HttpMethod method) {
        return new DefaultHttp2Headers()
            .method(method.asciiName())
            .scheme(tunnel.tls ? "https" : "http")
            .authority(tunnel.targetHost() + ":" + tunnel.targetPort())
            .path("/limit");
    }

    /**
     * A request whose header list is exactly {@code size} bytes as RFC 9113 counts it, made up with one filler field.
     */
    private Http2Headers headersOfSize(int size) {
        return headersOfSize(HttpMethod.GET, size);
    }

    private Http2Headers headersOfSize(HttpMethod method, int size) {
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
