package org.mockserver.netty;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.RefusedStart.RecordingMockServer;
import org.mockserver.lifecycle.RefusedStart.Started;
import org.mockserver.netty.http3.Http3NativeUnavailableException;

import java.net.BindException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.lifecycle.RefusedStart.assertStopComplete;
import static org.mockserver.lifecycle.RefusedStart.assertStopped;
import static org.mockserver.lifecycle.RefusedStart.refusedStart;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;

/**
 * {@code stop()} does not wait on an interrupted thread, and a JUnit timeout interrupts the thread that is starting a
 * server: a start refused on such a thread must still have stopped when it throws, and leave the thread interrupted.
 * The DNS and HTTP/3 refusals are covered with the rest of their start failures.
 */
public class RefusedStartOnAnInterruptedThreadTest {

    @Test
    public void shouldStopAndKeepTheInterruptWhenTheTcpBindFailsOnAnInterruptedThread() throws Exception {
        Started started = new Started();
        started.interruptOnceTcpIsBound = true;
        started.failOnceTcpIsBound = new RuntimeException("Exception while binding MockServer to port 0", new BindException("Address already in use"));
        AtomicBoolean interruptedAtTheThrow = new AtomicBoolean();

        Throwable refused = refusedStart(() -> new RecordingMockServer(started, configuration()), () -> {
            interruptedAtTheThrow.set(Thread.interrupted());
            assertStopped(started);
        });

        assertThat(refused, is(sameInstance(started.failOnceTcpIsBound)));
        assertThat("the caller must still see the interrupt", interruptedAtTheThrow.get(), is(true));
    }

    // the bind's own wait throws the InterruptedException, which clears the flag where it is thrown
    @Test
    public void shouldStopAndRestoreTheInterruptWhenTheTcpBindIsInterrupted() throws Exception {
        Started started = new Started();
        started.interruptBeforeTcpIsBound = true;
        AtomicBoolean interruptedAtTheThrow = new AtomicBoolean();

        Throwable refused = refusedStart(() -> new RecordingMockServer(started, configuration()), () -> {
            interruptedAtTheThrow.set(Thread.interrupted());
            assertStopComplete(started);
        });

        assertThat(refused.getMessage(), startsWith("Exception while binding MockServer to port 0"));
        assertThat(refused.getCause(), instanceOf(InterruptedException.class));
        assertThat("the caller must see the interrupt the bind consumed", interruptedAtTheThrow.get(), is(true));
    }

    // a cause another thread failed with, such as a future's, says nothing about this thread
    @Test
    public void shouldNotInterruptTheCallerForAnInterruptedExceptionFromAnotherThread() throws Exception {
        Started started = new Started();
        started.failOnceTcpIsBound = new RuntimeException("Exception while binding MockServer to port 0", new InterruptedException("on another thread"));
        AtomicBoolean interruptedAtTheThrow = new AtomicBoolean();

        Throwable refused = refusedStart(() -> new RecordingMockServer(started, configuration()), () -> {
            interruptedAtTheThrow.set(Thread.interrupted());
            assertStopped(started);
        });

        assertThat(refused, is(sameInstance(started.failOnceTcpIsBound)));
        assertThat("the starting thread was never interrupted", interruptedAtTheThrow.get(), is(false));
    }

    @Test
    public void shouldStopAndKeepTheInterruptWhenTheQuicNativeIsMissingOnAnInterruptedThread() throws Exception {
        Started started = new Started();
        AtomicBoolean interruptedAtTheThrow = new AtomicBoolean();

        Throwable refused = refusedStart(() -> startWithHttp3(udpPort -> new QuicNativeMissing(started, configuration().http3Port(udpPort))), () -> {
            interruptedAtTheThrow.set(Thread.interrupted());
            assertStopComplete(started);
        });

        assertThat(refused, instanceOf(Http3NativeUnavailableException.class));
        assertThat("refused before any port was bound", started.tcpPorts, is(empty()));
        assertThat("the caller must still see the interrupt", interruptedAtTheThrow.get(), is(true));
    }

    // the QUIC native cannot be unloaded from a JVM that has it
    private static final class QuicNativeMissing extends RecordingMockServer {

        private QuicNativeMissing(Started started, Configuration configuration) {
            super(started, configuration);
        }

        @Override
        boolean quicNativeAvailable() {
            recording().server = this;
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
