package org.mockserver.client;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.ClientConfiguration.clientConfiguration;
import static org.mockserver.test.ClosedPort.CLOSED_PORT;

/**
 * The client's first request builds its TLS context factory. The notice it logs must describe the
 * client's own trust of MockServer, not the server's forward-proxy setting, which a client never sets.
 * Mutates the global log level and log listener, so it relies on the client module's sequential suite.
 */
public class MockServerClientTlsTrustNoticeTest {

    private static final String FORWARD_PROXY_NOTICE = "Forward proxy is configured to trust ALL X.509 certificates";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private Level originalLogLevel;
    private MockServerClient mockServerClient;

    @Before
    public void captureLogAtInfo() {
        originalLogLevel = ConfigurationProperties.logLevel();
        ConfigurationProperties.logLevel("INFO");
        MockServerLogger.setGlobalLogEventListener(logEntry -> logged.add(logEntry.clone()));
    }

    @After
    public void restore() {
        MockServerLogger.setGlobalLogEventListener(null);
        ConfigurationProperties.logLevel(originalLogLevel != null ? originalLogLevel.name() : "OFF");
        if (mockServerClient != null) {
            mockServerClient.stop();
        }
    }

    @Test
    public void shouldLogClientTrustNoticeAtInfoAndNotTheForwardProxyNotice() {
        mockServerClient = new MockServerClient("127.0.0.1", CLOSED_PORT);

        sendFirstRequest();

        assertThat(messages(Level.WARN), not(hasItem(containsString(FORWARD_PROXY_NOTICE))));
        assertThat(messages(null), not(hasItem(containsString(FORWARD_PROXY_NOTICE))));
        assertThat(messages(Level.INFO), hasItem(allOf(
            containsString("MockServerClient verifies MockServer's TLS certificate against the JVM's default trusted certificate authorities"),
            containsString("mockserver.certificateAuthorityCertificate"),
            containsString("do not apply to MockServerClient")
        )));
    }

    @Test
    public void shouldNameTheControlPlaneCaChainWhenMutualTlsIsRequired() throws IOException {
        // the configuration checks these files exist; the client reads them only on a TLS connection
        String caChain = temporaryFolder.newFile("control-plane-ca.pem").getAbsolutePath();
        String keyOrCertificate = temporaryFolder.newFile("client.pem").getAbsolutePath();
        mockServerClient = new MockServerClient(
            clientConfiguration()
                .controlPlaneTLSMutualAuthenticationRequired(true)
                .controlPlaneTLSMutualAuthenticationCAChain(caChain)
                .controlPlanePrivateKeyPath(keyOrCertificate)
                .controlPlaneX509CertificatePath(keyOrCertificate),
            "127.0.0.1",
            CLOSED_PORT
        );

        sendFirstRequest();

        assertThat(messages(null), not(hasItem(containsString(FORWARD_PROXY_NOTICE))));
        assertThat(messages(Level.INFO), hasItem(allOf(
            containsString("MockServerClient verifies MockServer's TLS certificate against the certificate authorities in controlPlaneTLSMutualAuthenticationCAChain plus MockServer's CA certificate (mockserver.certificateAuthorityCertificate)"),
            containsString(caChain)
        )));
    }

    private void sendFirstRequest() {
        try {
            mockServerClient.reset();
        } catch (RuntimeException expectedAsNothingListens) {
            // only the client's TLS set-up, which runs before the connection attempt, matters here
        }
    }

    private List<String> messages(Level level) {
        return logged.stream()
            .filter(logEntry -> level == null || logEntry.getLogLevel() == level)
            .map(LogEntry::getMessage)
            .collect(Collectors.toList());
    }
}
