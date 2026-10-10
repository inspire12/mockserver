package org.mockserver.netty.integration.authenticatedcontrolplane;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.ClientException;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;

import javax.net.ssl.SSLHandshakeException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.configuration.ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_PRIVATE_KEY;
import static org.mockserver.configuration.ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * MockServerClient's callback and breakpoint WebSockets verify MockServer's certificate as its HTTP requests do,
 * and present the same client certificate. Every server and client pins its settings on its own Configuration.
 */
public class MockServerClientWebSocketTlsIntegrationTest {

    private static final String OTHER_CA = "org/mockserver/netty/integration/tls/ca.pem";
    private static final String OTHER_CA_PRIVATE_KEY = "org/mockserver/netty/integration/tls/ca-key-pkcs8.pem";
    // signed by OTHER_CA, with localhost in its subject alternative names and permitted for client and server use
    private static final String LEAF_PRIVATE_KEY = "org/mockserver/netty/integration/tls/leaf-key-pkcs8.pem";
    private static final String LEAF_X509_CERTIFICATE = "org/mockserver/netty/integration/tls/leaf-cert.pem";

    private static ClientAndServer serverSignedByOtherCa;
    private static ClientAndServer serverRequiringClientCertificates;
    private static ClientAndServer defaultServer;
    private final List<ClientOnly> clients = new ArrayList<>();
    private final List<Runnable> cleanUp = new ArrayList<>();

    @BeforeClass
    public static void startServers() {
        serverSignedByOtherCa = ClientAndServer.startClientAndServer(signedByOtherCa(withoutControlPlaneAuthentication(configuration())));
        serverRequiringClientCertificates = ClientAndServer.startClientAndServer(
            signedByOtherCa(withoutControlPlaneAuthentication(configuration()))
                .tlsMutualAuthenticationRequired(true)
                .tlsMutualAuthenticationCertificateChain(OTHER_CA)
                .controlPlaneTLSMutualAuthenticationRequired(true)
                .controlPlaneTLSMutualAuthenticationCAChain(OTHER_CA)
        );
        defaultServer = ClientAndServer.startClientAndServer(withBundledCa(withoutControlPlaneAuthentication(configuration())));
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(serverSignedByOtherCa);
        stopQuietly(serverRequiringClientCertificates);
        stopQuietly(defaultServer);
    }

    @After
    public void releaseClients() {
        cleanUp.forEach(Runnable::run);
        clients.forEach(ClientOnly::release);
    }

    @Test
    public void callbackWebSocketShouldRejectUntrustedServerCertificate() {
        MockServerClient client = client(untrustingClient(), serverSignedByOtherCa);

        ClientException exception = assertThrows(ClientException.class, () -> client.when(request("/untrusted-callback")).respond(httpRequest -> response()));

        assertThat(exception.getMessage(), is("Unable to retrieve client registration id"));
        assertThat(causes(exception), hasItem(instanceOf(SSLHandshakeException.class)));
    }

    @Test
    public void breakpointWebSocketShouldRejectUntrustedServerCertificate() {
        MockServerClient client = client(untrustingClient(), serverSignedByOtherCa);

        ClientException exception = assertThrows(ClientException.class, () -> client.addBreakpoint(request("/untrusted-breakpoint"), httpRequest -> null));

        assertThat(exception.getMessage(), is("Unable to establish breakpoint WebSocket connection"));
        assertThat(causes(exception), hasItem(instanceOf(SSLHandshakeException.class)));
    }

    @Test
    public void callbackWebSocketShouldPresentControlPlaneClientCertificate() {
        MockServerClient client = client(controlPlaneMutualTls(), serverRequiringClientCertificates);

        assertThat(client.when(request("/mutual-tls-callback")).respond(httpRequest -> response()).length, is(1));
    }

    @Test
    public void breakpointWebSocketShouldPresentControlPlaneClientCertificate() {
        MockServerClient client = client(controlPlaneMutualTls(), serverRequiringClientCertificates);

        String breakpointId = client.addBreakpoint(request("/mutual-tls-breakpoint"), httpRequest -> null);
        cleanUp.add(() -> client.removeBreakpointMatcher(breakpointId));

        assertThat(breakpointId, not(emptyOrNullString()));
    }

