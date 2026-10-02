package org.mockserver.authentication;

import org.mockserver.authentication.jwt.JWTAuthenticationHandler;
import org.mockserver.authentication.mtls.MTLSAuthenticationHandler;
import org.mockserver.authentication.oidc.OidcAuthenticationHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ControlPlaneAuthenticationSettings;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the control-plane {@link AuthenticationHandler} chain from a {@link Configuration}.
 *
 * <p>This exists so the handler chain has exactly ONE construction site that is a pure function of the
 * live configuration. Previously the chain was built once during server bootstrap and pushed into
 * {@link org.mockserver.mock.HttpState}; enabling control-plane authentication afterwards (via a system
 * property, a {@link Configuration} mutation, or {@code PUT /mockserver/configuration}) returned success
 * but left the handler {@code null}, and a {@code null} handler means "authenticated" — so the control
 * plane reported itself locked while remaining fully open. Deriving the handler from the configuration on
 * demand, keyed by {@link #signature(Configuration)}, makes every configuration route take effect.
 *
 * <p><strong>Fail closed.</strong> {@link #build} returns {@code null} only when no control-plane
 * authentication is required. If authentication IS required but the handler cannot be constructed (bad
 * JWKS source, unreadable CA chain, ...) it returns a handler that rejects every request rather than
 * {@code null}, so a misconfiguration can never degrade into an open control plane.
 */
public class ControlPlaneAuthenticationHandlerFactory {

    private ControlPlaneAuthenticationHandlerFactory() {
    }

    /**
     * @return {@code true} if any control-plane authentication mechanism is enabled
     */
    public static boolean authenticationRequired(Configuration configuration) {
        return ControlPlaneAuthenticationSettings.of(configuration).authenticationRequired();
    }

    /**
     * A stable string capturing every configuration value that feeds handler construction. When this
     * changes the cached handler must be discarded and rebuilt, which is how a runtime reconfiguration
     * (system property, {@code Configuration} setter, DTO, or {@code PUT /mockserver/configuration})
     * reaches the enforcement point. See {@link ControlPlaneAuthenticationSettings#signature()}.
     */
    public static String signature(Configuration configuration) {
        return ControlPlaneAuthenticationSettings.of(configuration).signature();
    }

    /**
     * Build the control-plane authentication handler for the supplied configuration.
     *
     * @return {@code null} when no control-plane authentication is required; otherwise a handler that
     * enforces every enabled mechanism (chained when more than one is enabled), or a deny-all handler if
     * construction failed
     */
    public static AuthenticationHandler build(Configuration configuration, MockServerLogger mockServerLogger) {
        return build(ControlPlaneAuthenticationSettings.of(configuration), configuration, mockServerLogger);
    }

    /**
     * Build the control-plane authentication handler from one settings snapshot, so every mechanism it
     * enables and every value it is configured with come from the same configuration update.
     *
     * @param configuration supplies the certificate authority the mTLS trust chain is completed with
     */
    public static AuthenticationHandler build(ControlPlaneAuthenticationSettings settings, Configuration configuration, MockServerLogger mockServerLogger) {
        if (!settings.authenticationRequired()) {
            return null;
        }
        try {
            List<AuthenticationHandler> handlers = new ArrayList<>();
            if (Boolean.TRUE.equals(settings.controlPlaneTLSMutualAuthenticationRequired())) {
                handlers.add(new MTLSAuthenticationHandler(
                    mockServerLogger,
                    new NettySslContextFactory(configuration, mockServerLogger, true)
                        .trustCertificateChain(settings.controlPlaneTLSMutualAuthenticationCAChain())
                ));
            }
            if (Boolean.TRUE.equals(settings.controlPlaneJWTAuthenticationRequired())) {
                handlers.add(new JWTAuthenticationHandler(mockServerLogger, settings.controlPlaneJWTAuthenticationJWKSource())
                    .withExpectedAudience(settings.controlPlaneJWTAuthenticationExpectedAudience())
                    .withMatchingClaims(settings.controlPlaneJWTAuthenticationMatchingClaims())
                    .withRequiredClaims(settings.controlPlaneJWTAuthenticationRequiredClaims()));
            }
            if (Boolean.TRUE.equals(settings.controlPlaneOidcAuthenticationRequired())) {
                handlers.add(new OidcAuthenticationHandler(
                    mockServerLogger,
                    settings.controlPlaneOidcJwksUri(),
                    settings.controlPlaneOidcIssuer(),
                    settings.controlPlaneOidcAudience(),
                    settings.controlPlaneOidcScopeClaim(),
                    settings.controlPlaneOidcRequiredScopes()
                ));
            }
            if (handlers.size() == 1) {
                return handlers.get(0);
            }
            return new ChainedAuthenticationHandler(handlers.toArray(new AuthenticationHandler[0]));
        } catch (Throwable throwable) {
            // Authentication IS required but could not be constructed. Returning null here would map to
            // "authenticated" at the enforcement point and silently open the control plane, so deny
            // everything instead and make the misconfiguration loud.
            if (mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("control plane authentication is enabled but the authentication handler could not be created - denying all control plane requests until the configuration is corrected")
                        .setThrowable(throwable)
                );
            }
            return new DenyAllAuthenticationHandler();
        }
    }

    /**
     * Rejects every control-plane request. Used when authentication is required but the configured
     * mechanism could not be constructed, so the failure mode is closed rather than open.
     */
    public static class DenyAllAuthenticationHandler implements AuthenticationHandler {

        @Override
        public boolean controlPlaneRequestAuthenticated(org.mockserver.model.HttpRequest request) {
            throw new AuthenticationException("control plane authentication is enabled but incorrectly configured", true);
        }
    }
}
