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
import org.mockserver.model.Header;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Protocol;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.NottableString.string;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A request an HTTP/2 client sends MockServer, directly or through a CONNECT or SOCKS5 tunnel, over TLS or h2c, is
 * recorded, logged and matched without the {@code x-http2-} extension headers Netty uses inside a process, and is
 * forwarded without them over HTTP/1.1, over HTTP/2, and over HTTP/1.1 when ALPN settles on it for an HTTPS upstream.
 */
@RunWith(Parameterized.class)
public class Http2RequestExtensionHeadersIntegrationTest {

    private static final String BODY = "served";
    private static final String TARGET_HOST = "localhost";
    private static final String EXTENSION_HEADER_PREFIX = "x-http2-";

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static MockServer upstream;
    private static MockServerClient upstreamClient;
    private static MockServer http1OnlyUpstream;
    private static MockServerClient http1OnlyUpstreamClient;
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
        // the forward keeps the inbound protocol, so an HTTPS forward offers HTTP/2 by ALPN
        mockServer = new MockServer(configuration().forwardProxyHttp2Enabled(true), 0);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        upstream = new MockServer(0);
        upstreamClient = new MockServerClient("localhost", upstream.getLocalPort());
        http1OnlyUpstream = new MockServer(configuration().http2Enabled(false), 0);
        http1OnlyUpstreamClient = new MockServerClient("localhost", http1OnlyUpstream.getLocalPort());
        clientGroup = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        stopQuietly(upstreamClient);
        stopQuietly(upstream);
        stopQuietly(http1OnlyUpstreamClient);
        stopQuietly(http1OnlyUpstream);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
    }

    @Before
    public void resetServers() {
        mockServerClient.reset();
        upstreamClient.reset();
        http1OnlyUpstreamClient.reset();
        mockServerClient.when(request().withPath("/mocked")).respond(response().withBody(BODY));
        mockServerClient.when(request().withPath("/forwarded-over-http1"))
            .forward(forward().withHost("localhost").withPort(upstream.getLocalPort()).withScheme(HttpForward.Scheme.HTTP));
        mockServerClient.when(request().withPath("/forwarded-over-http2"))
            .forward(forward().withHost("localhost").withPort(upstream.getLocalPort()).withScheme(HttpForward.Scheme.HTTPS));
        mockServerClient.when(request().withPath("/forwarded-over-alpn-http1"))
            .forward(forward().withHost("localhost").withPort(http1OnlyUpstream.getLocalPort()).withScheme(HttpForward.Scheme.HTTPS));
        upstreamClient.when(request().withPath("/forwarded-.*")).respond(response().withBody(BODY));
        http1OnlyUpstreamClient.when(request().withPath("/forwarded-.*")).respond(response().withBody(BODY));
    }

    @Test
    public void shouldRecordAMockedRequestWithoutExtensionHeaders() throws Exception {
        assertRequestCarriesNoExtensionHeader("/mocked", null, null);
    }

    @Test
    public void shouldForwardOverHttp1WithoutExtensionHeaders() throws Exception {
        assertRequestCarriesNoExtensionHeader("/forwarded-over-http1", upstreamClient, Protocol.HTTP_1_1);
    }

    @Test
    public void shouldForwardOverHttp2WithoutExtensionHeaders() throws Exception {
        assertRequestCarriesNoExtensionHeader("/forwarded-over-http2", upstreamClient, Protocol.HTTP_2);
    }

    @Test
    public void shouldForwardOverHttp1NegotiatedByAlpnWithoutExtensionHeaders() throws Exception {
        assertRequestCarriesNoExtensionHeader("/forwarded-over-alpn-http1", http1OnlyUpstreamClient, Protocol.HTTP_1_1);
    }

    private void assertRequestCarriesNoExtensionHeader(String path, MockServerClient upstreamClient, Protocol upstreamProtocol) throws Exception {
        try (Http2TestClient client = connect()) {
            Http2TestClient.Exchange get = client.send(requestHeaders(path), true);
            assertThat("the GET is answered on its own stream", get.body(), is(BODY));
        }

        HttpRequest[] recordedRequests = mockServerClient.retrieveRecordedRequests(request().withPath(path));
        assertThat("MockServer recorded the request", recordedRequests.length, is(1));
        assertThat("the recorded request arrived over HTTP/2", recordedRequests[0].getProtocol(), is(Protocol.HTTP_2));
        assertThat("the recorded request carries no x-http2- header", extensionHeaderNames(recordedRequests[0].getHeaderList()), is(empty()));
        // read before the matcher query below, which is itself logged with its x-http2- pattern
        String[] logMessages = mockServerClient.retrieveLogMessagesArray(request().withPath(path));
        assertThat("MockServer logged the request", logMessages.length, greaterThanOrEqualTo(1));
        assertThat("no log message names an x-http2- header", java.util.Arrays.asList(logMessages), everyItem(not(containsString(EXTENSION_HEADER_PREFIX))));
        assertThat("no recorded request matches an x-http2- header",
            mockServerClient.retrieveRecordedRequests(request().withPath(path).withHeader(string("x-http2-.*"), string(".*"))).length, is(0));

        if (upstreamClient != null) {
            HttpRequest[] upstreamRequests = upstreamClient.retrieveRecordedRequests(request().withPath(path));
            assertThat("the upstream received the request", upstreamRequests.length, is(1));
            assertThat("the upstream received the request over the expected protocol", upstreamRequests[0].getProtocol(), is(upstreamProtocol));
            assertThat("the request forwarded upstream carries no x-http2- header", extensionHeaderNames(upstreamRequests[0].getHeaderList()), is(empty()));
        }
    }

    private Http2TestClient connect() throws Exception {
        switch (route) {
            case DIRECT_TLS:
                return Http2TestClient.tls(clientGroup, mockServer.getLocalPort());
            case DIRECT_H2C:
                return Http2TestClient.h2c(clientGroup, mockServer.getLocalPort());
            case CONNECT_TLS:
            case CONNECT_H2C:
                return Http2TestClient.throughConnect(clientGroup, mockServer.getLocalPort(), TARGET_HOST, targetPort(), route.tls);
            default:
                return Http2TestClient.throughSocks5(clientGroup, mockServer.getLocalPort(), TARGET_HOST, targetPort(), route.tls);
        }
    }

    private boolean direct() {
        return route == Route.DIRECT_TLS || route == Route.DIRECT_H2C;
    }

    private int targetPort() {
        return route.tls ? 443 : 80;
    }

    private Http2Headers requestHeaders(String path) {
        String authority = direct() ? "localhost:" + mockServer.getLocalPort() : TARGET_HOST + ":" + targetPort();
        return new DefaultHttp2Headers()
            .method(HttpMethod.GET.asciiName())
            .scheme(route.tls ? "https" : "http")
            .authority(authority)
            .path(path);
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
