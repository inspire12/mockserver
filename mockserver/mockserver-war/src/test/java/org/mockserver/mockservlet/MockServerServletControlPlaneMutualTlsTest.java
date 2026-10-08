package org.mockserver.mockservlet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.authentication.ControlPlaneMutualTlsConfigurationException;
import org.mockserver.configuration.ConfigurationProperties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThrows;

/**
 * The servlet is built from the global configuration, so it refuses to start with control-plane mTLS required, no
 * control-plane CA chain and the bundled CA as MockServer's CA. The global values are restored after each test.
 */
public class MockServerServletControlPlaneMutualTlsTest {

    private boolean originalRequired;
    private String originalChain;
    private String originalCertificateAuthorityCertificate;
    private boolean originalDynamicCertificateAuthority;

    @Before
    public void saveGlobalConfiguration() {
        originalRequired = ConfigurationProperties.controlPlaneTLSMutualAuthenticationRequired();
        originalChain = ConfigurationProperties.controlPlaneTLSMutualAuthenticationCAChain();
        originalCertificateAuthorityCertificate = ConfigurationProperties.certificateAuthorityCertificate();
        originalDynamicCertificateAuthority = ConfigurationProperties.dynamicallyCreateCertificateAuthorityCertificate();
    }

    @After
    public void restoreGlobalConfiguration() {
        ConfigurationProperties.controlPlaneTLSMutualAuthenticationRequired(originalRequired);
        ConfigurationProperties.controlPlaneTLSMutualAuthenticationCAChain(originalChain);
        ConfigurationProperties.certificateAuthorityCertificate(originalCertificateAuthorityCertificate);
        ConfigurationProperties.dynamicallyCreateCertificateAuthorityCertificate(originalDynamicCertificateAuthority);
    }

    @Test
    public void shouldRefuseToStartWithControlPlaneMutualTlsWithoutChainAndTheBundledCa() {
        ConfigurationProperties.certificateAuthorityCertificate(ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE);
        ConfigurationProperties.dynamicallyCreateCertificateAuthorityCertificate(false);
        ConfigurationProperties.controlPlaneTLSMutualAuthenticationCAChain("");
        ConfigurationProperties.controlPlaneTLSMutualAuthenticationRequired(true);

        ControlPlaneMutualTlsConfigurationException exception = assertThrows(ControlPlaneMutualTlsConfigurationException.class, MockServerServlet::new);

        assertThat(exception.getMessage(), containsString("set controlPlaneTLSMutualAuthenticationCAChain"));
    }
}
