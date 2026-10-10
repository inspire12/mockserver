package org.mockserver.async.controlplane;

import org.junit.After;
import org.junit.Test;
import org.mockserver.async.subscribe.MessageSubscriber;
import org.mockserver.async.subscribe.RecordedMessage;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.fail;

/**
 * Closing brokers can log (the orchestrator does, and so can the broker client libraries), and the
 * console handler writes synchronously. These tests stand in for a broker whose close() logs to a
 * console that does not drain, and check that reset() does not hold the control plane's monitor
 * while it waits, and that it closes only the brokers it took out.
 */
public class AsyncApiControlPlaneResetConsoleTest {

    private static final long CONSOLE_RELEASE_DEADLINE_SECONDS = 120;

    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, AsyncApiControlPlaneResetConsoleTest.class.getSimpleName());
        thread.setDaemon(true);
        return thread;
    });
    private final BlockedConsole console = new BlockedConsole();
    // held strongly: java.util.logging keeps loggers weakly
    private final Logger rootLogger = Logger.getLogger("");
    private static final Logger BROKER_CLIENT_LOGGER = Logger.getLogger("broker.client");
    private final AsyncApiControlPlaneImpl controlPlane = new AsyncApiControlPlaneImpl();

    @After
    public void releaseConsole() {
        console.release();
        rootLogger.removeHandler(console);
        executor.shutdownNow();
    }

    @Test
    public void resetDoesNotHoldTheControlPlaneWhileABrokerCloseWaitsForTheConsole() throws Exception {
        // given - a loaded broker whose close() logs, and a console that does not drain
        ClosingSubscriber loaded = new ClosingSubscriber(true);
        controlPlane.addSubscriberForTesting(loaded);
        rootLogger.addHandler(console);

        // and - a reset stuck writing that log line
        CompletableFuture<Void> reset = CompletableFuture.runAsync(controlPlane::reset, executor);
        try {
            assertThat("the broker's close() reached the console", console.awaitFirstBlockedWrite(10), is(true));

            // when - another caller needs the control plane
            CompletableFuture<?> status = CompletableFuture.supplyAsync(controlPlane::status, executor);

            // then - it is not kept waiting behind the console
            try {
                status.get(5, SECONDS);
            } catch (TimeoutException e) {
                fail("status() waited for the control plane's monitor while reset() was closing a broker");
            }
        } finally {
            console.release();
            reset.get(60, SECONDS);
        }
        assertThat(loaded.closed.get(), is(1));
    }

    @Test
    public void resetClosesOnlyTheBrokersItTookOutOfTheControlPlane() throws Exception {
        // given - a reset stuck closing the broker loaded before it
        ClosingSubscriber loadedBefore = new ClosingSubscriber(true);
        controlPlane.addSubscriberForTesting(loadedBefore);
        rootLogger.addHandler(console);
        CompletableFuture<Void> reset = CompletableFuture.runAsync(controlPlane::reset, executor);
        ClosingSubscriber loadedAfter = new ClosingSubscriber(false);
        try {
            assertThat("the broker's close() reached the console", console.awaitFirstBlockedWrite(10), is(true));

            // when - a broker is loaded while that reset is still closing
            controlPlane.addSubscriberForTesting(loadedAfter);
        } finally {
            console.release();
            reset.get(60, SECONDS);
        }

        // then - the reset closed the old broker and left the new one alone, still in the control plane
        assertThat(loadedBefore.closed.get(), is(1));
        assertThat(loadedAfter.closed.get(), is(0));
        controlPlane.reset();
        assertThat("the next reset found the new broker and closed it", loadedAfter.closed.get(), is(1));
    }

    private static final class ClosingSubscriber implements MessageSubscriber {

        private final boolean logsOnClose;
        private final AtomicInteger closed = new AtomicInteger();

        private ClosingSubscriber(boolean logsOnClose) {
            this.logsOnClose = logsOnClose;
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
            closed.incrementAndGet();
            if (logsOnClose) {
                BROKER_CLIENT_LOGGER.log(Level.INFO, "closing broker client");
            }
        }
    }

    private static final class BlockedConsole extends Handler {

        private final CountDownLatch released = new CountDownLatch(1);
        private final CountDownLatch firstBlockedWrite = new CountDownLatch(1);

        @Override
        public void publish(LogRecord record) {
            if (released.getCount() == 0 || !BROKER_CLIENT_LOGGER.getName().equals(record.getLoggerName())) {
                return;
            }
            firstBlockedWrite.countDown();
            try {
                released.await(CONSOLE_RELEASE_DEADLINE_SECONDS, SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        boolean awaitFirstBlockedWrite(long seconds) throws InterruptedException {
            return firstBlockedWrite.await(seconds, SECONDS);
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
