package org.mockserver.async.controlplane;

import org.junit.After;
import org.junit.Test;
import org.mockserver.async.subscribe.MessageSubscriber;
import org.mockserver.async.subscribe.RecordedMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.fail;

/**
 * reset() closes the brokers it takes out on a daemon thread and waits for them only up to a bound, so a broker
 * that is slow to close cannot hold server stop(); a later load, on this control plane or on the one a restarted
 * server registers, waits for those closes before it connects brokers that may reuse their client ids.
 */
public class AsyncApiControlPlaneBoundedCloseTest {

    private static final Duration SHORT_CLOSE_WAIT = Duration.ofMillis(500);
    private static final long BROKER_RELEASE_DEADLINE_SECONDS = 120;

    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, AsyncApiControlPlaneBoundedCloseTest.class.getSimpleName());
        thread.setDaemon(true);
        return thread;
    });
    private final List<BlockingSubscriber> brokers = new ArrayList<>();

    @After
    public void releaseBrokers() throws InterruptedException {
        brokers.forEach(BlockingSubscriber::release);
        executor.shutdownNow();
        assertCloserThreadsEnd();
    }

    @Test
    public void resetReturnsWithinItsBoundWhileABrokerIsStillClosing() throws Exception {
        // given - a loaded broker whose close() does not return
        AsyncApiControlPlaneImpl controlPlane = controlPlane(SHORT_CLOSE_WAIT, Duration.ofSeconds(30));
        BlockingSubscriber broker = blockingBroker();
        controlPlane.addSubscriberForTesting(broker);

        // when
        CompletableFuture<Void> reset = CompletableFuture.runAsync(controlPlane::reset, executor);

        // then - reset() gives up waiting and returns, leaving the close running on a daemon thread
        try {
            reset.get(10, SECONDS);
        } catch (TimeoutException e) {
            fail("reset() waited for a broker close past its " + SHORT_CLOSE_WAIT.toMillis() + "ms bound");
        }
        assertThat("the broker's close() started", broker.closeStarted.await(10, SECONDS), is(true));
        assertThat("the close is still running", broker.closeFinished.get(), is(0));
        assertThat(AsyncApiControlPlaneImpl.closesInProgress(), is(1));
        List<Thread> closers = closerThreads();
        assertThat("one closer thread", closers.size(), is(1));
        assertThat("the closer thread cannot keep the JVM alive", closers.get(0).isDaemon(), is(true));

        // and when the broker finishes closing, the closer thread ends and nothing is left in progress
        broker.release();
        assertCloserThreadsEnd();
        assertThat(broker.closeFinished.get(), is(1));
        assertThat(AsyncApiControlPlaneImpl.closesInProgress(), is(0));
    }

    @Test
    public void resetWaitsForBrokersThatCloseWithinItsBound() throws Exception {
        // given - a broker that takes a moment to close
        AsyncApiControlPlaneImpl controlPlane = controlPlane(Duration.ofSeconds(30), Duration.ofSeconds(30));
        BlockingSubscriber broker = blockingBroker();
        controlPlane.addSubscriberForTesting(broker);
        executor.execute(() -> {
            try {
                broker.closeStarted.await(10, SECONDS);
                MILLISECONDS.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            broker.release();
        });

        // when
        controlPlane.reset();

        // then - reset() returned only once the broker had closed
        assertThat(broker.closeFinished.get(), is(1));
        assertThat(AsyncApiControlPlaneImpl.closesInProgress(), is(0));
    }

    @Test
    public void aLoadOnARestartedServerWaitsForBrokersTheStoppedServerLeftClosing() throws Exception {
        // given - a stop that returned while its broker was still closing
        AsyncApiControlPlaneImpl stopped = controlPlane(SHORT_CLOSE_WAIT, Duration.ofSeconds(30));
        BlockingSubscriber broker = blockingBroker();
        stopped.addSubscriberForTesting(broker);
        stopped.reset();
        assertThat("the close is still running", broker.closeFinished.get(), is(0));

        // when - the control plane of a restarted server loads a spec
        AsyncApiControlPlaneImpl restarted = controlPlane(SHORT_CLOSE_WAIT, Duration.ofSeconds(60));
        CompletableFuture<?> load = CompletableFuture.supplyAsync(() -> restarted.load("{\"asyncapi\": \"2.6.0\"}"), executor);

        // then - it does not connect while the earlier broker is closing
        try {
            load.get(1, SECONDS);
            fail("load() connected while a broker from the stopped server was still closing");
        } catch (TimeoutException expected) {
            // still waiting
        }
        broker.release();
        load.get(30, SECONDS);
        assertThat(broker.closeFinished.get(), is(1));
    }

    @Test
    public void aLoadConnectsAnywayOnceEarlierClosesOutlastItsWait() throws Exception {
        // given - a reset that returned while its broker was still closing, and stays closing
        AsyncApiControlPlaneImpl controlPlane = controlPlane(SHORT_CLOSE_WAIT, Duration.ofSeconds(1));
        BlockingSubscriber broker = blockingBroker();
        controlPlane.addSubscriberForTesting(broker);
        controlPlane.reset();

        // when
        CompletableFuture<?> load = CompletableFuture.supplyAsync(() -> controlPlane.load("{\"asyncapi\": \"2.6.0\"}"), executor);

        // then - it waits for the earlier close only up to its bound, then loads; the close goes on in the background
        try {
            load.get(30, SECONDS);
        } catch (TimeoutException e) {
            fail("load() waited for a stuck broker close past its 1s bound");
        }
        assertThat(controlPlane.status().get("loaded").asBoolean(), is(true));
        assertThat("the earlier broker is still closing", broker.closeFinished.get(), is(0));
    }

    @Test
    public void aResetWhileALoadWaitsForEarlierClosesReturnsWithinItsBoundAndCancelsTheLoad() throws Exception {
        // given - a load waiting for a broker an earlier reset left closing
        AsyncApiControlPlaneImpl controlPlane = controlPlane(SHORT_CLOSE_WAIT, Duration.ofSeconds(60));
        BlockingSubscriber broker = blockingBroker();
        controlPlane.addSubscriberForTesting(broker);
        controlPlane.reset();
        CompletableFuture<?> load = CompletableFuture.supplyAsync(() -> controlPlane.load("{\"asyncapi\": \"2.6.0\"}"), executor);
        assertStillWaiting(load);

        // when - the server is stopped, or reset, and asked for its status meanwhile
        CompletableFuture<?> status = CompletableFuture.supplyAsync(controlPlane::status, executor);
        CompletableFuture<Void> reset = CompletableFuture.runAsync(controlPlane::reset, executor);

        // then - neither waits for the load
        try {
            status.get(5, SECONDS);
            reset.get(5, SECONDS);
        } catch (TimeoutException e) {
            fail("status() or reset() waited for a load that was waiting for earlier broker connections to close");
        }

        // and - once the earlier broker has closed, the load gives up rather than connect after the reset
        broker.release();
        try {
            load.get(30, SECONDS);
            fail("load() went on to connect after a reset that happened while it waited");
        } catch (ExecutionException e) {
            assertThat(e.getCause().getMessage(), containsString("a reset or stop happened"));
        }
        assertThat(controlPlane.status().get("loaded").asBoolean(), is(false));
    }

    @Test
    public void aLoadClosesTheBrokersItReplacesWithoutHoldingTheControlPlane() throws Exception {
        // given - a loaded broker that is slow to close
        AsyncApiControlPlaneImpl controlPlane = controlPlane(SHORT_CLOSE_WAIT, Duration.ofSeconds(60));
        BlockingSubscriber replaced = blockingBroker();
        controlPlane.addSubscriberForTesting(replaced);

        // when - a new spec replaces it
        CompletableFuture<?> load = CompletableFuture.supplyAsync(() -> controlPlane.load("{\"asyncapi\": \"2.6.0\"}"), executor);
        assertThat("the replaced broker's close() started", replaced.closeStarted.await(10, SECONDS), is(true));

        // then - the control plane answers while that broker closes, and the load waits for the close
        try {
            CompletableFuture.supplyAsync(controlPlane::status, executor).get(5, SECONDS);
        } catch (TimeoutException e) {
            fail("status() waited while load() closed the broker it replaced");
        }
        assertStillWaiting(load);
        replaced.release();
        load.get(30, SECONDS);
        assertThat(replaced.closeFinished.get(), is(1));
        assertThat(controlPlane.status().get("loaded").asBoolean(), is(true));
    }

    private static void assertStillWaiting(CompletableFuture<?> load) throws Exception {
        try {
            load.get(1, SECONDS);
            fail("load() connected while an earlier broker was still closing");
        } catch (TimeoutException expected) {
            // still waiting
        }
    }

    private static AsyncApiControlPlaneImpl controlPlane(Duration closeWait, Duration earlierCloseWait) {
        return new AsyncApiControlPlaneImpl(null, closeWait, earlierCloseWait);
    }

    private BlockingSubscriber blockingBroker() {
        BlockingSubscriber broker = new BlockingSubscriber();
        brokers.add(broker);
        return broker;
    }

    private static List<Thread> closerThreads() {
        List<Thread> closers = new ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (AsyncApiControlPlaneImpl.CLOSER_THREAD_NAME.equals(thread.getName())) {
                closers.add(thread);
            }
        }
        return closers;
    }

    private static void assertCloserThreadsEnd() throws InterruptedException {
        for (Thread closer : closerThreads()) {
            closer.join(SECONDS.toMillis(30));
        }
        assertThat("every closer thread ended", closerThreads(), is(empty()));
    }

    private static final class BlockingSubscriber implements MessageSubscriber {

        private final CountDownLatch released = new CountDownLatch(1);
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final AtomicInteger closeFinished = new AtomicInteger();

        void release() {
            released.countDown();
        }

        @Override
        public void subscribe(String channel) {
        }

        @Override
        public void unsubscribe(String channel) {
        }

        @Override
        public List<RecordedMessage> getRecordedMessages(String channel) {
            return Collections.emptyList();
        }

        @Override
        public List<RecordedMessage> getAllRecordedMessages() {
            return Collections.emptyList();
        }

        @Override
        public void close() {
            closeStarted.countDown();
            try {
                released.await(BROKER_RELEASE_DEADLINE_SECONDS, SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            closeFinished.incrementAndGet();
        }
    }
}
