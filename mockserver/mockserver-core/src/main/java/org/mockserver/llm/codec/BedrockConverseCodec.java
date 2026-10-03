package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.llm.StreamingPhysicsExpander;
import org.mockserver.llm.TokenCounter;
import org.mockserver.model.*;
import org.mockserver.uuid.UUIDService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.SseEvent.sseEvent;

/**
 * Codec for the AWS Bedrock Runtime <strong>Converse</strong> API
 * ({@code POST /model/{modelId}/converse}) and <strong>ConverseStream</strong> API
 * ({@code POST /model/{modelId}/converse-stream}), service API version 2023-09-30.
 * <p>
 * Converse is Bedrock's model-agnostic message API, so its wire shape is the same for
 * every model family (Claude, Titan, Nova, Llama, ...):
 * <pre>
 * {"output":{"message":{"role":"assistant","content":[{"text":"..."},{"toolUse":{...}}]}},
 *  "stopReason":"end_turn","usage":{"inputTokens":N,"outputTokens":N,"totalTokens":N},
 *  "metrics":{"latencyMs":0}}
 * </pre>
 * <p>
 * <strong>Streaming:</strong> ConverseStream uses AWS event-stream framing, but unlike
 * {@code InvokeModelWithResponseStream} each frame's {@code :event-type} header is the event
 * name ({@code messageStart}, {@code contentBlockStart}, {@code contentBlockDelta},
 * {@code contentBlockStop}, {@code messageStop}, {@code metadata}) and its payload is that
 * event's raw JSON &mdash; there is no {@code {"bytes":"<base64>"}} wrapper. Each
 * {@link SseEvent} returned here carries the event name in {@code event} and the payload in
 * {@code data}; {@link StreamingFormat#AWS_CONVERSE_EVENT_STREAM} tells the write handler to
 * frame them that way.
 * <p>
 * Not registered in {@link org.mockserver.llm.ProviderCodecRegistry} on its own:
 * {@link BedrockCodec} selects it per request (see {@link BedrockCodec#isConverse}).
 */
