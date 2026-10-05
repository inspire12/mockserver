package org.mockserver.httpclient;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.util.internal.OutOfDirectMemoryError;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Message;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.httpclient.NettyHttpClient.REMOTE_SOCKET;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

/**
 * What the last handler of the pipeline of an HTTP/2 connection to an upstream logs for each kind of exception that
 * reaches it, which of them it closes the connection for, and that it passes none on to the end of Netty's pipeline.
 */
public class Http2ForwardConnectionExceptionHandlerTest {

    private static final InetSocketAddress UPSTREAM = InetSocketAddress.createUnresolved("upstream.example", 8443);

    private final List<LogEntry> logged = new ArrayList<>();
    private Level logLevel = Level.DEBUG;
    private final MockServerLogger mockServerLogger = new MockServerLogger(Http2ForwardConnectionExceptionHandlerTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return isEnabled(level, logLevel);
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            if (isEnabledForInstance(logEntry.getLogLevel())) {
                logged.add(logEntry);
            }
        }
    };

    @Test
    public void shouldLogAConnectionItsUpstreamClosedOrResetAtDebugWithoutAStackTrace() {
        for (IOException cause : new IOException[]{new SocketException("Connection reset"), new IOException("Connection reset by peer"), new IOException("Broken pipe")}) {
            EmbeddedChannel connection = connection();
            logged.clear();

            connection.pipeline().fireExceptionCaught(cause);

            assertThat(logged, hasSize(1));
            assertThat(logged.get(0).getLogLevel(), is(Level.DEBUG));
            assertThat(logged.get(0).getMessageFormat(), is("HTTP/2 connection to:{}closed by the upstream:{}"));
            assertThat(Arrays.asList(logged.get(0).getArguments()), contains(UPSTREAM, cause.getMessage()));
            assertThat(logged.get(0).getThrowable(), is(nullValue()));
            assertHandledWithoutClosing(connection);
        }
    }

    @Test
    public void shouldLogNothingForAConnectionItsUpstreamResetAtTheDefaultLevel() {
        logLevel = Level.INFO;
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(new IOException("Connection reset"));

        assertThat(logged, empty());
        assertHandledWithoutClosing(connection);
    }

    @Test
    public void shouldLogAnHttp2ConnectionErrorAsAWarningWithItsCodeAndMessageAndLeaveTheCodecToClose() {
        Http2Exception protocolError = Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, "Frame of type %d must be associated with a stream.", 0);
        // as a decoder wraps it, and with a message the closed-connection check would take for an upstream's reset
        Throwable[] causes = {protocolError, new DecoderException(protocolError), Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, "connection closed by a bad frame")};
        for (Throwable cause : causes) {
            EmbeddedChannel connection = connection();
            logged.clear();

            connection.pipeline().fireExceptionCaught(cause);

            assertThat(logged, hasSize(1));
            assertThat(logged.get(0).getLogLevel(), is(Level.WARN));
            assertThat(logged.get(0).getMessageFormat(), is("closing HTTP/2 connection to:{}for connection error:{}:{}"));
            Http2Exception embedded = cause instanceof Http2Exception ? (Http2Exception) cause : protocolError;
            assertThat(Arrays.asList(logged.get(0).getArguments()), contains(UPSTREAM, Http2Error.PROTOCOL_ERROR, embedded.getMessage()));
            assertThat("the failed forward carries the stack trace", logged.get(0).getThrowable(), is(nullValue()));
            assertHandledWithoutClosing(connection);
        }
    }

    @Test
    public void shouldNotLogAConnectionErrorBelowAWarning() {
        logLevel = Level.ERROR;
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, "bad frame"));

        assertThat(logged, empty());
        assertHandledWithoutClosing(connection);
    }

    @Test
    public void shouldNotLogAgainWhatWasLoggedAsAResponseOverTheHeaderLimit() {
        EmbeddedChannel connection = connection();
        HeaderLimitExceededException refusal = ForwardHeaderLimit.responseOverLimit(mockServerLogger, connection, ForwardHeaderLimit.RESPONSE_HEADERS, 1024);
        logged.clear();

        connection.pipeline().fireExceptionCaught(refusal);
        connection.pipeline().fireExceptionCaught(new PrematureChannelClosureException("cut short"));

        assertThat(logged, empty());
        assertHandledWithoutClosing(connection);
    }

    @Test
    public void shouldLogAnSslOrDecoderFaultOnceWithoutTheBytesReadAndCloseTheConnection() {
        // Netty's message for bytes that are not a TLS record is a hex dump of every one of them
        Throwable notTls = new DecoderException(new NotSslRecordException("not an SSL/TLS record: " + "41".repeat(60_000)));
        for (Throwable cause : new Throwable[]{new DecoderException("not a frame"), new RuntimeException(new SSLException("bad record")), notTls}) {
            EmbeddedChannel connection = connection();
            logged.clear();

            connection.pipeline().fireExceptionCaught(cause);

            String reason = "for " + cause.getClass().getName();
            assertThat(reason, logged, hasSize(1));
            assertThat(reason, logged.get(0).getLogLevel(), is(Level.WARN));
            assertThat(reason, logged.get(0).getMessageFormat(), is("closing HTTP/2 connection to:{}for SSL or decoder fault " + cause.getClass().getName() + ":{}"));
            assertThat(reason, logged.get(0).getArguments()[0], is(UPSTREAM));
            assertThat(reason, (String) logged.get(0).getArguments()[1], startsWith(cause.getMessage().substring(0, 10)));
            assertThat("the stack trace would carry the whole message", logged.get(0).getThrowable(), is(nullValue()));
            assertThat(reason, logged.get(0).getMessage(configuration()), not(containsString("4141")));
            assertThat(reason, logged.get(0).getMessage(configuration()).length(), lessThan(1024));
            assertThat("passed on to the end of the pipeline", reachedTheEndOfThePipeline(connection), is(nullValue()));
            assertThat("left open, to report every read that follows", connection.isOpen(), is(false));
            connection.finishAndReleaseAll();
        }
        assertThat((String) logged.get(0).getArguments()[1], is("io.netty.handler.ssl.NotSslRecordException: not an SSL/TLS record: 60000 bytes"));
    }

    @Test
    public void shouldCloseTheConnectionForAnSslOrDecoderFaultWhateverTheLogLevel() {
        logLevel = Level.ERROR;
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(new DecoderException("not a frame"));

        assertThat(logged, empty());
        assertThat("left open because the warning is not logged", connection.isOpen(), is(false));
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldLogNettysDirectMemoryLimitAsAnErrorAndCloseTheConnection() throws Exception {
        logLevel = Level.ERROR;
        Constructor<OutOfDirectMemoryError> constructor = OutOfDirectMemoryError.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(new DecoderException(constructor.newInstance("failed to allocate 16777216 byte(s) of direct memory")));

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.ERROR));
        assertThat(logged.get(0).getMessageFormat(), startsWith("direct memory limit (io.netty.maxDirectMemory) reached"));
        assertThat(logged.get(0).getThrowable(), is(nullValue()));
        assertThat("passed on to the end of the pipeline", reachedTheEndOfThePipeline(connection), is(nullValue()));
        assertThat(connection.isOpen(), is(false));
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldLogAnyOtherExceptionAsAnErrorWithItsCause() {
        logLevel = Level.ERROR;
        IllegalStateException unexpected = new IllegalStateException("unexpected");
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(unexpected);

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.ERROR));
        assertThat(logged.get(0).getMessageFormat(), startsWith("exception caught on HTTP/2 connection to upstream "));
        assertThat(logged.get(0).getThrowable(), is(sameInstance(unexpected)));
        assertHandledWithoutClosing(connection);
    }

    @Test
    public void shouldLeaveAForwardInFlightToTheHandlersBeforeIt() {
        CompletableFuture<Message> forward = new CompletableFuture<>();
        EmbeddedChannel connection = connection();
        connection.attr(RESPONSE_FUTURE).set(forward);

        connection.pipeline().fireExceptionCaught(new IOException("Connection reset"));
        connection.pipeline().fireExceptionCaught(Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, "bad frame"));

        assertThat(forward.isDone(), is(false));
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldTakeWhatTheHeaderLimitsHandlerPassesOn() {
        // as HttpClientInitializer ends the pipeline of an HTTP/2 connection
        EmbeddedChannel connection = new EmbeddedChannel(new ForwardHeaderLimit.Http2Connection(mockServerLogger, 1024), new Http2ForwardConnectionExceptionHandler(mockServerLogger));
        connection.attr(REMOTE_SOCKET).set(UPSTREAM);

        connection.pipeline().fireExceptionCaught(new IOException("Connection reset"));

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getMessageFormat(), is("HTTP/2 connection to:{}closed by the upstream:{}"));
        assertHandledWithoutClosing(connection);
    }

    private EmbeddedChannel connection() {
        EmbeddedChannel connection = new EmbeddedChannel(new Http2ForwardConnectionExceptionHandler(mockServerLogger));
        connection.attr(REMOTE_SOCKET).set(UPSTREAM);
        return connection;
    }

    private static void assertHandledWithoutClosing(EmbeddedChannel connection) {
        assertThat("passed on to the end of the pipeline", reachedTheEndOfThePipeline(connection), is(nullValue()));
        assertThat("closed, which for a connection error would lose the GOAWAY", connection.isOpen(), is(true));
        connection.finishAndReleaseAll();
    }

    /**
     * An {@link EmbeddedChannel} records what reaches the end of its pipeline and throws it from
     * {@code checkException()}.
     */
    private static Throwable reachedTheEndOfThePipeline(EmbeddedChannel connection) {
        try {
            connection.checkException();
            return null;
        } catch (Throwable reached) {
            return reached;
        }
    }
}
