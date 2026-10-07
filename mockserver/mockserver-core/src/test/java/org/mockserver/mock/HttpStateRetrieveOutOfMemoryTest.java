package org.mockserver.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Format;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RetrieveType;
import org.mockserver.model.StringBody;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A retrieve builds its whole response in memory. When that runs out of memory (a heap too small
 * for the log, or a response past the size of one array) the caller must be told so, not left with
 * a dropped connection. Running a JVM out of memory in a test is neither cheap nor safe, so the
 * recorded body below raises the same error when the retrieving thread serialises it.
 */
@RunWith(Parameterized.class)
public class HttpStateRetrieveOutOfMemoryTest {

    private static final Object[][] RETRIEVES = {
        {RetrieveType.LOGS, Format.LOG_ENTRIES},
        {RetrieveType.LOGS, Format.JSON},
        {RetrieveType.REQUESTS, Format.JSON},
        {RetrieveType.REQUESTS, Format.JAVA},
        {RetrieveType.REQUESTS, Format.LOG_ENTRIES},
        {RetrieveType.REQUEST_RESPONSES, Format.JSON},
        {RetrieveType.REQUEST_RESPONSES, Format.LOG_ENTRIES},
        {RetrieveType.RECORDED_EXPECTATIONS, Format.JSON},
        {RetrieveType.RECORDED_EXPECTATIONS, Format.JAVA},
        {RetrieveType.RECORDED_EXPECTATIONS, Format.LOG_ENTRIES},
        {RetrieveType.ACTIVE_EXPECTATIONS, Format.JSON},
        {RetrieveType.ACTIVE_EXPECTATIONS, Format.JAVA},
    };

    @Parameterized.Parameters(name = "{0} as {1} at {2}")
    public static Collection<Object[]> retrieves() {
        List<Object[]> retrieves = new ArrayList<>();
        for (Level logLevel : new Level[]{Level.WARN, Level.INFO}) {
            for (Object[] retrieve : RETRIEVES) {
                // LOGS as JSON is a list of the entries' messages. At INFO the event log has already rendered
                // and kept the message when it wrote the entry out, so the retrieve reads no body
                // and the body below cannot fail it; at WARN the retrieve renders the message.
                boolean readsNoBody = retrieve[0] == RetrieveType.LOGS && retrieve[1] == Format.JSON && logLevel == Level.INFO;
                if (!readsNoBody) {
                    retrieves.add(new Object[]{retrieve[0], retrieve[1], logLevel});
                }
            }
        }
        return retrieves;
    }

    private final RetrieveType type;
    private final Format format;
    private final Level logLevel;
    private HttpState httpState;
    private ScheduledExecutorService schedulerExecutor;

    public HttpStateRetrieveOutOfMemoryTest(RetrieveType type, Format format, Level logLevel) {
        this.type = type;
        this.format = format;
        this.logLevel = logLevel;
    }

    @Before
    public void setUp() {
        // set here, not left to the JVM-wide mockserver.logLevel, which differs between builds
        Configuration configuration = configuration().logLevel(logLevel);
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

    @Test(timeout = 60000)
    public void shouldAnswerAClearErrorWhenTheResponseCannotBeBuiltInMemory() {
        // given
        BodyThatRunsTheRetrievingThreadOutOfMemory body = new BodyThatRunsTheRetrievingThreadOutOfMemory();
        HttpRequest recorded = request("/recorded").withBody(body);
        httpState.log(new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setHttpRequest(recorded)
            .setMessageFormat("received request:{}")
            .setArguments(recorded));
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setHttpRequest(recorded)
            .setHttpResponse(response("forwarded"))
            .setExpectation(new Expectation(recorded).thenRespond(response("forwarded"))));
        httpState.add(new Expectation(recorded).thenRespond(response("mocked")));
        HttpRequest retrieve = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", type.name())
            .withQueryStringParameter("format", format.name());

        // when
        body.outOfMemory = true;
        HttpResponse tooLarge = handle(retrieve);

        // then
        assertThat(tooLarge.getStatusCode(), is(500));
        assertThat(tooLarge.getBodyAsString(), allOf(
            containsString("the retrieve response is too large to build in memory"),
            containsString("java.lang.OutOfMemoryError: Java heap space"),
            containsString("send a request matcher"),
            containsString("clear the log")
        ));
        assertThat(tooLarge.getBody().getContentType(), containsString("text/plain"));

        // and the same retrieve is answered once it fits
        body.outOfMemory = false;
        HttpResponse fits = handle(retrieve);
        assertThat(fits.getStatusCode(), is(200));
        assertThat(fits.getBodyAsString(), allOf(
            containsString("/recorded"),
            not(startsWith("the retrieve response is too large"))
        ));

        // and the failure is in the log
        HttpResponse logs = handle(request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.LOGS.name()));
        assertThat(logs.getBodyAsString(), allOf(
            containsString("retrieve response too large to build in memory for request:"),
            containsString("\"" + type.name() + "\""),
            containsString("Java heap space")
        ));
    }

    private HttpResponse handle(HttpRequest retrieve) {
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();
        assertThat(httpState.handle(retrieve, responseWriter, false), is(true));
        return responseWriter.response;
    }

    private static final class BodyThatRunsTheRetrievingThreadOutOfMemory extends StringBody {
        // the event log's own thread reads the body too; only the retrieving thread builds the response
        private final Thread retrievingThread = Thread.currentThread();
        private volatile boolean outOfMemory;

        private BodyThatRunsTheRetrievingThreadOutOfMemory() {
            super("a recorded body");
        }

        private void read() {
            if (outOfMemory && Thread.currentThread() == retrievingThread) {
                throw new OutOfMemoryError("Java heap space");
            }
        }

        @Override
        public String getValue() {
            read();
            return super.getValue();
        }

        @Override
        public String getValueWithoutCaching() {
            read();
            return super.getValueWithoutCaching();
        }

        @Override
        public byte[] getRawBytes() {
            read();
            return super.getRawBytes();
        }
    }

    private static final class CapturingResponseWriter extends ResponseWriter {
        private HttpResponse response;

        private CapturingResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
        }
    }
}
