package org.mockserver.netty.unification;

import io.netty.handler.codec.DecoderException;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import javax.net.ssl.SSLHandshakeException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.unification.ClientTlsHandshakeFailureLog.HTTP3;
import static org.mockserver.netty.unification.ClientTlsHandshakeFailureLog.MAX_CLIENT_ADDRESSES;
import static org.mockserver.netty.unification.ClientTlsHandshakeFailureLog.TCP;

/**
 * A client's failed TLS handshake is logged at {@code WARN} the first time its address fails one on a transport, and
 * at {@code DEBUG} after that, with a hint and without a stack trace or the peer's bytes; and only so many addresses
 * are remembered.
 */
public class ClientTlsHandshakeFailureLogTest {

    private static final SSLHandshakeException UNTRUSTED = new SSLHandshakeException("Received fatal alert: certificate_unknown");

    private final List<LogEntry> logged = new ArrayList<>();
    private Level logLevel = Level.DEBUG;
    private final MockServerLogger mockServerLogger = new MockServerLogger(ClientTlsHandshakeFailureLogTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return isEnabled(level, logLevel);
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry);
        }
    };
    private final ClientTlsHandshakeFailureLog log = new ClientTlsHandshakeFailureLog();

    @Test
    public void shouldWarnForAClientAddresssFirstFailureAndLogItsLaterOnesFromAnyPortAtDebug() {
        log.log(mockServerLogger, configuration(), TCP, address("10.0.0.1", 50001), UNTRUSTED);
        log.log(mockServerLogger, configuration(), TCP, address("10.0.0.1", 50002), UNTRUSTED);
        log.log(mockServerLogger, configuration(), TCP, address("10.0.0.1", 50002), UNTRUSTED);

        assertThat(levels(), contains(Level.WARN, Level.DEBUG, Level.DEBUG));
        String first = logged.get(0).getMessage(configuration());
        assertThat(first, containsString("TLS handshake failed on TCP connection from"));
        assertThat(first, containsString("10.0.0.1:50001"));
        assertThat(first, containsString("Received fatal alert: certificate_unknown"));
        assertThat(first, containsString("the client does not trust MockServer's Certificate Authority"));
        assertThat(first, containsString("https://mock-server.com/mock_server/HTTPS_TLS.html"));
        assertThat(first, containsString("x509CertificatePath="));
        assertThat(first, endsWith("later failed handshakes from this client address are logged at DEBUG"));
        assertThat(logged.get(0).getThrowable(), is(nullValue()));
        String later = logged.get(1).getMessage(configuration());
        assertThat(later, containsString("the client does not trust MockServer's Certificate Authority"));
        assertThat(later, not(containsString("later failed handshakes")));
        assertThat(logged.get(1).getThrowable(), is(nullValue()));
    }

    @Test
    public void shouldWarnForEachClientAddressAndEachTransportOfItsOwn() {
        log.log(mockServerLogger, configuration(), TCP, address("10.0.0.1", 50001), UNTRUSTED);
        log.log(mockServerLogger, configuration(), TCP, address("10.0.0.2", 50001), UNTRUSTED);
        log.log(mockServerLogger, configuration(), HTTP3, address("10.0.0.1", 50001), UNTRUSTED);
        log.log(mockServerLogger, configuration(), HTTP3, address("10.0.0.1", 50003), UNTRUSTED);

        assertThat(levels(), contains(Level.WARN, Level.WARN, Level.WARN, Level.DEBUG));
        assertThat(logged.get(2).getMessage(configuration()), containsString("TLS handshake failed on HTTP/3 connection from"));
    }

    @Test
    public void shouldRememberTheAddressesOfTheMostRecentFailuresUpToItsLimit() {
        log.log(mockServerLogger, configuration(), TCP, address("10.0.0.1", 1), UNTRUSTED);
        for (int i = 0; i < MAX_CLIENT_ADDRESSES + 10; i++) {
            log.log(mockServerLogger, configuration(), TCP, address("11.0." + (i / 256) + "." + (i % 256), 1), UNTRUSTED);
            if (i % 100 == 0) {
                // a recent failure keeps an address remembered
                log.log(mockServerLogger, configuration(), TCP, address("10.0.0.1", 2), UNTRUSTED);
            }
        }
        assertThat(log.size(), is(MAX_CLIENT_ADDRESSES));
        logged.clear();

        log.log(mockServerLogger, configuration(), TCP, address("10.0.0.1", 3), UNTRUSTED);
        log.log(mockServerLogger, configuration(), TCP, address("11.0.0.0", 1), UNTRUSTED);
        log.log(mockServerLogger, configuration(), TCP, address("11.0." + ((MAX_CLIENT_ADDRESSES + 9) / 256) + "." + ((MAX_CLIENT_ADDRESSES + 9) % 256), 1), UNTRUSTED);

        assertThat("remembered, forgotten as the least recent, remembered", levels(), contains(Level.DEBUG, Level.WARN, Level.DEBUG));
        assertThat(log.size(), is(MAX_CLIENT_ADDRESSES));
    }

    @Test
    public void shouldRememberAnAddressWhoseWarningTheLogLevelLeftOut() {
        logLevel = Level.ERROR;
        log.log(mockServerLogger, configuration(), TCP, address("10.0.0.1", 1), UNTRUSTED);
        assertThat(logged, is(empty()));

        logLevel = Level.DEBUG;
        log.log(mockServerLogger, configuration(), TCP, address("10.0.0.1", 2), UNTRUSTED);

        assertThat(levels(), contains(Level.DEBUG));
    }

    @Test
    public void shouldGiveAProbableCauseForEachAlertAClientSends() {
        Map<String, String> causes = new LinkedHashMap<>();
        causes.put("Received fatal alert: certificate_unknown", "the client does not trust MockServer's Certificate Authority");
        causes.put("error:10000418:SSL routines:OPENSSL_internal:TLSV1_ALERT_UNKNOWN_CA", "the client does not trust MockServer's Certificate Authority");
        causes.put("Received fatal alert: bad_certificate", "the client rejected MockServer's certificate");
        causes.put("Received fatal alert: unsupported_certificate", "the client rejected MockServer's certificate");
        causes.put("Received fatal alert: certificate_revoked", "the client rejected MockServer's certificate");
        causes.put("Received fatal alert: certificate_expired", "the client rejected MockServer's certificate");
        causes.put("error:10000133:SSL routines:OPENSSL_internal:NO_APPLICATION_PROTOCOL", "no application protocol (ALPN)");
        causes.put("Received fatal alert: handshake_failure", "no TLS protocol version or cipher suite");
        causes.put("Empty client certificate chain (no_certificate)", "the client sent no certificate");
        causes.put("Received fatal alert: certificate_required", "the client sent no certificate");
        causes.put("Received fatal alert: decrypt_error", "unknown");
        causes.forEach((alert, probableCause) -> {
            logged.clear();
            // as the TLS handler wraps it
            log.log(mockServerLogger, configuration(), TCP, address("10.0.0.1", 1), new DecoderException(new SSLHandshakeException(alert)));
            assertThat(alert, (String) logged.get(0).getArguments()[2], containsString(probableCause));
        });
    }

    @Test
    public void shouldGiveTheRejectedCertificateCauseForEachCertificateAlertAClientClosesWith() {
        // unsupported_certificate, certificate_revoked, certificate_expired, bad_certificate
        for (int alert : new int[]{43, 44, 45, 42}) {
            logged.clear();
            log.logClientAlert(mockServerLogger, configuration(), HTTP3, address("10.0.0.1", 1), alert);
            assertThat(String.valueOf(alert), (String) logged.get(0).getArguments()[2], containsString("the client rejected MockServer's certificate"));
        }
    }

    @Test
    public void shouldBoundTheFailuresMessageAndNameAFailureWithoutOne() {
        String dump = "41".repeat(30_000);
        log.log(mockServerLogger, configuration(), TCP, address("10.0.0.1", 1), new SSLHandshakeException("not an SSL/TLS record: " + dump));
        log.log(mockServerLogger, configuration(), TCP, address("10.0.0.1", 1), new SSLHandshakeException(null));

        String bounded = logged.get(0).getMessage(configuration());
        assertThat(bounded, not(containsString("4141")));
        assertThat(bounded, containsString("not an SSL/TLS record: 30000 bytes"));
        assertThat(bounded.length(), lessThan(1_500));
        assertThat(logged.get(1).getMessage(configuration()), containsString("javax.net.ssl.SSLHandshakeException"));
    }

    private List<Level> levels() {
        return logged.stream().map(LogEntry::getLogLevel).collect(Collectors.toList());
    }

    private static InetSocketAddress address(String host, int port) {
        return new InetSocketAddress(host, port);
    }
}
