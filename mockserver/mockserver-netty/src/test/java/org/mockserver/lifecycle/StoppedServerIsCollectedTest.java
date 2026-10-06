package org.mockserver.lifecycle;

import org.junit.Test;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.CrossProtocolEventBus;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.netty.MockServer;

import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A server registers itself in process-wide places as it starts and serves: a stopped server that nothing
 * else refers to must not be kept in memory by them, and its stop must not remove what a server still
 * running in the same JVM has registered there.
 */
public class StoppedServerIsCollectedTest {

    private static final long COLLECTED_WITHIN_SECONDS = 30;

    @Test
    public void shouldLetAStoppedServerThatServedARequestBeCollected() throws Exception {
        assertCollected(startServeAndStop(() -> {
        }));
    }

    @Test
    public void shouldLetAStoppedServerBeCollectedAndLeaveARunningServersRegistrations() throws Exception {
        MockServer[] newer = new MockServer[1];
        try {
            // the newer server registers its state on start; the older one then serves, so the request sender
            // registered last is the older server's
            WeakReference<HttpState> older = startServeAndStop(() -> {
                newer[0] = new MockServer(0);
                newer[0].httpState.getRequestMatchers().add(new Expectation(request("/newer")).thenRespond(response()), MockServerMatcherNotifier.Cause.API);
            });

            assertCollected(older);
            assertThat(Metrics.getActiveExpectationCountByType(), is(Collections.singletonMap("RESPONSE", 1)));
            assertThat(CrossProtocolEventBus.getInstance().getScenarioManager(), sameInstance(newer[0].httpState.getRequestMatchers().getScenarioManager()));
        } finally {
            if (newer[0] != null) {
                newer[0].stop();
            }
        }
        assertThat(Metrics.getActiveExpectationCountByType(), is(Collections.emptyMap()));
    }

    @Test
    public void shouldGiveARunningServerBackItsScenarioStateWhenANewerServerStops() throws Exception {
        MockServer older = new MockServer(0);
        try {
            assertCollected(startServeAndStop(() -> {
            }));

            // captures and scenario templates of the older server read and write this
            assertThat(CrossProtocolEventBus.getInstance().getScenarioManager(), sameInstance(older.httpState.getRequestMatchers().getScenarioManager()));
        } finally {
            older.stop();
        }
        assertThat(CrossProtocolEventBus.getInstance().getScenarioManager(), not(sameInstance(older.httpState.getRequestMatchers().getScenarioManager())));
    }

    private static WeakReference<HttpState> startServeAndStop(Runnable onceStarted) throws Exception {
        MockServer mockServer = new MockServer(0);
        try {
            onceStarted.run();
            assertThat(statusOfARequestTo(mockServer.getLocalPort()), is(404));
        } finally {
            mockServer.stop();
        }
        return new WeakReference<>(mockServer.httpState);
    }

    private static int statusOfARequestTo(int port) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/not-mocked").openConnection();
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static void assertCollected(WeakReference<HttpState> stopped) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COLLECTED_WITHIN_SECONDS);
        while (stopped.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(100);
        }
        assertThat("a stopped server that nothing refers to is still reachable after " + COLLECTED_WITHIN_SECONDS + "s of garbage collection",
            stopped.get(), is(nullValue()));
    }
}
