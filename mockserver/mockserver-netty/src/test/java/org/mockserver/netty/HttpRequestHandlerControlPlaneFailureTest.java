package org.mockserver.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Test;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.responsewriter.ControlPlaneFailureResponse.UNEXPECTED_FAILURE_MESSAGE;

/**
 * How the HTTP/1.1 and HTTP/2 frontend answers a control-plane request whose handling throws: a client error is a
 * {@code 400} carrying its message, anything else a {@code 500} with a generic message and one {@code ERROR} log entry.
 */
public class HttpRequestHandlerControlPlaneFailureTest {

    private static final String EXPECTATION_JSON = "{\"httpRequest\":{\"path\":\"/some_path\"},\"httpResponse\":{\"statusCode\":200}}";

    private final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private EmbeddedChannel embeddedChannel;
    private HttpState httpState;

    @After
    public void closeChannel() {
        if (embeddedChannel != null) {
            embeddedChannel.finishAndReleaseAll();
        }
        if (httpState != null) {
            httpState.stop();
        }
    }

    @Test
    public void shouldAnswerAnUnexpectedFailureWithAServerErrorAndAGenericMessage() {
        // given - a fault inside MockServer while it stores an expectation
        NullPointerException fault = new NullPointerException("internal detail of the fault");
        givenAddingAnExpectationThrows(() -> fault);

        // when
        HttpResponse response = putExpectation(EXPECTATION_JSON);

        // then
        assertThat(response.getStatusCode(), is(500));
        assertThat(response.getBodyAsString(), startsWith(UNEXPECTED_FAILURE_MESSAGE));
        assertThat(response.getBodyAsString(), not(containsString("internal detail of the fault")));
        assertThat(response.getFirstHeader("content-type"), is("text/plain; charset=utf-8"));

        // and - logged once at ERROR, with the stack trace, under the correlation id the caller was given
        List<LogEntry> errors = errorsFor(fault);
        assertThat(errors, hasSize(1));
        assertThat(response.getBodyAsString(), is(UNEXPECTED_FAILURE_MESSAGE + errors.get(0).getCorrelationId()));
        assertThat(errors.get(0).getCorrelationId(), not(emptyOrNullString()));
    }

    @Test
    public void shouldAnswerAnErrorThrownWhileHandlingWithAServerErrorRatherThanDropTheExchange() {
        // given
        StackOverflowError fault = new StackOverflowError();
        givenAddingAnExpectationThrows(() -> fault);

        // when
        HttpResponse response = putExpectation(EXPECTATION_JSON);

        // then
        assertThat(response.getStatusCode(), is(500));
        assertThat(response.getBodyAsString(), startsWith(UNEXPECTED_FAILURE_MESSAGE));
        assertThat(errorsFor(fault), hasSize(1));
    }

