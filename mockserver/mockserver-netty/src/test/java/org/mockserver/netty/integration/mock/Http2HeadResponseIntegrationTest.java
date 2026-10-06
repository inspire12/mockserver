package org.mockserver.netty.integration.mock;

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
import org.mockserver.model.HttpForward;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A response to {@code HEAD} reaches an HTTP/2 client with the header block a {@code GET} is sent, its
 * {@code content-length} included, and no body (RFC 9110 section 9.3.2), whether the response was mocked or forwarded,
 * on a connection made straight to MockServer and through MockServer as a CONNECT or SOCKS5 proxy.
 */
@RunWith(Parameterized.class)
public class Http2HeadResponseIntegrationTest {

    private static final String BODY = "served";
    private static final String TARGET_HOST = "localhost";

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static MockServer upstream;
    private static MockServerClient upstreamClient;
    private static EventLoopGroup clientGroup;

    public enum Route {
        DIRECT_TLS(true), DIRECT_H2C(false), CONNECT_TLS(true), CONNECT_H2C(false), SOCKS5_TLS(true), SOCKS5_H2C(false);

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
    public static void startServers() {
        // the forward keeps the inbound protocol, so a forward over TLS reaches the upstream as HTTP/2
        mockServer = new MockServer(configuration().forwardProxyHttp2Enabled(true), 0);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        upstream = new MockServer(0);
        upstreamClient = new MockServerClient("localhost", upstream.getLocalPort());
        clientGroup = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        stopQuietly(upstreamClient);
        stopQuietly(upstream);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
    }

    @Before
    public void resetServers() {
        mockServerClient.reset();
        upstreamClient.reset();
        mockServerClient.when(request().withPath("/mocked")).respond(response().withBody(BODY));
        mockServerClient.when(request().withPath("/forwarded-over-http1"))
            .forward(forward().withHost("localhost").withPort(upstream.getLocalPort()).withScheme(HttpForward.Scheme.HTTP));
        mockServerClient.when(request().withPath("/forwarded-over-http2"))
            .forward(forward().withHost("localhost").withPort(upstream.getLocalPort()).withScheme(HttpForward.Scheme.HTTPS));
        upstreamClient.when(request().withPath("/forwarded-.*")).respond(response().withBody(BODY));
    }

    @Test
    public void shouldSendAMockedResponseToHeadWithoutItsBody() throws Exception {
        assertHeadIsSentTheHeadersOfGetWithoutTheBody("/mocked");
    }

    @Test
    public void shouldSendAResponseForwardedOverHttp1ToHeadWithoutItsBody() throws Exception {
        assertHeadIsSentTheHeadersOfGetWithoutTheBody("/forwarded-over-http1");
    }

    @Test
    public void shouldSendAResponseForwardedOverHttp2ToHeadWithoutItsBody() throws Exception {
        assertHeadIsSentTheHeadersOfGetWithoutTheBody("/forwarded-over-http2");
    }

    private void assertHeadIsSentTheHeadersOfGetWithoutTheBody(String path) throws Exception {
        try (Http2TestClient client = connect()) {
            Http2TestClient.Exchange get = client.send(requestHeaders(HttpMethod.GET, path), true);
            assertThat("a GET is sent the body", get.body(), is(BODY));
            assertThat("a GET is sent the length of the body", get.header("content-length"), is(String.valueOf(BODY.length())));

            Http2TestClient.Exchange head = client.send(requestHeaders(HttpMethod.HEAD, path), true);
            assertThat("a HEAD is sent no body", head.body(), is(""));
            assertThat("a HEAD is sent the header block a GET is sent", headerBlock(head), is(headerBlock(get)));
        }
    }

    private Http2TestClient connect() throws Exception {
        int port = mockServer.getLocalPort();
        int targetPort = route.tls ? 443 : 80;
        switch (route) {
            case DIRECT_TLS:
                return Http2TestClient.tls(clientGroup, port);
            case DIRECT_H2C:
                return Http2TestClient.h2c(clientGroup, port);
            case CONNECT_TLS:
            case CONNECT_H2C:
                return Http2TestClient.throughConnect(clientGroup, port, TARGET_HOST, targetPort, route.tls);
            default:
                return Http2TestClient.throughSocks5(clientGroup, port, TARGET_HOST, targetPort, route.tls);
        }
    }

    private Http2Headers requestHeaders(HttpMethod method, String path) {
        String authority = route == Route.DIRECT_TLS || route == Route.DIRECT_H2C
            ? "localhost:" + mockServer.getLocalPort()
            : TARGET_HOST + ":" + (route.tls ? 443 : 80);
        return new DefaultHttp2Headers()
            .method(method.asciiName())
            .scheme(route.tls ? "https" : "http")
            .authority(authority)
            .path(path);
    }

    /**
     * The header block without the {@code x-http2-} extension headers Netty uses inside a process: a tunnel relays the
     * stream weight on an aggregated response, which is not what this test is about.
     */
    private static Map<String, String> headerBlock(Http2TestClient.Exchange exchange) throws Exception {
        Map<String, String> headers = new TreeMap<>();
        exchange.headers().forEach(header -> {
            String name = header.getKey().toString();
            if (!name.startsWith("x-http2-")) {
                headers.merge(name, header.getValue().toString(), (first, second) -> first + ", " + second);
            }
        });
        return headers;
    }
}
