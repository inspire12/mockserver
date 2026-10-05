package org.mockserver.netty.http3;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.NettyBufferLeaks;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * An HTTP/3 request's trailer section is limited by {@code maxHeaderSize} as its header section is: one of exactly
 * that size is served, and one over it closes the connection with {@code H3_EXCESSIVE_LOAD} before the request is
 * dispatched, with one WARN that names the trailers. A request stream cannot carry a section after its request has
 * ended, so there is no later moment to test. Needs the QUIC native library, and fails without it.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3TrailerSectionLimitIntegrationTest {

    // above the 8,192 bytes Netty limits a field section to when it is not told otherwise
    private static final int LIMIT = 32 * 1024;
    private static final int FIELD_OVERHEAD = 32;
    private static final String TRAILER = "x-trailer";

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static NioEventLoopGroup clientGroup;
    private static int leaksBefore;

    @BeforeClass
    public static void startServer() {
        leaksBefore = NettyBufferLeaks.recorded();
        // the test JVM defaults to ERROR, which would drop the WARN entries this class asserts on
        mockServer = startWithHttp3(configuration().http3MaxIdleTimeout(30000L).maxHeaderSize(LIMIT).logLevel("WARN"));
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        clientGroup = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopServer() throws Exception {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
        // the body held when the trailers were refused was released
        NettyBufferLeaks.assertNoneSince(leaksBefore);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
        mockServerClient.when(request().withPath("/upload")).respond(response().withBody("served"));
    }

    @Test
    public void shouldServeARequestWhoseTrailerSectionIsExactlyMaxHeaderSize() throws Exception {
        try (Http3TestClient connection = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange exchange = connection.send(requestHeaders(), "request body", trailersOfSize(LIMIT));

            assertThat(exchange.status(), is(200));
            assertThat(exchange.body(), is("served"));
        }
        assertThat(mockServerClient.retrieveRecordedRequests(request().withPath("/upload"))[0].getBodyAsString(), is("request body"));
        assertThat(otherLogMessages(), is(empty()));
    }

    @Test
    public void shouldCloseTheConnectionForATrailerSectionOneByteOver() throws Exception {
        try (Http3TestClient connection = Http3TestClient.open(clientGroup, mockServer)) {
            connection.send(requestHeaders(), "request body", trailersOfSize(LIMIT + 1));

            QuicConnectionCloseEvent closed = connection.closedByServer();
            assertThat(closed.isApplicationClose(), is(true));
            assertThat(closed.error(), is(Http3ErrorCode.H3_EXCESSIVE_LOAD.code()));
        }
        assertThat("never dispatched", mockServerClient.retrieveRecordedRequests(request().withPath("/upload")), emptyArray());
        assertThat(otherLogMessages(), contains(containsString("because a request's trailer section is larger than maxHeaderSize")));

        try (Http3TestClient next = Http3TestClient.open(clientGroup, mockServer)) {
            assertThat("the server carries on", next.send(requestHeaders(), "request body", trailersOfSize(LIMIT)).status(), is(200));
        }
    }

    private static Http3Headers requestHeaders() {
        return new DefaultHttp3Headers()
            .method(HttpMethod.POST.asciiName())
            .scheme("https")
            .authority("127.0.0.1:" + mockServer.getHttp3Port())
            .path("/upload");
    }

    /**
     * A trailer section of exactly {@code size} bytes as RFC 9114 counts it.
     */
    private static Http3Headers trailersOfSize(int size) {
        return new DefaultHttp3Headers().add(TRAILER, "a".repeat(size - TRAILER.length() - FIELD_OVERHEAD));
    }

    /**
     * What MockServer logged since the last reset, other than the requests it received and the responses it returned.
     */
    private static List<String> otherLogMessages() {
        return Arrays.stream(mockServerClient.retrieveLogMessagesArray(null))
            .map(message -> message.substring(message.indexOf(" - ") + " - ".length()))
            .filter(message -> !message.startsWith("received request") && !message.startsWith("returning "))
            .collect(Collectors.toList());
    }
}
