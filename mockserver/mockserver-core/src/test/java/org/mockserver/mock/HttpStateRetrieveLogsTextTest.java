package org.mockserver.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.formatting.StringFormatterWriteLogMessageTest;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Format;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RetrieveType;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.mock.HttpState.LOG_SEPARATOR;
import static org.mockserver.model.HttpRequest.request;

/**
 * The plain-text LOGS retrieve writes each message as it is rendered. A message argument that cannot be
 * written as JSON is shown by its fields in the rendered message, so the retrieve then answers the text
 * built from the rendered messages, as it did before.
 */
public class HttpStateRetrieveLogsTextTest {

    private static final long EPOCH = 1_700_000_000_123L;

    private Configuration configuration;
    private HttpState httpState;
    private ScheduledExecutorService schedulerExecutor;

    @Before
    public void setUp() {
        configuration = configuration().logLevel(Level.WARN);
        Scheduler scheduler = mock(Scheduler.class);
        schedulerExecutor = Executors.newScheduledThreadPool(2);
        when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler);
    }

    @After
    public void tearDown() {
        if (httpState != null) {
            httpState.stop();
        }
        schedulerExecutor.shutdownNow();
    }

    private static LogEntry entry(String messageFormat, Object... arguments) {
        LogEntry logEntry = new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setEpochTime(EPOCH)
            .setHttpRequest(request("/received").withBody("body\nwith a line"))
            .setMessageFormat(messageFormat);
        return arguments.length == 0 ? logEntry : logEntry.setArguments(arguments);
    }

    // the log keeps its own copy of an entry, so the expected text is rendered from an equal entry
    private String logAndRender(String messageFormat, Object... arguments) {
        httpState.log(entry(messageFormat, arguments));
        LogEntry expected = entry(messageFormat, arguments);
        return expected.getTimestamp() + " - " + expected.getMessage(configuration);
    }

    private String retrieveLogs() {
        HttpResponse response = httpState.retrieve(request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.LOGS.name())
            .withQueryStringParameter("format", Format.JSON.name()));
        assertThat(response.getStatusCode(), is(200));
        return response.getBodyAsString();
    }

    @Test
    public void shouldWriteEachMessageAsItIsRendered() {
        // given
        String first = logAndRender("received request:{}", request("/received").withBody("body\nwith a line"));
        String second = logAndRender("short message without arguments");

        // when
        String logs = retrieveLogs();

        // then
        assertThat(logs, is(first + LOG_SEPARATOR + second + NEW_LINE));
        assertThat(logs, containsString("  \"path\" : \"/received\""));
    }

    @Test
    public void shouldAnswerTheRenderedMessagesWhenAnArgumentCannotBeWrittenAsJson() {
        // given
        String first = logAndRender("received request:{}", request("/first"));
        String unserialisable = logAndRender("value:{}and request:{}", new StringFormatterWriteLogMessageTest.Unserialisable(), request("/second"));

        // when
        String logs = retrieveLogs();

        // then
        assertThat(logs, is(first + LOG_SEPARATOR + unserialisable + NEW_LINE));
        assertThat(logs, containsString("Unserialisable[]"));
    }
}
