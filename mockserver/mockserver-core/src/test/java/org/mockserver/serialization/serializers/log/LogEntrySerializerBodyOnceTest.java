package org.mockserver.serialization.serializers.log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.log.model.DeferredLogArgument;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.LogEntrySerializer;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_MATCHED;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.log.model.LogEntryMessages.RECEIVED_REQUEST_MESSAGE_FORMAT;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * In {@code LOG_ENTRIES} output a body is written once, in {@code httpRequest} or {@code httpResponse}; an argument
 * that is the entry's own request, response or curl command is written in its compact form, in {@code arguments}
 * and in {@code message}.
 */
public class LogEntrySerializerBodyOnceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REQUEST_BODY = "request-body-written-once";
    private static final String RESPONSE_BODY = "response-body-written-once";

    private static HttpRequest ownRequest() {
        return request().withMethod("POST").withPath("/upload").withBody(REQUEST_BODY);
    }

    private static int occurrences(String text, String of) {
        int count = 0;
        for (int index = text.indexOf(of); index >= 0; index = text.indexOf(of, index + 1)) {
            count++;
        }
        return count;
    }

    private static JsonNode serialize(LogEntry entry) throws Exception {
        return MAPPER.readTree(new LogEntrySerializer(new MockServerLogger()).serialize(entry));
    }

    @Test
    public void shouldWriteReceivedRequestBodyOnce() throws Exception {
        HttpRequest request = ownRequest();
        LogEntry entry = new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setLogLevel(Level.INFO)
            .setHttpRequest(request)
            .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
            .setArguments(request);

        String json = new LogEntrySerializer(new MockServerLogger()).serialize(entry);
        JsonNode node = MAPPER.readTree(json);

        assertThat(occurrences(json, REQUEST_BODY), is(1));
        assertThat(node.at("/httpRequest/body").asText(), is(REQUEST_BODY));
        assertThat(node.get("arguments").size(), is(1));
        assertThat(node.get("arguments").get(0).asText(), is("POST /upload"));
        assertThat(node.get("message").toString(), containsString("POST /upload"));
        assertThat(node.get("messageFormat").asText(), is(RECEIVED_REQUEST_MESSAGE_FORMAT));
    }

    @Test
    public void shouldWriteForwardedRequestAndResponseBodiesOnceInArgumentsAndMessage() throws Exception {
        HttpRequest request = ownRequest().withHeader("Host", "localhost:1080");
        HttpResponse response = response().withStatusCode(201).withBody(RESPONSE_BODY);
        LogEntry entry = new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(Level.INFO)
            .setHttpRequest(request)
            .setHttpResponse(response)
            .setMessageFormat("returning response:{}for forwarded request\n\n in json:{}\n\n in curl:{}")
            .setArguments(response, request, DeferredLogArgument.curl(new HttpRequestToCurlSerializer(new MockServerLogger()), request, new InetSocketAddress("localhost", 1080)));

        JsonNode node = serialize(entry);

        assertThat(node.get("arguments").get(0).asText(), is("201"));
        assertThat(node.get("arguments").get(1).asText(), is("POST /upload"));
        assertThat(node.get("arguments").get(2).asText(), is("POST /upload"));
        assertThat(node.get("arguments").toString(), not(containsString(REQUEST_BODY)));
        assertThat(node.get("arguments").toString(), not(containsString(RESPONSE_BODY)));
        assertThat(node.get("message").toString(), not(containsString(REQUEST_BODY)));
        assertThat(node.get("message").toString(), not(containsString(RESPONSE_BODY)));
        assertThat(node.at("/httpRequest/body").asText(), is(REQUEST_BODY));
        assertThat(node.at("/httpResponse/body").asText(), is(RESPONSE_BODY));
    }

    @Test
    public void shouldWriteAForwardedRequestsBodiesOnceIncludingItsRecordedExpectation() throws Exception {
        HttpRequest request = ownRequest();
        HttpResponse response = response().withStatusCode(201).withBody(RESPONSE_BODY);
        LogEntry entry = new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(Level.INFO)
            .setHttpRequest(request)
            .setHttpResponse(response)
            .setExpectation(request, response)
            .setMessageFormat("returning response:{}for forwarded request:{}")
            .setArguments(response, request);

        String json = new LogEntrySerializer(new MockServerLogger()).serialize(entry);
        JsonNode node = MAPPER.readTree(json);

        assertThat(occurrences(json, REQUEST_BODY), is(1));
        assertThat(occurrences(json, RESPONSE_BODY), is(1));
        assertThat(node.at("/expectation/httpRequest/path").asText(), is("/upload"));
        assertThat(node.at("/expectation/httpRequest/method").asText(), is("POST"));
        assertThat(node.at("/expectation/httpResponse/statusCode").asInt(), is(201));
        assertThat(node.at("/expectation/id").asText().isEmpty(), is(false));
        // the in-memory expectation, which recorded-expectation retrieval and the dashboard read, keeps its bodies
        assertThat(entry.getExpectation().getHttpResponse().getBodyAsString(), is(RESPONSE_BODY));
    }

    @Test
    public void shouldWriteAForwardedRequestsBodiesOnceWithRedactionOn() throws Exception {
        HttpRequest request = ownRequest().withHeader("Authorization", "Bearer secret-token-value");
        HttpResponse response = response().withStatusCode(201).withBody(RESPONSE_BODY);
        LogEntry entry = new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(Level.INFO)
            .setHttpRequest(request)
            .setHttpResponse(response)
            .setExpectation(request, response)
            .setMessageFormat("returning response:{}for forwarded request:{}")
            .setArguments(response, request);

        String json = new LogEntrySerializer(new MockServerLogger(), org.mockserver.configuration.Configuration.configuration().redactSecretsInLog(true)).serialize(entry);
        JsonNode node = MAPPER.readTree(json);

        assertThat(occurrences(json, REQUEST_BODY), is(1));
        assertThat(occurrences(json, RESPONSE_BODY), is(1));
        assertThat(json, not(containsString("secret-token-value")));
        assertThat(node.at("/expectation/httpRequest/path").asText(), is("/upload"));
        assertThat(node.at("/expectation/httpResponse/statusCode").asInt(), is(201));
        assertThat(node.get("arguments").get(1).asText(), is("POST /upload"));
    }

    @Test
    public void shouldWriteARealExpectationInFull() throws Exception {
        HttpRequest request = ownRequest();
        LogEntry entry = new LogEntry()
            .setType(EXPECTATION_MATCHED)
            .setLogLevel(Level.INFO)
            .setHttpRequest(request)
            .setExpectation(new org.mockserver.mock.Expectation(request().withBody("matcher-body")).thenRespond(response("canned-body")))
            .setMessageFormat("request:{}matched expectation")
            .setArguments(request);

        JsonNode node = serialize(entry);

        assertThat(node.at("/expectation/httpRequest/body").asText(), is("matcher-body"));
        assertThat(node.at("/expectation/httpResponse/body").asText(), is("canned-body"));
    }

    @Test
    public void shouldStillWriteAnArgumentThatIsNotTheEntrysOwnRequestInFull() throws Exception {
        HttpRequest other = request().withMethod("PUT").withPath("/other").withBody("other-body-in-full");
        LogEntry entry = new LogEntry()
            .setLogLevel(Level.INFO)
            .setHttpRequest(ownRequest())
            .setMessageFormat("compared with:{}")
            .setArguments(other);

        JsonNode node = serialize(entry);

        assertThat(node.at("/arguments/0/path").asText(), is("/other"));
        assertThat(node.at("/arguments/0/body").asText(), is("other-body-in-full"));
        assertThat(node.get("message").toString(), containsString("other-body-in-full"));
    }

    @Test
    public void shouldDeserializeTheCompactArgumentAsTheJavaClientDoes() {
        HttpRequest request = ownRequest();
        LogEntry entry = new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setLogLevel(Level.INFO)
            .setHttpRequest(request)
            .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
            .setArguments(request);
        LogEntrySerializer serializer = new LogEntrySerializer(new MockServerLogger());

        LogEntry[] deserialized = serializer.deserializeArray(serializer.serialize(java.util.List.of(entry)));

        assertThat(deserialized[0].getArguments()[0], is("POST /upload"));
        assertThat(deserialized[0].getMessage(), containsString("POST /upload"));
        assertThat(deserialized[0].getMessage(), not(containsString(REQUEST_BODY)));
    }

    @Test
    public void shouldLeaveTheRenderedMessageOutsideLogEntriesUnchanged() {
        HttpRequest request = ownRequest();
        LogEntry entry = new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setLogLevel(Level.INFO)
            .setHttpRequest(request)
            .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
            .setArguments(request);

        assertThat(entry.getMessage(), containsString(REQUEST_BODY));
        assertThat(entry.getArguments()[0] instanceof HttpRequest, is(true));
    }
}
