package org.mockserver.netty.http3;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.Http3Headers;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpForward;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.NettyBufferLeaks;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A response to {@code HEAD} reaches an HTTP/3 client as the header section a {@code GET} is sent, its
 * {@code content-length} included, ending the stream with no DATA frame and no trailers (RFC 9110 section 9.3.2):
 * a mocked response, a mocked response with trailers, a forwarded response, an MCP error and a 413. Skipped when the
 * QUIC native library is missing.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3HeadResponseIntegrationTest {

    private static final String BODY = "served";
    private static final int MAX_REQUEST_BODY = 1024;

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static MockServer upstream;
    private static MockServerClient upstreamClient;
    private static NioEventLoopGroup clientGroup;
    private static int leaksBefore;

    @BeforeClass
    public static void startServers() {
        assumeQuicAvailable();
        leaksBefore = NettyBufferLeaks.recorded();
        mockServer = startWithHttp3(configuration()
            .http3MaxIdleTimeout(30000L)
            .attemptToProxyIfNoMatchingExpectation(false)
            .maxRequestBodySize(MAX_REQUEST_BODY));
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        upstream = new MockServer(0);
        upstreamClient = new MockServerClient("127.0.0.1", upstream.getLocalPort());
        clientGroup = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopServers() throws Exception {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        stopQuietly(upstreamClient);
        stopQuietly(upstream);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
            NettyBufferLeaks.assertNoneSince(leaksBefore);
        }
    }

    @Before
    public void resetServers() {
        mockServerClient.reset();
        upstreamClient.reset();
        mockServerClient.when(request().withPath("/mocked")).respond(response().withHeader("x-mocked", "yes").withBody(BODY));
        mockServerClient.when(request().withPath("/mocked-with-length"))
            .respond(response().withHeader("content-length", String.valueOf(BODY.length())).withBody(BODY));
        mockServerClient.when(request().withPath("/mocked-with-trailers"))
            .respond(response().withBody(BODY).withTrailer("x-checksum", "abc123"));
        mockServerClient.when(request().withPath("/forwarded"))
            .forward(forward().withHost("127.0.0.1").withPort(upstream.getLocalPort()).withScheme(HttpForward.Scheme.HTTP));
        upstreamClient.when(request().withPath("/forwarded")).respond(response().withBody(BODY));
    }

    @Test
    public void shouldSendAMockedResponseToHeadWithoutItsBody() throws Exception {
        assertHeadIsSentTheHeadersOfGetAlone("/mocked");
    }

    @Test
    public void shouldKeepTheContentLengthOfAMockedResponseToHead() throws Exception {
        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange head = client.send(requestHeaders(HttpMethod.HEAD, "/mocked-with-length"));
            assertThat(head.body(), is(""));
            assertThat(head.headers().get("content-length").toString(), is(String.valueOf(BODY.length())));
        }
        assertHeadIsSentTheHeadersOfGetAlone("/mocked-with-length");
    }

    @Test
    public void shouldSendAMockedResponseToHeadWithoutItsBodyOrTrailers() throws Exception {
        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange get = client.send(requestHeaders(HttpMethod.GET, "/mocked-with-trailers"));
            assertThat(get.body(), is(BODY));
            assertThat("a GET is sent the trailers", get.trailer("x-checksum"), is("abc123"));

            Http3TestClient.Exchange head = client.send(requestHeaders(HttpMethod.HEAD, "/mocked-with-trailers"));
            assertThat(head.body(), is(""));
            assertThat("a HEAD is sent no DATA frame", head.dataFrames(), is(0));
            assertThat("a HEAD is sent no trailers", head.receivedTrailers(), is(false));
            assertThat(headerSection(head), is(headerSection(get)));
        }
    }

    @Test
    public void shouldSendAForwardedResponseToHeadWithoutABody() throws Exception {
        assertHeadIsSentTheHeadersOfGetAlone("/forwarded");
    }

    @Test
    public void shouldSendAnMcpErrorToHeadWithoutItsBody() throws Exception {
        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange head = client.send(requestHeaders(HttpMethod.HEAD, "/mockserver/mcp"));
            assertThat(head.status(), is(405));
            assertThat(head.body(), is(""));
            assertThat("a HEAD is sent no DATA frame", head.dataFrames(), is(0));
        }
    }

    @Test
    public void shouldSendA413ToHeadWithoutItsBody() throws Exception {
        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange head = client.start(requestHeaders(HttpMethod.HEAD, "/mocked")).data(new byte[MAX_REQUEST_BODY + 1]);
            assertThat(head.status(), is(413));
            assertThat(head.body(), is(""));
            assertThat("a HEAD is sent no DATA frame", head.dataFrames(), is(0));
            assertThat("the length a GET is sent is kept", head.headers().contains("content-length"), is(true));
        }
    }

    private static void assertHeadIsSentTheHeadersOfGetAlone(String path) throws Exception {
        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange get = client.send(requestHeaders(HttpMethod.GET, path));
            assertThat("a GET is sent the body", get.body(), is(BODY));

            Http3TestClient.Exchange head = client.send(requestHeaders(HttpMethod.HEAD, path));
            assertThat("a HEAD is sent no body, and its stream ends", head.body(), is(""));
            assertThat("a HEAD is sent no DATA frame", head.dataFrames(), is(0));
            assertThat("a HEAD is sent the header section a GET is sent", headerSection(head), is(headerSection(get)));
        }
    }

    private static Http3Headers requestHeaders(HttpMethod method, String path) {
        return new DefaultHttp3Headers()
            .method(method.asciiName())
            .scheme("https")
            .authority("127.0.0.1:" + mockServer.getHttp3Port())
            .path(path);
    }

    private static Map<String, String> headerSection(Http3TestClient.Exchange exchange) throws Exception {
        Map<String, String> headers = new TreeMap<>();
        exchange.headers().forEach(header ->
            headers.merge(header.getKey().toString(), header.getValue().toString(), (first, second) -> first + ", " + second));
        return headers;
    }

    private static void assumeQuicAvailable() {
        boolean available;
        try {
            available = io.netty.handler.codec.quic.Quic.isAvailable();
        } catch (Throwable notLoaded) {
            available = false;
        }
        Assume.assumeTrue("native QUIC transport not available on this platform -- skipping HTTP/3 HEAD test", available);
    }
}
