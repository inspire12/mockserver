package org.mockserver.collections;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * A process-wide slot that each server running in the JVM registers its own instance in: the one in use is the
 * one registered most recently. When a stopping server unregisters the one in use, the one registered most
 * recently by a server still running is used instead, so an older server keeps working once a newer one stops.
 * <p>
 * The instance in use is held strongly; the others are held weakly, so the instance of a server that was never
 * stopped is dropped once nothing else refers to it, and is passed over when falling back.
 */
public final class MostRecentRegistration<T> {

    private volatile T inUse;
    // guarded by this
    private final List<WeakReference<T>> registered = new ArrayList<>();

    /**
     * @return the instance in use, or {@code null} if none
     */
    public T get() {
        return inUse;
    }

    /**
     * Use {@code instance} from now on without registering it, so no later {@link #unregister} falls back to
     * it; {@code null} leaves nothing in use. The instances registered are kept.
     */
    public synchronized void set(T instance) {
        this.inUse = instance;
    }

    /**
     * Use {@code instance}, a server's, from now on and keep it, as the most recent, to fall back to; registering
     * it again makes it the most recent again. {@code null} is ignored.
     */
    public synchronized void register(T instance) {
        if (instance != null) {
            removeRegistered(instance);
            registered.add(new WeakReference<>(instance));
            this.inUse = instance;
        }
    }

    /**
     * Forget {@code instance}, a stopping server's. If it is the one in use, the one registered most recently that
     * is still registered is used instead, or none; an instance in use that is not {@code instance} is kept.
     */
    public synchronized void unregister(T instance) {
        if (instance != null) {
            removeRegistered(instance);
            if (this.inUse == instance) {
                this.inUse = mostRecentlyRegistered();
            }
        }
    }

    private T mostRecentlyRegistered() {
        for (int i = registered.size() - 1; i >= 0; i--) {
            T instance = registered.get(i).get();
            if (instance != null) {
                return instance;
            }
            registered.remove(i);
        }
        return null;
    }

    // also drops the entries of instances already collected
    private void removeRegistered(T instance) {
        registered.removeIf(reference -> {
            T referent = reference.get();
            return referent == null || referent == instance;
        });
    }
}
