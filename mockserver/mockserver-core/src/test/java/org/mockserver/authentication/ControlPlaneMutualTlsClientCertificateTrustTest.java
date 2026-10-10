package org.mockserver.authentication;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.JDKCertificateToMockServerX509Certificate;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.model.ConfigurationDTO;
import org.mockserver.socket.tls.KeyAndCertificateFactory;
import org.mockserver.socket.tls.PEMToFile;
import org.slf4j.event.Level;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.configuration.ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_PRIVATE_KEY;
import static org.mockserver.configuration.ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE;
import static org.mockserver.file.FileReader.readFileFromClassPathOrPath;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.socket.tls.KeyAndCertificateFactoryFactory.createKeyAndCertificateFactory;

/**
 * Which client certificates control-plane mTLS accepts. With controlPlaneTLSMutualAuthenticationCAChain set, only
 * those signed by that chain; without it, those signed by MockServer's CA, unless that is the bundled CA.
 */
public class ControlPlaneMutualTlsClientCertificateTrustTest {

    private static final String CONTROL_PLANE_CA_CHAIN = "org/mockserver/authentication/mtls/ca.pem";
    private static final String CONTROL_PLANE_CA_PRIVATE_KEY = "org/mockserver/authentication/mtls/ca-key-pkcs8.pem";
    // signed by CONTROL_PLANE_CA_CHAIN
    private static final String CONTROL_PLANE_LEAF = "org/mockserver/authentication/mtls/leaf-cert.pem";

    @ClassRule
    public static final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    @Test
    public void shouldRejectClientCertificateSignedOnlyByMockServerCaWhenChainIsSet() throws IOException {
        AuthenticationHandler handler = ControlPlaneAuthenticationHandlerFactory.build(withBundledCa().controlPlaneTLSMutualAuthenticationCAChain(CONTROL_PLANE_CA_CHAIN), mockServerLogger);

        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> handler.controlPlaneRequestAuthenticated(requestPresenting(signedByBundledCa())));

