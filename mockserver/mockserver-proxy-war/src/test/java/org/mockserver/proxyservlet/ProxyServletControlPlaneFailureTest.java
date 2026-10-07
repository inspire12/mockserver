package org.mockserver.proxyservlet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.responsewriter.ControlPlaneFailureResponse.UNEXPECTED_FAILURE_MESSAGE;

/**
 * How the ProxyServlet answers a control-plane request whose handling throws: a client error is a {@code 400}
 * carrying its message, anything else a {@code 500} with a generic message and one {@code ERROR} log entry.
 */
public class ProxyServletControlPlaneFailureTest {

    private static final String EXPECTATION_JSON = "{\"httpRequest\":{\"path\":\"/some_path\"},\"httpResponse\":{\"statusCode\":200}}";

    private RuntimeException fault;
    private HttpState httpStateHandler;
    private MockServerLogger mockServerLogger;

    @InjectMocks
    private ProxyServlet servlet;

    @Before
    public void setupFixture() {
        httpStateHandler = spy(new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class)) {
            @Override
            public List<Expectation> add(Expectation... expectations) {
                if (fault != null) {
                    throw fault;
                }
                return super.add(expectations);
            }
        });
        mockServerLogger = mock(MockServerLogger.class);
        servlet = new ProxyServlet();
        openMocks(this);
    }

    @After
    public void shutdown() {
        servlet.destroy();
    }

    @Test
    public void shouldAnswerAnUnexpectedFailureWithAServerErrorAndAGenericMessage() {
        // given
        fault = new NullPointerException("internal detail of the fault");

        // when
        MockHttpServletResponse response = putExpectation(EXPECTATION_JSON);

        // then
        String body = new String(response.getContentAsByteArray(), UTF_8);
        assertThat(response.getStatus(), is(500));
        assertThat(body, startsWith(UNEXPECTED_FAILURE_MESSAGE));
        assertThat(body, not(containsString("internal detail of the fault")));

        // and - logged once at ERROR, with the stack trace, under the correlation id the caller was given
        ArgumentCaptor<LogEntry> logged = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, atLeastOnce()).logEvent(logged.capture());
        List<LogEntry> errors = logged.getAllValues().stream()
            .filter(entry -> entry.getLogLevel() == Level.ERROR && entry.getThrowable() == fault)
            .collect(Collectors.toList());
        assertThat(errors, hasSize(1));
        assertThat(body, is(UNEXPECTED_FAILURE_MESSAGE + errors.get(0).getCorrelationId()));
    }

    @Test
    public void shouldAnswerAnUnsupportedOperationAsABadRequestWithItsMessage() {
        // given
        fault = new UnsupportedOperationException("expectation type not supported here");

        // when
        MockHttpServletResponse response = putExpectation(EXPECTATION_JSON);

        // then
        assertThat(response.getStatus(), is(400));
        assertThat(new String(response.getContentAsByteArray(), UTF_8), is("expectation type not supported here"));
    }

    @Test
    public void shouldAnswerAnInvalidExpectationAsABadRequestWithTheValidationMessage() {
        // when
        MockHttpServletResponse response = putExpectation("{\"httpRequest\":{\"path\":\"/some_path\"},\"httpResponse\":{\"statusCode\":\"not a number\"}}");

        // then
        assertThat(response.getStatus(), is(400));
        assertThat(new String(response.getContentAsByteArray(), UTF_8), containsString("incorrect expectation json format"));
    }

    private MockHttpServletResponse putExpectation(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/mockserver/expectation");
        request.addHeader(CONTENT_TYPE.toString(), "application/json; charset=utf-8");
        request.setContent(body.getBytes(UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.service(request, response);
        return response;
    }
}
