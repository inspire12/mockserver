package org.mockserver.lifecycle;

import org.junit.After;
import org.junit.Test;
import org.mockserver.netty.MockServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * MockServer's console handler writes synchronously, so a stdout that nobody drains blocks every
 * thread that logs. This test stands in for that console with a root handler that does not return
 * (with MockServer's own loggers at every java.util.logging level), and checks that stopping a server
 * with a client still connected does not wait for it. If it fails, the message names the blocked threads:
 * log through the server's event log instead, or not at all.
 */
public class StopWithBlockedConsoleIntegrationTest {

    // stop() itself gives up after 30 s; the bound must sit well below that to tell a stop that
    // finished from one that timed out, and well above the 2 s the event log may wait for its writer
    private static final long STOP_BOUND_SECONDS = 15;
    private static final long CONSOLE_RELEASE_DEADLINE_SECONDS = 120;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, StopWithBlockedConsoleIntegrationTest.class.getSimpleName());
        thread.setDaemon(true);
        return thread;
    });
    private final BlockedConsole console = new BlockedConsole();
    // held strongly: java.util.logging keeps loggers weakly, and a collected logger loses its level
    private final Logger mockServerLogger = Logger.getLogger("org.mockserver");
    private final Logger rootLogger = Logger.getLogger("");
    private Level originalLevel;
    private boolean consoleBlocked;

    @After
    public void releaseConsole() {
        console.release();
        if (consoleBlocked) {
            rootLogger.removeHandler(console);
            mockServerLogger.setLevel(originalLevel);
        }
        executor.shutdownNow();
    }

    @Test
    public void stopCompletesWhileTheConsoleIsBlocked() throws Exception {
        // given - a running server, at the default INFO level, with a client still connected
        MockServer mockServer = new MockServer(configuration().logLevel(org.slf4j.event.Level.INFO), 0);
        CompletableFuture<Void> stopped = null;
        try (Socket client = new Socket(Proxy.NO_PROXY)) {
            client.setSoTimeout(10_000);
            client.connect(new InetSocketAddress("127.0.0.1", mockServer.getLocalPort()), 10_000);
            OutputStream out = client.getOutputStream();
            out.write(("GET /anything HTTP/1.1\r\nHost: 127.0.0.1:" + mockServer.getLocalPort() + "\r\n\r\n").getBytes(US_ASCII));
            out.flush();
            assertThat(new BufferedReader(new InputStreamReader(client.getInputStream(), US_ASCII)).readLine(),
                startsWith("HTTP/1.1 404"));

            // and - from now on, every line that reaches the console blocks the thread writing it,
            // MockServer's at every level
            originalLevel = mockServerLogger.getLevel();
            consoleBlocked = true;
            mockServerLogger.setLevel(Level.ALL);
            rootLogger.addHandler(console);

            // when
            stopped = CompletableFuture.runAsync(mockServer::stop, executor);

            // then
            try {
                stopped.get(STOP_BOUND_SECONDS, SECONDS);
            } catch (TimeoutException e) {
                fail("stop() did not return within " + STOP_BOUND_SECONDS + "s while the console was blocked;"
                    + " threads blocked on the console:\n" + console.blockedThreads());
            }
            assertThat("stop() returned because the server stopped, not because its wait timed out",
                mockServer.stopAsync().isDone(), is(true));
            assertThat(mockServer.isRunning(), is(false));
            assertThat("a thread was blocked on the console during stop(), so the console really was in the way",
                console.blockedWrites(), greaterThan(0));
        } finally {
            console.release();
            if (stopped != null) {
                stopped.get(60, SECONDS);
            } else {
                mockServer.stop();
            }
        }
    }

    private static final class BlockedConsole extends Handler {

        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicInteger blockedWrites = new AtomicInteger();
        private final Map<Thread, String> blocked = new ConcurrentHashMap<>();

        @Override
        public void publish(LogRecord record) {
            if (released.getCount() == 0) {
                return;
            }
            blockedWrites.incrementAndGet();
            blocked.put(Thread.currentThread(), record.getLoggerName() + " " + record.getLevel());
            try {
                released.await(CONSOLE_RELEASE_DEADLINE_SECONDS, SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                blocked.remove(Thread.currentThread());
            }
        }

        int blockedWrites() {
            return blockedWrites.get();
        }

        String blockedThreads() {
            return blocked.entrySet().stream()
                .map(entry -> entry.getKey().getName() + " writing " + entry.getValue() + "\n    at "
                    + Arrays.stream(entry.getKey().getStackTrace()).limit(25).map(String::valueOf)
                    .collect(Collectors.joining("\n    at ")))
                .collect(Collectors.joining("\n"));
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