        assertThat(exception.getMessage(), containsString("no client certificates can be validated by control plane CA"));
    }

    @Test
    public void shouldAcceptClientCertificateSignedByChain() {
        AuthenticationHandler handler = ControlPlaneAuthenticationHandlerFactory.build(withBundledCa().controlPlaneTLSMutualAuthenticationCAChain(CONTROL_PLANE_CA_CHAIN), mockServerLogger);

        assertThat(handler.controlPlaneRequestAuthenticated(requestPresenting(PEMToFile.x509FromPEMFile(CONTROL_PLANE_LEAF))), is(true));
    }

    @Test
    public void shouldTrustOnlyTheChainWhenChainIsSet() {
        X509Certificate[] trusted = ControlPlaneAuthenticationHandlerFactory.controlPlaneClientCertificateTrust(CONTROL_PLANE_CA_CHAIN, withBundledCa(), mockServerLogger);

        assertThat(trusted, arrayContaining(PEMToFile.x509ChainFromPEMFile(CONTROL_PLANE_CA_CHAIN).toArray()));
    }

    @Test
    public void shouldDenyEveryRequestWhenNoChainIsSetAndMockServerCaIsTheBundledCa() throws IOException {
        AuthenticationHandler handler = ControlPlaneAuthenticationHandlerFactory.build(withBundledCa().controlPlaneTLSMutualAuthenticationCAChain(""), mockServerLogger);

        assertThat(handler, instanceOf(ControlPlaneAuthenticationHandlerFactory.DenyAllAuthenticationHandler.class));
        assertThrows(AuthenticationException.class, () -> handler.controlPlaneRequestAuthenticated(requestPresenting(signedByBundledCa())));
    }

    @Test
    public void shouldTrustMockServerCaWhenNoChainIsSetAndMockServerCaIsNotTheBundledCa() {
        Configuration configuration = mutualTlsServer()
            .certificateAuthorityCertificate(CONTROL_PLANE_CA_CHAIN)
            .certificateAuthorityPrivateKey(CONTROL_PLANE_CA_PRIVATE_KEY)
            .controlPlaneTLSMutualAuthenticationCAChain("");

        AuthenticationHandler handler = ControlPlaneAuthenticationHandlerFactory.build(configuration, mockServerLogger);

        assertThat(handler.controlPlaneRequestAuthenticated(requestPresenting(PEMToFile.x509FromPEMFile(CONTROL_PLANE_LEAF))), is(true));
    }

    @Test
    public void shouldWarnWhenChainIncludesBundledCa() throws IOException {
        File chain = temporaryFolder.newFile("chain-with-bundled-ca.pem");
        Files.write(chain.toPath(), (readFileFromClassPathOrPath(CONTROL_PLANE_CA_CHAIN) + "\n"
            + readFileFromClassPathOrPath(DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE)).getBytes(StandardCharsets.UTF_8));
        CapturingLogger logger = new CapturingLogger();

        ControlPlaneAuthenticationHandlerFactory.controlPlaneClientCertificateTrust(chain.getAbsolutePath(), withBundledCa(), logger);

        assertThat(logger.messages(Level.WARN), hasItem(containsString("controlPlaneTLSMutualAuthenticationCAChain includes the bundled default Certificate Authority")));
    }

    @Test
    public void shouldNotWarnWhenChainExcludesBundledCa() {
        CapturingLogger logger = new CapturingLogger();

        ControlPlaneAuthenticationHandlerFactory.controlPlaneClientCertificateTrust(CONTROL_PLANE_CA_CHAIN, withBundledCa(), logger);

        assertThat(logger.messages(Level.WARN), empty());
    }

    @Test
    public void shouldRefuseMutualTlsWithoutChainWhenMockServerCaIsTheBundledCa() {
        ControlPlaneMutualTlsConfigurationException exception = assertThrows(ControlPlaneMutualTlsConfigurationException.class,
            () -> ControlPlaneAuthenticationHandlerFactory.requireUsableControlPlaneMutualTls(withBundledCa().controlPlaneTLSMutualAuthenticationCAChain("")));

        assertThat(exception.getMessage(), allOf(
            containsString("set controlPlaneTLSMutualAuthenticationCAChain"),
            containsString("configure MockServer's own CA with certificateAuthorityCertificate and certificateAuthorityPrivateKey")
        ));
    }

    @Test
    public void shouldRefuseMutualTlsWithoutChainWhenMockServerCaIsACopyOfTheBundledCa() throws IOException {
        File copy = temporaryFolder.newFile("copy-of-bundled-ca.pem");
        Files.write(copy.toPath(), readFileFromClassPathOrPath(DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE).getBytes(StandardCharsets.UTF_8));

        assertThrows(ControlPlaneMutualTlsConfigurationException.class,
            () -> ControlPlaneAuthenticationHandlerFactory.requireUsableControlPlaneMutualTls(withBundledCa().certificateAuthorityCertificate(copy.getAbsolutePath()).controlPlaneTLSMutualAuthenticationCAChain("")));
    }

    @Test
    public void shouldAcceptUsableControlPlaneMutualTlsSetUps() {
        ControlPlaneAuthenticationHandlerFactory.requireUsableControlPlaneMutualTls(withBundledCa().controlPlaneTLSMutualAuthenticationCAChain(CONTROL_PLANE_CA_CHAIN));
        ControlPlaneAuthenticationHandlerFactory.requireUsableControlPlaneMutualTls(withBundledCa().controlPlaneTLSMutualAuthenticationRequired(false).controlPlaneTLSMutualAuthenticationCAChain(""));
        ControlPlaneAuthenticationHandlerFactory.requireUsableControlPlaneMutualTls(withBundledCa().dynamicallyCreateCertificateAuthorityCertificate(true).controlPlaneTLSMutualAuthenticationCAChain(""));
        ControlPlaneAuthenticationHandlerFactory.requireUsableControlPlaneMutualTls(mutualTlsServer()
            .certificateAuthorityCertificate(CONTROL_PLANE_CA_CHAIN)
            .certificateAuthorityPrivateKey(CONTROL_PLANE_CA_PRIVATE_KEY)
            .controlPlaneTLSMutualAuthenticationCAChain(""));
    }

    @Test
    public void shouldRefuseAnUpdateThatLeavesMutualTlsWithoutChainAndTheBundledCa() {
        Configuration withoutMutualTls = withBundledCa().controlPlaneTLSMutualAuthenticationRequired(false).controlPlaneTLSMutualAuthenticationCAChain("");
        Configuration withChain = withBundledCa().controlPlaneTLSMutualAuthenticationCAChain(CONTROL_PLANE_CA_CHAIN);

        assertThrows(ControlPlaneMutualTlsConfigurationException.class, () -> ControlPlaneAuthenticationHandlerFactory.requireUsableControlPlaneMutualTls(
            new ConfigurationDTO().setControlPlaneTLSMutualAuthenticationRequired(true), withoutMutualTls));
        assertThrows(ControlPlaneMutualTlsConfigurationException.class, () -> ControlPlaneAuthenticationHandlerFactory.requireUsableControlPlaneMutualTls(
            new ConfigurationDTO().setControlPlaneTLSMutualAuthenticationCAChain(""), withChain));
        assertThrows(ControlPlaneMutualTlsConfigurationException.class, () -> ControlPlaneAuthenticationHandlerFactory.requireUsableControlPlaneMutualTls(
            new ConfigurationDTO().setControlPlaneTLSMutualAuthenticationCAChain("").setCertificateAuthorityCertificate(DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE),
            mutualTlsServer().certificateAuthorityCertificate(CONTROL_PLANE_CA_CHAIN).certificateAuthorityPrivateKey(CONTROL_PLANE_CA_PRIVATE_KEY).controlPlaneTLSMutualAuthenticationCAChain(CONTROL_PLANE_CA_CHAIN)));
    }

    @Test
    public void shouldAcceptAnUpdateThatLeavesUsableMutualTls() {
        Configuration withoutMutualTls = withBundledCa().controlPlaneTLSMutualAuthenticationRequired(false).controlPlaneTLSMutualAuthenticationCAChain("");

        ControlPlaneAuthenticationHandlerFactory.requireUsableControlPlaneMutualTls(
            new ConfigurationDTO().setControlPlaneTLSMutualAuthenticationRequired(true).setControlPlaneTLSMutualAuthenticationCAChain(CONTROL_PLANE_CA_CHAIN), withoutMutualTls);
        ControlPlaneAuthenticationHandlerFactory.requireUsableControlPlaneMutualTls(
            new ConfigurationDTO().setControlPlaneTLSMutualAuthenticationRequired(true).setDynamicallyCreateCertificateAuthorityCertificate(true), withoutMutualTls);
        ControlPlaneAuthenticationHandlerFactory.requireUsableControlPlaneMutualTls(
            new ConfigurationDTO().setMaxExpectations(10), withoutMutualTls);
    }

    private static Configuration mutualTlsServer() {
        return configuration()
            .controlPlaneTLSMutualAuthenticationRequired(true)
            .controlPlaneJWTAuthenticationRequired(false)
            .controlPlaneOidcAuthenticationRequired(false)
            .dynamicallyCreateCertificateAuthorityCertificate(false)
            .proactivelyInitialiseTLS(false);
    }

    private static Configuration withBundledCa() {
        return mutualTlsServer()
            .certificateAuthorityCertificate(DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE)
            .certificateAuthorityPrivateKey(DEFAULT_CERTIFICATE_AUTHORITY_PRIVATE_KEY);
    }

    /**
     * A certificate MockServer issues with its bundled CA, which permits client authentication.
     */
    private X509Certificate signedByBundledCa() throws IOException {
        KeyAndCertificateFactory factory = createKeyAndCertificateFactory(
            withBundledCa().directoryToSaveDynamicSSLCertificate(temporaryFolder.newFolder().getAbsolutePath()),
            mockServerLogger
        );
        factory.buildAndSavePrivateKeyAndX509Certificate();
        X509Certificate certificate = factory.x509Certificate();
        assertThat(certificate.getIssuerX500Principal(), is(PEMToFile.x509FromPEMFile(DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE).getSubjectX500Principal()));
        return certificate;
    }

    private HttpRequest requestPresenting(X509Certificate certificate) {
        return new JDKCertificateToMockServerX509Certificate(mockServerLogger).setClientCertificates(request(), new X509Certificate[]{certificate});
    }

    private static class CapturingLogger extends MockServerLogger {
        private final List<LogEntry> logged = new CopyOnWriteArrayList<>();

        CapturingLogger() {
            super(configuration().logLevel("INFO"), ControlPlaneMutualTlsClientCertificateTrustTest.class);
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry);
        }

        List<String> messages(Level level) {
            return logged.stream()
                .filter(logEntry -> logEntry.getLogLevel() == level)
                .map(LogEntry::getMessage)
                .collect(Collectors.toList());
        }
    }
}
