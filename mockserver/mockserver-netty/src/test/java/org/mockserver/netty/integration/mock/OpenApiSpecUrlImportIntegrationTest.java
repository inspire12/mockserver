package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Imports an OpenAPI spec by URL where the URL is served by the importing MockServer itself.
 *
 * <p>The server runs a single event loop, so the import request and the server's own fetch of the spec share it: the
 * import must not block that loop while the fetch is in flight.
 */
public class OpenApiSpecUrlImportIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String SPEC = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Self Hosted\",\"version\":\"1.0.0\"}," +
        "\"paths\":{\"/self-hosted/pets\":{\"get\":{\"operationId\":\"listPets\",\"responses\":{\"200\":{\"description\":\"ok\"," +
        "\"content\":{\"application/json\":{\"example\":[{\"id\":1}]}}}}}}}}";

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static int port;
    private static HttpClient httpClient;

    @BeforeClass
    public static void startServer() {
        mockServer = new MockServer(configuration().nioEventLoopThreadCount(1));
        port = mockServer.getLocalPort();
        mockServerClient = new MockServerClient("localhost", port);
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    @Test
    public void shouldImportSpecUrlServedByTheSameServerWithoutBlockingTheEventLoop() throws Exception {
        mockServerClient.when(request().withPath("/self-spec/.*")).respond(response().withHeader("Content-Type", "application/json").withBody(SPEC));

        for (int i = 0; i < 3; i++) {
            HttpResponse<String> imported = importSpecUrl("http://localhost:" + port + "/self-spec/" + i + "/openapi.json");

            assertThat("import " + i + ": " + imported.body(), imported.statusCode(), is(201));
        }
        assertThat(OBJECT_MAPPER.readTree(get("/self-hosted/pets").body()), is(OBJECT_MAPPER.readTree("[{\"id\":1}]")));
    }

    @Test
    public void shouldImportSpecUrlWithoutAFileExtension() throws Exception {
        mockServerClient.when(request().withPath("/v3/api-docs")).respond(response().withHeader("Content-Type", "application/json").withBody(SPEC));

        HttpResponse<String> imported = importSpecUrl("http://localhost:" + port + "/v3/api-docs?group=public");

        assertThat(imported.body(), imported.statusCode(), is(201));
        JsonNode created = OBJECT_MAPPER.readTree(imported.body());
        assertThat(created.size(), is(1));
        assertThat(get("/self-hosted/pets").statusCode(), is(200));
    }

    private static HttpResponse<String> importSpecUrl(String specUrl) throws Exception {
        String body = OBJECT_MAPPER.writeValueAsString(OBJECT_MAPPER.createObjectNode().put("specUrlOrPayload", specUrl));
        return httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mockserver/openapi"))
                .timeout(Duration.ofSeconds(20))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }
}
