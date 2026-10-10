package org.mockserver.netty.integration.proxy.http;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
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

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A response whose header block ends its stream reaches a client that speaks HTTP/2 through MockServer as a CONNECT or
 * SOCKS proxy with the header block a direct connection is sent: a response to {@code HEAD} keeps the length of the
 * body it does not carry. Each route is compared with a connection made straight to MockServer in the same test.
 */
@RunWith(Parameterized.class)
public class Http2TunnelHeaderOnlyResponseIntegrationTest {

    private static final String TARGET_HOST = "localhost";
    // the length of the body a GET would have
    private static final String DECLARED_LENGTH = "6";

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static EventLoopGroup clientGroup;

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

    @BeforeClass
    public static void startServer() {
        mockServer = new MockServer(configuration(), 0);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        clientGroup = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
        mockServerClient.when(request().withPath("/head")).respond(response().withHeader("content-length", DECLARED_LENGTH));
        mockServerClient.when(request().withPath("/no-content")).respond(response().withStatusCode(204));
    }

    @Test
    public void shouldKeepTheDeclaredLengthOfAResponseToHead() throws Exception {
        Map<String, String> direct = headersOnADirectConnection(HttpMethod.HEAD, "/head");
        Map<String, String> tunnelled = headersThroughTheTunnel(HttpMethod.HEAD, "/head");

        assertThat("a direct connection is sent the declared length", direct.get("content-length"), is(DECLARED_LENGTH));
        assertThat(tunnelled, is(direct));
    }

    @Test
    public void shouldRelayAResponseWithNoContentAsADirectConnectionIsSentIt() throws Exception {
        assertThat(headersThroughTheTunnel(HttpMethod.GET, "/no-content"), is(headersOnADirectConnection(HttpMethod.GET, "/no-content")));
    }

    private Map<String, String> headersOnADirectConnection(HttpMethod method, String path) throws Exception {
        try (Http2TestClient direct = route.tls ? Http2TestClient.tls(clientGroup, mockServer.getLocalPort()) : Http2TestClient.h2c(clientGroup, mockServer.getLocalPort())) {
            return headerBlockOfAResponseWithNoBody(direct.send(requestHeaders(method, path, "localhost:" + mockServer.getLocalPort()), true));
        }
    }

    private Map<String, String> headersThroughTheTunnel(HttpMethod method, String path) throws Exception {
        int targetPort = route.tls ? 443 : 80;
        try (Http2TestClient tunnel = route == Route.CONNECT_TLS || route == Route.CONNECT_H2C
            ? Http2TestClient.throughConnect(clientGroup, mockServer.getLocalPort(), TARGET_HOST, targetPort, route.tls)
            : Http2TestClient.throughSocks5(clientGroup, mockServer.getLocalPort(), TARGET_HOST, targetPort, route.tls)) {
            return headerBlockOfAResponseWithNoBody(tunnel.send(requestHeaders(method, path, TARGET_HOST + ":" + targetPort), true));
        }
    }

    private Http2Headers requestHeaders(HttpMethod method, String path, String authority) {
        return new DefaultHttp2Headers()
            .method(method.asciiName())
            .scheme(route.tls ? "https" : "http")
            .authority(authority)
            .path(path);
    }

    private static Map<String, String> headerBlockOfAResponseWithNoBody(Http2TestClient.Exchange exchange) throws Exception {
        assertThat("the response has no body", exchange.body(), is(""));
        Map<String, String> headers = new TreeMap<>();
        exchange.headers().forEach(header -> headers.merge(header.getKey().toString(), header.getValue().toString(), (first, second) -> first + ", " + second));
        return headers;
    }
}
