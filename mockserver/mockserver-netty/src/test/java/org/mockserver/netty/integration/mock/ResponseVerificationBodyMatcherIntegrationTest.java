package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonPathBody.jsonPath;
import static org.mockserver.model.RegexBody.regex;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.verify.VerificationTimes.atLeast;

/**
 * A response verification whose body is a request-style matcher (regex, JSON path, ...) must constrain the
 * match over the REST API and through the Java client: a body nothing recorded satisfies fails the
 * verification instead of being ignored.
 */
public class ResponseVerificationBodyMatcherIntegrationTest {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static ClientAndServer mockServer;

    @BeforeClass
    public static void startServerAndRecordTwoExchanges() throws Exception {
        mockServer = ClientAndServer.startClientAndServer();
        mockServer.when(request().withPath("/order")).respond(response().withBody("order-42"));
        mockServer.when(request().withPath("/status")).respond(response().withBody("{\"status\":\"ok\"}"));
        assertThat(send("GET", "/order", null).statusCode(), is(200));
        assertThat(send("GET", "/status", null).statusCode(), is(200));
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServer);
    }

    @Test
    public void restVerifyShouldFailWhenNoRecordedResponseMatchesTheRegexBody() throws Exception {
        HttpResponse<String> response = send("PUT", "/mockserver/verify",
            "{\"httpResponse\":{\"body\":{\"type\":\"REGEX\",\"regex\":\"zzz-nothing\"}},\"times\":{\"atLeast\":1}}");

        assertThat(response.statusCode(), is(406));
        assertThat(response.body(), containsString("Response not found"));
    }

    @Test
    public void restVerifyShouldPassWhenARecordedResponseMatchesTheRegexBody() throws Exception {
        HttpResponse<String> response = send("PUT", "/mockserver/verify",
            "{\"httpResponse\":{\"body\":{\"type\":\"REGEX\",\"regex\":\"order-[0-9]+\"}},\"times\":{\"atLeast\":1}}");

        assertThat(response.statusCode(), is(202));
    }

    @Test
    public void restVerifySequenceShouldApplyJsonPathBodies() throws Exception {
        assertThat(send("PUT", "/mockserver/verifySequence",
            "{\"httpResponses\":[{\"body\":{\"type\":\"REGEX\",\"regex\":\"order-.*\"}},{\"body\":{\"type\":\"JSON_PATH\",\"jsonPath\":\"$.status\"}}]}"
        ).statusCode(), is(202));
        assertThat(send("PUT", "/mockserver/verifySequence",
            "{\"httpResponses\":[{\"body\":{\"type\":\"REGEX\",\"regex\":\"order-.*\"}},{\"body\":{\"type\":\"JSON_PATH\",\"jsonPath\":\"$.missing\"}}]}"
        ).statusCode(), is(406));
    }

    @Test
    public void javaClientVerifyShouldApplyTheBodyMatcher() {
        mockServer.verify(request().withPath("/status"), response().withBodyMatching(jsonPath("$.status")), atLeast(1));

        AssertionError failure = assertThrows(AssertionError.class,
            () -> mockServer.verify(request().withPath("/order"), response().withBodyMatching(regex("zzz-nothing")), atLeast(1)));
        assertThat(failure.getMessage(), containsString("Response not found"));
    }

    private static HttpResponse<String> send(String method, String path, String body) throws Exception {
        return HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + mockServer.getPort() + path))
            .timeout(Duration.ofSeconds(10))
            .method(method, body != null ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody())
            .build(), HttpResponse.BodyHandlers.ofString());
    }
}
