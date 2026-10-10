package org.mockserver.authentication;

/**
 * Control-plane mTLS is required with no control-plane CA chain while MockServer's CA certificate is the CA bundled
 * with MockServer. MockServer refuses to start, and {@code PUT /mockserver/configuration} answers {@code 400}, with
 * the message, which names both fixes.
 */
public class ControlPlaneMutualTlsConfigurationException extends IllegalArgumentException {

    static final String MESSAGE = "controlPlaneTLSMutualAuthenticationRequired is enabled without controlPlaneTLSMutualAuthenticationCAChain"
        + " while MockServer's CA certificate is the default CA bundled with MockServer, which must not be trusted for control-plane"
        + " client certificates: set controlPlaneTLSMutualAuthenticationCAChain to the CA chain that signs your control-plane client"
        + " certificates, or configure MockServer's own CA with certificateAuthorityCertificate and certificateAuthorityPrivateKey";

    public ControlPlaneMutualTlsConfigurationException() {
        super(MESSAGE);
    }
}
