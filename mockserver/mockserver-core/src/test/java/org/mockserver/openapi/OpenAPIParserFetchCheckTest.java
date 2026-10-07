package org.mockserver.openapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.proxyconfiguration.HostLookups;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * forwardProxyBlockPrivateNetworks applies to what the OpenAPI parser fetches: the spec URL, each remote $ref and
 * each redirect, for OpenAPI 3 and Swagger 2 specs. Each test uses its own paths, so no parse is answered from the
 * parser's cache.
 */
public class OpenAPIParserFetchCheckTest {

    private static final InetAddress PUBLIC = address();
    private static final MockServerLogger LOGGER = new MockServerLogger(OpenAPIParserFetchCheckTest.class);

    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    private final Map<String, String> redirects = new ConcurrentHashMap<>();
    private final Map<String, String> documents = new ConcurrentHashMap<>();
    private final String prefix = "/" + UUID.randomUUID();
    private HttpServer server;

    private static InetAddress address() {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) 203, 0, 113, 10});
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("localhost"), 0), 0);
        server.createContext("/", this::answer);
        server.start();
    }

    @After
    public void stopServer() {
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        String location = redirects.get(path);
        String document = documents.get(path);
        if (location != null) {
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302, -1);
        } else if (document != null) {
            byte[] body = document.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } else {
            exchange.sendResponseHeaders(404, -1);
        }
        exchange.close();
    }

    private int requestsFor(String path) {
        AtomicInteger count = requests.get(path);
        return count != null ? count.get() : 0;
    }

    private String url(String host, String path) {
        return "http://" + host + ":" + server.getAddress().getPort() + path;
    }

    private static String openApi3(String schemaRef) {
        return "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"pets\",\"version\":\"1\"},"
            + "\"paths\":{\"/pets\":{\"get\":{\"operationId\":\"listPets\",\"responses\":{\"200\":{\"description\":\"ok\","
            + "\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"" + schemaRef + "\"}}}}}}}},"
            + "\"components\":{\"schemas\":{\"Pet\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}}}}";
    }

    private static String swagger2(String schemaRef) {
        return "{\"swagger\":\"2.0\",\"info\":{\"title\":\"pets\",\"version\":\"1\"},"
            + "\"paths\":{\"/pets\":{\"get\":{\"operationId\":\"listPets\",\"produces\":[\"application/json\"],"
            + "\"responses\":{\"200\":{\"description\":\"ok\",\"schema\":{\"$ref\":\"" + schemaRef + "\"}}}}}}}";
    }

    private static final String PET = "{\"Pet\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}}";

    private static Configuration blocking(boolean block) {
        return configuration().forwardProxyBlockPrivateNetworks(block);
    }

    @Test
    public void shouldRefuseASpecUrlToABlockedAddress() {
        documents.put(prefix + "/spec.json", openApi3("#/components/schemas/Pet"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> OpenAPIParser.buildOpenAPI(url("127.0.0.1", prefix + "/spec.json"), LOGGER, blocking(true)));

        assertThat(refused.getMessage(), containsString(OpenAPIParser.OPEN_API_LOAD_ERROR));
        assertThat(refused.getMessage(), containsString("loopback"));
        assertThat(requestsFor(prefix + "/spec.json"), is(0));
    }

    @Test
    public void shouldFetchASpecUrlToAPrivateAddressWithTheSettingOff() {
        documents.put(prefix + "/spec.json", openApi3("#/components/schemas/Pet"));

        OpenAPI openAPI = OpenAPIParser.buildOpenAPI(url("127.0.0.1", prefix + "/spec.json"), LOGGER, blocking(false));

        assertThat(openAPI.getPaths().get("/pets"), notNullValue());
        assertThat(requestsFor(prefix + "/spec.json") > 0, is(true));
    }

    @Test
    public void shouldRefuseARemoteRefToABlockedAddress() {
        documents.put(prefix + "/pet.json", PET);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> OpenAPIParser.buildOpenAPI(openApi3(url("127.0.0.1", prefix + "/pet.json") + "#/Pet"), LOGGER, blocking(true)));

        assertThat(refused.getMessage(), containsString("loopback"));
        assertThat(requestsFor(prefix + "/pet.json"), is(0));
    }

    @Test
    public void shouldFetchARemoteRefToAPrivateAddressWithTheSettingOff() {
        documents.put(prefix + "/pet.json", PET);

        OpenAPI openAPI = OpenAPIParser.buildOpenAPI(openApi3(url("127.0.0.1", prefix + "/pet.json") + "#/Pet"), LOGGER, blocking(false));

        assertThat(openAPI.getPaths().get("/pets"), notNullValue());
        assertThat(requestsFor(prefix + "/pet.json"), is(1));
    }

    @Test
    public void shouldRefuseARedirectToABlockedAddress() {
        documents.put(prefix + "/spec.json", openApi3("#/components/schemas/Pet"));
        redirects.put(prefix + "/moved/spec.json", url("127.0.0.1", prefix + "/spec.json"));

        // the check sees "localhost" as a public address; the connection reaches the local server, which redirects
        try (HostLookups ignored = HostLookups.answer("localhost", PUBLIC)) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> OpenAPIParser.buildOpenAPI(url("localhost", prefix + "/moved/spec.json"), LOGGER, blocking(true)));

            assertThat(refused.getMessage(), containsString("loopback"));
        }
        assertThat(requestsFor(prefix + "/moved/spec.json") > 0, is(true));
        assertThat(requestsFor(prefix + "/spec.json"), is(0));
    }

    @Test
    public void shouldRefuseASwagger2RemoteRefToABlockedAddress() {
        documents.put(prefix + "/swagger.json", swagger2(url("127.0.0.1", prefix + "/pet.json") + "#/Pet"));
        documents.put(prefix + "/pet.json", PET);

        try (HostLookups ignored = HostLookups.answer("localhost", PUBLIC)) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> OpenAPIParser.buildOpenAPI(url("localhost", prefix + "/swagger.json"), LOGGER, blocking(true)));

            assertThat(refused.getMessage(), containsString("loopback"));
        }
        assertThat(requestsFor(prefix + "/swagger.json") > 0, is(true));
        assertThat(requestsFor(prefix + "/pet.json"), is(0));
    }

    @Test
    public void shouldFetchASwagger2RemoteRefWithTheSettingOff() {
        documents.put(prefix + "/swagger.json", swagger2(url("127.0.0.1", prefix + "/pet.json") + "#/Pet"));
        documents.put(prefix + "/pet.json", PET);

        OpenAPI openAPI = OpenAPIParser.buildOpenAPI(url("localhost", prefix + "/swagger.json"), LOGGER, blocking(false));

        assertThat(openAPI.getPaths().get("/pets"), notNullValue());
        assertThat(requestsFor(prefix + "/pet.json") > 0, is(true));
    }
}
