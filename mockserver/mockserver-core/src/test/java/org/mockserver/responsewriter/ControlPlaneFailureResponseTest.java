package org.mockserver.responsewriter;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.responsewriter.ControlPlaneFailureResponse.UNEXPECTED_FAILURE_MESSAGE;

public class ControlPlaneFailureResponseTest {

    @Test
    public void shouldTreatInvalidInputAndUnsupportedOperationsAsClientErrors() {
        assertThat(ControlPlaneFailureResponse.isClientError(new IllegalArgumentException("invalid")), is(true));
        assertThat(ControlPlaneFailureResponse.isClientError(new NumberFormatException("not a number")), is(true));
        assertThat(ControlPlaneFailureResponse.isClientError(new UnsupportedOperationException("not supported")), is(true));
        assertThat(ControlPlaneFailureResponse.isClientError(new JsonParseException(null, "unreadable json")), is(true));
    }

    @Test
    public void shouldTreatEverythingElseAsAFaultInMockServer() {
        assertThat(ControlPlaneFailureResponse.isClientError(new NullPointerException()), is(false));
        assertThat(ControlPlaneFailureResponse.isClientError(new IllegalStateException("bad state")), is(false));
        assertThat(ControlPlaneFailureResponse.isClientError(new RuntimeException(new IllegalArgumentException("wrapped"))), is(false));
        assertThat(ControlPlaneFailureResponse.isClientError(new OutOfMemoryError()), is(false));
    }

    @Test
    public void shouldTreatAFailureToWriteJsonAsAFaultInMockServer() {
        assertThat(ControlPlaneFailureResponse.isClientError(new JsonGenerationException("cannot write", (JsonGenerator) null)), is(false));
        assertThat(ControlPlaneFailureResponse.isClientError(InvalidDefinitionException.from((JsonGenerator) null, "no serializer found", (JavaType) null)), is(false));
    }

    @Test
    public void shouldAnswerAFailureToWriteJsonWithAGenericMessage() {
        // when
        Captured captured = write(requestWithCorrelationId("some-id"), new JsonGenerationException("internal detail of the fault", (JsonGenerator) null));

        // then
        assertThat(captured.response.getStatusCode(), is(500));
        assertThat(captured.response.getBodyAsString(), is(UNEXPECTED_FAILURE_MESSAGE + "some-id"));
    }

    @Test
    public void shouldAnswerAClientErrorWithItsMessage() {
        // when
        Captured captured = write(requestWithCorrelationId("some-id"), new IllegalArgumentException("incorrect expectation json format"));

        // then
        assertThat(captured.response.getStatusCode(), is(400));
        assertThat(captured.response.getBodyAsString(), is("incorrect expectation json format"));
        assertThat(captured.response.getFirstHeader("content-type"), is("text/plain; charset=utf-8"));
        assertThat(captured.errors(), hasSize(1));
        assertThat(captured.errors().get(0).getCorrelationId(), is("some-id"));
    }

    @Test
    public void shouldAnswerAFaultWithAGenericMessageAndLogItOnceWithTheCorrelationId() {
        // given
        IllegalStateException fault = new IllegalStateException("internal detail of the fault");

        // when
        Captured captured = write(requestWithCorrelationId("some-id"), fault);

        // then
        assertThat(captured.response.getStatusCode(), is(500));
        assertThat(captured.response.getBodyAsString(), is(UNEXPECTED_FAILURE_MESSAGE + "some-id"));
        assertThat(captured.response.getFirstHeader("content-type"), is("text/plain; charset=utf-8"));
        assertThat(captured.errors(), hasSize(1));
        assertThat(captured.errors().get(0).getThrowable(), sameInstance(fault));
        assertThat(captured.errors().get(0).getCorrelationId(), is("some-id"));
    }

    @Test
    public void shouldNameACorrelationIdForAFaultBeforeTheRequestWasGivenOne() {
        // when
        Captured captured = write(request("/mockserver/expectation"), new IllegalStateException("fault"));

        // then
        String correlationId = captured.errors().get(0).getCorrelationId();
        assertThat(correlationId, not(emptyOrNullString()));
        assertThat(captured.response.getBodyAsString(), is(UNEXPECTED_FAILURE_MESSAGE + correlationId));
    }

    private static HttpRequest requestWithCorrelationId(String correlationId) {
        HttpRequest request = request("/mockserver/expectation");
        request.withLogCorrelationId(correlationId);
        return request;
    }

    private static Captured write(HttpRequest request, Throwable throwable) {
        Captured captured = new Captured();
        MockServerLogger logger = new MockServerLogger(ControlPlaneFailureResponseTest.class) {
            @Override
            public void logEvent(LogEntry logEntry) {
                captured.logged.add(logEntry);
            }
        };
        ResponseWriter responseWriter = new ResponseWriter(configuration(), logger) {
            @Override
            public void sendResponse(HttpRequest request, HttpResponse response) {
                assertThat("one response", captured.response, nullValue());
                captured.response = response;
            }
        };
        ControlPlaneFailureResponse.write(logger, responseWriter, request, throwable);
        assertThat("a response", captured.response, notNullValue());
        return captured;
    }

    private static class Captured {
        private final List<LogEntry> logged = new ArrayList<>();
        private HttpResponse response;

        private List<LogEntry> errors() {
            List<LogEntry> errors = new ArrayList<>();
            for (LogEntry entry : logged) {
                if (entry.getLogLevel() == Level.ERROR) {
                    errors.add(entry);
                }
            }
            return errors;
        }
    }
}
