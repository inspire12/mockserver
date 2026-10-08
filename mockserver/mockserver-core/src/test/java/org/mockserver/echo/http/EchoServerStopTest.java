package org.mockserver.echo.http;

import org.junit.Test;
import org.mockserver.log.MockServerEventLog;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * An echo server's event log runs a thread of its own, which keeps the log and every entry in it until the log is
 * stopped, so stopping the echo server must stop its log too.
 */
public class EchoServerStopTest {

    @Test
    public void shouldLetAStoppedEchoServersEventLogBeCollected() throws InterruptedException {
        WeakReference<MockServerEventLog> eventLog = startAndStop();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (eventLog.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(100);
        }
        assertThat("the event log of a stopped echo server is still reachable after 30s of garbage collection", eventLog.get(), is(nullValue()));
    }

    private static WeakReference<MockServerEventLog> startAndStop() {
        EchoServer echoServer = new EchoServer(false);
        try {
            echoServer.getPort();
        } finally {
            echoServer.stop();
        }
        return new WeakReference<>(echoServer.mockServerEventLog());
    }
}
