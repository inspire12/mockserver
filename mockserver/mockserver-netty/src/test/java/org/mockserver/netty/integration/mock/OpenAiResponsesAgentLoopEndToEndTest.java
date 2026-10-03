package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.client.LlmConversationBuilder.conversation;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.Provider.OPENAI_RESPONSES;
import static org.mockserver.model.ToolUse.toolUse;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Replays the OpenAI Agents SDK tool loop against a booted MockServer: turn 1 returns a
 * function_call, the client echoes that output item and answers it with a function_call_output
 * carrying its call_id, and turn 2 is selected by {@code whenContainsToolResultFor}. The SDK
 * reads each streamed turn from response.completed's output, so the streaming test does too.
 */
public class OpenAiResponsesAgentLoopEndToEndTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ARGS = "{\"city\":\"Paris\"}";
    private static final String USER_TURN = "{\"role\":\"user\",\"content\":\"What is the weather in Paris?\"}";
    private static int mockServerPort;
    private static MockServerClient mockServerClient;

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

    private static void expectToolLoop(boolean streaming) {
        conversation()
            .withPath("/v1/responses")
            .withProvider(OPENAI_RESPONSES)
            .withModel("gpt-4o")
            .turn()
                .whenTurnIndex(0)
                .respondingWith(completion()
                    .withToolCall(toolUse("get_weather").withArguments(ARGS))
                    .withStreaming(streaming))
            .andThen()
            .turn()
                .whenContainsToolResultFor("get_weather")
                .respondingWith(completion()
                    .withText("It is 18C and sunny in Paris.")
                    .withStreaming(streaming))
            .andThen()
            .applyTo(mockServerClient);
    }

    @Test
    public void toolResultEchoingTheCallIdSelectsTheNextTurn() throws Exception {
        expectToolLoop(false);

        Reply turn1 = post("{\"model\":\"gpt-4o\",\"input\":[" + USER_TURN + "]}");
        assertThat(turn1.statusCode(), is(200));
        JsonNode call = OBJECT_MAPPER.readTree(turn1.body()).path("output").path(0);
        assertThat(call.path("type").asText(), is("function_call"));
        assertThat(call.path("id").asText(), startsWith("fc_"));
        assertThat(call.path("call_id").asText(), startsWith("call_"));

        Reply turn2 = post(turnTwo(List.of(call), call.path("call_id").asText()));
        assertThat(turn2.statusCode(), is(200));
        assertThat(outputText(OBJECT_MAPPER.readTree(turn2.body()).path("output")), is("It is 18C and sunny in Paris."));
    }

    @Test
    public void toolResultKeyedByTheItemIdDoesNotSelectTheNextTurn() throws Exception {
        expectToolLoop(false);

        JsonNode call = OBJECT_MAPPER.readTree(post("{\"model\":\"gpt-4o\",\"input\":[" + USER_TURN + "]}").body())
            .path("output").path(0);

        Reply turn2 = post(turnTwo(List.of(call), call.path("id").asText()));
        assertThat(turn2.statusCode(), is(404));
    }

    @Test
    public void streamedLoopReadsEachTurnFromResponseCompleted() throws Exception {
        expectToolLoop(true);

        List<JsonNode> turn1 = streamedEvents(post("{\"model\":\"gpt-4o\",\"stream\":true,\"input\":[" + USER_TURN + "]}"));
        StringBuilder arguments = new StringBuilder();
        for (int i = 0; i < turn1.size(); i++) {
            assertThat("sequence_number of event " + i, turn1.get(i).path("sequence_number").asInt(-1), is(i));
            if ("response.function_call_arguments.delta".equals(turn1.get(i).path("type").asText())) {
                arguments.append(turn1.get(i).path("delta").asText());
            }
        }
        assertThat(arguments.toString(), is(ARGS));
        JsonNode completed = turn1.get(turn1.size() - 1);
        assertThat(completed.path("type").asText(), is("response.completed"));
        JsonNode output = completed.path("response").path("output");
        assertThat(output.size(), is(1));
        JsonNode call = output.get(0);
        assertThat(call.path("call_id").asText(), startsWith("call_"));
        assertThat(call.path("arguments").asText(), is(ARGS));

        List<JsonNode> turn2 = streamedEvents(post(turnTwo(List.of(call), call.path("call_id").asText())
            .replaceFirst("\\{", "{\"stream\":true,")));
        JsonNode finalTurn = turn2.get(turn2.size() - 1);
        assertThat(finalTurn.path("type").asText(), is("response.completed"));
        assertThat(outputText(finalTurn.path("response").path("output")), is("It is 18C and sunny in Paris."));
    }

    @Test
    public void toolResultChainedByPreviousResponseIdSelectsTheNextTurn() throws Exception {
        // Two tools, so the matcher cannot fall back to positional correlation and must use call_id.
        conversation()
            .withPath("/v1/responses")
            .withProvider(OPENAI_RESPONSES)
            .withModel("gpt-4o")
            .turn()
                .whenTurnIndex(0)
                .respondingWith(completion()
                    .withToolCall(toolUse("get_weather").withArguments(ARGS))
                    .withToolCall(toolUse("get_time").withArguments("{\"tz\":\"CET\"}")))
            .andThen()
            .turn()
                .whenContainsToolResultFor("get_weather")
                .respondingWith(completion().withText("It is 18C and sunny in Paris."))
            .andThen()
            .applyTo(mockServerClient);

        JsonNode turn1 = OBJECT_MAPPER.readTree(post("{\"model\":\"gpt-4o\",\"input\":[" + USER_TURN + "]}").body());
        String callId = turn1.path("output").path(0).path("call_id").asText();

        Reply turn2 = post("{\"model\":\"gpt-4o\",\"previous_response_id\":\"" + turn1.path("id").asText() + "\","
            + "\"input\":[{\"type\":\"function_call_output\",\"call_id\":\"" + callId + "\",\"output\":\"18C and sunny\"}]}");
        assertThat(turn2.statusCode(), is(200));
        assertThat(outputText(OBJECT_MAPPER.readTree(turn2.body()).path("output")), is("It is 18C and sunny in Paris."));
    }

    @Test
    public void chainedToolResultAnsweringAConfiguredToolUseIdSelectsTheNextTurn() throws Exception {
        conversation()
            .withPath("/v1/responses")
            .withProvider(OPENAI_RESPONSES)
            .withModel("gpt-4o")
            .turn()
                .whenTurnIndex(0)
                .respondingWith(completion()
                    .withToolCall(toolUse("get_weather").withId("call_abc123").withArguments(ARGS))
                    .withToolCall(toolUse("get_time").withId("call_def456").withArguments("{\"tz\":\"CET\"}")))
            .andThen()
            .turn()
                .whenContainsToolResultFor("get_weather")
                .respondingWith(completion().withText("It is 18C and sunny in Paris."))
            .andThen()
            .applyTo(mockServerClient);

        JsonNode turn1 = OBJECT_MAPPER.readTree(post("{\"model\":\"gpt-4o\",\"input\":[" + USER_TURN + "]}").body());
        assertThat(turn1.path("output").path(0).path("call_id").asText(), is("call_abc123"));

        Reply turn2 = post("{\"model\":\"gpt-4o\",\"previous_response_id\":\"" + turn1.path("id").asText() + "\","
            + "\"input\":[{\"type\":\"function_call_output\",\"call_id\":\"call_abc123\",\"output\":\"18C and sunny\"}]}");
        assertThat(turn2.statusCode(), is(200));
        assertThat(outputText(OBJECT_MAPPER.readTree(turn2.body()).path("output")), is("It is 18C and sunny in Paris."));
    }

    /** Turn 2 as the Agents SDK builds it: the user turn, the prior output items verbatim, then the tool result. */
    private static String turnTwo(List<JsonNode> priorOutput, String resultCallId) {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("model", "gpt-4o");
        ArrayNode input = body.putArray("input");
        try {
            input.add(OBJECT_MAPPER.readTree(USER_TURN));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        priorOutput.forEach(input::add);
        ObjectNode result = input.addObject();
        result.put("type", "function_call_output");
        result.put("call_id", resultCallId);
        result.put("output", "18C and sunny");
        return body.toString();
    }

    private static String outputText(JsonNode output) {
        StringBuilder text = new StringBuilder();
        for (JsonNode item : output) {
            for (JsonNode part : item.path("content")) {
                if ("output_text".equals(part.path("type").asText())) {
                    text.append(part.path("text").asText());
                }
            }
        }
        return text.toString();
    }

    private static List<JsonNode> streamedEvents(Reply response) throws Exception {
        assertThat(response.statusCode(), is(200));
        assertThat(response.head(), containsString("text/event-stream"));
        List<JsonNode> events = new ArrayList<>();
        for (String line : response.body().split("\r?\n")) {
            if (line.startsWith("data:")) {
                events.add(OBJECT_MAPPER.readTree(line.substring("data:".length()).trim()));
            }
        }
        assertThat(events, not(empty()));
        return events;
    }

    /** One exchange on its own socket with Connection: close, so no connection is open when the server stops. */
    private static Reply post(String body) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(10000);
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(("POST /v1/responses HTTP/1.1\r\n"
                + "Host: localhost:" + mockServerPort + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Connection: close\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(bodyBytes);
            output.flush();
            return Reply.parse(socket.getInputStream().readAllBytes());
        }
    }

    /** A raw HTTP/1.1 response: lower-cased head, and the body with any chunked framing removed. */
    private record Reply(int statusCode, String head, String body) {

        static Reply parse(byte[] raw) {
            // ISO-8859-1 maps one byte to one char, so a chunk's byte length indexes the text
            String text = new String(raw, StandardCharsets.ISO_8859_1);
            int headEnd = text.indexOf("\r\n\r\n");
            assertThat("response has a header/body boundary", headEnd, greaterThan(0));
            String head = text.substring(0, headEnd).toLowerCase(Locale.ROOT);
            String payload = text.substring(headEnd + 4);
            if (head.contains("transfer-encoding: chunked")) {
                payload = deChunk(payload);
            }
            return new Reply(Integer.parseInt(head.split(" ")[1]), head,
                new String(payload.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
        }

        private static String deChunk(String chunked) {
            StringBuilder payload = new StringBuilder();
            int position = 0;
            int lineEnd;
            while ((lineEnd = chunked.indexOf("\r\n", position)) >= 0) {
                int size = Integer.parseInt(chunked.substring(position, lineEnd).trim(), 16);
                if (size == 0) {
                    break;
                }
                payload.append(chunked, lineEnd + 2, lineEnd + 2 + size);
                position = lineEnd + 2 + size + 2;
            }
            return payload.toString();
        }
    }
}
