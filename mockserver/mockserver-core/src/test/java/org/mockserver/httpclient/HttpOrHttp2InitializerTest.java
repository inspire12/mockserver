package org.mockserver.httpclient;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.util.internal.OutOfDirectMemoryError;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.httpclient.NettyHttpClient.REMOTE_SOCKET;

/**
 * What the forward client logs for an exception that arrives before the protocol is negotiated with an upstream,
 * when {@link HttpOrHttp2Initializer} is the last handler of the pipeline: one entry, the connection closed, and
 * nothing passed on to the end of Netty's pipeline.
 */
public class HttpOrHttp2InitializerTest {

    private static final InetSocketAddress UPSTREAM = InetSocketAddress.createUnresolved("upstream.example", 8443);

    private final List<LogEntry> logged = new ArrayList<>();
    private Level logLevel = Level.DEBUG;
    private final MockServerLogger mockServerLogger = new MockServerLogger(HttpOrHttp2InitializerTest.class) {
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
    public void shouldLogAFailedTlsHandshakeAsAWarningWithItsCause() {
        Throwable untrusted = new DecoderException(new SSLHandshakeException("PKIX path building failed"));
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(untrusted);

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.WARN));
        assertThat(logged.get(0).getMessageFormat(), is("TLS could not be set up on connection to:{}"));
        assertThat(Arrays.asList(logged.get(0).getArguments()), contains(UPSTREAM));
        assertThat("the forward fails only as a closed connection", logged.get(0).getThrowable(), is(sameInstance(untrusted)));
        assertHandledAndClosed(connection);
    }

    @Test
    public void shouldLogAnyDecoderFaultAsAWarningAndNotAsAClosedConnection() {
        // the closed-connection check is false for every decoder fault, so the fault must be asked about first
        Throwable undecodable = new DecoderException("not a proxy's answer");
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(undecodable);

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.WARN));
        assertThat(logged.get(0).getMessageFormat(), is("TLS could not be set up on connection to:{}"));
        assertThat(logged.get(0).getThrowable(), is(sameInstance(undecodable)));
        assertHandledAndClosed(connection);
    }

    @Test
    public void shouldLogAnAnswerThatIsNotTlsWithoutItsBytes() {
        // Netty's message for bytes that are not a TLS record is a hex dump of every one of them
        Throwable notTls = new DecoderException(new NotSslRecordException("not an SSL/TLS record: " + "41".repeat(60_000)));
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(notTls);

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.WARN));
        assertThat(logged.get(0).getMessage(configuration()), not(containsString("4141")));
        assertThat(logged.get(0).getThrowable().toString(), is("io.netty.handler.codec.DecoderException: io.netty.handler.ssl.NotSslRecordException: not an SSL/TLS record: 60000 bytes"));
        assertThat(logged.get(0).getThrowable().getStackTrace(), is(notTls.getStackTrace()));
        assertHandledAndClosed(connection);
    }

    @Test
    public void shouldLogAConnectionThatFailedOrWasClosedAtDebugWithoutAStackTrace() {
        // a proxy that refuses the tunnel, and an upstream that resets: each fails the forward, which is logged there
        for (IOException cause : new IOException[]{new ProxyConnectException("http, none, proxy => upstream, status: 407 Proxy Authentication Required"), new SocketException("Connection reset"), new IOException("Broken pipe")}) {
            EmbeddedChannel connection = connection();
            logged.clear();

            connection.pipeline().fireExceptionCaught(cause);

            assertThat(logged, hasSize(1));
            assertThat(logged.get(0).getLogLevel(), is(Level.DEBUG));
            assertThat(logged.get(0).getMessageFormat(), is("connection to:{}failed or was closed before TLS was set up:{}"));
            assertThat(Arrays.asList(logged.get(0).getArguments()), contains(UPSTREAM, cause.getMessage()));
            assertThat(logged.get(0).getThrowable(), is(nullValue()));
            assertHandledAndClosed(connection);
        }
    }

    @Test
    public void shouldCloseTheConnectionWhateverTheLogLevel() {
        logLevel = Level.ERROR;
        for (Throwable cause : new Throwable[]{new DecoderException(new SSLHandshakeException("PKIX path building failed")), new SocketException("Connection reset")}) {
            EmbeddedChannel connection = connection();

            connection.pipeline().fireExceptionCaught(cause);

            assertThat(logged, empty());
            assertHandledAndClosed(connection);
        }
    }

    @Test
    public void shouldNotLogAgainWhatWasLoggedAsAProxyAnswerOverTheHeaderLimit() {
        EmbeddedChannel connection = connection();
        HeaderLimitExceededException refusal = ForwardHeaderLimit.responseOverLimit(mockServerLogger, connection, ForwardHeaderLimit.CONNECT_RESPONSE_HEADERS, 1024);
        logged.clear();

        connection.pipeline().fireExceptionCaught(refusal);

        assertThat(logged, empty());
        assertHandledAndClosed(connection);
    }

    @Test
    public void shouldLogNettysDirectMemoryLimitAsAnError() throws Exception {
        logLevel = Level.ERROR;
        Constructor<OutOfDirectMemoryError> constructor = OutOfDirectMemoryError.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(new DecoderException(constructor.newInstance("failed to allocate 16777216 byte(s) of direct memory")));

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.ERROR));
        assertThat(logged.get(0).getMessageFormat(), startsWith("direct memory limit (io.netty.maxDirectMemory) reached"));
        assertThat(logged.get(0).getThrowable(), is(nullValue()));
        assertHandledAndClosed(connection);
    }

    @Test
    public void shouldLogAnyOtherExceptionAsAnErrorWithItsCause() {
        logLevel = Level.ERROR;
        IllegalStateException unexpected = new IllegalStateException("unexpected");
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(unexpected);

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.ERROR));
        assertThat(logged.get(0).getMessageFormat(), startsWith("exception caught before TLS was set up on connection to upstream "));
        assertThat(logged.get(0).getThrowable(), is(sameInstance(unexpected)));
        assertHandledAndClosed(connection);
    }

    private EmbeddedChannel connection() {
        EmbeddedChannel connection = new EmbeddedChannel(new HttpOrHttp2Initializer(mockServerLogger, pipeline -> {
        }, pipeline -> {
        }));
        connection.attr(REMOTE_SOCKET).set(UPSTREAM);
        return connection;
    }

    /**
     * An {@link EmbeddedChannel} records what reaches the end of its pipeline and throws it from
     * {@code checkException()}.
     */
    private static void assertHandledAndClosed(EmbeddedChannel connection) {
        connection.checkException();
        assertThat("left open, with nothing to say what the connection speaks", connection.isOpen(), is(false));
        connection.finishAndReleaseAll();
    }
}
