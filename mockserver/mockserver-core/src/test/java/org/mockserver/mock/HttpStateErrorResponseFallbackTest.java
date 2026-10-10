package org.mockserver.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.responsewriter.ControlPlaneFailureResponse.UNEXPECTED_FAILURE_MESSAGE;

/**
 * When the JSON body of a caller's error cannot be written, that is a fault in MockServer: the endpoint answers
 * {@code 500} with the generic correlation-id message, logged once at {@code ERROR} with the stack trace, not a
 * {@code 400} that reads as the caller's mistake.
 */
public class HttpStateErrorResponseFallbackTest {

    private static final String FAULT_DETAIL = "internal detail of the fault";

    private final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private final Configuration configuration = configuration();
    private final Scheduler scheduler = new Scheduler(configuration, new MockServerLogger(), true);
    private final HttpState httpState = new HttpState(configuration, capturingLogger(), scheduler);

    @After
    public void stop() {
        httpState.stop();
        scheduler.shutdown();
    }

    @Test
    public void shouldAnswerAnUnwritableErrorBodyOfEachErrorBuilderAsAFault() throws Exception {
        Object[][] builders = {
            {"serviceChaosError", new Class<?>[]{ObjectMapper.class, String.class}, new Object[]{null, "caller error"}},
            {"chaosExperimentError", new Class<?>[]{ObjectMapper.class, String.class}, new Object[]{null, "caller error"}},
            {"loadScenarioError", new Class<?>[]{ObjectMapper.class, int.class, String.class}, new Object[]{null, 404, "caller error"}},
            {"tcpChaosError", new Class<?>[]{ObjectMapper.class, String.class}, new Object[]{null, "caller error"}},
            {"grpcChaosError", new Class<?>[]{ObjectMapper.class, String.class}, new Object[]{null, "caller error"}},
            {"cassetteError", new Class<?>[]{ObjectMapper.class, String.class}, new Object[]{null, "caller error"}},
            {"scenarioError", new Class<?>[]{ObjectMapper.class, String.class}, new Object[]{null, "caller error"}},
            {"generateExpectationError", new Class<?>[]{ObjectMapper.class, String.class}, new Object[]{null, "caller error"}},
            {"sloError", new Class<?>[]{ObjectMapper.class, int.class, String.class}, new Object[]{null, 400, "caller error"}},
            {"buildGenerateExpectationResponse", new Class<?>[]{ObjectMapper.class, List.class, double.class, boolean.class, String.class}, new Object[]{null, Collections.emptyList(), 0.5, true, null}},
            {"breakpointErrorResponse", new Class<?>[]{HttpRequest.class, ObjectMapper.class, Exception.class}, new Object[]{request(), null, new IllegalArgumentException("caller error")}},
        };
        for (Object[] builder : builders) {
            // given
            logged.clear();
            Class<?>[] parameterTypes = (Class<?>[]) builder[1];
            Object[] arguments = ((Object[]) builder[2]).clone();
            for (int i = 0; i < parameterTypes.length; i++) {
                if (parameterTypes[i] == ObjectMapper.class) {
                    arguments[i] = new UnwritableObjectMapper();
                }
            }
            Method method = HttpState.class.getDeclaredMethod((String) builder[0], parameterTypes);
            method.setAccessible(true);

            // when
            HttpResponse response = (HttpResponse) method.invoke(httpState, arguments);

            // then
            assertFault(builder[0] + "", response);
        }
    }

    private void assertFault(String reason, HttpResponse response) {
        assertThat(reason, response, notNullValue());
        assertThat(reason, response.getStatusCode(), is(500));
        assertThat(reason, response.getBodyAsString(), containsString(UNEXPECTED_FAILURE_MESSAGE));
        assertThat(reason, response.getBodyAsString(), not(containsString(FAULT_DETAIL)));
        List<LogEntry> errors = logged.stream()
            .filter(entry -> entry.getLogLevel() == Level.ERROR && entry.getThrowable() != null && FAULT_DETAIL.equals(entry.getThrowable().getMessage()))
            .collect(Collectors.toList());
        assertThat(reason + ": the fault is logged once at ERROR with its stack trace", errors, hasSize(1));
        assertThat(reason, response.getBodyAsString(), containsString(UNEXPECTED_FAILURE_MESSAGE + errors.get(0).getCorrelationId()));
    }

    private MockServerLogger capturingLogger() {
        return new MockServerLogger(HttpStateErrorResponseFallbackTest.class) {
            @Override
            public void logEvent(LogEntry logEntry) {
                logged.add(logEntry);
            }
        };
    }

    /**
     * Reads and builds JSON normally, but cannot write it.
     */
    private static class UnwritableObjectMapper extends ObjectMapper {
        @Override
        public ObjectWriter writerWithDefaultPrettyPrinter() {
            throw new IllegalStateException(FAULT_DETAIL);
        }

        @Override
        public String writeValueAsString(Object value) {
            throw new IllegalStateException(FAULT_DETAIL);
        }
    }
}
