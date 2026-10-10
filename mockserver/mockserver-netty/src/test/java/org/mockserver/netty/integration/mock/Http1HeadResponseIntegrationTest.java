package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.RawHttp1Connection;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A mocked response to {@code HEAD} on HTTP/1.1 keeps the {@code content-length} a {@code GET} is sent and has no body:
 * a {@code GET} pipelined behind it is read straight after its head.
 */
public class Http1HeadResponseIntegrationTest {

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServer() {
        mockServer = new MockServer(configuration(), 0);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        mockServerClient.when(request().withPath("/mocked")).respond(response().withBody("served"));
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Test
    public void shouldSendAMockedResponseToHeadWithoutItsBody() throws Exception {
        try (RawHttp1Connection connection = new RawHttp1Connection(mockServer.getLocalPort())) {
            connection.send("HEAD", "/mocked");
            connection.sendGet("/mocked");

            assertThat(connection.readHead().toLowerCase(), containsString("content-length: 6"));
            RawHttp1Connection.Response get = connection.readResponse();
            assertThat(get.status, is(200));
            assertThat(get.body, is("served"));
        }
    }
}
