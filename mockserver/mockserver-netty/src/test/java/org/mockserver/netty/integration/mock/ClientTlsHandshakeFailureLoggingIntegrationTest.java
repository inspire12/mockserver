package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.MockServer;
import org.slf4j.event.Level;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * What MockServer logs when a client's TLS handshake on its TCP port fails because the client does not trust
 * MockServer's Certificate Authority: one {@code WARN} entry, with a hint and no stack trace, for the first such
 * failure from a client address, and one {@code DEBUG} entry for each later one from the same address.
 */
public class ClientTlsHandshakeFailureLoggingIntegrationTest {

    private static final long WAIT_SECONDS = 10;

    private static final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private static MockServer mockServer;

    @BeforeClass
    public static void startServer() {
        // a clone: the event log clears the entry it is handed once it has copied it
        MockServerLogger.setGlobalLogEventListener(logEntry -> logged.add(logEntry.clone()));
        // the test JVM defaults to ERROR, which would drop the entries this class asserts on
        mockServer = new MockServer(configuration().logLevel("DEBUG"), 0);
    }

    @AfterClass
    public static void stopServer() {
        try {
            stopQuietly(mockServer);
        } finally {
            MockServerLogger.setGlobalLogEventListener(null);
        }
    }

    @Test
    public void shouldWarnOnceForAClientAddressThatDoesNotTrustMockServersCertificateAuthorityAndLogItsLaterFailuresAtDebug() throws Exception {
        int firstClientPort = failAHandshakeWithAClientThatTrustsAnotherCertificateAuthority();
        LogEntry first = awaitTheHandshakeEntryOf(firstClientPort);
        int secondClientPort = failAHandshakeWithAClientThatTrustsAnotherCertificateAuthority();
        LogEntry second = awaitTheHandshakeEntryOf(secondClientPort);

        assertThat(first.getLogLevel(), is(Level.WARN));
        String firstMessage = first.getMessage(configuration());
        assertThat(firstMessage, containsString("TLS handshake failed on TCP connection from"));
        assertThat(firstMessage, containsStringIgnoringCase("certificate_unknown"));
        assertThat(firstMessage, containsString("the client does not trust MockServer's Certificate Authority"));
        assertThat(firstMessage, containsString("https://mock-server.com/mock_server/HTTPS_TLS.html"));
        assertThat(firstMessage, containsString("later failed handshakes from this client address are logged at DEBUG"));
        assertThat(first.getThrowable(), is(nullValue()));

        assertThat(second.getLogLevel(), is(Level.DEBUG));
        String secondMessage = second.getMessage(configuration());
        assertThat(secondMessage, containsString("TLS handshake failed on TCP connection from"));
        assertThat(secondMessage, not(containsString("later failed handshakes")));
        assertThat(second.getThrowable(), is(nullValue()));
    }

    /**
     * A trust store with one of the JDK's own Certificate Authorities in it, so the client rejects MockServer's
     * certificate as one a browser or a JDK client would, with a certificate_unknown alert.
     */
    private static int failAHandshakeWithAClientThatTrustsAnotherCertificateAuthority() throws Exception {
        KeyStore jdkCertificateAuthorities = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream cacerts = Files.newInputStream(Paths.get(System.getProperty("java.home"), "lib", "security", "cacerts"))) {
            jdkCertificateAuthorities.load(cacerts, null);
        }
        KeyStore anotherCertificateAuthority = KeyStore.getInstance(KeyStore.getDefaultType());
        anotherCertificateAuthority.load(null, null);
        String alias = jdkCertificateAuthorities.aliases().nextElement();
        anotherCertificateAuthority.setCertificateEntry(alias, jdkCertificateAuthorities.getCertificate(alias));
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(anotherCertificateAuthority);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        try (SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", mockServer.getLocalPort()), (int) TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertThrows(SSLException.class, socket::startHandshake);
            return socket.getLocalPort();
        }
    }

    private static LogEntry awaitTheHandshakeEntryOf(int clientPort) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (handshakeEntriesOf(clientPort).isEmpty() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        // whatever else the same failure logs follows at once
        TimeUnit.MILLISECONDS.sleep(200);
        List<LogEntry> entries = handshakeEntriesOf(clientPort);
        assertThat(entries.toString(), entries, hasSize(1));
        return entries.get(0);
    }

    private static List<LogEntry> handshakeEntriesOf(int clientPort) {
        return logged.stream()
            .filter(entry -> String.valueOf(entry.getMessageFormat()).contains("TLS handshake"))
            .filter(entry -> entry.getMessage(configuration()).contains("127.0.0.1:" + clientPort))
            .collect(Collectors.toList());
    }
}
