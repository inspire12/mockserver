package org.mockserver.serialization;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.OpenAPIExpectation;
import org.mockserver.model.ExpectationId;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpRequestAndHttpResponse;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.model.RequestDefinition;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A serializer that fails part way through a list must say how many values there were, keep the cause,
 * and never render the values into its exception message or its logged error.
 */
public class SerializerFailureMessageTest {

    private static final String MARKER = "marker-that-must-not-be-rendered";

    private static HttpRequest markedRequest() {
        return request("/" + MARKER);
    }

    private static HttpResponse markedResponse() {
        return response(MARKER);
    }

    private static HttpRequest failingRequest() {
        HttpRequest request = spy(markedRequest());
        doThrow(new IllegalStateException("boom")).when(request).getMethod();
        return request;
    }

    private static HttpResponse failingResponse() {
        HttpResponse response = spy(markedResponse());
        doThrow(new IllegalStateException("boom")).when(response).getStatusCode();
        return response;
    }

    @Test
    public void httpRequests() {
        assertBoundedFailure("HttpRequest", logger -> new HttpRequestSerializer(logger).serialize(markedRequest(), failingRequest()));
        assertBoundedFailure("HttpRequest", logger -> new HttpRequestSerializer(logger).serialize(true, markedRequest(), failingRequest()));
    }

    @Test
    public void httpResponses() {
        assertBoundedFailure("HttpResponse", logger -> new HttpResponseSerializer(logger).serialize(markedResponse(), failingResponse()));
    }

    @Test
    public void requestDefinitions() {
        assertBoundedFailure("RequestDefinition", logger -> new RequestDefinitionSerializer(logger).serialize(false, markedRequest(), failingRequest()));
        assertBoundedFailure("RequestDefinition", logger -> new RequestDefinitionSerializer(logger).serializeRecordedRequests(false, List.<RequestDefinition>of(markedRequest(), failingRequest())));
    }

    @Test
    public void httpRequestsAndResponses() {
        HttpRequestAndHttpResponse failing = spy(new HttpRequestAndHttpResponse().withHttpRequest(markedRequest()).withHttpResponse(markedResponse()));
        doThrow(new IllegalStateException("boom")).when(failing).getHttpResponse();
        assertBoundedFailure("HttpRequestAndHttpResponse", logger -> new HttpRequestAndHttpResponseSerializer(logger).serialize(
            new HttpRequestAndHttpResponse().withHttpRequest(markedRequest()).withHttpResponse(markedResponse()), failing));
    }

    @Test
    public void logEventRequestsAndResponses() {
        LogEventRequestAndResponse failing = spy(new LogEventRequestAndResponse().withHttpRequest(markedRequest()).withHttpResponse(markedResponse()));
        doThrow(new IllegalStateException("boom")).when(failing).getHttpResponse();
        assertBoundedFailure("HttpRequestAndHttpResponse", logger -> new LogEventRequestAndResponseSerializer(logger).serialize(
            new LogEventRequestAndResponse().withHttpRequest(markedRequest()).withHttpResponse(markedResponse()), failing));
    }

    @Test
    public void expectations() {
        Expectation failing = spy(new Expectation(markedRequest()).thenRespond(markedResponse()));
        doThrow(new IllegalStateException("boom")).when(failing).getHttpRequest();
        assertBoundedFailure("expectation", logger -> new ExpectationSerializer(logger).serialize(
            new Expectation(markedRequest()).thenRespond(markedResponse()), failing));
    }

    @Test
    public void openApiExpectations() {
        OpenAPIExpectation failing = spy(OpenAPIExpectation.openAPIExpectation(MARKER));
        doThrow(new IllegalStateException("boom")).when(failing).getSpecUrlOrPayload();
        assertBoundedFailure("expectation", logger -> new OpenAPIExpectationSerializer(logger).serialize(
            OpenAPIExpectation.openAPIExpectation(MARKER), failing));
    }

    @Test
    public void expectationIds() {
        ExpectationId failing = spy(ExpectationId.expectationId(MARKER));
        doThrow(new IllegalStateException("boom")).when(failing).getId();
        assertBoundedFailure("ExpectationId", logger -> new ExpectationIdSerializer(logger).serialize(ExpectationId.expectationId(MARKER), failing));
    }

    private static void assertBoundedFailure(String typeName, Function<MockServerLogger, String> serialize) {
        MockServerLogger logger = mock(MockServerLogger.class);
        RuntimeException thrown = null;
        try {
            serialize.apply(logger);
        } catch (RuntimeException e) {
            thrown = e;
        }
        if (thrown == null) {
            fail("expected serializing " + typeName + " to fail");
        }
        assertThat(thrown.getMessage(), containsString(typeName + " to JSON (2 values"));
        assertThat(thrown.getMessage(), not(containsString(MARKER)));
        assertThat(ExceptionUtils.getRootCause(thrown).getMessage(), is("boom"));

        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(captor.capture());
        LogEntry logged = captor.getValue();
        assertThat(logged.getThrowable(), is(thrown.getCause()));
        assertThat(logged.getMessage(), containsString("2 values"));
        assertThat(logged.getMessage(), not(containsString(MARKER)));
        Arrays.stream(logged.getArguments()).forEach(argument -> assertThat(argument, not(instanceOf(Iterable.class))));
    }
}