public class BedrockConverseCodec implements ProviderCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Converse ContentBlock union members that may appear in a request message. */
    private static final Set<String> CONTENT_BLOCK_MEMBERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "text", "image", "document", "video", "audio", "toolUse", "toolResult", "guardContent",
        "cachePoint", "reasoningContent", "citationsContent", "searchResult")));

    /** Top-level request fields that exist only in the Converse request shape. */
    private static final Set<String> CONVERSE_ONLY_REQUEST_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "inferenceConfig", "toolConfig", "additionalModelRequestFields", "additionalModelResponseFieldPaths",
        "guardrailConfig", "promptVariables", "performanceConfig", "outputConfig")));

    @Override
    public Provider provider() {
        return Provider.BEDROCK;
    }

    @Override
    public String apiVersion() {
        return "bedrock-runtime-2023-09-30";
    }

    @Override
    public StreamingFormat streamingFormat() {
        return StreamingFormat.AWS_CONVERSE_EVENT_STREAM;
    }

    @Override
    public HttpResponse encode(Completion completion, String model) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        ObjectNode message = root.putObject("output").putObject("message");
        message.put("role", "assistant");
        ArrayNode content = message.putArray("content");

        String reasoningText = completion.getReasoningText();
        if (reasoningText != null && !reasoningText.isEmpty()) {
            ObjectNode reasoning = content.addObject().putObject("reasoningContent").putObject("reasoningText");
            reasoning.put("text", reasoningText);
            String signature = completion.getReasoningSignature();
            if (signature != null && !signature.isEmpty()) {
                reasoning.put("signature", signature);
            }
        }

        String text = completion.getText();
        if (text != null && !text.isEmpty()) {
            content.addObject().put("text", text);
        }

        List<ToolUse> toolCalls = completion.getToolCalls();
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
        if (hasToolCalls) {
            for (ToolUse toolCall : toolCalls) {
                ObjectNode toolUse = content.addObject().putObject("toolUse");
                toolUse.put("toolUseId", toolUseId(toolCall));
                toolUse.put("name", toolCall.getName());
                toolUse.set("input", toolInput(toolCall.getArguments()));
            }
        }

        root.put("stopReason", mapStopReason(completion.getStopReason(), hasToolCalls));
        root.set("usage", usageNode(completion.getUsage()));
        root.putObject("metrics").put("latencyMs", 0);

        try {
            return response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody(OBJECT_MAPPER.writeValueAsString(root));
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode Bedrock Converse response", e);
        }
    }

    @Override
    public List<SseEvent> encodeStreaming(Completion completion, String model, StreamingPhysics physics) {
        List<SseEvent> events = new ArrayList<>();
        events.add(event("messageStart", OBJECT_MAPPER.createObjectNode().put("role", "assistant")));

        int index = 0;

        // ContentBlockStart has no text/reasoning member, so text and reasoning blocks go
        // straight to deltas; only toolUse blocks open with a contentBlockStart.
        String reasoningText = completion.getReasoningText();
        if (reasoningText != null && !reasoningText.isEmpty()) {
            ObjectNode reasoningDelta = OBJECT_MAPPER.createObjectNode();
            reasoningDelta.putObject("reasoningContent").put("text", reasoningText);
            events.add(event("contentBlockDelta", blockDelta(index, reasoningDelta)));
            String signature = completion.getReasoningSignature();
            if (signature != null && !signature.isEmpty()) {
                ObjectNode signatureDelta = OBJECT_MAPPER.createObjectNode();
                signatureDelta.putObject("reasoningContent").put("signature", signature);
                events.add(event("contentBlockDelta", blockDelta(index, signatureDelta)));
            }
            events.add(event("contentBlockStop", blockIndex(index)));
            index++;
        }

        String text = completion.getText();
        if (text != null && !text.isEmpty()) {
            for (String token : TokenCounter.streamingTextTokens(text, physics)) {
                if (!token.isEmpty()) {
                    events.add(event("contentBlockDelta", blockDelta(index, OBJECT_MAPPER.createObjectNode().put("text", token))));
                }
            }
            events.add(event("contentBlockStop", blockIndex(index)));
            index++;
        }

        List<ToolUse> toolCalls = completion.getToolCalls();
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
        if (hasToolCalls) {
            for (ToolUse toolCall : toolCalls) {
                ObjectNode start = blockIndex(index);
                ObjectNode toolUseStart = start.putObject("start").putObject("toolUse");
                toolUseStart.put("toolUseId", toolUseId(toolCall));
                toolUseStart.put("name", toolCall.getName());
                events.add(event("contentBlockStart", start));

                // ToolUseBlockDelta.input is the (partial) tool input as a JSON *string*.
                ObjectNode inputDelta = OBJECT_MAPPER.createObjectNode();
                inputDelta.putObject("toolUse").put("input", toolCall.getArguments() != null ? toolCall.getArguments() : "{}");
                events.add(event("contentBlockDelta", blockDelta(index, inputDelta)));
                events.add(event("contentBlockStop", blockIndex(index)));
                index++;
            }
        }

        events.add(event("messageStop", OBJECT_MAPPER.createObjectNode()
            .put("stopReason", mapStopReason(completion.getStopReason(), hasToolCalls))));

        ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
        metadata.set("usage", usageNode(completion.getUsage()));
        metadata.putObject("metrics").put("latencyMs", 0);
        events.add(event("metadata", metadata));

        return StreamingPhysicsExpander.applyPhysics(events, physics);
    }

    @Override
    public ParsedConversation decode(HttpRequest request) {
        try {
            String body = request != null ? request.getBodyAsText() : null;
            if (body == null || body.isEmpty()) {
                return ParsedConversation.empty();
            }
            JsonNode root = OBJECT_MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                return ParsedConversation.empty();
            }
            JsonNode messagesNode = root.get("messages");
            if (messagesNode == null || !messagesNode.isArray()) {
                return ParsedConversation.empty();
            }

            List<ParsedMessage> parsed = new ArrayList<>();
            String systemText = systemPromptText(root.get("system"));
            if (!systemText.isEmpty()) {
                parsed.add(new ParsedMessage(ParsedMessage.Role.SYSTEM, systemText, null, null, null));
            }

            for (JsonNode msgNode : messagesNode) {
                parsed.add(decodeMessage(msgNode));
            }
            return ParsedConversation.of(parsed);
        } catch (Exception e) {
            return ParsedConversation.empty();
        }
    }

    /**
     * True when a parsed request body has the Converse shape rather than the Anthropic
     * InvokeModel shape: a Converse-only top-level field ({@code inferenceConfig},
     * {@code toolConfig}, ...), or a {@code system}/{@code messages[].content} block that is
     * keyed by its union member ({@code {"text":...}}) instead of carrying a {@code type}.
     */
    static boolean isConverseShaped(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        for (String field : CONVERSE_ONLY_REQUEST_FIELDS) {
            if (root.has(field)) {
                return true;
            }
        }
        if (root.has("anthropic_version")) {
            return false;
        }
        JsonNode system = root.get("system");
        if (system != null && system.isArray()) {
            for (JsonNode block : system) {
                if (isUnionKeyedBlock(block)) {
                    return true;
                }
            }
        }
        JsonNode messages = root.get("messages");
        if (messages != null && messages.isArray()) {
            for (JsonNode message : messages) {
                JsonNode content = message.get("content");
                if (content != null && content.isArray()) {
                    for (JsonNode block : content) {
                        if (isUnionKeyedBlock(block)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean isUnionKeyedBlock(JsonNode block) {
        if (block == null || !block.isObject() || block.has("type")) {
            return false;
        }
        java.util.Iterator<String> names = block.fieldNames();
        while (names.hasNext()) {
            if (CONTENT_BLOCK_MEMBERS.contains(names.next())) {
                return true;
            }
        }
        return false;
    }

    private static ParsedMessage decodeMessage(JsonNode msgNode) {
        String rawRole = msgNode.path("role").asText("");
        JsonNode contentNode = msgNode.get("content");

        StringBuilder text = new StringBuilder();
        List<ToolUse> toolCalls = new ArrayList<>();
        Map<String, String> toolResults = new LinkedHashMap<>();
        List<ParsedMessage.ImagePart> images = new ArrayList<>();
        List<ParsedMessage.AudioPart> audio = new ArrayList<>();

        if (contentNode != null && contentNode.isArray()) {
            for (JsonNode block : contentNode) {
                if (block.has("text") && block.get("text").isTextual()) {
                    text.append(block.get("text").asText(""));
                } else if (block.has("image")) {
                    // ImageBlock.format is png | jpeg | gif | webp
                    String format = block.path("image").path("format").asText(null);
                    images.add(new ParsedMessage.ImagePart(format != null ? "image/" + format : null));
                } else if (block.has("audio")) {
                    audio.add(new ParsedMessage.AudioPart(block.path("audio").path("format").asText(null)));
                } else if (block.has("toolUse")) {
                    JsonNode toolUse = block.get("toolUse");
                    JsonNode input = toolUse.get("input");
                    String arguments = input == null || input.isNull() ? "" : (input.isTextual() ? input.asText("") : input.toString());
                    ToolUse parsedToolUse = ToolUse.toolUse(toolUse.path("name").asText("")).withArguments(arguments);
                    String toolUseId = toolUse.path("toolUseId").asText("");
                    if (!toolUseId.isEmpty()) {
                        parsedToolUse.withId(toolUseId);
                    }
                    toolCalls.add(parsedToolUse);
                } else if (block.has("toolResult")) {
                    JsonNode toolResult = block.get("toolResult");
                    toolResults.put(toolResult.path("toolUseId").asText(""), toolResultText(toolResult.get("content")));
                }
            }
        }

        // A message carrying toolResult blocks is the TOOL turn for matcher purposes, mirroring
        // how the Anthropic decoder treats tool_result blocks.
        ParsedMessage.Role role = !toolResults.isEmpty() ? ParsedMessage.Role.TOOL : mapRole(rawRole);
        return new ParsedMessage(
            role,
            text.toString(),
            toolCalls.isEmpty() ? null : toolCalls,
            toolResults.isEmpty() ? null : toolResults,
            images.isEmpty() ? null : images,
            audio.isEmpty() ? null : audio
        );
    }

    /** ToolResultContentBlock is a union of text / json / image / document / video; text and json are kept. */
    private static String toolResultText(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (!content.isArray()) {
            return content.isTextual() ? content.asText("") : content.toString();
        }
        StringBuilder result = new StringBuilder();
        for (JsonNode part : content) {
            if (part.has("text")) {
                result.append(part.path("text").asText(""));
            } else if (part.has("json")) {
                result.append(part.get("json").toString());
            }
        }
        return result.toString();
    }

    /** SystemContentBlock is a union of text / guardContent / cachePoint; only text is prompt content. */
    private static String systemPromptText(JsonNode systemNode) {
        if (systemNode == null || !systemNode.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode block : systemNode) {
            if (block.has("text") && block.get("text").isTextual()) {
                builder.append(block.get("text").asText(""));
            }
        }
        return builder.toString();
    }

    private static ParsedMessage.Role mapRole(String rawRole) {
        return "assistant".equalsIgnoreCase(rawRole) ? ParsedMessage.Role.ASSISTANT : ParsedMessage.Role.USER;
    }

    /** The AWS Converse {@code StopReason} enum. */
    private static final Set<String> CONVERSE_STOP_REASONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "end_turn", "tool_use", "max_tokens", "stop_sequence", "guardrail_intervened", "content_filtered",
        "malformed_model_output", "malformed_tool_use", "model_context_window_exceeded")));

    /**
     * Always returns a value from the Converse {@code StopReason} enum, because AWS SDKs
     * deserialise it as an enum. Native values pass through; other providers' values
     * (OpenAI, Anthropic, Gemini) are translated, and anything unrecognised becomes {@code end_turn}.
     */
    static String mapStopReason(String stopReason, boolean hasToolCalls) {
        if (stopReason == null) {
            return hasToolCalls ? "tool_use" : "end_turn";
        }
        if (CONVERSE_STOP_REASONS.contains(stopReason)) {
            return stopReason;
        }
        switch (stopReason) {
            case "length":
            case "MAX_TOKENS":
                return "max_tokens";
            case "tool_calls":
                return "tool_use";
            case "content_filter":
            case "refusal":
            case "SAFETY":
                return "content_filtered";
            default:
                // stop, pause_turn, STOP and unknown values
                return "end_turn";
        }
    }

    /**
     * Converse TokenUsage. {@code inputTokens} is the non-cached input (AWS semantics, the same
     * as Anthropic's {@code input_tokens}), so {@code totalTokens} adds the cache read/write
     * counts to input + output. The cache fields are emitted only when set, like the Anthropic codec.
     */
    private static ObjectNode usageNode(Usage usage) {
        int inputTokens = usage != null && usage.getInputTokens() != null ? usage.getInputTokens() : 0;
        int outputTokens = usage != null && usage.getOutputTokens() != null ? usage.getOutputTokens() : 0;
        int cacheRead = usage != null && usage.getCachedInputTokens() != null ? usage.getCachedInputTokens() : 0;
        int cacheWrite = usage != null && usage.getCacheCreationTokens() != null ? usage.getCacheCreationTokens() : 0;

        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("inputTokens", inputTokens);
        node.put("outputTokens", outputTokens);
        node.put("totalTokens", inputTokens + outputTokens + cacheRead + cacheWrite);
        if (cacheRead != 0) {
            node.put("cacheReadInputTokens", cacheRead);
        }
        if (cacheWrite != 0) {
            node.put("cacheWriteInputTokens", cacheWrite);
        }
        return node;
    }

    private static JsonNode toolInput(String arguments) {
        if (arguments == null || arguments.trim().isEmpty()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(arguments);
            return parsed == null || parsed.isNull() || parsed.isMissingNode() ? OBJECT_MAPPER.createObjectNode() : parsed;
        } catch (Exception e) {
            return OBJECT_MAPPER.getNodeFactory().textNode(arguments);
        }
    }

    private static String toolUseId(ToolUse toolCall) {
        String id = toolCall.getId();
        if (id != null && !id.isEmpty()) {
            return id;
        }
        return "tooluse_" + UUIDService.getNonSecureUUID().replace("-", "").substring(0, 22);
    }

    private static ObjectNode blockIndex(int index) {
        return OBJECT_MAPPER.createObjectNode().put("contentBlockIndex", index);
    }

    private static ObjectNode blockDelta(int index, ObjectNode delta) {
        ObjectNode node = blockIndex(index);
        node.set("delta", delta);
        return node;
    }

    private static SseEvent event(String name, ObjectNode payload) {
        try {
            return sseEvent().withEvent(name).withData(OBJECT_MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode Bedrock ConverseStream event " + name, e);
        }
    }
}
