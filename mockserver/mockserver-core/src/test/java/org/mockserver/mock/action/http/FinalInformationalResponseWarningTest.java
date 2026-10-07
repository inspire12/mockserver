package org.mockserver.mock.action.http;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Action;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;
import org.slf4j.event.Level;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class FinalInformationalResponseWarningTest {

    private MockServerLogger mockServerLogger;
    private FinalInformationalResponseWarning warning;

    @Before
    public void createWarning() {
        mockServerLogger = mock(MockServerLogger.class);
        when(mockServerLogger.isEnabledForInstance(Level.WARN)).thenReturn(true);
        warning = new FinalInformationalResponseWarning();
    }

    @Test
    public void shouldWarnOnceForAnExpectationsFinalInformationalResponseOverHttp2() {
        HttpResponse action = expectationResponse("one", 102);

        for (int request = 0; request < 3; request++) {
            warning.warnOnce(mockServerLogger, request().withProtocol(Protocol.HTTP_2), action, action);
        }

        ArgumentCaptor<LogEntry> logged = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, times(1)).logEvent(logged.capture());
        LogEntry entry = logged.getValue();
        assertThat(entry.getLogLevel(), is(Level.WARN));
        assertThat("no stack trace", entry.getThrowable(), nullValue());
        assertThat(entry.getExpectationId(), is("one"));
        assertThat(entry.getMessage(), org.hamcrest.Matchers.containsString("reset the stream with NO_ERROR"));
    }

    @Test
    public void shouldWarnForEachExpectationEvenWhenTheirResponsesAreEqual() {
        warning.warnOnce(mockServerLogger, request().withProtocol(Protocol.HTTP_2), response().withStatusCode(103), expectationResponse("one", 103));
        warning.warnOnce(mockServerLogger, request().withProtocol(Protocol.HTTP_2), response().withStatusCode(103), expectationResponse("two", 103));

        ArgumentCaptor<LogEntry> logged = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, times(2)).logEvent(logged.capture());
        assertThat(logged.getAllValues().stream().map(LogEntry::getExpectationId).collect(java.util.stream.Collectors.toList()), contains("one", "two"));
    }

    @Test
    public void shouldNotWarnForAnythingElse() {
        Action<?> action = expectationResponse("one", 102);

        warning.warnOnce(mockServerLogger, request().withProtocol(Protocol.HTTP_1_1), response().withStatusCode(102), action);
        warning.warnOnce(mockServerLogger, request(), response().withStatusCode(102), action);
        warning.warnOnce(mockServerLogger, request().withProtocol(Protocol.HTTP_3), response().withStatusCode(102), action);
        warning.warnOnce(mockServerLogger, request().withProtocol(Protocol.HTTP_2), response().withStatusCode(101), action);
        warning.warnOnce(mockServerLogger, request().withProtocol(Protocol.HTTP_2), response().withStatusCode(200), action);
        warning.warnOnce(mockServerLogger, request().withProtocol(Protocol.HTTP_2), response(), action);
        warning.warnOnce(mockServerLogger, request().withProtocol(Protocol.HTTP_2), response().withStatusCode(102), null);

        verify(mockServerLogger, never()).logEvent(any(LogEntry.class));
    }

    private static HttpResponse expectationResponse(String expectationId, int statusCode) {
        HttpResponse response = response().withStatusCode(statusCode);
        response.setExpectationId(expectationId);
        return response;
    }
}
