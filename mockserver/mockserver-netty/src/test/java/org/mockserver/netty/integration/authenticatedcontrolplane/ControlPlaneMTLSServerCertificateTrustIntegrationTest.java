package org.mockserver.netty.integration.authenticatedcontrolplane;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;

import javax.net.ssl.SSLHandshakeException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.file.FileReader.readFileFromClassPathOrPath;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Which certificate authorities MockServerClient trusts for MockServer's own certificate. With control-plane
 * mTLS it trusts only controlPlaneTLSMutualAuthenticationCAChain; without it, MockServer's CA certificate.
 * Every server and client pins its settings on its own Configuration so the global store cannot move them.
 */
public class ControlPlaneMTLSServerCertificateTrustIntegrationTest {

    private static final String CONTROL_PLANE_CA_CHAIN = "org/mockserver/netty/integration/tls/ca.pem";
    private static final String CONTROL_PLANE_CA_PRIVATE_KEY = "org/mockserver/netty/integration/tls/ca-key-pkcs8.pem";
    // signed by CONTROL_PLANE_CA_CHAIN, with localhost in its subject alternative names
    private static final String LEAF_PRIVATE_KEY = "org/mockserver/netty/integration/tls/leaf-key-pkcs8.pem";
    private static final String LEAF_X509_CERTIFICATE = "org/mockserver/netty/integration/tls/leaf-cert.pem";

    @ClassRule
    public static final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static ClientAndServer serverSignedByMockServerCa;
    private static ClientAndServer serverSignedByControlPlaneCa;
    private static ClientAndServer serverWithoutControlPlaneMutualTls;
    private static String controlPlaneCaChainPlusMockServerCa;
    private final List<ClientOnly> clients = new ArrayList<>();

    @BeforeClass
    public static void startServers() throws Exception {
        Configuration signedByMockServerCa = controlPlaneMutualTlsServer();
        serverSignedByMockServerCa = ClientAndServer.startClientAndServer(signedByMockServerCa);
        serverSignedByControlPlaneCa = ClientAndServer.startClientAndServer(
            controlPlaneMutualTlsServer()
                .certificateAuthorityCertificate(CONTROL_PLANE_CA_CHAIN)
                .certificateAuthorityPrivateKey(CONTROL_PLANE_CA_PRIVATE_KEY)
                .privateKeyPath(LEAF_PRIVATE_KEY)
                .x509CertificatePath(LEAF_X509_CERTIFICATE)
        );
        serverWithoutControlPlaneMutualTls = ClientAndServer.startClientAndServer(withoutControlPlaneAuthentication(configuration()));

        File chain = temporaryFolder.newFile("control-plane-ca-chain-plus-mockserver-ca.pem");
        Files.write(chain.toPath(), (readFileFromClassPathOrPath(CONTROL_PLANE_CA_CHAIN) + "\n"
            + readFileFromClassPathOrPath(signedByMockServerCa.certificateAuthorityCertificate())).getBytes(StandardCharsets.UTF_8));
        controlPlaneCaChainPlusMockServerCa = chain.getAbsolutePath();
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(serverSignedByMockServerCa);
        stopQuietly(serverSignedByControlPlaneCa);
        stopQuietly(serverWithoutControlPlaneMutualTls);
    }

    @After
    public void releaseClients() {
        clients.forEach(ClientOnly::release);
    }

    @Test
    public void shouldRejectServerCertificateSignedOnlyByMockServerCaWhenControlPlaneChainIsSet() {
        MockServerClient client = controlPlaneMutualTlsClient(CONTROL_PLANE_CA_CHAIN, serverSignedByMockServerCa);

        RuntimeException exception = assertThrows(RuntimeException.class, client::reset);

        assertThat(causes(exception), hasItem(instanceOf(SSLHandshakeException.class)));
    }

    @Test
    public void shouldAcceptServerCertificateSignedByControlPlaneChain() {
        assertControlPlaneUsable(controlPlaneMutualTlsClient(CONTROL_PLANE_CA_CHAIN, serverSignedByControlPlaneCa));
    }

    @Test
    public void shouldAcceptServerCertificateSignedByMockServerCaWhenControlPlaneChainIncludesIt() {
        assertControlPlaneUsable(controlPlaneMutualTlsClient(controlPlaneCaChainPlusMockServerCa, serverSignedByMockServerCa));
    }

    @Test
    public void shouldTrustMockServerCaWithoutControlPlaneMutualTls() {
        MockServerClient client = track(new ClientOnly(
            withoutControlPlaneAuthentication(configuration()),
            serverWithoutControlPlaneMutualTls.getPort()
        ));

        assertControlPlaneUsable(client);
    }

    private static Configuration controlPlaneMutualTlsServer() {
        return withoutControlPlaneAuthentication(configuration())
            .controlPlaneTLSMutualAuthenticationRequired(true)
            .controlPlaneTLSMutualAuthenticationCAChain(CONTROL_PLANE_CA_CHAIN)
            .controlPlanePrivateKeyPath(LEAF_PRIVATE_KEY)
            .controlPlaneX509CertificatePath(LEAF_X509_CERTIFICATE);
    }

    private static Configuration withoutControlPlaneAuthentication(Configuration configuration) {
        return configuration
            .controlPlaneTLSMutualAuthenticationRequired(false)
            .controlPlaneJWTAuthenticationRequired(false)
            .controlPlaneOidcAuthenticationRequired(false);
    }

    private MockServerClient controlPlaneMutualTlsClient(String controlPlaneCaChain, ClientAndServer server) {
        return track(new ClientOnly(
            configuration()
                .controlPlaneTLSMutualAuthenticationRequired(true)
                .controlPlaneTLSMutualAuthenticationCAChain(controlPlaneCaChain)
                .controlPlanePrivateKeyPath(LEAF_PRIVATE_KEY)
                .controlPlaneX509CertificatePath(LEAF_X509_CERTIFICATE),
            server.getPort()
        ));
    }

    private MockServerClient track(ClientOnly client) {
        clients.add(client);
        return client.withSecure(true);
    }

    private static void assertControlPlaneUsable(MockServerClient client) {
        client.reset();
        client.when(request("/trusted")).respond(response().withBody("trusted"));
        assertThat(client.retrieveActiveExpectations(request("/trusted")).length, is(1));
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

    private static List<Throwable> causes(Throwable throwable) {
        List<Throwable> causes = new ArrayList<>();
        for (Throwable cause = throwable; cause != null && !causes.contains(cause); cause = cause.getCause()) {
            causes.add(cause);
        }
        return causes;
    }
}
