package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.model.*;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.ToolUse.toolUse;

public class OpenAiChatCompletionsCodecStreamingTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final OpenAiChatCompletionsCodec codec = new OpenAiChatCompletionsCodec();

    @Test
    public void shouldProduceCorrectChunkSequenceForTextOnly() throws Exception {
        // given
        Completion completion = completion().withText("Hello world");

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null);

        // then — first chunk (role), N text chunks, final chunk (finish_reason), [DONE]
        assertThat(events.size(), is(greaterThanOrEqualTo(5)));

        // First chunk: role-only
        JsonNode first = OBJECT_MAPPER.readTree(events.get(0).getData());
        assertThat(first.get("object").asText(), is("chat.completion.chunk"));
        JsonNode delta0 = first.get("choices").get(0).get("delta");
        assertThat(delta0.get("role").asText(), is("assistant"));
        assertThat(first.get("choices").get(0).get("finish_reason").isNull(), is(true));

        // Last event: [DONE]
        SseEvent lastEvent = events.get(events.size() - 1);
        assertThat(lastEvent.getData(), is("[DONE]"));
        assertThat(lastEvent.getEvent(), is(nullValue()));

        // Second-to-last event: final chunk with finish_reason
        JsonNode finalChunk = OBJECT_MAPPER.readTree(events.get(events.size() - 2).getData());
        assertThat(finalChunk.get("choices").get(0).get("finish_reason").asText(), is("stop"));
    }

    @Test
    public void shouldProduceSubwordContentDeltaChunksByDefault() throws Exception {
        // given — subword streaming is the default: "Hello world" segments into
        // finer subword deltas ["Hell", "o", " worl", "d"]
        Completion completion = completion().withText("Hello world");

        // when — null physics selects the default (subword) split
        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null);

        // then — 4 content chunks between first (role) and final (finish_reason) and [DONE]
        int contentDeltaCount = 0;
        StringBuilder concatenated = new StringBuilder();
        for (int i = 1; i < events.size() - 2; i++) {
            JsonNode chunk = OBJECT_MAPPER.readTree(events.get(i).getData());
            JsonNode delta = chunk.get("choices").get(0).get("delta");
            if (delta.has("content")) {
                contentDeltaCount++;
                concatenated.append(delta.get("content").asText());
            }
        }
        assertThat(contentDeltaCount, is(4));
        assertThat(concatenated.toString(), is("Hello world"));
    }

    @Test
    public void shouldProduceWholeWordContentDeltaChunksWhenSubwordStreamingDisabled() throws Exception {
        // given — an explicit subwordStreaming=false opts back into whole-word deltas:
        // "Hello world" -> ["Hello", " ", "world"]
        Completion completion = completion().withText("Hello world");

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o",
            StreamingPhysics.streamingPhysics().withSubwordStreaming(false));

        // then — 3 content chunks between first (role) and final (finish_reason) and [DONE]
        int contentDeltaCount = 0;
        StringBuilder concatenated = new StringBuilder();
        for (int i = 1; i < events.size() - 2; i++) {
            JsonNode chunk = OBJECT_MAPPER.readTree(events.get(i).getData());
            JsonNode delta = chunk.get("choices").get(0).get("delta");
            if (delta.has("content")) {
                contentDeltaCount++;
                concatenated.append(delta.get("content").asText());
            }
        }
        assertThat(contentDeltaCount, is(3));
        assertThat(concatenated.toString(), is("Hello world"));
    }

    @Test
    public void shouldProduceToolCallDelta() throws Exception {
        // given
        Completion completion = completion()
            .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"Paris\"}"));

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null);

        // then — first chunk (role), tool call chunk, final chunk (finish_reason), [DONE]
        assertThat(events.size(), is(4));

        JsonNode toolChunk = OBJECT_MAPPER.readTree(events.get(1).getData());
        JsonNode delta = toolChunk.get("choices").get(0).get("delta");
        assertThat(delta.has("tool_calls"), is(true));
        JsonNode toolCall = delta.get("tool_calls").get(0);
        assertThat(toolCall.get("id").asText(), startsWith("call_"));
        assertThat(toolCall.get("type").asText(), is("function"));
        assertThat(toolCall.get("function").get("name").asText(), is("get_weather"));
        assertThat(toolCall.get("function").get("arguments").asText(), is("{\"city\":\"Paris\"}"));

        // finish_reason should be "tool_calls"
        JsonNode finalChunk = OBJECT_MAPPER.readTree(events.get(2).getData());
        assertThat(finalChunk.get("choices").get(0).get("finish_reason").asText(), is("tool_calls"));
    }

    @Test
    public void shouldUseConsistentIdAndCreatedAcrossChunks() throws Exception {
        // given
        Completion completion = completion().withText("test tokens here");

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null);

        // then — all JSON chunks share the same id and created
        String expectedId = null;
        Long expectedCreated = null;
        for (SseEvent event : events) {
            if ("[DONE]".equals(event.getData())) {
                continue;
            }
            JsonNode chunk = OBJECT_MAPPER.readTree(event.getData());
            String id = chunk.get("id").asText();
            long created = chunk.get("created").asLong();
            if (expectedId == null) {
                expectedId = id;
                expectedCreated = created;
            }
            assertThat(id, is(expectedId));
            assertThat(created, is(expectedCreated));
        }
    }

    @Test
    public void shouldProduceDoneEvent() throws Exception {
        // given
        Completion completion = completion().withText("test");

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null);

        // then
        SseEvent lastEvent = events.get(events.size() - 1);
        assertThat(lastEvent.getData(), is("[DONE]"));
        // [DONE] has no event name
        assertThat(lastEvent.getEvent(), is(nullValue()));
    }

    @Test
    public void shouldMapFinishReasonInStreamingChunks() throws Exception {
        // given
        Completion completion = completion()
            .withText("test")
            .withStopReason("max_tokens");

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null);

        // then
        JsonNode finalChunk = OBJECT_MAPPER.readTree(events.get(events.size() - 2).getData());
        assertThat(finalChunk.get("choices").get(0).get("finish_reason").asText(), is("length"));
    }

    private static HttpRequest chatRequest(String streamOptions) {
        String body = "{\"model\":\"gpt-4o\",\"stream\":true,"
            + (streamOptions != null ? "\"stream_options\":" + streamOptions + "," : "")
            + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        return HttpRequest.request().withMethod("POST").withPath("/v1/chat/completions").withBody(body);
    }

    @Test
    public void shouldStreamUsageChunkBeforeDoneWhenRequestSetsIncludeUsage() throws Exception {
        // given
        Completion completion = completion()
            .withText("Hello world")
            .withUsage(Usage.usage().withInputTokens(12).withOutputTokens(8)
                .withCachedInputTokens(4).withReasoningTokens(3));

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null, chatRequest("{\"include_usage\":true}"));

        // then — [DONE] last, preceded by the usage chunk with an empty choices array
        assertThat(events.get(events.size() - 1).getData(), is("[DONE]"));
        JsonNode usageChunk = OBJECT_MAPPER.readTree(events.get(events.size() - 2).getData());
        JsonNode firstChunk = OBJECT_MAPPER.readTree(events.get(0).getData());
        assertThat(usageChunk.get("object").asText(), is("chat.completion.chunk"));
        assertThat(usageChunk.get("id").asText(), is(firstChunk.get("id").asText()));
        assertThat(usageChunk.get("created").asLong(), is(firstChunk.get("created").asLong()));
        assertThat(usageChunk.get("model").asText(), is("gpt-4o"));
        assertThat(usageChunk.get("choices").isArray(), is(true));
        assertThat(usageChunk.get("choices").size(), is(0));
        JsonNode usage = usageChunk.get("usage");
        assertThat(usage.get("prompt_tokens").asInt(), is(12));
        assertThat(usage.get("completion_tokens").asInt(), is(8));
        assertThat(usage.get("total_tokens").asInt(), is(20));
        assertThat(usage.get("prompt_tokens_details").get("cached_tokens").asInt(), is(4));
        assertThat(usage.get("completion_tokens_details").get("reasoning_tokens").asInt(), is(3));

        // every other chunk carries "usage": null, and the finish_reason chunk still precedes the usage chunk
        for (SseEvent event : events.subList(0, events.size() - 2)) {
            JsonNode chunk = OBJECT_MAPPER.readTree(event.getData());
            assertThat(chunk.has("usage"), is(true));
            assertThat(chunk.get("usage").isNull(), is(true));
            assertThat(chunk.get("choices").size(), is(1));
        }
        JsonNode finishChunk = OBJECT_MAPPER.readTree(events.get(events.size() - 3).getData());
        assertThat(finishChunk.get("choices").get(0).get("finish_reason").asText(), is("stop"));
    }

    @Test
    public void shouldStreamZeroUsageWithoutDetailsWhenCompletionSetsNoUsage() throws Exception {
        // when
        List<SseEvent> events = codec.encodeStreaming(completion().withText("hi"), "gpt-4o", null,
            chatRequest("{\"include_usage\":true}"));

        // then
        JsonNode usage = OBJECT_MAPPER.readTree(events.get(events.size() - 2).getData()).get("usage");
        assertThat(usage.get("prompt_tokens").asInt(), is(0));
        assertThat(usage.get("completion_tokens").asInt(), is(0));
        assertThat(usage.get("total_tokens").asInt(), is(0));
        assertThat(usage.has("prompt_tokens_details"), is(false));
        assertThat(usage.has("completion_tokens_details"), is(false));
    }

    @Test
    public void shouldStreamUsageChunkAfterToolCallChunksWhenRequestSetsIncludeUsage() throws Exception {
        // given
        Completion completion = completion()
            .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"London\"}"))
            .withUsage(Usage.usage().withInputTokens(25).withOutputTokens(15));

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null, chatRequest("{\"include_usage\":true}"));

        // then
        JsonNode finishChunk = OBJECT_MAPPER.readTree(events.get(events.size() - 3).getData());
        assertThat(finishChunk.get("choices").get(0).get("finish_reason").asText(), is("tool_calls"));
        JsonNode usageChunk = OBJECT_MAPPER.readTree(events.get(events.size() - 2).getData());
        assertThat(usageChunk.get("choices").size(), is(0));
        assertThat(usageChunk.get("usage").get("total_tokens").asInt(), is(40));
    }

    @Test
    public void shouldNotStreamUsageWhenRequestDoesNotOptIn() throws Exception {
        // given — the expectation sets usage, but the real API streams none without include_usage
        Completion completion = completion()
            .withText("Hello world")
            .withUsage(Usage.usage().withInputTokens(12).withOutputTokens(8));
        HttpRequest[] requests = {
            chatRequest(null),
            chatRequest("{\"include_usage\":false}"),
            chatRequest("{\"include_usage\":\"true\"}"),
            chatRequest("{}"),
            chatRequest("null"),
            HttpRequest.request().withBody("not json"),
            HttpRequest.request(),
            null
        };

        for (HttpRequest request : requests) {
            // when
            List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null, request);

            // then — byte-identical in shape to the request-less stream: no usage key anywhere
            assertThat(events.get(events.size() - 1).getData(), is("[DONE]"));
            for (SseEvent event : events.subList(0, events.size() - 1)) {
                JsonNode chunk = OBJECT_MAPPER.readTree(event.getData());
                assertThat("request " + request, chunk.has("usage"), is(false));
                assertThat("request " + request, chunk.get("choices").size(), is(1));
            }
        }
    }
}
