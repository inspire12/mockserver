package org.mockserver.persistence;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.slf4j.event.Level;

import java.io.File;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class PersistenceSerializerFailureMessageTest {

    private static final String MARKER = "marker-that-must-not-be-rendered";

    private static Expectation marked() {
        return new Expectation(request("/" + MARKER)).thenRespond(response(MARKER));
    }

    private static Expectation failing() {
        Expectation expectation = spy(marked());
        doThrow(new IllegalStateException("boom")).when(expectation).getHttpRequest();
        return expectation;
    }

    @Test
    public void persistedExpectations() throws Exception {
        File file = File.createTempFile("persistedExpectations", ".json");
        file.deleteOnExit();
        Configuration configuration = configuration().persistExpectations(true).persistedExpectationsPath(file.getAbsolutePath());
        MockServerLogger logger = mock(MockServerLogger.class);
        ExpectationFileSystemPersistence persistence = new ExpectationFileSystemPersistence(configuration, logger, mock(RequestMatchers.class));
        try {
            assertBoundedFailure("expectation", logger, ignored -> persistence.serialize(marked(), failing()));
        } finally {
            persistence.stop();
        }
    }

    @Test
    public void persistedRecordedExpectations() throws Exception {
        File file = File.createTempFile("persistedRecordedExpectations", ".json");
        file.deleteOnExit();
        Configuration configuration = configuration().persistRecordedExpectations(true).persistedRecordedExpectationsPath(file.getAbsolutePath());
        MockServerLogger logger = mock(MockServerLogger.class);
        RecordedExpectationFileSystemPersistence persistence = new RecordedExpectationFileSystemPersistence(configuration, logger, mock(MockServerEventLog.class));
        assertBoundedFailure("recorded expectation", logger, ignored -> persistence.serialize(marked(), failing()));
    }

    private static void assertBoundedFailure(String typeName, MockServerLogger logger, Function<Void, String> serialize) {
        RuntimeException thrown = null;
        try {
            serialize.apply(null);
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
        verify(logger, atLeastOnce()).logEvent(captor.capture());
        LogEntry error = captor.getAllValues().stream()
            .filter(entry -> entry.getLogLevel() == Level.ERROR)
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("no ERROR entry logged"));
        assertThat(error.getThrowable(), is(thrown.getCause()));
        assertThat(error.getMessage(), containsString("2 values"));
        assertThat(error.getMessage(), not(containsString(MARKER)));
    }
}
