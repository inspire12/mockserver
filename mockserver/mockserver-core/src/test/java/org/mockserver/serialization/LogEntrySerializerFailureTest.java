package org.mockserver.serialization;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;

public class LogEntrySerializerFailureTest {

    private static final String MARKER = "/marker-path-that-must-not-be-rendered";

    private static LogEntry renderableEntry() {
        return new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setCorrelationId("good-correlation-id")
            .setHttpRequest(request(MARKER).withBody("marker-body-that-must-not-be-rendered"));
    }

    // fails only on its first read, so a fallback that renders it afterwards still renders its content
    private static LogEntry entryThatFailsOnce() {
        AtomicBoolean failed = new AtomicBoolean();
        return new LogEntry() {
            @Override
            public Level getLogLevel() {
                if (failed.compareAndSet(false, true)) {
                    throw new IllegalStateException("boom");
                }
                return super.getLogLevel();
            }
        }
            .setType(RECEIVED_REQUEST)
            .setCorrelationId("failing-correlation-id")
            .setHttpRequest(request(MARKER).withBody("marker-body-that-must-not-be-rendered"));
    }

    @Test
    public void shouldNotRenderEntriesWhenSerializingSeveralFails() {
        MockServerLogger logger = mock(MockServerLogger.class);
        LogEntrySerializer serializer = new LogEntrySerializer(logger);

        RuntimeException thrown = serializeExpectingFailure(() -> serializer.serialize(renderableEntry(), entryThatFailsOnce()));

        assertThat(thrown.getMessage(), not(containsString(MARKER)));
        assertThat(thrown.getMessage(), not(containsString("good-correlation-id")));
        assertThat(thrown.getMessage(), containsString("LogEntry to JSON (2 values"));
        assertThat(thrown.getMessage(), containsString("failed at index 1"));
        assertThat(thrown.getMessage(), containsString("failing-correlation-id"));
        assertThat(thrown.getMessage(), containsString("RECEIVED_REQUEST"));
        assertThat(ExceptionUtils.getRootCause(thrown).getMessage(), is("boom"));

        LogEntry logged = loggedEntry(logger);
        assertThat(logged.getLogLevel(), is(Level.ERROR));
        assertThat(logged.getThrowable(), sameInstance(thrown.getCause()));
        assertThat(logged.getMessage(), not(containsString(MARKER)));
        assertThat(logged.getMessage(), containsString("failed at index 1"));
        assertNoLogEntryArgument(logged);
    }

    @Test
    public void shouldNotRenderEntryWhenSerializingOneFails() {
        MockServerLogger logger = mock(MockServerLogger.class);
        LogEntrySerializer serializer = new LogEntrySerializer(logger);

        RuntimeException thrown = serializeExpectingFailure(() -> serializer.serialize(entryThatFailsOnce()));

        assertThat(thrown.getMessage(), not(containsString(MARKER)));
        assertThat(thrown.getMessage(), containsString("failing-correlation-id"));
        assertThat(ExceptionUtils.getRootCause(thrown).getMessage(), is("boom"));

        LogEntry logged = loggedEntry(logger);
        assertThat(logged.getThrowable(), sameInstance(thrown.getCause()));
        assertThat(logged.getMessage(), not(containsString(MARKER)));
        assertNoLogEntryArgument(logged);
    }

    private static RuntimeException serializeExpectingFailure(Runnable serialize) {
        try {
            serialize.run();
        } catch (RuntimeException e) {
            assertThat(e.getCause(), notNullValue());
            return e;
        }
        fail("expected serialization to fail");
        return null;
    }

    private static LogEntry loggedEntry(MockServerLogger logger) {
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(captor.capture());
        return captor.getValue();
    }

    private static void assertNoLogEntryArgument(LogEntry logged) {
        if (logged.getArguments() != null) {
            Arrays.stream(logged.getArguments()).forEach(argument -> {
                assertThat(argument, not(instanceOf(LogEntry.class)));
                assertThat(argument, not(instanceOf(Iterable.class)));
            });
        }
    }
}
