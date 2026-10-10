package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * An {@link HttpState} whose constructor throws gives its caller nothing to stop, so the constructor must end the
 * event-log thread it started and undo the process-wide registrations it made before it rethrows.
 * <p>
 * Runs in the sequential phase: it uses the bus singleton and the static metrics readers every HttpState replaces.
 */
public class HttpStateFailedConstructionTest {

    private static final long DEADLINE_SECONDS = 30;

    @Test
    public void shouldEndItsEventLogThreadAndUndoItsRegistrationsWhenTheConstructorThrowsAtItsLastStep() throws Exception {
        HttpState running = new HttpState(configuration(), new MockServerLogger(), null);
        try {
            IllegalStateException failure = new IllegalStateException("gRPC configuration failed on purpose");
            Configuration failingLast = new Configuration() {
                @Override
                public Boolean grpcEnabled() {
                    throw failure;
                }
            };
            AtomicReference<WeakReference<HttpState>> constructed = new AtomicReference<>();
            ThreadGroup group = new ThreadGroup("failed-http-state-last-step");

            Throwable thrown = constructInGroup(group, failingLast, constructed);

            assertThat(thrown, sameInstance(failure));
            assertThat(thrown.getSuppressed(), emptyArray());
            assertThat("event-log threads of the HttpState whose constructor threw", eventLogThreadsStillAlive(group), is(empty()));
            assertThat(CrossProtocolEventBus.getInstance().getScenarioManager(), sameInstance(running.getRequestMatchers().getScenarioManager()));
            assertThat(constructed.get(), notNullValue());
            assertCollected(constructed.get());
        } finally {
            running.stop();
        }
    }

    @Test
    public void shouldEndItsEventLogThreadWhenTheConstructorThrowsBeforeTheStateExists() throws Exception {
        IllegalStateException failure = new IllegalStateException("state backend configuration failed on purpose");
        Configuration failingFirst = new Configuration() {
            @Override
            public String stateBackend() {
                throw failure;
            }
        };
        ThreadGroup group = new ThreadGroup("failed-http-state-first-step");

        Throwable thrown = constructInGroup(group, failingFirst, new AtomicReference<>());

        assertThat(thrown, sameInstance(failure));
        assertThat("releasing what was started must cope with what was never created", thrown.getSuppressed(), emptyArray());
        assertThat("event-log threads of the HttpState whose constructor threw", eventLogThreadsStillAlive(group), is(empty()));
    }

    /**
     * Constructs on a thread of {@code group}, so the threads the constructor starts join it.
     */
    private static Throwable constructInGroup(ThreadGroup group, Configuration configuration, AtomicReference<WeakReference<HttpState>> constructed) throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = new Thread(group, () -> {
            MockServerLogger logger = new MockServerLogger() {
                @Override
                public MockServerLogger setHttpStateHandler(HttpState httpStateHandler) {
                    constructed.set(new WeakReference<>(httpStateHandler));
                    return super.setHttpStateHandler(httpStateHandler);
                }
            };
            try {
                new HttpState(configuration, logger, null);
            } catch (Throwable throwable) {
                thrown.set(throwable);
            }
        }, group.getName());
        thread.start();
        thread.join(TimeUnit.SECONDS.toMillis(DEADLINE_SECONDS));
        assertThat("constructor finished within " + DEADLINE_SECONDS + "s", thread.isAlive(), is(false));
        assertThat("the constructor threw", thrown.get(), notNullValue());
        return thrown.get();
    }

    private static List<String> eventLogThreadsStillAlive(ThreadGroup group) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        List<String> alive = eventLogThreads(group);
        while (!alive.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
            alive = eventLogThreads(group);
        }
        return alive;
    }

    private static List<String> eventLogThreads(ThreadGroup group) {
        Thread[] threads = new Thread[group.activeCount() + 16];
        int count = group.enumerate(threads, true);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (threads[i].isAlive() && threads[i].getName().startsWith("MockServer-EventLog")) {
                names.add(threads[i].getName());
            }
        }
        return names;
    }

    private static void assertCollected(WeakReference<HttpState> reference) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        while (reference.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(100);
        }
        assertThat("an HttpState whose constructor threw is still reachable after " + DEADLINE_SECONDS + "s of garbage collection",
            reference.get(), is(nullValue()));
    }
}
