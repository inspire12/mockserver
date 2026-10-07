package org.mockserver.socket.tls;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Which TLS trust notice each kind of factory logs: the server, including the factory it forwards with,
 * keeps the forward-proxy WARN; the factory MockServerClient uses logs the client's own trust at INFO.
 */
public class NettySslContextFactoryTrustNoticeTest {

    private static final String FORWARD_PROXY_NOTICE = "Forward proxy is configured to trust ALL X.509 certificates";
    private static final String CLIENT_NOTICE = "MockServerClient verifies MockServer's TLS certificate";

    @Test
    public void shouldLogClientTrustAtInfoInsteadOfForwardProxyNoticeForMockServerClient() {
        CapturingLogger logger = new CapturingLogger("INFO");

        NettySslContextFactory.forMockServerClient(configuration().certificateAuthorityCertificate("some/ca.pem"), logger);

        assertThat(logger.messages(null), not(hasItem(containsString(FORWARD_PROXY_NOTICE))));
        assertThat(logger.messages(Level.INFO), contains(allOf(
            containsString(CLIENT_NOTICE + " against the JVM's default trusted certificate authorities plus MockServer's CA certificate"),
            containsString("mockserver.certificateAuthorityCertificate is:"),
            containsString("some/ca.pem"),
            containsString("do not apply to MockServerClient")
        )));
    }

    @Test
    public void shouldNameControlPlaneCaChainForMockServerClientRequiringMutualTls() {
        CapturingLogger logger = new CapturingLogger("INFO");
        Configuration configuration = configuration()
            .controlPlaneTLSMutualAuthenticationRequired(true)
            .controlPlaneTLSMutualAuthenticationCAChain("org/mockserver/authentication/mtls/separateca/ca.pem");

        NettySslContextFactory.forMockServerClient(configuration, logger);

        assertThat(logger.messages(null), not(hasItem(containsString(FORWARD_PROXY_NOTICE))));
        assertThat(logger.messages(Level.INFO), contains(allOf(
            containsString(CLIENT_NOTICE + " against the certificate authorities in controlPlaneTLSMutualAuthenticationCAChain plus MockServer's CA certificate (mockserver.certificateAuthorityCertificate)"),
            containsString("org/mockserver/authentication/mtls/separateca/ca.pem"),
            containsString("do not apply to MockServerClient")
        )));
    }

    @Test
    public void shouldNotLogClientTrustBelowInfo() {
        CapturingLogger logger = new CapturingLogger("WARN");

        NettySslContextFactory.forMockServerClient(configuration(), logger);

        assertThat(logger.messages(null), not(hasItem(containsString(CLIENT_NOTICE))));
        assertThat(logger.messages(null), not(hasItem(containsString(FORWARD_PROXY_NOTICE))));
    }

    @Test
    public void shouldKeepForwardProxyNoticeForServerForwardingFactory() {
        CapturingLogger logger = new CapturingLogger("INFO");

        new NettySslContextFactory(configuration(), logger, false);

        assertThat(logger.messages(Level.WARN), hasItem(containsString(FORWARD_PROXY_NOTICE)));
        assertThat(logger.messages(null), not(hasItem(containsString(CLIENT_NOTICE))));
    }

    @Test
    public void shouldKeepForwardProxyNoticeForServerFactory() {
        CapturingLogger logger = new CapturingLogger("INFO");

        new NettySslContextFactory(configuration().dynamicallyCreateCertificateAuthorityCertificate(true), logger, true);

        assertThat(logger.messages(Level.WARN), hasItem(containsString(FORWARD_PROXY_NOTICE)));
        assertThat(logger.messages(null), not(hasItem(containsString(CLIENT_NOTICE))));
    }

    private static class CapturingLogger extends MockServerLogger {
        private final List<LogEntry> logged = new CopyOnWriteArrayList<>();

        CapturingLogger(String logLevel) {
            super(configuration().logLevel(logLevel), NettySslContextFactoryTrustNoticeTest.class);
        }

        // records every entry the factory hands over, including any below this logger's level
        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry);
        }

        List<String> messages(Level level) {
            return logged.stream()
                .filter(logEntry -> level == null || logEntry.getLogLevel() == level)
                .map(LogEntry::getMessage)
                .collect(Collectors.toList());
        }
    }
}
