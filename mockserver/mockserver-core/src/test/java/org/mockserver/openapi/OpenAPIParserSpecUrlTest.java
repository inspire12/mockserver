package org.mockserver.openapi;

import com.sun.net.httpserver.HttpServer;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * A spec reference is recognised by being a URL, not only by a .json / .yaml / .yml suffix, so specs served from
 * paths like Spring's {@code /v3/api-docs} or from URLs with a query string are fetched rather than parsed as inline
 * content.
 */
public class OpenAPIParserSpecUrlTest {

    private static final String SPEC = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Docs\",\"version\":\"1\"}," +
        "\"paths\":{\"/docs-pets\":{\"get\":{\"operationId\":\"listDocsPets\",\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}";

    @Test
    public void shouldRecogniseUrlsWhateverTheirSuffix() {
        assertThat(OpenAPIParser.isSpecUrl("http://localhost:8080/v3/api-docs"), is(true));
        assertThat(OpenAPIParser.isSpecUrl("https://example.com/openapi?format=json"), is(true));
        assertThat(OpenAPIParser.isSpecUrl("HTTPS://example.com/spec"), is(true));
        assertThat(OpenAPIParser.isSpecUrl("file:/specs/petstore"), is(true));
    }

    @Test
    public void shouldStillRecogniseFileSuffixes() {
        assertThat(OpenAPIParser.isSpecUrl("org/mockserver/openapi/openapi_petstore_example.json"), is(true));
        assertThat(OpenAPIParser.isSpecUrl("specs/petstore.yaml"), is(true));
        assertThat(OpenAPIParser.isSpecUrl("specs/petstore.yml"), is(true));
    }

    @Test
    public void shouldNotTreatInlineSpecsAsUrls() {
        assertThat(OpenAPIParser.isSpecUrl(SPEC), is(false));
        assertThat(OpenAPIParser.isSpecUrl("openapi: 3.0.0\ninfo:\n  title: http://example.com\n"), is(false));
        assertThat(OpenAPIParser.isSpecUrl("http://example.com/a spec with spaces"), is(false));
        assertThat(OpenAPIParser.isSpecUrl("ftp://example.com/spec"), is(false));
        assertThat(OpenAPIParser.isSpecUrl(null), is(false));
    }

    @Test
    public void shouldFetchAndParseASpecFromAUrlWithoutASuffix() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("localhost"), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = SPEC.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/" + UUID.randomUUID() + "/v3/api-docs?group=public";

            OpenAPI openAPI = OpenAPIParser.buildOpenAPI(url, new MockServerLogger(OpenAPIParserSpecUrlTest.class), configuration());

            assertThat(openAPI.getPaths().get("/docs-pets").getGet().getOperationId(), is("listDocsPets"));
        } finally {
            server.stop(0);
        }
    }
}
