package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.EmbeddingResponse;
import org.mockserver.model.Provider;
import org.mockserver.netty.MockServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.client.LlmMockBuilder.llmMock;
import static org.mockserver.model.Provider.AZURE_OPENAI;
import static org.mockserver.model.Provider.BEDROCK;
import static org.mockserver.model.Provider.GEMINI;
import static org.mockserver.model.Provider.OLLAMA;
import static org.mockserver.model.Provider.OPENAI;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Embedding requests over a real socket, one test per provider family. Each sends three
 * documents in the provider's own request shape (one request per document where the API takes a
 * single input), expects three distinct vectors, then embeds one document again and expects the
 * identical vector. The documents differ, so a codec that reads the wrong input field (and
 * embeds an empty string for each) or returns one vector for the batch fails.
 */
public class LlmEmbeddingsE2eTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DIMENSIONS = 64;
    private static final String[] DOCUMENTS = {
        "the cat sat on the mat",
        "quarterly financial report for the board",
        "how to repair a bicycle puncture"
    };

    private static int mockServerPort;
    private static MockServerClient mockServerClient;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeClass
    public static void startServer() {
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    private static void mock(String path, Provider provider, String model) {
        llmMock(path)
            .withProvider(provider)
            .withModel(model)
            .respondingWith(EmbeddingResponse.embedding().withDimensions(DIMENSIONS).withDeterministicFromInput(true).withSeed(11L))
            .applyTo(mockServerClient);
    }

    private static HttpResponse<String> send(String path, String body) throws Exception {
        return HTTP.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + mockServerPort + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode post(String path, String body) throws Exception {
        HttpResponse<String> response = send(path, body);
        assertThat(response.body(), response.statusCode(), is(200));
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static String documents(int count) throws Exception {
        String[] documents = new String[count];
        for (int i = 0; i < count; i++) {
            documents[i] = "document " + i;
        }
        return jsonArray(documents);
    }

    private static String jsonArray(String... texts) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(texts);
    }

    private static String json(String text) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(text);
    }

    private static List<Double> vector(JsonNode array) {
        List<Double> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asDouble()));
        assertThat(values, hasSize(DIMENSIONS));
        return values;
    }

    private static List<List<Double>> vectors(JsonNode arrays, Function<JsonNode, JsonNode> vectorOf) {
        List<List<Double>> result = new ArrayList<>();
        arrays.forEach(element -> result.add(vector(vectorOf.apply(element))));
        return result;
    }

    private static void assertThreeDistinct(List<List<Double>> vectors) {
        assertThat(vectors, hasSize(3));
        assertThat(vectors.get(0), is(not(vectors.get(1))));
        assertThat(vectors.get(0), is(not(vectors.get(2))));
        assertThat(vectors.get(1), is(not(vectors.get(2))));
    }

    @Test
    public void shouldEmbedOpenAiBatchOneVectorPerDocument() throws Exception {
        mock("/v1/embeddings", OPENAI, "text-embedding-3-small");

        JsonNode batch = post("/v1/embeddings", "{\"model\":\"text-embedding-3-small\",\"input\":" + jsonArray(DOCUMENTS) + "}");
        List<List<Double>> vectors = vectors(batch.get("data"), d -> d.get("embedding"));
        assertThreeDistinct(vectors);
        for (int i = 0; i < 3; i++) {
            assertThat(batch.get("data").get(i).get("index").asInt(), is(i));
        }

        JsonNode again = post("/v1/embeddings", "{\"model\":\"text-embedding-3-small\",\"input\":" + json(DOCUMENTS[2]) + "}");
        assertThat(vector(again.get("data").get(0).get("embedding")), is(vectors.get(2)));
    }

    @Test
    public void shouldEmbedAzureOpenAiBatchOneVectorPerDocument() throws Exception {
        mock("/openai/deployments/embed/embeddings", AZURE_OPENAI, "text-embedding-3-small");

        JsonNode batch = post("/openai/deployments/embed/embeddings", "{\"input\":" + jsonArray(DOCUMENTS) + "}");
        List<List<Double>> vectors = vectors(batch.get("data"), d -> d.get("embedding"));
        assertThreeDistinct(vectors);

        JsonNode again = post("/openai/deployments/embed/embeddings", "{\"input\":" + jsonArray(DOCUMENTS[1]) + "}");
        assertThat(vector(again.get("data").get(0).get("embedding")), is(vectors.get(1)));
    }

    @Test
    public void shouldEmbedGeminiBatchOneVectorPerRequest() throws Exception {
        mock("/v1beta/models/text-embedding-004:batchEmbedContents", GEMINI, "text-embedding-004");
        mock("/v1beta/models/text-embedding-004:embedContent", GEMINI, "text-embedding-004");

        StringBuilder requests = new StringBuilder();
        for (String document : DOCUMENTS) {
            requests.append(requests.length() > 0 ? "," : "")
                .append("{\"model\":\"models/text-embedding-004\",\"content\":{\"parts\":[{\"text\":").append(json(document)).append("}]}}");
        }
        JsonNode batch = post("/v1beta/models/text-embedding-004:batchEmbedContents", "{\"requests\":[" + requests + "]}");
        List<List<Double>> vectors = vectors(batch.get("embeddings"), e -> e.get("values"));
        assertThreeDistinct(vectors);

        JsonNode single = post("/v1beta/models/text-embedding-004:embedContent",
            "{\"model\":\"models/text-embedding-004\",\"content\":{\"parts\":[{\"text\":" + json(DOCUMENTS[0]) + "}]}}");
        assertThat(vector(single.get("embedding").get("values")), is(vectors.get(0)));
    }

    @Test
    public void shouldEmbedBedrockTitanOneVectorPerDocument() throws Exception {
        String path = "/model/amazon.titan-embed-text-v2:0/invoke";
        mock(path, BEDROCK, null);

        List<List<Double>> vectors = new ArrayList<>();
        for (String document : DOCUMENTS) {
            JsonNode response = post(path, "{\"inputText\":" + json(document) + "}");
            vectors.add(vector(response.get("embedding")));
            assertThat(vector(response.get("embeddingsByType").get("float")), is(vectors.get(vectors.size() - 1)));
        }
        assertThreeDistinct(vectors);

        JsonNode again = post(path, "{\"inputText\":" + json(DOCUMENTS[1]) + "}");
        assertThat(vector(again.get("embedding")), is(vectors.get(1)));
    }

    @Test
    public void shouldEmbedBedrockCohereBatchOneVectorPerText() throws Exception {
        String path = "/model/us.cohere.embed-v4:0/invoke";
        mock(path, BEDROCK, null);

        JsonNode batch = post(path, "{\"input_type\":\"search_document\",\"texts\":" + jsonArray(DOCUMENTS) + "}");
        assertThat(batch.get("response_type").asText(), is("embeddings_floats"));
        assertThat(batch.get("texts").size(), is(3));
        List<List<Double>> vectors = vectors(batch.get("embeddings"), e -> e);
        assertThreeDistinct(vectors);

        JsonNode byType = post(path, "{\"input_type\":\"search_query\",\"texts\":" + jsonArray(DOCUMENTS[2]) + ",\"embedding_types\":[\"float\"]}");
        assertThat(byType.get("response_type").asText(), is("embeddings_by_type"));
        assertThat(vector(byType.get("embeddings").get("float").get(0)), is(vectors.get(2)));
    }

    @Test
    public void shouldEmbedOllamaBatchAndLegacyPrompt() throws Exception {
        mock("/api/embed", OLLAMA, "nomic-embed-text");
        mock("/api/embeddings", OLLAMA, "nomic-embed-text");

        JsonNode batch = post("/api/embed", "{\"model\":\"all-minilm\",\"input\":" + jsonArray(DOCUMENTS) + "}");
        assertThat(batch.get("model").asText(), is("all-minilm"));
        List<List<Double>> vectors = vectors(batch.get("embeddings"), e -> e);
        assertThreeDistinct(vectors);

        List<List<Double>> legacy = new ArrayList<>();
        for (String document : DOCUMENTS) {
            legacy.add(vector(post("/api/embeddings", "{\"model\":\"all-minilm\",\"prompt\":" + json(document) + "}").get("embedding")));
        }
        assertThat("the legacy endpoint embeds prompt, giving the same vectors as /api/embed", legacy, is(vectors));
    }

    @Test
    public void shouldGiveTheSameTextTheSameVectorOnEveryProvider() throws Exception {
        mock("/v1/embeddings", OPENAI, null);
        mock("/v1beta/models/text-embedding-004:embedContent", GEMINI, null);
        mock("/model/amazon.titan-embed-text-v2:0/invoke", BEDROCK, null);
        mock("/model/cohere.embed-english-v3/invoke", BEDROCK, null);
        mock("/api/embed", OLLAMA, null);
        String text = DOCUMENTS[0];

        List<Double> openAi = vector(post("/v1/embeddings", "{\"input\":" + json(text) + "}").get("data").get(0).get("embedding"));
        assertThat(vector(post("/v1beta/models/text-embedding-004:embedContent",
            "{\"content\":{\"parts\":[{\"text\":" + json(text) + "}]}}").get("embedding").get("values")), is(openAi));
        assertThat(vector(post("/model/amazon.titan-embed-text-v2:0/invoke",
            "{\"inputText\":" + json(text) + "}").get("embedding")), is(openAi));
        assertThat(vector(post("/model/cohere.embed-english-v3/invoke",
            "{\"texts\":" + jsonArray(text) + "}").get("embeddings").get(0)), is(openAi));
        assertThat(vector(post("/api/embed", "{\"input\":" + json(text) + "}").get("embeddings").get(0)), is(openAi));
    }

    @Test
    public void shouldRejectARequestOverTheLimitsWithTheProvidersErrorAndKeepServing() throws Exception {
        mock("/v1/embeddings", OPENAI, null);
        String cohere = "/model/cohere.embed-v4:0/invoke";
        mock(cohere, BEDROCK, null);

        assertThat(post("/v1/embeddings", "{\"input\":" + documents(2048) + "}").get("data").size(), is(2048));

        HttpResponse<String> tooManyInputs = send("/v1/embeddings", "{\"input\":" + documents(2049) + "}");
        assertThat(tooManyInputs.body(), tooManyInputs.statusCode(), is(400));
        JsonNode error = OBJECT_MAPPER.readTree(tooManyInputs.body()).get("error");
        assertThat(error.get("type").asText(), is("invalid_request_error"));
        assertThat(error.get("param").asText(), is("input"));

        HttpResponse<String> tooManyDimensions = send("/v1/embeddings", "{\"input\":\"x\",\"dimensions\":2147483647}");
        assertThat(tooManyDimensions.body(), tooManyDimensions.statusCode(), is(400));
        assertThat(OBJECT_MAPPER.readTree(tooManyDimensions.body()).get("error").get("param").asText(), is("dimensions"));

        HttpResponse<String> tooManyTexts = send(cohere, "{\"texts\":" + documents(97) + "}");
        assertThat(tooManyTexts.body(), tooManyTexts.statusCode(), is(400));
        assertThat(tooManyTexts.headers().firstValue("x-amzn-ErrorType").orElse(null), is("ValidationException"));
        assertThat(OBJECT_MAPPER.readTree(tooManyTexts.body()).get("message").asText(), is("texts must have at most 96 entries, got 97"));

        assertThat(post("/v1/embeddings", "{\"input\":" + jsonArray(DOCUMENTS) + "}").get("data").size(), is(3));
    }
}
