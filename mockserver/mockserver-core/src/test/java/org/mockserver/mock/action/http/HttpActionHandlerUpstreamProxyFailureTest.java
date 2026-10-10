package org.mockserver.mock.action.http;

import io.netty.channel.ConnectTimeoutException;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.httpclient.UpstreamProxyUnreachableException;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.lang.reflect.Constructor;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;

/**
 * A forward that failed because its upstream proxy could not be reached is answered, and logged, on the matched
 * forward and unmatched proxy routes exactly as the failure under it was before it was told apart.
 */
public class HttpActionHandlerUpstreamProxyFailureTest {

    private static final HttpForward ACTION = forward().withHost("upstream.example").withPort(8080).withScheme(HttpForward.Scheme.HTTP);
    private static final InetSocketAddress UPSTREAM = InetSocketAddress.createUnresolved("upstream.example", 8080);
    private static final InetSocketAddress PROXY = InetSocketAddress.createUnresolved("proxy.example", 3128);

    private interface Route {
        void fail(HttpActionHandler handler, HttpRequest request, ResponseWriter responseWriter, Throwable failure);
    }

    private static final Route MATCHED_FORWARD = (handler, request, writer, failure) -> handler.handleExceptionDuringForwardingRequest(ACTION, request, writer, failure);
    private static final Route UNMATCHED_PROXY = (handler, request, writer, failure) -> handler.handleUnmatchedForwardFailure(failure, request, writer, UPSTREAM, false);
    private static final Route EXPLORATORY_PROXY = (handler, request, writer, failure) -> handler.handleUnmatchedForwardFailure(failure, request, writer, UPSTREAM, true);

    @Test
    public void shouldAnswerAndLogAnUnreachableProxyAsTheFailureUnderItOnEveryRoute() throws Exception {
        Throwable[] causes = {
            new ConnectException("Connection refused"),
            new ConnectTimeoutException("connection timed out after 5000 ms: proxy.example/192.0.2.1:3128"),
            new UnknownHostException("proxy.example: nodename nor servname provided, or not known"),
            new UnresolvedAddressException(),
            new NoRouteToHostException("No route to host")
        };
        List<Function<Throwable, Throwable>> deliveries = List.of(failure -> failure, ExecutionException::new, CompletionException::new);
        for (Route route : new Route[]{MATCHED_FORWARD, UNMATCHED_PROXY, EXPLORATORY_PROXY}) {
            for (Throwable cause : causes) {
                for (Function<Throwable, Throwable> delivered : deliveries) {
                    String asBefore = outcome(route, delivered.apply(cause));
                    String asUnreachable = outcome(route, delivered.apply(unreachable(cause)));

                    assertThat(asUnreachable, is(asBefore));
                    assertThat(asUnreachable, not(containsString(UpstreamProxyUnreachableException.class.getSimpleName())));
                }
            }
        }
    }

    private static String outcome(Route route, Throwable failure) {
        List<LogEntry> logged = new CopyOnWriteArrayList<>();
        MockServerLogger mockServerLogger = new MockServerLogger(HttpActionHandlerUpstreamProxyFailureTest.class) {
            @Override
            public boolean isEnabledForInstance(Level level) {
                return true;
            }

            @Override
            public void logEvent(LogEntry logEntry) {
                logged.add(logEntry);
            }
        };
        HttpState httpState = mock(HttpState.class);
        when(httpState.getMockServerLogger()).thenReturn(mockServerLogger);
        when(httpState.getUniqueLoopPreventionHeaderName()).thenReturn("x-forwarded-by");
        when(httpState.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_" + UUIDService.getUUID());
        HttpActionHandler handler = new HttpActionHandler(configuration(), null, httpState, null, null);
        ResponseWriter responseWriter = mock(ResponseWriter.class);
        HttpRequest request = request("/some_path");
        request.withLogCorrelationId("correlation");

        route.fail(handler, request, responseWriter, failure);

        ArgumentCaptor<HttpResponse> response = ArgumentCaptor.forClass(HttpResponse.class);
        verify(responseWriter).writeResponse(eq(request), response.capture(), eq(false));
        return "answer: " + response.getValue() + "\nlogged: " + logged.stream()
            .map(entry -> entry.getLogLevel() + " " + entry.getType() + " " + entry.getMessage())
            .collect(Collectors.joining("\n"));
    }

    private static Throwable unreachable(Throwable cause) throws Exception {
        Constructor<UpstreamProxyUnreachableException> constructor = UpstreamProxyUnreachableException.class.getDeclaredConstructor(InetSocketAddress.class, Throwable.class);
        constructor.setAccessible(true);
        return constructor.newInstance(PROXY, cause);
    }
}
