package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.model.*;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.ToolUse.toolUse;
import static org.mockserver.model.Usage.usage;

/**
 * Bedrock Converse / ConverseStream wire shape, and how {@link BedrockCodec} chooses between
 * it and InvokeModel. Expected shapes follow the AWS Bedrock Runtime API reference
 * (API_runtime_Converse, API_runtime_ConverseStream).
 */
public class BedrockConverseCodecTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String MODEL = "amazon.titan-text-express-v1";

    private final BedrockConverseCodec converse = new BedrockConverseCodec();
    private final BedrockCodec bedrock = new BedrockCodec();

    // --- Converse (non-streaming) ---

    @Test
    public void shouldEncodeTextCompletionAsConverseEnvelope() throws Exception {
        HttpResponse response = converse.encode(completion()
            .withText("mocked response")
            .withUsage(usage().withInputTokens(5).withOutputTokens(5)), MODEL);

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getFirstHeader("content-type"), is("application/json"));
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode message = root.path("output").path("message");
        assertThat(message.path("role").asText(), is("assistant"));
        assertThat(message.path("content").size(), is(1));
        assertThat(message.path("content").path(0).path("text").asText(), is("mocked response"));
        assertThat(root.path("stopReason").asText(), is("end_turn"));
        assertThat(root.path("usage").path("inputTokens").asInt(-1), is(5));
        assertThat(root.path("usage").path("outputTokens").asInt(-1), is(5));
        assertThat(root.path("usage").path("totalTokens").asInt(-1), is(10));
        assertThat(root.path("metrics").path("latencyMs").isNumber(), is(true));
        // none of the Anthropic Messages fields leak into the Converse envelope
        assertThat(root.has("id"), is(false));
        assertThat(root.has("type"), is(false));
        assertThat(root.has("content"), is(false));
        assertThat(root.has("stop_reason"), is(false));
        assertThat(root.path("usage").has("input_tokens"), is(false));
    }

    @Test
    public void shouldEncodeToolUseBlockWithStructuredInput() throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(converse.encode(completion()
            .withText("Let me check.")
            .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"London\"}")), MODEL).getBodyAsString());

        JsonNode content = root.path("output").path("message").path("content");
        assertThat(content.size(), is(2));
        assertThat(content.path(0).path("text").asText(), is("Let me check."));
        JsonNode toolUse = content.path(1).path("toolUse");
        assertThat(toolUse.path("toolUseId").asText(), startsWith("tooluse_"));
        assertThat(toolUse.path("name").asText(), is("get_weather"));
        assertThat(toolUse.path("input").isObject(), is(true));
        assertThat(toolUse.path("input").path("city").asText(), is("London"));
        assertThat(root.path("stopReason").asText(), is("tool_use"));
    }

    @Test
    public void shouldKeepConfiguredToolUseId() throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(converse.encode(completion()
            .withToolCall(toolUse("search").withId("tooluse_fixed").withArguments("{}")), MODEL).getBodyAsString());

        assertThat(root.path("output").path("message").path("content").path(0).path("toolUse").path("toolUseId").asText(),
            is("tooluse_fixed"));
    }

    @Test
    public void shouldEncodeCacheTokensAndIncludeThemInTotal() throws Exception {
        JsonNode usageNode = OBJECT_MAPPER.readTree(converse.encode(completion()
            .withText("cached")
            .withUsage(usage().withInputTokens(10).withOutputTokens(4).withCachedInputTokens(100).withCacheCreationTokens(20)), MODEL)
            .getBodyAsString()).path("usage");

        assertThat(usageNode.path("inputTokens").asInt(-1), is(10));
        assertThat(usageNode.path("outputTokens").asInt(-1), is(4));
        assertThat(usageNode.path("cacheReadInputTokens").asInt(-1), is(100));
        assertThat(usageNode.path("cacheWriteInputTokens").asInt(-1), is(20));
        // AWS: inputTokens excludes cached tokens, so the total adds the cache read/write counts
        assertThat(usageNode.path("totalTokens").asInt(-1), is(134));
    }

    @Test
    public void shouldOmitCacheFieldsAndZeroUsageWhenUnset() throws Exception {
        JsonNode usageNode = OBJECT_MAPPER.readTree(converse.encode(completion().withText("x"), MODEL).getBodyAsString()).path("usage");

        assertThat(usageNode.path("inputTokens").asInt(-1), is(0));
        assertThat(usageNode.path("outputTokens").asInt(-1), is(0));
        assertThat(usageNode.path("totalTokens").asInt(-1), is(0));
        assertThat(usageNode.has("cacheReadInputTokens"), is(false));
        assertThat(usageNode.has("cacheWriteInputTokens"), is(false));
    }

    @Test
    public void shouldEncodeReasoningAsLeadingReasoningContentBlock() throws Exception {
        JsonNode content = OBJECT_MAPPER.readTree(converse.encode(completion()
            .withReasoningText("thinking it over")
            .withReasoningSignature("sig-1")
            .withText("answer"), MODEL).getBodyAsString()).path("output").path("message").path("content");

        JsonNode reasoningText = content.path(0).path("reasoningContent").path("reasoningText");
        assertThat(reasoningText.path("text").asText(), is("thinking it over"));
        assertThat(reasoningText.path("signature").asText(), is("sig-1"));
        assertThat(content.path(1).path("text").asText(), is("answer"));
    }

    @Test
    public void shouldMapStopReasonsToConverseValues() {
        assertThat(BedrockConverseCodec.mapStopReason(null, false), is("end_turn"));
        assertThat(BedrockConverseCodec.mapStopReason(null, true), is("tool_use"));
        assertThat(BedrockConverseCodec.mapStopReason("stop", false), is("end_turn"));
        assertThat(BedrockConverseCodec.mapStopReason("length", false), is("max_tokens"));
        assertThat(BedrockConverseCodec.mapStopReason("tool_calls", true), is("tool_use"));
        assertThat(BedrockConverseCodec.mapStopReason("content_filter", false), is("content_filtered"));
        assertThat(BedrockConverseCodec.mapStopReason("max_tokens", false), is("max_tokens"));
        assertThat(BedrockConverseCodec.mapStopReason("guardrail_intervened", false), is("guardrail_intervened"));
        assertThat(BedrockConverseCodec.mapStopReason("model_context_window_exceeded", false), is("model_context_window_exceeded"));
        // Anthropic values outside the Converse enum
        assertThat(BedrockConverseCodec.mapStopReason("refusal", false), is("content_filtered"));
        assertThat(BedrockConverseCodec.mapStopReason("pause_turn", false), is("end_turn"));
        // Gemini values
        assertThat(BedrockConverseCodec.mapStopReason("STOP", false), is("end_turn"));
        assertThat(BedrockConverseCodec.mapStopReason("SAFETY", false), is("content_filtered"));
        assertThat(BedrockConverseCodec.mapStopReason("MAX_TOKENS", false), is("max_tokens"));
        // anything else must still be a valid enum value
        assertThat(BedrockConverseCodec.mapStopReason("something_new", false), is("end_turn"));
        assertThat(BedrockConverseCodec.mapStopReason("", true), is("end_turn"));
    }

    @Test
    public void shouldEncodeEmptyObjectInputForBlankToolArguments() throws Exception {
        for (String blank : new String[]{null, "", "   ", "null"}) {
            JsonNode toolUse = OBJECT_MAPPER.readTree(converse.encode(completion()
                .withToolCall(toolUse("ping").withArguments(blank)), MODEL).getBodyAsString())
                .path("output").path("message").path("content").path(0).path("toolUse");
            assertThat("arguments=" + blank, toolUse.path("input").isObject(), is(true));
            assertThat("arguments=" + blank, toolUse.path("input").size(), is(0));
        }
    }

    // --- ConverseStream ---

    @Test
    public void shouldDeclareConverseEventStreamFormat() {
        assertThat(converse.streamingFormat(), is(StreamingFormat.AWS_CONVERSE_EVENT_STREAM));
    }

    @Test
    public void shouldStreamTextAsConverseStreamEvents() throws Exception {
        List<SseEvent> events = converse.encodeStreaming(completion()
            .withText("Hello there friend")
            .withUsage(usage().withInputTokens(7).withOutputTokens(3)), MODEL, null);

        assertThat(events.get(0).getEvent(), is("messageStart"));
        assertThat(OBJECT_MAPPER.readTree(events.get(0).getData()).path("role").asText(), is("assistant"));

        StringBuilder text = new StringBuilder();
        List<String> names = new ArrayList<>();
        for (SseEvent event : events) {
            names.add(event.getEvent());
            JsonNode payload = OBJECT_MAPPER.readTree(event.getData());
            // ConverseStream payloads are the event body itself, never wrapped in the event name
            assertThat(payload.has(event.getEvent()), is(false));
            if ("contentBlockDelta".equals(event.getEvent())) {
                assertThat(payload.path("contentBlockIndex").asInt(-1), is(0));
                text.append(payload.path("delta").path("text").asText());
            }
        }
        assertThat(text.toString(), is("Hello there friend"));
        // text blocks have no contentBlockStart (ContentBlockStart has no text member)
        assertThat(names, not(hasItem("contentBlockStart")));
        assertThat(names.get(names.size() - 3), is("contentBlockStop"));
        assertThat(names.get(names.size() - 2), is("messageStop"));
        assertThat(names.get(names.size() - 1), is("metadata"));

        JsonNode messageStop = OBJECT_MAPPER.readTree(events.get(events.size() - 2).getData());
        assertThat(messageStop.path("stopReason").asText(), is("end_turn"));
        JsonNode metadata = OBJECT_MAPPER.readTree(events.get(events.size() - 1).getData());
        assertThat(metadata.path("usage").path("inputTokens").asInt(-1), is(7));
        assertThat(metadata.path("usage").path("outputTokens").asInt(-1), is(3));
        assertThat(metadata.path("usage").path("totalTokens").asInt(-1), is(10));
        assertThat(metadata.path("metrics").path("latencyMs").isNumber(), is(true));
    }

    @Test
    public void shouldStreamToolUseWithStartAndStringInputDelta() throws Exception {
        List<SseEvent> events = converse.encodeStreaming(completion()
            .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"London\"}")), MODEL, null);

        List<String> names = new ArrayList<>();
        for (SseEvent event : events) {
            names.add(event.getEvent());
        }
        assertThat(names, contains("messageStart", "contentBlockStart", "contentBlockDelta", "contentBlockStop", "messageStop", "metadata"));

        JsonNode start = OBJECT_MAPPER.readTree(events.get(1).getData());
        assertThat(start.path("contentBlockIndex").asInt(-1), is(0));
        assertThat(start.path("start").path("toolUse").path("name").asText(), is("get_weather"));
        assertThat(start.path("start").path("toolUse").path("toolUseId").asText(), startsWith("tooluse_"));
        JsonNode delta = OBJECT_MAPPER.readTree(events.get(2).getData()).path("delta").path("toolUse");
        assertThat(delta.path("input").isTextual(), is(true));
        assertThat(delta.path("input").asText(), is("{\"city\":\"London\"}"));
        assertThat(OBJECT_MAPPER.readTree(events.get(4).getData()).path("stopReason").asText(), is("tool_use"));
    }

    @Test
    public void shouldStreamReasoningBeforeText() throws Exception {
        List<SseEvent> events = converse.encodeStreaming(completion()
            .withReasoningText("hmm")
            .withReasoningSignature("sig")
            .withText("ok"), MODEL, null);

        JsonNode first = OBJECT_MAPPER.readTree(events.get(1).getData());
        assertThat(first.path("contentBlockIndex").asInt(-1), is(0));
        assertThat(first.path("delta").path("reasoningContent").path("text").asText(), is("hmm"));
        JsonNode signature = OBJECT_MAPPER.readTree(events.get(2).getData());
        assertThat(signature.path("delta").path("reasoningContent").path("signature").asText(), is("sig"));
        assertThat(events.get(3).getEvent(), is("contentBlockStop"));
        JsonNode textDelta = OBJECT_MAPPER.readTree(events.get(4).getData());
        assertThat(textDelta.path("contentBlockIndex").asInt(-1), is(1));
        assertThat(textDelta.path("delta").path("text").asText(), is("ok"));
    }

    // --- Converse request decode ---

    @Test
    public void shouldDecodeConverseRequestSystemTextAndToolTurns() {
        String body = "{"
            + "\"system\":[{\"text\":\"You are terse.\"},{\"cachePoint\":{\"type\":\"default\"}}],"
            + "\"messages\":["
            + "{\"role\":\"user\",\"content\":[{\"text\":\"What is the weather?\"},{\"image\":{\"format\":\"png\",\"source\":{\"bytes\":\"AAAA\"}}}]},"
            + "{\"role\":\"assistant\",\"content\":[{\"text\":\"Checking.\"},{\"toolUse\":{\"toolUseId\":\"tooluse_1\",\"name\":\"get_weather\",\"input\":{\"city\":\"Paris\"}}}]},"
            + "{\"role\":\"user\",\"content\":[{\"toolResult\":{\"toolUseId\":\"tooluse_1\",\"content\":[{\"text\":\"18C\"},{\"json\":{\"wind\":\"low\"}}],\"status\":\"success\"}}]}"
            + "],"
            + "\"toolConfig\":{\"tools\":[{\"toolSpec\":{\"name\":\"get_weather\",\"inputSchema\":{\"json\":{\"type\":\"object\"}}}}]},"
            + "\"inferenceConfig\":{\"maxTokens\":100}"
            + "}";

        List<ParsedMessage> messages = converse.decode(request().withBody(body)).getMessages();

        assertThat(messages.size(), is(4));
        assertThat(messages.get(0).getRole(), is(ParsedMessage.Role.SYSTEM));
        assertThat(messages.get(0).getTextContent(), is("You are terse."));
        assertThat(messages.get(1).getRole(), is(ParsedMessage.Role.USER));
        assertThat(messages.get(1).getTextContent(), is("What is the weather?"));
        assertThat(messages.get(1).imageCount(), is(1));
        assertThat(messages.get(1).getImages().get(0).getMediaType(), is("image/png"));
        assertThat(messages.get(2).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(messages.get(2).getTextContent(), is("Checking."));
        assertThat(messages.get(2).getToolCalls().get(0).getName(), is("get_weather"));
        assertThat(messages.get(2).getToolCalls().get(0).getId(), is("tooluse_1"));
        assertThat(messages.get(2).getToolCalls().get(0).getArguments(), is("{\"city\":\"Paris\"}"));
        assertThat(messages.get(3).getRole(), is(ParsedMessage.Role.TOOL));
        assertThat(messages.get(3).getToolResults().get("tooluse_1"), is("18C{\"wind\":\"low\"}"));
    }

    @Test
    public void shouldDecodeEmptyForMalformedOrMissingMessages() {
        assertThat(converse.decode(request().withBody("not json")).getMessages(), is(empty()));
        assertThat(converse.decode(request().withBody("{\"inferenceConfig\":{}}")).getMessages(), is(empty()));
        assertThat(converse.decode(null).getMessages(), is(empty()));
    }

    @Test
    public void shouldRecogniseConverseShapedBodies() throws Exception {
        assertThat(BedrockConverseCodec.isConverseShaped(OBJECT_MAPPER.readTree(
            "{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"hi\"}]}]}")), is(true));
        assertThat(BedrockConverseCodec.isConverseShaped(OBJECT_MAPPER.readTree(
            "{\"messages\":[],\"inferenceConfig\":{\"maxTokens\":1}}")), is(true));
        assertThat(BedrockConverseCodec.isConverseShaped(OBJECT_MAPPER.readTree(
            "{\"system\":[{\"text\":\"s\"}],\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")), is(true));
        // Anthropic InvokeModel bodies: typed blocks, string content, anthropic_version
        assertThat(BedrockConverseCodec.isConverseShaped(OBJECT_MAPPER.readTree(
            "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}]}")), is(false));
        assertThat(BedrockConverseCodec.isConverseShaped(OBJECT_MAPPER.readTree(
            "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")), is(false));
        assertThat(BedrockConverseCodec.isConverseShaped(OBJECT_MAPPER.readTree(
            "{\"anthropic_version\":\"bedrock-2023-05-31\",\"system\":[{\"text\":\"s\"}],\"messages\":[]}")), is(false));
    }

    // --- BedrockCodec API selection ---

    @Test
    public void shouldSelectConverseByPath() {
        assertThat(BedrockCodec.isConverse(request().withPath("/model/amazon.titan-text-express-v1/converse")), is(true));
        assertThat(BedrockCodec.isConverse(request().withPath("/model/anthropic.claude-3-5-sonnet-20241022-v2:0/converse-stream")), is(true));
        assertThat(BedrockCodec.isConverse(request().withPath("/model/anthropic.claude-3-5-sonnet-20241022-v2%3A0/converse/")), is(true));
        assertThat(BedrockCodec.isConverse(request().withPath("/model/anthropic.claude-3-5-sonnet-20241022-v2:0/invoke")), is(false));
        assertThat(BedrockCodec.isConverse(request().withPath("/model/x/invoke-with-response-stream")), is(false));
        assertThat(BedrockCodec.isConverse(request().withPath("/model/x/notconverse")), is(false));
        assertThat(BedrockCodec.isConverse(null), is(false));
    }

    @Test
    public void shouldLetInvokePathWinOverConverseShapedBody() {
        HttpRequest request = request()
            .withPath("/model/x/invoke")
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"hi\"}]}]}");
        assertThat(BedrockCodec.isConverse(request), is(false));
    }

    @Test
    public void shouldFallBackToBodyShapeOnUnrecognisedPath() {
        assertThat(BedrockCodec.isConverse(request()
            .withPath("/gateway/bedrock")
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"hi\"}]}]}")), is(true));
        assertThat(BedrockCodec.isConverse(request()
            .withPath("/gateway/bedrock")
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")), is(false));
        assertThat(BedrockCodec.isConverse(request().withPath("/gateway/bedrock")), is(false));
    }

    @Test
    public void shouldEncodeConverseShapeOnConversePathAndAnthropicShapeOnInvokePath() throws Exception {
        Completion completion = completion().withText("hi").withUsage(usage().withInputTokens(1).withOutputTokens(2));

        JsonNode converseBody = OBJECT_MAPPER.readTree(bedrock.encode(completion, MODEL,
            request().withPath("/model/" + MODEL + "/converse")).getBodyAsString());
        assertThat(converseBody.path("output").path("message").path("content").path(0).path("text").asText(), is("hi"));
        assertThat(converseBody.path("usage").path("totalTokens").asInt(-1), is(3));

        JsonNode invokeBody = OBJECT_MAPPER.readTree(bedrock.encode(completion, MODEL,
            request().withPath("/model/" + MODEL + "/invoke")).getBodyAsString());
        assertThat(invokeBody.path("type").asText(), is("message"));
        assertThat(invokeBody.path("usage").path("input_tokens").asInt(-1), is(1));

        // the request-less overload keeps the original InvokeModel (Anthropic) behaviour
        JsonNode legacyBody = OBJECT_MAPPER.readTree(bedrock.encode(completion, MODEL).getBodyAsString());
        assertThat(legacyBody.path("type").asText(), is("message"));
    }

    @Test
    public void shouldSelectStreamingFormatAndEventsByPath() {
        HttpRequest converseStream = request().withPath("/model/" + MODEL + "/converse-stream");
        HttpRequest invokeStream = request().withPath("/model/" + MODEL + "/invoke-with-response-stream");

        assertThat(bedrock.streamingFormat(converseStream), is(StreamingFormat.AWS_CONVERSE_EVENT_STREAM));
        assertThat(bedrock.streamingFormat(invokeStream), is(StreamingFormat.AWS_EVENT_STREAM));
        assertThat(bedrock.streamingFormat(), is(StreamingFormat.AWS_EVENT_STREAM));

        Completion completion = completion().withText("hi");
        assertThat(bedrock.encodeStreaming(completion, MODEL, null, converseStream).get(0).getEvent(), is("messageStart"));
        assertThat(bedrock.encodeStreaming(completion, MODEL, null, invokeStream).get(0).getEvent(), is("message_start"));
    }

    @Test
    public void shouldDecodeConverseRequestThroughBedrockCodec() {
        HttpRequest request = request()
            .withPath("/model/" + MODEL + "/converse")
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"Hello Converse\"}]}]}");

        ParsedConversation conversation = bedrock.decode(request);

        assertThat(conversation.getMessages().size(), is(1));
        assertThat(conversation.getMessages().get(0).getTextContent(), is("Hello Converse"));
    }

    @Test
    public void shouldStillDecodeAnthropicRequestOnInvokePath() {
        HttpRequest request = request()
            .withPath("/model/anthropic.claude-3-5-sonnet-20241022-v2:0/invoke")
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Hello Invoke\"}]}]}");

        assertThat(bedrock.decode(request).getMessages().get(0).getTextContent(), is("Hello Invoke"));
    }
}
