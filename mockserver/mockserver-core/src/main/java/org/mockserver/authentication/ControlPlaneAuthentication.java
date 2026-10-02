package org.mockserver.authentication;

import org.mockserver.configuration.ControlPlaneAuthenticationSettings;

import java.util.Collections;
import java.util.Set;

/**
 * A control-plane caller's {@link AuthenticationResult} together with the
 * {@link ControlPlaneAuthenticationSettings} snapshot it was authenticated under.
 *
 * <p>A path that authenticates a request and authorizes an operation later (an MCP tool call) must
 * authorize from this same snapshot. Reading a fresh one for the authorization could pair the old
 * authentication with authorization settings from a {@code PUT} applied in between.
 */
public final class ControlPlaneAuthentication {

    private final ControlPlaneAuthenticationSettings settings;
    private final AuthenticationResult result;

    private ControlPlaneAuthentication(ControlPlaneAuthenticationSettings settings, AuthenticationResult result) {
        if (settings == null) {
            throw new IllegalArgumentException("settings must not be null");
        }
        this.settings = settings;
        this.result = result;
    }

    /**
     * @param settings the snapshot the authentication handler was resolved from
     * @param result   the authentication outcome, or {@code null} when the caller was not authenticated
     *                 (it is then authorized with no scopes)
     */
    public static ControlPlaneAuthentication of(ControlPlaneAuthenticationSettings settings, AuthenticationResult result) {
        return new ControlPlaneAuthentication(settings, result);
    }

    public ControlPlaneAuthenticationSettings settings() {
        return settings;
    }

    public AuthenticationResult result() {
        return result;
    }

    /**
     * @return the verified scopes, empty when there is no authentication result
     */
    public Set<String> scopes() {
        return result != null ? result.getScopes() : Collections.emptySet();
    }
}
