package org.mockserver.netty.http3;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3Headers;
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
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A mocked {@code 1xx} that ends the exchange can only be an interim response over HTTP/3 (RFC 9114 section 4.1), so
 * its stream is reset with {@code H3_NO_ERROR} after it, as HTTP/2's is with {@code NO_ERROR}, rather than ended with a
 * FIN. A body or trailers it carries are not sent: after an interim response Netty takes either as a connection error,
 * which closed every stream on the connection. Each expectation that does this is warned about once.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3FinalInformationalResponseIntegrationTest {

    private static final long H3_NO_ERROR = Http3ErrorCode.H3_NO_ERROR.code();

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static NioEventLoopGroup clientGroup;
    private static int leaksBefore;

    @BeforeClass
    public static void startServer() {
        leaksBefore = NettyBufferLeaks.recorded();
        mockServer = startWithHttp3(configuration().logLevel("WARN").http3MaxIdleTimeout(30000L).attemptToProxyIfNoMatchingExpectation(false));
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
        NettyBufferLeaks.assertNoneSince(leaksBefore);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
        mockServerClient.when(request().withPath("/processing")).respond(response().withStatusCode(102));
        mockServerClient.when(request().withPath("/hints")).respond(response().withStatusCode(103).withHeader("link", "</style.css>; rel=preload"));
        mockServerClient.when(request().withPath("/served")).respond(response().withBody("served"));
    }

    @Test
    public void shouldResetTheStreamOfAMockedInformationalResponseWithNoError() throws Exception {
        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            for (String path : Arrays.asList("/processing", "/hints")) {
                Http3TestClient.Exchange informational = client.send(requestHeaders(HttpMethod.GET, path));

                assertThat(path + ": reset, not ended", informational.resetErrorCode(), is(H3_NO_ERROR));
                assertThat(path + ": only the stream", client.send(requestHeaders(HttpMethod.GET, "/served")).body(), is("served"));
            }
        }
        assertThat("ending a stream this way is not a fault", warningsAndErrors(), is(empty()));
        assertThat(finalInformationalWarningStatuses(), containsInAnyOrder(102, 103));
    }

    /**
     * A body and trailers go out after the header section, which Netty refuses after a {@code 1xx} with
     * {@code H3_FRAME_UNEXPECTED} for the whole connection; a response to {@code HEAD}, one written from another
     * thread after a delay, and a {@code 101}, which HTTP/3 has no upgrade for, are reset as any other.
     */
    @Test
    public void shouldResetTheStreamHoweverTheInformationalResponseIsMocked() throws Exception {
        mockServerClient.when(request().withPath("/with-a-body")).respond(response().withStatusCode(102).withBody("a body no 1xx carries"));
        mockServerClient.when(request().withPath("/with-trailers")).respond(response().withStatusCode(102).withTrailer("x-checksum", "abc123"));
        mockServerClient.when(request().withPath("/with-a-body-and-trailers")).respond(response().withStatusCode(102).withBody("a body").withTrailer("x-checksum", "abc123"));
        mockServerClient.when(request().withPath("/delayed")).respond(response().withStatusCode(102).withDelay(TimeUnit.MILLISECONDS, 100));
        mockServerClient.when(request().withPath("/switching")).respond(response().withStatusCode(101));
        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            for (String path : Arrays.asList("/with-a-body", "/with-trailers", "/with-a-body-and-trailers", "/delayed", "/switching")) {
                Http3TestClient.Exchange informational = client.send(requestHeaders(HttpMethod.GET, path));

                assertThat(path, informational.resetErrorCode(), is(H3_NO_ERROR));
                assertThat(path + ": no DATA frame", informational.dataFrames(), is(0));
                assertThat(path + ": the connection carries on", client.send(requestHeaders(HttpMethod.GET, "/served")).body(), is("served"));
            }
            Http3TestClient.Exchange head = client.send(requestHeaders(HttpMethod.HEAD, "/with-a-body"));
            assertThat("HEAD", head.resetErrorCode(), is(H3_NO_ERROR));
            assertThat(client.send(requestHeaders(HttpMethod.GET, "/served")).body(), is("served"));
        }
        assertThat(warningsAndErrors(), is(empty()));
    }

    @Test
    public void shouldWarnOnceForEachExpectationNotForEachRequest() throws Exception {
        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            for (int request = 0; request < 3; request++) {
                assertThat(client.send(requestHeaders(HttpMethod.GET, "/processing")).resetErrorCode(), is(H3_NO_ERROR));
            }
        }
        assertThat(finalInformationalWarningStatuses(), contains(102));
        String warning = finalInformationalWarnings().get(0);
        assertThat("names the expectation", warning, containsString(mockServerClient.retrieveActiveExpectations(request().withPath("/processing"))[0].getId()));
        assertThat("says what happens", warning, containsString("reset the stream with H3_NO_ERROR"));
        assertThat("and what to do", warning, containsString("add a protocol of HTTP_1_1"));
        assertThat(warningsAndErrors(), is(empty()));
    }

    private static Http3Headers requestHeaders(HttpMethod method, String path) {
        return new DefaultHttp3Headers()
            .method(method.asciiName())
            .scheme("https")
            .authority("127.0.0.1:" + mockServer.getHttp3Port())
            .path(path);
    }

    /**
     * What MockServer logged since the last reset, other than the requests it received, the responses it returned and
     * the warnings about a final 1xx over HTTP/3.
     */
    private static List<String> warningsAndErrors() {
        return logMessages()
            .filter(message -> !message.startsWith("received request") && !message.startsWith("returning ") && !isFinalInformationalWarning(message))
            .collect(Collectors.toList());
    }

    private static List<String> finalInformationalWarnings() {
        return logMessages().filter(Http3FinalInformationalResponseIntegrationTest::isFinalInformationalWarning).collect(Collectors.toList());
    }

    private static List<Integer> finalInformationalWarningStatuses() {
        return finalInformationalWarnings().stream()
            .map(warning -> Integer.parseInt(warning.replaceAll("(?s).*with the status:\\s*(\\d+).*", "$1")))
            .collect(Collectors.toList());
    }

    private static boolean isFinalInformationalWarning(String message) {
        return message.startsWith("expectation:") && message.contains("answered a request over HTTP/3 with the status:");
    }

    private static Stream<String> logMessages() {
        return Arrays.stream(mockServerClient.retrieveLogMessagesArray(null))
            .map(message -> message.substring(message.indexOf(" - ") + " - ".length()));
    }
}
