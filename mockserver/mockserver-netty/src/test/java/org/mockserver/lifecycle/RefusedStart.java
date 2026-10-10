package org.mockserver.lifecycle;

import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;
import org.mockserver.stop.Stoppable;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;

/**
 * Runs a start that must be refused and checks what it left: the constructor throws, so the caller gets no
 * reference it could stop.
 */
public final class RefusedStart {

    private RefusedStart() {
    }

    @FunctionalInterface
    public interface AtTheThrow {
        void check() throws Exception;
    }

    public static Throwable refusedStart(Supplier<? extends Stoppable> start) throws InterruptedException {
        return refusedStart(start, () -> {
        });
    }

    /**
     * Runs {@code start} in a thread group of its own, so the threads that server started can be told from
     * those of every other test in the JVM, and stops the server if it wrongly started.
     *
     * @param atTheThrow checks made on the starting thread as soon as {@code start} has thrown, which is when
     *                   the caller of a refused start may rely on everything having been stopped
     * @return what {@code start} threw, once no thread it started is left alive
     */
    public static Throwable refusedStart(Supplier<? extends Stoppable> start, AtTheThrow atTheThrow) throws InterruptedException {
        ThreadGroup serverThreads = new ThreadGroup("refused-start");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Throwable> wrongAtTheThrow = new AtomicReference<>();
        Thread starter = new Thread(serverThreads, () -> {
            try {
                start.get().stop();
            } catch (Throwable throwable) {
                thrown.set(throwable);
                try {
                    atTheThrow.check();
                } catch (Throwable wrong) {
                    wrongAtTheThrow.set(wrong);
                }
            }
        }, "refused-start");
        starter.start();
        starter.join(TimeUnit.SECONDS.toMillis(LeftBehind.DEADLINE_SECONDS));

        assertThat("the start neither returned nor threw within " + LeftBehind.DEADLINE_SECONDS + "s", starter.isAlive(), is(false));
        assertThat("the server started, but must have been refused", thrown.get() != null, is(true));
        if (wrongAtTheThrow.get() != null) {
            throw new AssertionError("when the start threw " + thrown.get() + ": " + wrongAtTheThrow.get().getMessage(), wrongAtTheThrow.get());
        }
        assertThat("threads the refused server left running", LeftBehind.threadsStillAlive(serverThreads), is(empty()));
        return thrown.get();
    }

    /**
     * Fails unless the TCP port was bound, its listener is closed, and the stop is complete.
     */
    public static void assertStopped(Started started) {
        assertThat("the TCP port was bound before the start was refused", started.tcpPorts, contains(greaterThan(0)));
        assertStopComplete(started);
        assertThrows("the TCP listener on port " + started.tcpPorts.get(0) + " must be closed", ConnectException.class, () -> connect(started.tcpPorts.get(0)));
    }

    public static void assertStopComplete(Started started) {
        assertThat("the stop must be complete when the constructor throws, not merely begun", started.server.stopAsync().isDone(), is(true));
    }

    private static void connect(int tcpPort) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", tcpPort), 5000);
        }
    }

    /**
     * What a refused server had started: its constructor throws, so this can only be seen from inside it.
     */
    public static final class Started {
        public final List<Integer> tcpPorts = new CopyOnWriteArrayList<>();
        public volatile MockServer server;
        public volatile boolean interruptBeforeTcpIsBound;
        public volatile boolean interruptOnceTcpIsBound;
        public volatile RuntimeException failOnceTcpIsBound;
        public volatile int dnsPort = -1;
    }

    /**
     * Records into {@link Started} once its TCP port is bound, and can then interrupt its thread or fail.
     */
    public static class RecordingMockServer extends MockServer {

        private static final ThreadLocal<Started> RECORD_INTO = new ThreadLocal<>();

        public RecordingMockServer(Started started, Configuration configuration) {
            super(recordInto(started, configuration), 0);
        }

        private static Configuration recordInto(Started started, Configuration configuration) {
            RECORD_INTO.set(started);
            return configuration;
        }

        // called from MockServer's constructor, before this class's fields are set
        protected static Started recording() {
            return RECORD_INTO.get();
        }

        @Override
        public List<Integer> bindServerPorts(List<Integer> requestedPortBindings) {
            Started started = recording();
            started.server = this;
            if (started.interruptBeforeTcpIsBound) {
                Thread.currentThread().interrupt();
            }
            List<Integer> bound = super.bindServerPorts(requestedPortBindings);
            started.tcpPorts.addAll(bound);
            if (started.interruptOnceTcpIsBound) {
                Thread.currentThread().interrupt();
            }
            if (started.failOnceTcpIsBound != null) {
                throw started.failOnceTcpIsBound;
            }
            return bound;
        }
    }
}