    @Test
    public void shouldAnswerAnUnsupportedOperationAsABadRequestWithItsMessage() {
        // given
        givenAddingAnExpectationThrows(() -> new UnsupportedOperationException("expectation type not supported here"));

        // when
        HttpResponse response = putExpectation(EXPECTATION_JSON);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), is("expectation type not supported here"));
    }

    @Test
    public void shouldAnswerAnInvalidExpectationAsABadRequestWithTheValidationMessage() {
        // given
        givenAddingAnExpectationThrows(null);

        // when
        HttpResponse response = putExpectation("{\"httpRequest\":{\"path\":\"/some_path\"},\"httpResponse\":{\"statusCode\":\"not a number\"}}");

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("incorrect expectation json format"));
        assertThat(response.getBodyAsString(), not(startsWith(UNEXPECTED_FAILURE_MESSAGE)));
    }

    @Test
    public void shouldAnswerUnreadableJsonForAnAgentRunDiffAsABadRequest() {
        // given
        givenAddingAnExpectationThrows(null);

        // when
        embeddedChannel.writeInbound(request("/mockserver/llm/diffRuns").withMethod("PUT").withBody("{not json"));
        HttpResponse response = embeddedChannel.readOutbound();

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), not(startsWith(UNEXPECTED_FAILURE_MESSAGE)));
    }

    @Test
    public void shouldNotReportAnEmptyOptimisationReportWhenRetrievingTheRecordedTrafficFailed() {
        // given - the retrieve behind the report fails rather than finding nothing
        httpState = new HttpState(configuration(), capturingLogger(), synchronousScheduler()) {
            @Override
            public HttpResponse retrieve(HttpRequest request) {
                return response().withStatusCode(500).withBody("the retrieve response is too large to build in memory");
            }
        };
        build(httpState);

        // when
        embeddedChannel.writeInbound(request("/mockserver/llm/optimisationReport").withMethod("GET"));
        HttpResponse response = embeddedChannel.readOutbound();

        // then
        assertThat(response.getStatusCode(), is(500));
        assertThat(response.getBodyAsString(), startsWith(UNEXPECTED_FAILURE_MESSAGE));
    }

    @Test
    public void shouldAnswerAFaultWhileUpdatingTheConfigurationWithAServerErrorAndAGenericMessage() {
        // given - a fault inside MockServer while it applies a configuration update
        IllegalStateException fault = new IllegalStateException("internal detail of the fault");
        httpState = new HttpState(configuration(), capturingLogger(), synchronousScheduler()) {
            @Override
            public void applyConfigurationUpdate(org.mockserver.serialization.model.ConfigurationDTO suppliedConfiguration) {
                throw fault;
            }
        };
        build(httpState);

        // when
        embeddedChannel.writeInbound(request("/mockserver/configuration").withMethod("PUT").withBody("{}"));
        HttpResponse response = embeddedChannel.readOutbound();

        // then
        assertThat(response.getStatusCode(), is(500));
        assertThat(response.getBodyAsString(), startsWith(UNEXPECTED_FAILURE_MESSAGE));
        assertThat(response.getBodyAsString(), not(containsString("internal detail of the fault")));
        List<LogEntry> errors = errorsFor(fault);
        assertThat(errors, hasSize(1));
        assertThat(response.getBodyAsString(), is(UNEXPECTED_FAILURE_MESSAGE + errors.get(0).getCorrelationId()));
    }

    @Test
    public void shouldAnswerUnreadableConfigurationJsonAsABadRequest() {
        // given
        givenAddingAnExpectationThrows(null);

        // when
        embeddedChannel.writeInbound(request("/mockserver/configuration").withMethod("PUT").withBody("{not json"));
        HttpResponse response = embeddedChannel.readOutbound();

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), is("Invalid configuration JSON"));
    }

    private void givenAddingAnExpectationThrows(Supplier<Throwable> fault) {
        httpState = new HttpState(configuration(), capturingLogger(), synchronousScheduler()) {
            @Override
            public List<Expectation> add(Expectation... expectations) {
                if (fault == null) {
                    return super.add(expectations);
                }
                Throwable throwable = fault.get();
                if (throwable instanceof Error) {
                    throw (Error) throwable;
                }
                throw (RuntimeException) throwable;
            }
        };
        build(httpState);
    }

    private void build(HttpState httpState) {
        LifeCycle server = mock(MockServer.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        embeddedChannel = new EmbeddedChannel(new HttpRequestHandler(configuration(), server, httpState, null));
    }

    private MockServerLogger capturingLogger() {
        return new MockServerLogger(HttpRequestHandlerControlPlaneFailureTest.class) {
            @Override
            public void logEvent(LogEntry logEntry) {
                logged.add(logEntry);
            }
        };
    }

    private static Scheduler synchronousScheduler() {
        Scheduler scheduler = mock(Scheduler.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(scheduler).submit(any(Runnable.class));
        return scheduler;
    }

    private HttpResponse putExpectation(String body) {
        embeddedChannel.writeInbound(request("/mockserver/expectation").withMethod("PUT").withBody(body));
        return embeddedChannel.readOutbound();
    }

    private List<LogEntry> errorsFor(Throwable fault) {
        return logged.stream()
            .filter(entry -> entry.getLogLevel() == Level.ERROR && entry.getThrowable() == fault)
            .collect(Collectors.toList());
    }
}
