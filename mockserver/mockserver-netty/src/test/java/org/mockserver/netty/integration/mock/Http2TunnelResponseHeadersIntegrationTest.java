package org.mockserver.netty.integration.mock;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.Header;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A response relayed through MockServer as a CONNECT or SOCKS5 proxy reaches an HTTP/2 client with the header block a
 * connection made straight to MockServer is sent, with none of the {@code x-http2-} extension headers Netty uses inside
 * a process, whether the response was mocked or forwarded. Neither the requests MockServer and its upstream record nor
 * the responses MockServer records carry the priority extension headers the tunnel's legs set. The other extension
 * headers of a recorded or forwarded request are covered by {@link Http2RequestExtensionHeadersIntegrationTest}.
 */
@RunWith(Parameterized.class)
public class Http2TunnelResponseHeadersIntegrationTest {

    private static final String BODY = "served";
    private static final String TARGET_HOST = "localhost";
    private static final String EXTENSION_HEADER_PREFIX = "x-http2-";
    private static final List<String> PRIORITY_EXTENSION_HEADERS = Arrays.asList(
        ExtensionHeaderNames.STREAM_WEIGHT.text().toString(),
        ExtensionHeaderNames.STREAM_DEPENDENCY_ID.text().toString(),
        ExtensionHeaderNames.STREAM_PROMISE_ID.text().toString()
    );

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static MockServer upstream;
    private static MockServerClient upstreamClient;
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
        mockServerClient.when(request().withPath("/mocked")).respond(response().withBody(BODY).withHeader("x-mocked", "one", "two"));
        mockServerClient.when(request().withPath("/forwarded-over-http1"))
            .forward(forward().withHost("localhost").withPort(upstream.getLocalPort()).withScheme(HttpForward.Scheme.HTTP));
        mockServerClient.when(request().withPath("/forwarded-over-http2"))
            .forward(forward().withHost("localhost").withPort(upstream.getLocalPort()).withScheme(HttpForward.Scheme.HTTPS));
        upstreamClient.when(request().withPath("/forwarded-.*")).respond(response().withBody(BODY).withHeader("x-upstream", "one"));
    }

    @Test
    public void shouldRelayAMockedResponseWithTheHeadersOfADirectConnection() throws Exception {
        assertTunnelIsSentTheHeadersOfADirectConnection("/mocked", false);
    }

    @Test
    public void shouldRelayAResponseForwardedOverHttp1WithTheHeadersOfADirectConnection() throws Exception {
        assertTunnelIsSentTheHeadersOfADirectConnection("/forwarded-over-http1", true);
    }

    @Test
    public void shouldRelayAResponseForwardedOverHttp2WithTheHeadersOfADirectConnection() throws Exception {
        assertTunnelIsSentTheHeadersOfADirectConnection("/forwarded-over-http2", true);
    }

    private void assertTunnelIsSentTheHeadersOfADirectConnection(String path, boolean forwarded) throws Exception {
        Map<String, String> direct;
        try (Http2TestClient client = route.tls ? Http2TestClient.tls(clientGroup, mockServer.getLocalPort()) : Http2TestClient.h2c(clientGroup, mockServer.getLocalPort())) {
            Http2TestClient.Exchange get = client.send(requestHeaders("localhost:" + mockServer.getLocalPort(), path), true);
            assertThat("a direct GET is sent the body", get.body(), is(BODY));
            direct = headerBlock(get);
        }
        try (Http2TestClient client = throughTunnel()) {
            Http2TestClient.Exchange get = client.send(requestHeaders(TARGET_HOST + ":" + targetPort(), path), true);
            assertThat("a GET through the tunnel is sent the body", get.body(), is(BODY));
            assertThat("a GET through the tunnel is sent no x-http2- header", extensionHeaderNames(get.headers()), is(empty()));
            assertThat("a GET through the tunnel is sent the header block of a direct connection", headerBlock(get), is(direct));
        }

        HttpRequest[] recordedRequests = mockServerClient.retrieveRecordedRequests(request().withPath(path));
        assertThat("MockServer recorded the direct and tunnelled requests", recordedRequests.length, greaterThanOrEqualTo(2));
        for (HttpRequest recorded : recordedRequests) {
            assertThat("a request MockServer recorded carries no priority extension header", priorityExtensionHeaderNames(recorded.getHeaderList()), is(empty()));
        }
        LogEventRequestAndResponse[] recordedExchanges = mockServerClient.retrieveRecordedRequestsAndResponses(request().withPath(path));
        assertThat("MockServer recorded the direct and tunnelled exchanges", recordedExchanges.length, greaterThanOrEqualTo(2));
        for (LogEventRequestAndResponse recorded : recordedExchanges) {
            assertThat("a response MockServer recorded carries no x-http2- header", extensionHeaderNames(recorded.getHttpResponse().getHeaderList()), is(empty()));
        }
        if (forwarded) {
            HttpRequest[] upstreamRequests = upstreamClient.retrieveRecordedRequests(request().withPath(path));
            assertThat("the upstream received the direct and tunnelled requests", upstreamRequests.length, greaterThanOrEqualTo(2));
            for (HttpRequest recorded : upstreamRequests) {
                assertThat("a request forwarded upstream carries no priority extension header", priorityExtensionHeaderNames(recorded.getHeaderList()), is(empty()));
            }
        }
    }

    private Http2TestClient throughTunnel() throws Exception {
        if (route == Route.CONNECT_TLS || route == Route.CONNECT_H2C) {
            return Http2TestClient.throughConnect(clientGroup, mockServer.getLocalPort(), TARGET_HOST, targetPort(), route.tls);
        }
        return Http2TestClient.throughSocks5(clientGroup, mockServer.getLocalPort(), TARGET_HOST, targetPort(), route.tls);
    }

    private int targetPort() {
        return route.tls ? 443 : 80;
    }

    private Http2Headers requestHeaders(String authority, String path) {
        return new DefaultHttp2Headers()
            .method(HttpMethod.GET.asciiName())
            .scheme(route.tls ? "https" : "http")
            .authority(authority)
            .path(path);
    }

    private static Map<String, String> headerBlock(Http2TestClient.Exchange exchange) throws Exception {
        Map<String, String> headers = new TreeMap<>();
        exchange.headers().forEach(header -> headers.merge(header.getKey().toString(), header.getValue().toString(), (first, second) -> first + ", " + second));
        assertThat("the response has a header block", headers.size(), greaterThanOrEqualTo(1));
        return headers;
    }

    private static List<String> extensionHeaderNames(Http2Headers headers) {
        List<String> names = new ArrayList<>();
        headers.forEach(header -> {
            if (header.getKey().toString().toLowerCase(Locale.ROOT).startsWith(EXTENSION_HEADER_PREFIX)) {
                names.add(header.getKey().toString());
            }
        });
        return names;
    }

    private static List<String> priorityExtensionHeaderNames(List<Header> headers) {
        List<String> names = new ArrayList<>();
        for (Header header : headers) {
            if (PRIORITY_EXTENSION_HEADERS.contains(header.getName().getValue().toLowerCase(Locale.ROOT))) {
                names.add(header.getName().getValue());
            }
        }
        return names;
    }

    private static List<String> extensionHeaderNames(List<Header> headers) {
        List<String> names = new ArrayList<>();
        for (Header header : headers) {
            if (header.getName().getValue().toLowerCase(Locale.ROOT).startsWith(EXTENSION_HEADER_PREFIX)) {
                names.add(header.getName().getValue());
            }
        }
        return names;
    }
}
