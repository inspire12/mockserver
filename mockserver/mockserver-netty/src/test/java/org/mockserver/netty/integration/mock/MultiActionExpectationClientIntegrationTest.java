package org.mockserver.netty.integration.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.LocalCallbackRegistry;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.action.ExpectationForwardCallback;
import org.mockserver.model.HttpObjectCallback;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.UUID;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.test.Retries.tryWaitForSuccess;

/**
 * An expectation with a primary response and a secondary forward callback, created through the Java client.
 */
public class MultiActionExpectationClientIntegrationTest {

    private final String clientId = "secondary-forward-" + UUID.randomUUID();
    private ClientAndServer mockServer;

    @Before
    public void startServer() {
        mockServer = startClientAndServer();
        LocalCallbackRegistry.registerCallback(clientId, (ExpectationForwardCallback) httpRequest ->
            request().withPath("/forwarded").withHeader(HOST.toString(), "127.0.0.1:" + mockServer.getLocalPort()));
    }

    @After
    public void stopServer() {
        LocalCallbackRegistry.unregisterCallback(clientId);
        stopQuietly(mockServer);
    }

    @Test
    public void shouldCreateAnExpectationWithAPrimaryResponseAndASecondaryForwardCallback() throws Exception {
        // when
        mockServer.upsert(
            new Expectation(request().withPath("/primary"))
                .thenRespond(response().withStatusCode(202).withPrimary(true))
                .thenForward(new HttpObjectCallback().withClientId(clientId))
        );

        // then - the server holds both actions, the response marked primary
        Expectation[] active = mockServer.retrieveActiveExpectations(request().withPath("/primary"));
        assertThat(active, arrayWithSize(1));
        assertThat(active[0].getHttpResponse().isPrimary(), is(true));
        assertThat(active[0].getHttpForwardObjectCallback(), notNullValue());

        // and - a matching request is answered by the primary action and also runs the secondary one
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        java.net.http.HttpResponse<String> answer = httpClient.send(
            java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mockServer.getLocalPort() + "/primary"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build(),
            BodyHandlers.ofString()
        );
        assertThat(answer.statusCode(), is(202));
        tryWaitForSuccess(() -> assertThat(mockServer.retrieveRecordedRequests(request().withPath("/forwarded")), arrayWithSize(1)), 200, 100, MILLISECONDS);
    }
}
