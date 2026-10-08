package org.mockserver.netty.integration.authenticatedcontrolplane;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.rules.TemporaryFolder;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.testing.integration.mock.AbstractBasicMockingSameJVMIntegrationTest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.mockserver.configuration.ConfigurationProperties.*;
import static org.mockserver.file.FileReader.readFileFromClassPathOrPath;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * @author jamesdbloom
 */
public class AuthenticatedControlPlaneUsingMTLSClientMockingIntegrationTest extends AbstractBasicMockingSameJVMIntegrationTest {

    private static String originalControlPlaneTLSMutualAuthenticationCAChain;
    private static String originalControlPlanePrivateKeyPath;
    private static String originalControlPlaneX509CertificatePath;
    private static boolean originalControlPlaneTLSMutualAuthenticationRequired;

    @ClassRule
    public static final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @BeforeClass
    public static void startServer() throws IOException {
        // save original value
        originalControlPlaneTLSMutualAuthenticationCAChain = controlPlaneTLSMutualAuthenticationCAChain();
        originalControlPlanePrivateKeyPath = controlPlanePrivateKeyPath();
        originalControlPlaneX509CertificatePath = controlPlaneX509CertificatePath();
        originalControlPlaneTLSMutualAuthenticationRequired = controlPlaneTLSMutualAuthenticationRequired();

        // set new certificate authority values
        // one chain for both sides: the server checks the client's leaf against ca.pem, and the client checks
        // the server's certificate, which MockServer's CA signed, against MockServer's CA
        File chain = temporaryFolder.newFile("ca-plus-mockserver-ca.pem");
        Files.write(chain.toPath(), (readFileFromClassPathOrPath("org/mockserver/netty/integration/tls/ca.pem") + "\n"
            + readFileFromClassPathOrPath(certificateAuthorityCertificate())).getBytes(StandardCharsets.UTF_8));
        controlPlaneTLSMutualAuthenticationCAChain(chain.getAbsolutePath());
        controlPlanePrivateKeyPath("org/mockserver/netty/integration/tls/leaf-key-pkcs8.pem");
        controlPlaneX509CertificatePath("org/mockserver/netty/integration/tls/leaf-cert.pem");
        controlPlaneTLSMutualAuthenticationRequired(true);

        mockServerClient = ClientAndServer.startClientAndServer().withSecure(true);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);

        // set back to original value
        controlPlaneTLSMutualAuthenticationCAChain(originalControlPlaneTLSMutualAuthenticationCAChain);
        controlPlanePrivateKeyPath(originalControlPlanePrivateKeyPath);
        controlPlaneX509CertificatePath(originalControlPlaneX509CertificatePath);
        controlPlaneTLSMutualAuthenticationRequired(originalControlPlaneTLSMutualAuthenticationRequired);
    }

    @Override
    public int getServerPort() {
        return mockServerClient.getPort();
    }

    @Override
    protected boolean isSecureControlPlane() {
        return true;
    }

}
