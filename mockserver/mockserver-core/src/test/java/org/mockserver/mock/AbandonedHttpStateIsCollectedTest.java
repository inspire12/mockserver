package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * An {@link HttpState} dropped without {@link HttpState#stop()} is kept by its own event-log thread until that
 * thread ends; after that, the process-wide places it registered in, such as the {@link CrossProtocolEventBus},
 * must not keep it or its scenario manager, and a server still running must get its own scenario manager back when
 * a newer one stops.
 * <p>
 * Runs in the sequential phase: it uses the bus singleton and the static metrics readers every HttpState replaces.
 */
public class AbandonedHttpStateIsCollectedTest {

    private static final long COLLECTED_WITHIN_SECONDS = 30;

    @Test
    public void shouldLetAnHttpStateDroppedWithoutStopBeCollectedOnceItsEventLogHasStopped() throws InterruptedException {
        HttpState running = newHttpState();
        try {
            WeakReference<?>[] abandoned = abandonedWithoutStop();
            // a newer server replaces the readers the abandoned one registered with the metrics
            HttpState newer = newHttpState();
            try {
                assertCollected("the scenario manager of an HttpState never stopped", abandoned[1]);
                assertCollected("an HttpState never stopped", abandoned[0]);
            } finally {
                newer.stop();
            }

            assertThat(CrossProtocolEventBus.getInstance().getScenarioManager(), sameInstance(running.getRequestMatchers().getScenarioManager()));
        } finally {
            running.stop();
        }
    }

    private static WeakReference<?>[] abandonedWithoutStop() {
        HttpState abandoned = newHttpState();
        abandoned.getMockServerLog().stop();
        return new WeakReference<?>[]{new WeakReference<>(abandoned), new WeakReference<>(abandoned.getRequestMatchers().getScenarioManager())};
    }

    private static HttpState newHttpState() {
        return new HttpState(configuration(), new MockServerLogger(), null);
    }

    private static void assertCollected(String what, WeakReference<?> reference) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COLLECTED_WITHIN_SECONDS);
        while (reference.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(100);
        }
        assertThat(what + " that nothing refers to is still reachable after " + COLLECTED_WITHIN_SECONDS + "s of garbage collection",
            reference.get(), is(nullValue()));
    }
}
