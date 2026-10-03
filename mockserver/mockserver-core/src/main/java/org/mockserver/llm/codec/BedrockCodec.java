package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.model.*;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.mockserver.model.HttpResponse.response;

/**
 * Codec for AWS Bedrock Runtime. One {@link Provider#BEDROCK} covers two Bedrock wire APIs,
 * selected per request by {@link #isConverse(HttpRequest)}:
 * <ul>
 *   <li><strong>InvokeModel</strong> ({@code /model/{id}/invoke},
 *       {@code /model/{id}/invoke-with-response-stream}) for Anthropic Claude (version
 *       bedrock-2023-05-31): the response is the plain Anthropic Messages body, so this codec
 *       delegates to {@link AnthropicCodec}. Streaming wraps each Anthropic chunk as an AWS
 *       event-stream {@code chunk} frame whose payload is {@code {"bytes":"<base64(chunkJson)>"}}
 *       ({@link StreamingFormat#AWS_EVENT_STREAM}, encoded by {@link BedrockEventStreamEncoder}).</li>
 *   <li><strong>Converse</strong> ({@code /model/{id}/converse}, {@code /model/{id}/converse-stream}):
 *       the model-agnostic Converse envelope, delegated to {@link BedrockConverseCodec}
 *       ({@link StreamingFormat#AWS_CONVERSE_EVENT_STREAM} for streaming).</li>
 * </ul>
 * The request-less {@link #encode(Completion, String)} / {@link #encodeStreaming(Completion, String, StreamingPhysics)}
 * overloads keep the original InvokeModel behaviour.
 * <p>
 * <strong>SigV4 signing</strong> applies to the runtime client path ({@code BedrockLlmClient}),
 * not to this codec.
 */
public class BedrockCodec implements ProviderCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Pattern CONVERSE_PATH = Pattern.compile("/converse(-stream)?/*$");
    private static final Pattern INVOKE_PATH = Pattern.compile("/invoke(-with-response-stream)?/*$");

    private final AnthropicCodec delegate = new AnthropicCodec();
    private final BedrockConverseCodec converse = new BedrockConverseCodec();

    /**
     * Whether this request targets the Converse API rather than InvokeModel. The path decides
     * when it names either API; otherwise (a gateway or custom mock path) a Converse-shaped
     * request body selects Converse. Anything else, including a {@code null} request, is
     * InvokeModel, which preserves the behaviour that predates Converse support.
     */
    public static boolean isConverse(HttpRequest request) {
        if (request == null) {
            return false;
        }
        String path = request.getPath() != null ? request.getPath().getValue() : null;
        if (path != null) {
            String lowerPath = path.toLowerCase(Locale.ROOT);
            if (CONVERSE_PATH.matcher(lowerPath).find()) {
                return true;
            }
            if (INVOKE_PATH.matcher(lowerPath).find()) {
                return false;
            }
        }
        try {
            String body = request.getBodyAsText();
            return body != null && !body.isEmpty() && BedrockConverseCodec.isConverseShaped(OBJECT_MAPPER.readTree(body));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Provider provider() {
        return Provider.BEDROCK;
    }

    @Override
    public String apiVersion() {
        return "bedrock-2023-05-31";
    }

    @Override
    public StreamingFormat streamingFormat() {
        return StreamingFormat.AWS_EVENT_STREAM;
    }

    @Override
    public StreamingFormat streamingFormat(HttpRequest request) {
        return isConverse(request) ? converse.streamingFormat() : streamingFormat();
    }

    @Override
    public HttpResponse encode(Completion completion, String model, HttpRequest request) {
        return isConverse(request) ? converse.encode(completion, model) : encode(completion, model);
    }

    @Override
    public List<SseEvent> encodeStreaming(Completion completion, String model, StreamingPhysics physics, HttpRequest request) {
        return isConverse(request) ? converse.encodeStreaming(completion, model, physics) : encodeStreaming(completion, model, physics);
    }

    @Override
    public HttpResponse encode(Completion completion, String model) {
        return delegate.encode(completion, model);
    }

    /**
     * Encode a streaming completion as event-stream chunks.
     * <p>
     * Delegates to {@link AnthropicCodec#encodeStreaming} to produce the Anthropic
     * SSE events, then transforms each event's {@code data} payload into a form
     * suitable for event-stream binary wrapping. The downstream write handler
     * performs the actual binary encoding via {@link BedrockEventStreamEncoder}.
     * <p>
     * Each event's data becomes one event-stream chunk whose payload is
     * {@code {"bytes":"<base64(data)>"}}.
     */
    @Override
    public List<SseEvent> encodeStreaming(Completion completion, String model, StreamingPhysics physics) {
        // Get the Anthropic-format SSE events (with physics already applied)
        List<SseEvent> anthropicEvents = delegate.encodeStreaming(completion, model, physics);

        // Return events as-is — the data payloads are the raw Anthropic chunk JSON.
        // The HttpSseResponseActionHandler will wrap each chunk in event-stream
        // binary framing (including base64 encoding) when it detects
        // StreamingFormat.AWS_EVENT_STREAM.
        return anthropicEvents;
    }

    @Override
    public ParsedConversation decode(HttpRequest request) {
        return isConverse(request) ? converse.decode(request) : delegate.decode(request);
    }

    /**
     * Bedrock embeddings without a model hint default to the Amazon Titan shape.
     * Use the model-aware overload to select Cohere-on-Bedrock.
     */
    @Override
    public HttpResponse encodeEmbedding(EmbeddingResponse embedding, String input) {
        return encodeEmbedding(embedding, input, null);
    }

    /**
     * Encodes a Bedrock {@code InvokeModel} embedding response. Bedrock's
     * embedding wire shape is model-family specific:
     * <ul>
     *   <li><strong>Amazon Titan</strong> ({@code amazon.titan-embed-text-*}) —
     *       {@code {"embedding":[...],"inputTextTokenCount":N}}</li>
     *   <li><strong>Cohere</strong> ({@code cohere.embed-*}) —
     *       {@code {"embeddings":[[...]]}}</li>
     * </ul>
     * When {@code model} starts with {@code cohere} the Cohere shape is emitted,
     * otherwise the Titan shape (the Bedrock default). Both default to 1024
     * dimensions.
     */
    @Override
    public HttpResponse encodeEmbedding(EmbeddingResponse embedding, String input, String model) {
        double[] vector = EmbeddingVectors.build(embedding, input, 1024);
        ObjectNode root = OBJECT_MAPPER.createObjectNode();

        boolean cohere = model != null && model.toLowerCase().startsWith("cohere");
        if (cohere) {
            ArrayNode embeddings = root.putArray("embeddings");
            ArrayNode first = embeddings.addArray();
            for (double v : vector) {
                first.add(v);
            }
        } else {
            ArrayNode embeddingArray = root.putArray("embedding");
            for (double v : vector) {
                embeddingArray.add(v);
            }
            root.put("inputTextTokenCount", EmbeddingVectors.approximateTokens(input));
        }

        try {
            String json = OBJECT_MAPPER.writeValueAsString(root);
            return response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode Bedrock embedding response", e);
        }
    }
}
