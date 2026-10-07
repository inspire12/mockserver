package org.mockserver.mock.action.http;

import com.google.common.collect.MapMaker;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Action;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;
import org.slf4j.event.Level;

import java.util.Collections;
import java.util.Set;

/**
 * Over HTTP/2 a {@code 1xx} status can only be an interim response (RFC 9113 section 8.1), so a mocked one that ends
 * the exchange is sent and its stream reset with {@code NO_ERROR}, which most clients report as a failed request.
 * Warned once per expectation, not per request: a user who mocks a {@code 1xx} on purpose would find it noise.
 */
final class FinalInformationalResponseWarning {

    private static final String MESSAGE_FORMAT = "expectation:{}answered a request over HTTP/2 with the status:{}"
        + "which HTTP/2 allows only as an interim response, so MockServer sent it and then reset the stream with NO_ERROR"
        + " and most clients report the request as failed rather than answered; to answer HTTP/2 clients give the expectation"
        + " a status of 200 or above, or to keep the 1xx for HTTP/1.1 clients only add a protocol of HTTP_1_1 to its request"
        + " matcher (logged once for each expectation)";

    // by identity and weakly: an expectation's actions are its own, and go with it when it is removed or replaced
    private final Set<Action<?>> warned = Collections.newSetFromMap(new MapMaker().weakKeys().makeMap());

    void warnOnce(final MockServerLogger mockServerLogger, final HttpRequest request, final HttpResponse response, final Action<?> action) {
        if (action == null || !Protocol.HTTP_2.equals(request.getProtocol()) || !isFinalInformational(response) || !warned.add(action)) {
            return;
        }
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setExpectationId(action.getExpectationId())
                    .setMessageFormat(MESSAGE_FORMAT)
                    .setArguments(action.getExpectationId(), response.getStatusCode())
            );
        }
    }

    private static boolean isFinalInformational(HttpResponse response) {
        Integer statusCode = response != null ? response.getStatusCode() : null;
        return statusCode != null && statusCode >= 100 && statusCode < 200 && statusCode != 101;
    }
}
