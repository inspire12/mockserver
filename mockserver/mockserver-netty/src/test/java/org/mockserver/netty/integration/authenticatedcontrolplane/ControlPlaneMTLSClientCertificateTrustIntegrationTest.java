package org.mockserver.netty.integration.authenticatedcontrolplane;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.authentication.AuthenticationException;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.KeyAndCertificateFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.configuration.ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_PRIVATE_KEY;
import static org.mockserver.configuration.ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE;
import static org.mockserver.file.FileReader.readFileFromClassPathOrPath;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.socket.tls.KeyAndCertificateFactoryFactory.createKeyAndCertificateFactory;
import static org.mockserver.socket.tls.PEMToFile.certToPEM;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Which client certificates MockServer accepts on the control plane. With control-plane mTLS and a
 * controlPlaneTLSMutualAuthenticationCAChain, only those signed by that chain, not those signed by MockServer's
 * own CA. Every server and client pins its settings on its own Configuration so the global store cannot move them.
 */
public class ControlPlaneMTLSClientCertificateTrustIntegrationTest {

    private static final String CONTROL_PLANE_CA_CHAIN = "org/mockserver/netty/integration/tls/ca.pem";
    // signed by CONTROL_PLANE_CA_CHAIN
    private static final String CONTROL_PLANE_LEAF_PRIVATE_KEY = "org/mockserver/netty/integration/tls/leaf-key-pkcs8.pem";
    private static final String CONTROL_PLANE_LEAF_X509_CERTIFICATE = "org/mockserver/netty/integration/tls/leaf-cert.pem";

    @ClassRule
    public static final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static ClientAndServer serverWithControlPlaneChain;
    private static ClientAndServer serverWithoutControlPlaneMutualTls;
    // MockServer's CA signed the servers' certificates, so a client must trust it as well as the control-plane chain
    private static String clientTrustChain;
    private static String leafSignedByMockServerCaPrivateKey;
    private static String leafSignedByMockServerCaX509Certificate;
    private final List<ClientOnly> clients = new ArrayList<>();

    @BeforeClass
    public static void startServers() throws Exception {
        serverWithControlPlaneChain = ClientAndServer.startClientAndServer(
            withBundledCa(withoutControlPlaneAuthentication(configuration()))
                .controlPlaneTLSMutualAuthenticationRequired(true)
                .controlPlaneTLSMutualAuthenticationCAChain(CONTROL_PLANE_CA_CHAIN)
        );
        serverWithoutControlPlaneMutualTls = ClientAndServer.startClientAndServer(withBundledCa(withoutControlPlaneAuthentication(configuration())));

        File chain = temporaryFolder.newFile("control-plane-ca-chain-plus-mockserver-ca.pem");
        Files.write(chain.toPath(), (readFileFromClassPathOrPath(CONTROL_PLANE_CA_CHAIN) + "\n"
            + readFileFromClassPathOrPath(DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE)).getBytes(StandardCharsets.UTF_8));
        clientTrustChain = chain.getAbsolutePath();

        KeyAndCertificateFactory mockServerCa = createKeyAndCertificateFactory(
            withBundledCa(configuration()).directoryToSaveDynamicSSLCertificate(temporaryFolder.newFolder().getAbsolutePath()),
            new MockServerLogger()
        );
        mockServerCa.buildAndSavePrivateKeyAndX509Certificate();
        leafSignedByMockServerCaPrivateKey = write("leaf-signed-by-mockserver-ca-key.pem", "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(mockServerCa.privateKey().getEncoded())
            + "\n-----END PRIVATE KEY-----\n");
        leafSignedByMockServerCaX509Certificate = write("leaf-signed-by-mockserver-ca-cert.pem", certToPEM(mockServerCa.x509Certificate()));
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(serverWithControlPlaneChain);
        stopQuietly(serverWithoutControlPlaneMutualTls);
    }

    @After
    public void releaseClients() {
        clients.forEach(ClientOnly::release);
    }

    @Test
    public void shouldRejectClientCertificateSignedOnlyByMockServerCaWhenControlPlaneChainIsSet() {
        MockServerClient client = controlPlaneMutualTlsClient(leafSignedByMockServerCaPrivateKey, leafSignedByMockServerCaX509Certificate);

        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> client.retrieveActiveExpectations(request()));

        assertThat(exception.getMessage(), containsString("no client certificates can be validated by control plane CA"));
    }

    @Test
    public void shouldAcceptClientCertificateSignedByControlPlaneChain() {
        assertControlPlaneUsable(controlPlaneMutualTlsClient(CONTROL_PLANE_LEAF_PRIVATE_KEY, CONTROL_PLANE_LEAF_X509_CERTIFICATE));
    }

    @Test
    public void shouldAcceptClientWithoutCertificateWithoutControlPlaneMutualTls() {
        MockServerClient client = track(new ClientOnly(withBundledCa(withoutControlPlaneAuthentication(configuration())), serverWithoutControlPlaneMutualTls.getPort()));

        assertControlPlaneUsable(client);
    }

    private static Configuration withoutControlPlaneAuthentication(Configuration configuration) {
        return configuration
            .controlPlaneTLSMutualAuthenticationRequired(false)
            .controlPlaneJWTAuthenticationRequired(false)
            .controlPlaneOidcAuthenticationRequired(false);
    }

    private static Configuration withBundledCa(Configuration configuration) {
        return configuration
            .dynamicallyCreateCertificateAuthorityCertificate(false)
            .certificateAuthorityCertificate(DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE)
            .certificateAuthorityPrivateKey(DEFAULT_CERTIFICATE_AUTHORITY_PRIVATE_KEY);
    }

    private static String write(String name, String pem) throws IOException {
        File file = temporaryFolder.newFile(name);
        Files.write(file.toPath(), pem.getBytes(StandardCharsets.UTF_8));
        return file.getAbsolutePath();
    }

    private MockServerClient controlPlaneMutualTlsClient(String privateKey, String x509Certificate) {
        return track(new ClientOnly(
            withBundledCa(configuration())
                .controlPlaneTLSMutualAuthenticationRequired(true)
                .controlPlaneTLSMutualAuthenticationCAChain(clientTrustChain)
                .controlPlanePrivateKeyPath(privateKey)
                .controlPlaneX509CertificatePath(x509Certificate),
            serverWithControlPlaneChain.getPort()
        ));
    }

    private MockServerClient track(ClientOnly client) {
        clients.add(client);
        return client.withSecure(true);
    }

    private static void assertControlPlaneUsable(MockServerClient client) {
        client.when(request("/trusted-client")).respond(response().withBody("trusted"));
        assertThat(client.retrieveActiveExpectations(request("/trusted-client")).length, is(1));
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