    @Test
    public void callbackWebSocketShouldWorkInDefaultSetup() throws Exception {
        MockServerClient client = client(withBundledCa(withoutControlPlaneAuthentication(configuration())), defaultServer);

        client.when(request("/default-callback")).respond(httpRequest -> response().withBody("from-callback"));

        assertThat(send("/default-callback").body(), is("from-callback"));
    }

    @Test
    public void breakpointWebSocketShouldWorkInDefaultSetup() throws Exception {
        MockServerClient client = client(withBundledCa(withoutControlPlaneAuthentication(configuration())), defaultServer);
        client.when(request("/default-breakpoint")).respond(response().withBody("not-paused"));

        String breakpointId = client.addBreakpoint(request("/default-breakpoint"), httpRequest -> response().withStatusCode(418).withBody("from-breakpoint"));
        cleanUp.add(() -> client.removeBreakpointMatcher(breakpointId));

        java.net.http.HttpResponse<String> response = send("/default-breakpoint");
        assertThat(response.statusCode(), is(418));
        assertThat(response.body(), is("from-breakpoint"));
    }

    /**
     * Trusts MockServer's bundled CA, not the CA that signed serverSignedByOtherCa's certificate. A rejection is
     * expected within seconds, so a hang fails well inside the default future timeout.
     */
    private static Configuration untrustingClient() {
        return withBundledCa(withoutControlPlaneAuthentication(configuration())).maxFutureTimeoutInMillis(20_000L);
    }

    private static Configuration withoutControlPlaneAuthentication(Configuration configuration) {
        return configuration
            .controlPlaneTLSMutualAuthenticationRequired(false)
            .controlPlaneJWTAuthenticationRequired(false)
            .controlPlaneOidcAuthenticationRequired(false)
            .tlsMutualAuthenticationRequired(false)
            .tlsMutualAuthenticationCertificateChain("");
    }

    private static Configuration withBundledCa(Configuration configuration) {
        return configuration
            .dynamicallyCreateCertificateAuthorityCertificate(false)
            .certificateAuthorityCertificate(DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE)
            .certificateAuthorityPrivateKey(DEFAULT_CERTIFICATE_AUTHORITY_PRIVATE_KEY);
    }

    private static Configuration signedByOtherCa(Configuration configuration) {
        return configuration
            .dynamicallyCreateCertificateAuthorityCertificate(false)
            .certificateAuthorityCertificate(OTHER_CA)
            .certificateAuthorityPrivateKey(OTHER_CA_PRIVATE_KEY)
            .privateKeyPath(LEAF_PRIVATE_KEY)
            .x509CertificatePath(LEAF_X509_CERTIFICATE);
    }

    private static Configuration controlPlaneMutualTls() {
        return withBundledCa(configuration())
            .controlPlaneTLSMutualAuthenticationRequired(true)
            .controlPlaneTLSMutualAuthenticationCAChain(OTHER_CA)
            .controlPlanePrivateKeyPath(LEAF_PRIVATE_KEY)
            .controlPlaneX509CertificatePath(LEAF_X509_CERTIFICATE);
    }

    private MockServerClient client(Configuration configuration, ClientAndServer server) {
        ClientOnly client = new ClientOnly(configuration, server.getPort());
        clients.add(client);
        return client.withSecure(true);
    }

    private static java.net.http.HttpResponse<String> send(String path) throws Exception {
        return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + defaultServer.getPort() + path)).timeout(Duration.ofSeconds(30)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
            );
    }

    private static List<Throwable> causes(Throwable throwable) {
        List<Throwable> causes = new ArrayList<>();
        for (Throwable cause = throwable; cause != null && !causes.contains(cause); cause = cause.getCause()) {
            causes.add(cause);
        }
        return causes;
    }

    /**
     * A client whose clean-up releases only its own event loops: MockServerClient.stop() would stop the
     * shared server it points at.
     */
    private static class ClientOnly extends MockServerClient {
        ClientOnly(Configuration configuration, int port) {
            super(configuration, "localhost", port);
        }

        void release() {
            releaseEventLoops();
        }
    }
}
