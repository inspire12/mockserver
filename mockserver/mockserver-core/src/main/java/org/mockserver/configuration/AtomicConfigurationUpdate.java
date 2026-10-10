package org.mockserver.configuration;

/**
 * Applies several {@link Configuration} changes so that readers of the security-relevant snapshots
 * ({@link ControlPlaneAuthenticationSettings}, {@link ServerTlsSettings}) see either all of them or none.
 *
 * <p>The update runs under {@code synchronized (configuration)}. Setters called inside it write their
 * fields as usual (so the getters see each change at once) but leave the snapshots alone; the snapshots
 * are replaced once, when the outermost update finishes, whether it completes or throws. Setters called
 * outside an update replace the snapshots themselves.
 */
public final class AtomicConfigurationUpdate {

    private AtomicConfigurationUpdate() {
    }

    public static void apply(Configuration configuration, Runnable update) {
        configuration.applyAtomically(update);
    }
}
