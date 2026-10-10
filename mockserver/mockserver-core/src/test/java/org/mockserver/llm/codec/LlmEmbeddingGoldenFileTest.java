package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.http.HttpLlmResponseActionHandler;
import org.mockserver.model.EmbeddingResponse;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpLlmResponse.llmResponse;
import static org.mockserver.model.HttpRequest.request;

/**
 * Golden files for every embedding wire shape, under {@code src/test/resources/llm/fixtures/embeddings/}.
 * Each case sends a fixed request in the provider's real request shape through the action
 * handler. Vectors are replaced by {@code "<vector:N>"} (and base64 vectors by
 * {@code "<base64-float32:N>"}) and Cohere's {@code id} by {@code "<id>"}, so the goldens pin the
 * envelope, the number and length of vectors, and the token counts. Regenerate with
 * {@code -Dmockserver.updateLlmGoldens=true}, as for {@link LlmCodecGoldenFileTest}.
 */
public class LlmEmbeddingGoldenFileTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final String THREE_TEXTS = "[\"the cat sat on the mat\",\"quarterly financial report\",\"the cat sat on the mat\"]";

    private static final Object[][] CASES = {
        {"openai-batch", Provider.OPENAI, "text-embedding-3-small", "/v1/embeddings",
            "{\"model\":\"text-embedding-3-large\",\"input\":" + THREE_TEXTS + "}"},
        {"openai-base64", Provider.OPENAI, null, "/v1/embeddings",
            "{\"model\":\"text-embedding-3-small\",\"input\":[\"alpha\",\"beta\"],\"encoding_format\":\"base64\",\"dimensions\":256}"},
        {"azure-openai-batch", Provider.AZURE_OPENAI, "text-embedding-3-small", "/openai/deployments/embed/embeddings",
            "{\"input\":" + THREE_TEXTS + "}"},
        {"gemini-embed-content", Provider.GEMINI, null, "/v1beta/models/text-embedding-004:embedContent",
            "{\"model\":\"models/text-embedding-004\",\"content\":{\"parts\":[{\"text\":\"What is the meaning of life?\"}]}}"},
        {"gemini-batch", Provider.GEMINI, null, "/v1beta/models/text-embedding-004:batchEmbedContents",
            "{\"requests\":[{\"model\":\"models/text-embedding-004\",\"content\":{\"parts\":[{\"text\":\"alpha\"}]}},"
                + "{\"model\":\"models/text-embedding-004\",\"content\":{\"parts\":[{\"text\":\"beta\"}]},\"outputDimensionality\":256}]}"},
        {"ollama-embed", Provider.OLLAMA, null, "/api/embed",
            "{\"model\":\"all-minilm\",\"input\":[\"Why is the sky blue?\",\"Why is the grass green?\"]}"},
        {"ollama-embeddings-legacy", Provider.OLLAMA, null, "/api/embeddings",
            "{\"model\":\"all-minilm\",\"prompt\":\"Here is an article about llamas...\"}"},
        {"bedrock-titan-v1", Provider.BEDROCK, null, "/model/amazon.titan-embed-text-v1/invoke",
            "{\"inputText\":\"What are the different services that you offer?\"}"},
        {"bedrock-titan-v2", Provider.BEDROCK, null, "/model/amazon.titan-embed-text-v2:0/invoke",
            "{\"inputText\":\"What are the different services that you offer?\",\"dimensions\":512}"},
        {"bedrock-titan-v2-binary", Provider.BEDROCK, null, "/model/amazon.titan-embed-text-v2:0/invoke",
            "{\"inputText\":\"What are the different services that you offer?\",\"embeddingTypes\":[\"binary\"]}"},
        {"bedrock-titan-image", Provider.BEDROCK, null, "/model/amazon.titan-embed-image-v1/invoke",
            "{\"inputText\":\"a red bicycle\"}"},
        {"bedrock-cohere-v3", Provider.BEDROCK, null, "/model/cohere.embed-english-v3/invoke",
            "{\"texts\":[\"hello world\",\"this is a test\"],\"input_type\":\"search_document\"}"},
        {"bedrock-cohere-v4-by-type", Provider.BEDROCK, null, "/model/us.cohere.embed-v4:0/invoke",
            "{\"texts\":[\"hello world\",\"this is a test\"],\"input_type\":\"search_document\",\"embedding_types\":[\"int8\",\"float\"]}"},
        {"bedrock-cohere-v4-inputs", Provider.BEDROCK, null, "/model/cohere.embed-v4:0/invoke",
            "{\"input_type\":\"search_document\",\"inputs\":[{\"content\":[{\"type\":\"text\",\"text\":\"Quarterly ARR growth chart\"}]}],\"output_dimension\":512}"},
    };

    @Test
    public void shouldMatchEmbeddingGoldenFiles() throws Exception {
        boolean updateMode = Boolean.parseBoolean(System.getProperty("mockserver.updateLlmGoldens", System.getenv("MOCKSERVER_UPDATE_LLM_GOLDENS")));
        Path dir = fixturesDir();
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        List<String> failures = new ArrayList<>();

        for (Object[] testCase : CASES) {
            String name = (String) testCase[0];
            HttpResponse response = handler.handle(
                llmResponse().withProvider((Provider) testCase[1]).withModel((String) testCase[2]).withEmbedding(EmbeddingResponse.embedding()),
                request().withMethod("POST").withPath((String) testCase[3]).withBody((String) testCase[4])
            );
            JsonNode tree = OBJECT_MAPPER.readTree(response.getBodyAsString());
            normalize(tree);
            String actual = response.getStatusCode() + "\n" + OBJECT_MAPPER.writeValueAsString(tree) + "\n";
            Path golden = dir.resolve(name + ".json");
            if (updateMode) {
                Files.createDirectories(dir);
                Files.write(golden, actual.getBytes(StandardCharsets.UTF_8));
            } else if (!Files.exists(golden)) {
                failures.add(name + ": golden file missing at " + golden);
            } else {
                String expected = new String(Files.readAllBytes(golden), StandardCharsets.UTF_8);
                if (!expected.equals(actual)) {
                    failures.add(name + ": wire format drifted\n--- expected\n" + expected + "--- actual\n" + actual);
                }
            }
        }
        assertThat(String.join("\n\n", failures), failures, is(empty()));
    }

    private static Path fixturesDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path candidate = cwd.resolve("src/test/resources/llm/fixtures");
        if (!Files.isDirectory(candidate)) {
            candidate = cwd.resolve("mockserver-core/src/test/resources/llm/fixtures");
        }
        return candidate.resolve("embeddings");
    }

    private static void normalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            List<String> names = new ArrayList<>();
            fields.forEachRemaining(entry -> names.add(entry.getKey()));
            for (String name : names) {
                JsonNode value = object.get(name);
                if ("id".equals(name) && value.isTextual()) {
                    object.put(name, "<id>");
                } else if ("embedding".equals(name) && value.isTextual()) {
                    object.put(name, "<base64-float32:" + Base64.getDecoder().decode(value.asText()).length / Float.BYTES + ">");
                } else if (isNumberArray(value)) {
                    object.put(name, "<vector:" + value.size() + ">");
                } else {
                    normalize(value);
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                if (isNumberArray(array.get(i))) {
                    array.set(i, array.textNode("<vector:" + array.get(i).size() + ">"));
                } else {
                    normalize(array.get(i));
                }
            }
        }
    }

    private static boolean isNumberArray(JsonNode node) {
        if (!node.isArray() || node.size() == 0) {
            return false;
        }
        for (JsonNode element : node) {
            if (!element.isNumber()) {
                return false;
            }
        }
        return true;
    }
}
