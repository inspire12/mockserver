package org.mockserver.configuration;

/**
 * An immutable, consistent view of the {@link Configuration} values a server TLS context is built from.
 *
 * <p>Published through one volatile reference and replaced only once a whole update has been applied
 * (see {@link AtomicConfigurationUpdate}), so a context build that reads its client-authentication,
 * trust, protocol and ALPN inputs from one snapshot, and records that same snapshot's signature, can
 * never cache a context built from one configuration under the signature of another.
 *
 * <p>Values not set on the instance are resolved from {@link ConfigurationProperties}, exactly as the
 * {@link Configuration} getters resolve them, and re-resolved when a global property changes.
 */
public final class ServerTlsSettings {

    static final ServerTlsSettings UNSET = new ServerTlsSettings(Values.UNSET, Values.UNSET, ControlPlaneAuthenticationSettings.UNRESOLVED, 0);

    /** the values as set on the {@link Configuration} instance, {@code null} where unset */
    final Values raw;
    private final Values resolved;
    /** the {@link ConfigurationProperties#modificationCount()} the unset values were resolved at */
    final long generation;
    /** how many {@link AtomicConfigurationUpdate}s had completed on the configuration when this was published */
    final long atomicUpdates;

    ServerTlsSettings(Values raw, Values resolved, long generation, long atomicUpdates) {
        this.raw = raw;
        this.resolved = resolved;
        this.generation = generation;
        this.atomicUpdates = atomicUpdates;
    }

    /**
     * @return the configuration's current server TLS inputs, all from one update
     */
    public static ServerTlsSettings of(Configuration configuration) {
        return configuration.serverTlsSettings();
    }

    /**
     * @return {@code true} when a multi-field update, such as {@code PUT /mockserver/configuration}, has
     * been applied since {@code earlier} was published
     */
    public boolean updatedSince(ServerTlsSettings earlier) {
        return atomicUpdates != earlier.atomicUpdates;
    }

    public Boolean tlsMutualAuthenticationRequired() {
        return resolved.tlsMutualAuthenticationRequired;
    }

    public String tlsMutualAuthenticationCertificateChain() {
        return resolved.tlsMutualAuthenticationCertificateChain;
    }

    public String tlsProtocols() {
        return resolved.tlsProtocols;
    }

    public Boolean tlsAllowInsecureProtocols() {
        return resolved.tlsAllowInsecureProtocols;
    }

    public Boolean http2Enabled() {
        return resolved.http2Enabled;
    }

    public String certificateAuthorityCertificate() {
        return resolved.certificateAuthorityCertificate;
    }

    public String certificateAuthorityPrivateKey() {
        return resolved.certificateAuthorityPrivateKey;
    }

    /**
     * @return the effective value, {@code true} whenever {@code proxySetup} is enabled, as
     * {@link Configuration#dynamicallyCreateCertificateAuthorityCertificate()} returns it
     */
    public Boolean dynamicallyCreateCertificateAuthorityCertificate() {
        return resolved.dynamicallyCreateCertificateAuthorityCertificate;
    }

    public String directoryToSaveDynamicSSLCertificate() {
        return resolved.directoryToSaveDynamicSSLCertificate;
    }

    public String privateKeyPath() {
        return resolved.privateKeyPath;
    }

    public String x509CertificatePath() {
        return resolved.x509CertificatePath;
    }

    public Boolean preventCertificateDynamicUpdate() {
        return resolved.preventCertificateDynamicUpdate;
    }

    static final class Values {

        static final Values UNSET = new Values(null, null, null, null, null, null, null, null, null, null, null, null, null);

        final Boolean tlsMutualAuthenticationRequired;
        final String tlsMutualAuthenticationCertificateChain;
        final String tlsProtocols;
        final Boolean tlsAllowInsecureProtocols;
        final Boolean http2Enabled;
        final String certificateAuthorityCertificate;
        final String certificateAuthorityPrivateKey;
        final Boolean dynamicallyCreateCertificateAuthorityCertificate;
        final Boolean proxySetup;
        final String directoryToSaveDynamicSSLCertificate;
        final String privateKeyPath;
        final String x509CertificatePath;
        final Boolean preventCertificateDynamicUpdate;

        @SuppressWarnings("java:S107")
        Values(Boolean tlsMutualAuthenticationRequired, String tlsMutualAuthenticationCertificateChain,
               String tlsProtocols, Boolean tlsAllowInsecureProtocols, Boolean http2Enabled,
               String certificateAuthorityCertificate, String certificateAuthorityPrivateKey,
               Boolean dynamicallyCreateCertificateAuthorityCertificate, Boolean proxySetup,
               String directoryToSaveDynamicSSLCertificate, String privateKeyPath, String x509CertificatePath,
               Boolean preventCertificateDynamicUpdate) {
            this.tlsMutualAuthenticationRequired = tlsMutualAuthenticationRequired;
            this.tlsMutualAuthenticationCertificateChain = tlsMutualAuthenticationCertificateChain;
            this.tlsProtocols = tlsProtocols;
            this.tlsAllowInsecureProtocols = tlsAllowInsecureProtocols;
            this.http2Enabled = http2Enabled;
            this.certificateAuthorityCertificate = certificateAuthorityCertificate;
            this.certificateAuthorityPrivateKey = certificateAuthorityPrivateKey;
            this.dynamicallyCreateCertificateAuthorityCertificate = dynamicallyCreateCertificateAuthorityCertificate;
            this.proxySetup = proxySetup;
            this.directoryToSaveDynamicSSLCertificate = directoryToSaveDynamicSSLCertificate;
            this.privateKeyPath = privateKeyPath;
            this.x509CertificatePath = x509CertificatePath;
            this.preventCertificateDynamicUpdate = preventCertificateDynamicUpdate;
        }
    }
}
