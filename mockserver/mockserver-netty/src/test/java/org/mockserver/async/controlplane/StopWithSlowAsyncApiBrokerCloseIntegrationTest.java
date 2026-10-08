package org.mockserver.async.controlplane;

import org.junit.After;
import org.junit.Test;
import org.mockserver.async.AsyncApiControlPlaneRegistry;
import org.mockserver.async.subscribe.MessageSubscriber;
import org.mockserver.async.subscribe.RecordedMessage;
import org.mockserver.netty.MockServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Server stop() closes the AsyncAPI brokers, and a broker can be slow to close: its client library can block,
 * and while stdout is blocked every close that logs waits for the console. stop() waits for those closes only
 * for a bounded time, says once that they are still running, and leaves them to finish on a daemon thread.
 */
public class StopWithSlowAsyncApiBrokerCloseIntegrationTest {

    // reset() waits 5 s for the closes and stop() gives up after 30 s; the bound tells the two apart
    private static final long STOP_BOUND_SECONDS = 15;
    private static final long RELEASE_DEADLINE_SECONDS = 120;
    private static final Logger BROKER_CLIENT_LOGGER = Logger.getLogger("broker.client");

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, StopWithSlowAsyncApiBrokerCloseIntegrationTest.class.getSimpleName());
        thread.setDaemon(true);
        return thread;
    });
    private final BlockedConsole console = new BlockedConsole();
    // held strongly: java.util.logging keeps loggers weakly, and a collected logger loses its level
    private final Logger mockServerLogger = Logger.getLogger("org.mockserver");
    private final Logger rootLogger = Logger.getLogger("");
    private final SlowBroker broker = new SlowBroker();
    private Level originalLevel;
    private boolean consoleBlocked;

    @After
    public void release() throws InterruptedException {
        console.release();
        broker.release();
        if (consoleBlocked) {
            rootLogger.removeHandler(console);
            mockServerLogger.setLevel(originalLevel);
        }
        executor.shutdownNow();
        for (Thread closer : closerThreads()) {
            closer.join(SECONDS.toMillis(30));
        }
    }

    @Test
    public void stopReturnsWhileABrokerIsStillClosingAndLeavesItClosingInTheBackground() throws Exception {
        // given - a running server whose AsyncAPI control plane holds a broker that is slow to close
        MockServer mockServer = new MockServer(configuration().logLevel(org.slf4j.event.Level.INFO), 0);
        AsyncApiControlPlaneImpl controlPlane = new AsyncApiControlPlaneImpl(null);
        controlPlane.addSubscriberForTesting(broker);
        AsyncApiControlPlaneRegistry.getInstance().register(controlPlane);
        CompletableFuture<Void> stopped = null;
        try {
            // and - from now on every line that reaches the console blocks the thread writing it,
            // MockServer's at every level
            originalLevel = mockServerLogger.getLevel();
            consoleBlocked = true;
            mockServerLogger.setLevel(Level.ALL);
            rootLogger.addHandler(console);

            // when
            stopped = CompletableFuture.runAsync(mockServer::stop, executor);

            // then - stop() finishes with the broker still closing
            try {
                stopped.get(STOP_BOUND_SECONDS, SECONDS);
            } catch (TimeoutException e) {
                fail("stop() did not return within " + STOP_BOUND_SECONDS + "s while an AsyncAPI broker was closing");
            }
            assertThat("stop() returned because the server stopped, not because its wait timed out",
                mockServer.stopAsync().isDone(), is(true));
            assertThat(mockServer.isRunning(), is(false));
            assertThat("the broker's close() started", broker.closeStarted.await(10, SECONDS), is(true));
            assertThat("the broker is still closing", broker.closeFinished.get(), is(0));

            // and - it says so once, from a thread the console holds instead of stop()
            assertThat("the notice reached the console", console.awaitWriteFrom(AsyncApiControlPlaneImpl.class.getName(), 10), is(true));
            assertThat(console.writesFrom(AsyncApiControlPlaneImpl.class.getName()), is(1));

            // and when the console drains and the broker closes, the closer thread ends
            console.release();
            broker.release();
            for (Thread closer : closerThreads()) {
                closer.join(SECONDS.toMillis(30));
            }
            assertThat("the closer thread ended", closerThreads(), is(empty()));
            assertThat(broker.closeFinished.get(), is(1));
            assertThat(console.loggers(), hasItem(BROKER_CLIENT_LOGGER.getName()));
        } finally {
            console.release();
            broker.release();
            if (stopped != null) {
                stopped.get(60, SECONDS);
            } else {
                mockServer.stop();
            }
        }
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

    private static final class SlowBroker implements MessageSubscriber {

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
            BROKER_CLIENT_LOGGER.log(Level.INFO, "closing broker client");
            try {
                released.await(RELEASE_DEADLINE_SECONDS, SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            closeFinished.incrementAndGet();
        }
    }

    private static final class BlockedConsole extends Handler {

        private final CountDownLatch released = new CountDownLatch(1);
        private final Queue<String> loggers = new ConcurrentLinkedQueue<>();

        @Override
        public void publish(LogRecord record) {
            loggers.add(record.getLoggerName());
            if (released.getCount() == 0) {
                return;
            }
            try {
                released.await(RELEASE_DEADLINE_SECONDS, SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        boolean awaitWriteFrom(String logger, long seconds) throws InterruptedException {
            long deadline = System.nanoTime() + SECONDS.toNanos(seconds);
            while (writesFrom(logger) == 0) {
                if (System.nanoTime() > deadline) {
                    return false;
                }
                Thread.sleep(20);
            }
            return true;
        }

        int writesFrom(String logger) {
            return (int) loggers.stream().filter(logger::equals).count();
        }

        List<String> loggers() {
            return new ArrayList<>(loggers);
        }

        void release() {
            released.countDown();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
