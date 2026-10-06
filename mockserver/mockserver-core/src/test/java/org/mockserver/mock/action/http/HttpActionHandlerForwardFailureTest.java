package org.mockserver.mock.action.http;

import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.ssl.NotSslRecordException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.httpclient.SocketConnectionException;
import org.mockserver.httpclient.UndecodableResponseException;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import javax.net.ssl.SSLHandshakeException;
import java.lang.reflect.Constructor;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;

/**
 * The {@code 502} a failed forward is answered with, and the entry it is logged with, for each reason the forward
 * client can name: a connection it could not set up from MockServer's configuration, TLS with the upstream, and an
 * HTTP/2 error from the upstream. A connection that could not be made is answered as before.
 */
public class HttpActionHandlerForwardFailureTest {

    private static final HttpForward ACTION = forward().withHost("upstream.example").withPort(8443).withScheme(HttpForward.Scheme.HTTPS);
    private static final InetSocketAddress UPSTREAM = InetSocketAddress.createUnresolved("upstream.example", 8443);

    private final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private final MockServerLogger mockServerLogger = new MockServerLogger(HttpActionHandlerForwardFailureTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return true;
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry);
        }
    };
    private HttpActionHandler actionHandler;
    private ResponseWriter responseWriter;
    private final HttpRequest request = request("/some_path").withHeader("Authorization", "Bearer secret-token");

    @Before
    public void createActionHandler() {
        HttpState httpState = mock(HttpState.class);
        when(httpState.getMockServerLogger()).thenReturn(mockServerLogger);
        when(httpState.getUniqueLoopPreventionHeaderName()).thenReturn("x-forwarded-by");
        when(httpState.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_" + UUIDService.getUUID());
        actionHandler = new HttpActionHandler(configuration(), null, httpState, null, null);
        responseWriter = mock(ResponseWriter.class);
    }

    @Test
    public void shouldAnswerAConfigurationErrorWithItsReason() throws Exception {
        Throwable failure = configurationError("connection to upstream.example:8443 could not be set up: RuntimeException: Exception creating SSL context for client", new RuntimeException("Exception creating SSL context for client", new IllegalArgumentException("deep cause")));

        actionHandler.handleExceptionDuringForwardingRequest(ACTION, request, responseWriter, new CompletionException(failure));

        String reason = "connection to the upstream could not be set up: RuntimeException: Exception creating SSL context for client";
        assertThat(answer().getStatusCode(), is(502));
        assertThat(answer().getBodyAsString(), is(reason));
        assertThat("only the top-level reason: a deeper cause may quote the configured files", answer().getBodyAsString(), not(containsString("deep cause")));
        assertLoggedOnceAsAnError(reason);
    }

    @Test
    public void shouldAnswerAnUntrustedCertificateWithTheHandshakesReason() {
        Throwable failure = socketConnectionException("TLS handshake with upstream.example:8443 failed: SSLHandshakeException: PKIX path building failed", new SSLHandshakeException("PKIX path building failed"));

        actionHandler.handleExceptionDuringForwardingRequest(ACTION, request, responseWriter, failure);

        String reason = "TLS with the upstream failed: SSLHandshakeException: PKIX path building failed";
        assertThat(answer().getStatusCode(), is(502));
        assertThat(answer().getBodyAsString(), is(reason));
        assertLoggedOnceAsAnError(reason);
    }

    @Test
    public void shouldAnswerAFailedOpenSslHandshakeWithTheReasonInItsCause() {
        SSLHandshakeException openSsl = new SSLHandshakeException("General OpenSslEngine problem");
        openSsl.initCause(new java.security.cert.CertificateException("No subject alternative names matching IP address 127.0.0.1 found"));
        Throwable failure = socketConnectionException("TLS handshake failed", openSsl);

        actionHandler.handleExceptionDuringForwardingRequest(ACTION, request, responseWriter, failure);

        String reason = "TLS with the upstream failed: SSLHandshakeException: General OpenSslEngine problem; caused by CertificateException: No subject alternative names matching IP address 127.0.0.1 found";
        assertThat(answer().getBodyAsString(), is(reason));
        assertLoggedOnceAsAnError(reason);
    }

    @Test
    public void shouldAnswerAnUpstreamThatIsNotTlsWithoutItsBytes() {
        NotSslRecordException notTls = new NotSslRecordException("not an SSL/TLS record: " + "485454502f".repeat(1000));
        Throwable failure = socketConnectionException("TLS handshake failed", notTls);

        actionHandler.handleExceptionDuringForwardingRequest(ACTION, request, responseWriter, failure);

        String reason = "TLS with the upstream failed: NotSslRecordException: not an SSL/TLS record: 5000 bytes";
        assertThat(answer().getBodyAsString(), is(reason));
        assertLoggedOnceAsAnError(reason);
        LogEntry error = errors().get(0);
        assertThat(String.valueOf(error.getThrowable().getCause()), not(containsString("485454502f")));
    }

    @Test
    public void shouldAnswerAnHttp2ErrorWithItsCodeAndMessage() {
        Http2Exception protocolError = Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, "Frame of type %d must be associated with a stream.", 0);
        Throwable failure = socketConnectionException("HTTP/2 connection to upstream.example:8443 failed", protocolError);

        actionHandler.handleExceptionDuringForwardingRequest(ACTION, request, responseWriter, failure);

        String reason = "HTTP/2 error from the upstream: PROTOCOL_ERROR: Frame of type 0 must be associated with a stream.";
        assertThat(answer().getBodyAsString(), is(reason));
        assertLoggedOnceAsAnError(reason);
    }

    @Test
    public void shouldAnswerAResponseThatCouldNotBeDecodedWithTheDecodersReason() {
        Throwable failure = new UndecodableResponseException("response from upstream.example:8080 could not be decoded: IllegalArgumentException: No colon found", new IllegalArgumentException("No colon found"));

        actionHandler.handleExceptionDuringForwardingRequest(ACTION, request, responseWriter, new CompletionException(failure));

        String reason = "response from the upstream could not be decoded: IllegalArgumentException: No colon found";
        assertThat(answer().getStatusCode(), is(502));
        assertThat(answer().getBodyAsString(), is(reason));
        assertLoggedOnceAsAnError(reason);
    }

    @Test
    public void shouldAnswerAStatusLineThatIsNotHttpWithAtMostTheBoundOfIt() {
        String notHttp = "invalid version format: " + "A".repeat(5000);
        Throwable failure = new UndecodableResponseException("response from upstream could not be decoded", new IllegalArgumentException(notHttp));

        actionHandler.handleUnmatchedForwardFailure(failure, request, responseWriter, UPSTREAM, true);

        String prefix = "response from the upstream could not be decoded: IllegalArgumentException: invalid version format: AAA";
        assertThat(answer().getStatusCode(), is(502));
        assertThat(answer().getBodyAsString(), startsWith(prefix));
        assertThat(answer().getBodyAsString().length(), is("response from the upstream could not be decoded: IllegalArgumentException: ".length() + 256));
    }

    @Test
    public void shouldAnswerAConnectionThatCouldNotBeMadeAsBefore() {
        actionHandler.handleExceptionDuringForwardingRequest(ACTION, request, responseWriter, new ConnectException("Connection refused: upstream.example/127.0.0.1:8443"));

        assertThat(answer().getStatusCode(), is(502));
        assertThat(answer().getBodyAsString(), is(nullValue()));
        assertThat(errors(), empty());
    }

    @Test
    public void shouldAnswerAProxiedRequestsFailureWithItsReason() {
        Throwable failure = socketConnectionException("TLS handshake failed", new SSLHandshakeException("PKIX path building failed"));

        actionHandler.handleUnmatchedForwardFailure(failure, request, responseWriter, UPSTREAM, true);

        String reason = "TLS with the upstream failed: SSLHandshakeException: PKIX path building failed";
        assertThat("not taken for an exploratory proxy's failed connect", answer().getBodyAsString(), is(reason));
        assertLoggedOnceAsAnError(reason);
    }

    private HttpResponse answer() {
        ArgumentCaptor<HttpResponse> response = ArgumentCaptor.forClass(HttpResponse.class);
        verify(responseWriter).writeResponse(eq(request), response.capture(), eq(false));
        return response.getValue();
    }

    private List<LogEntry> errors() {
        return logged.stream().filter(entry -> entry.getLogLevel() == Level.ERROR).collect(Collectors.toList());
    }

    private void assertLoggedOnceAsAnError(String reason) {
        List<LogEntry> errors = errors();
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0).getHttpRequest(), is(request));
        assertThat(errors.get(0).getMessage(configuration()), containsString(reason));
        assertThat("the cause, for its stack trace", errors.get(0).getThrowable() != null, is(true));
    }

    private static Throwable socketConnectionException(String message, Throwable cause) {
        return construct(SocketConnectionException.class, message, cause);
    }

    private static Throwable configurationError(String message, Throwable cause) {
        return construct(org.mockserver.httpclient.ClientConfigurationException.class, message, cause);
    }

    private static Throwable construct(Class<? extends Throwable> type, String message, Throwable cause) {
        try {
            Constructor<? extends Throwable> constructor = type.getDeclaredConstructor(String.class, Throwable.class);
            constructor.setAccessible(true);
            return constructor.newInstance(message, cause);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
