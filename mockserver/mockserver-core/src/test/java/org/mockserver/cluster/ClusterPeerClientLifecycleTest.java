package org.mockserver.cluster;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.verify.Verification;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.Reference;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * The JDK HttpClient that queries cluster peers owns a selector thread and file descriptors, which Java 17
 * can only end by collecting the client. So a server that does no fan-in must never build one, and a server
 * that did must let go of it when it stops, however long the stopped server itself stays referenced.
 *
 * <p>The selector thread joins the thread group of the thread that builds the client, so each test builds
 * in a group of its own and is not misled by clients other tests build in the same JVM.
 */
public class ClusterPeerClientLifecycleTest {

    private static final long DEADLINE_SECONDS = 30;
    private static final Pattern JDK_HTTP_CLIENT_SELECTOR = Pattern.compile("HttpClient-\\d+-SelectorManager");

    private final AtomicInteger peerQueries = new AtomicInteger();
    private final List<ScheduledExecutorService> schedulerExecutors = new CopyOnWriteArrayList<>();
    private HttpServer peer;
    private String peerUrl;

    @Before
    public void startPeer() throws IOException {
        peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        peer.createContext("/mockserver/retrieve", this::noMatchingTraffic);
        peer.start();
        peerUrl = "http://127.0.0.1:" + peer.getAddress().getPort();
    }

    @After
    public void stopPeerAndSchedulers() {
        peer.stop(0);
        schedulerExecutors.forEach(ScheduledExecutorService::shutdownNow);
    }

    private void noMatchingTraffic(HttpExchange exchange) throws IOException {
        peerQueries.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        byte[] payload = "[]".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(payload);
        }
    }

    @Test
    public void shouldStartNoJdkClientThreadForServersThatDoNoFanIn() throws Exception {
        ThreadGroup group = new ThreadGroup("no-fan-in");
        List<HttpState> stopped = new ArrayList<>();

        in(group, () -> {
            for (int i = 0; i < 5; i++) {
                HttpState httpState = httpState(configuration());
                assertThat(httpState.verify(new Verification().withRequest(request("/api")).withTimes(exactly(0))).get(DEADLINE_SECONDS, TimeUnit.SECONDS), is(""));
                httpState.stop();
                stopped.add(httpState);
            }
            return null;
        });

        assertThat(selectorThreads(group), is(empty()));
        assertThat(peerQueries.get(), is(0));
    }

    @Test
    public void shouldStartOneJdkClientOnTheFirstPeerQueryAndReuseIt() throws Exception {
        ThreadGroup group = new ThreadGroup("first-peer-query");

        HttpClusterPeerAccessor accessor = in(group, () -> new HttpClusterPeerAccessor(configuration(), new MockServerLogger()));
        assertThat("no client before the first peer query", selectorThreads(group), is(empty()));

        in(group, () -> accessor.retrieveRequests(peerUrl, request("/api")));
        assertThat(selectorThreads(group), hasSize(1));

        in(group, () -> accessor.retrieveRequestResponses(peerUrl, request("/api")));
        assertThat(selectorThreads(group), hasSize(1));
        assertThat(peerQueries.get(), is(2));

        accessor.close();
    }

    @Test
    public void shouldLetGoOfItsJdkClientOnCloseAndRefuseLaterQueries() throws Exception {
        ThreadGroup group = new ThreadGroup("closed-accessor");
        HttpClusterPeerAccessor accessor = new HttpClusterPeerAccessor(configuration(), new MockServerLogger());
        in(group, () -> accessor.retrieveRequests(peerUrl, request("/api")));
        assertThat(selectorThreads(group), hasSize(1));

        accessor.close();

        assertThat("the accessor is still referenced, its client must not be", selectorThreadsOnceCollected(group), is(empty()));
        AtomicReference<Throwable> refused = new AtomicReference<>();
        in(group, () -> {
            try {
                accessor.retrieveRequests(peerUrl, request("/api"));
            } catch (Throwable throwable) {
                refused.set(throwable);
            }
            return null;
        });
        assertThat(refused.get(), instanceOf(IllegalStateException.class));
        assertThat(refused.get().getMessage(), is("cluster fan-in is closed because MockServer has stopped"));
        assertThat("a query after close must not build another client", selectorThreads(group), is(empty()));
        assertThat(peerQueries.get(), is(1));
    }

    @Test
    public void shouldLetGoOfTheJdkClientItsFanInStartedWhenTheServerStops() throws Exception {
        ThreadGroup group = new ThreadGroup("fan-in");
        Configuration fanIn = configuration().clusterVerifyFanIn(true).clusterVerifyFanInPeers(peerUrl);

        HttpState httpState = in(group, () -> httpState(fanIn));
        assertThat("no client until a verify or retrieve fans in", selectorThreads(group), is(empty()));

        String verified = in(group, () -> httpState.verify(new Verification().withRequest(request("/api")).withTimes(exactly(0))).get(DEADLINE_SECONDS, TimeUnit.SECONDS));
        assertThat(verified, is(""));
        assertThat(peerQueries.get(), is(1));
        assertThat(selectorThreads(group), hasSize(1));

        httpState.stop();

        assertThat("the stopped server is still referenced, its client must not be", selectorThreadsOnceCollected(group), is(empty()));
        Reference.reachabilityFence(httpState);
    }

    @Test
    public void shouldFailAFanInClosedOnceTheServerHasStopped() throws Exception {
        Configuration fanIn = configuration().clusterVerifyFanIn(true).clusterVerifyFanInPeers(peerUrl);
        ClusterFanIn clusterFanIn = new ClusterFanIn(fanIn, new MockServerLogger(), new HttpClusterPeerAccessor(fanIn, new MockServerLogger()));
        assertThat(clusterFanIn.fanInRequests(request("/api")).hasUnreachablePeers(), is(false));

        clusterFanIn.close();

        assertThat(clusterFanIn.fanInRequests(request("/api")).unreachablePeers().toString(), containsString(peerUrl));
        assertThat(clusterFanIn.fanInRequestResponses(request("/api")).unreachablePeers().toString(), containsString(peerUrl));
        assertThat(peerQueries.get(), is(1));
    }

    private HttpState httpState(Configuration configuration) {
        ScheduledExecutorService schedulerExecutor = Executors.newScheduledThreadPool(2);
        schedulerExecutors.add(schedulerExecutor);
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);
        return new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler);
    }

    private static <T> T in(ThreadGroup group, Callable<T> body) throws Exception {
        AtomicReference<T> returned = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = new Thread(group, () -> {
            try {
                returned.set(body.call());
            } catch (Throwable throwable) {
                thrown.set(throwable);
            }
        }, group.getName());
        thread.start();
        thread.join(TimeUnit.SECONDS.toMillis(DEADLINE_SECONDS));
        assertThat("finished within " + DEADLINE_SECONDS + "s", thread.isAlive(), is(false));
        if (thrown.get() != null) {
            throw new AssertionError("in thread group " + group.getName() + ": " + thrown.get(), thrown.get());
        }
        return returned.get();
    }

    private static List<String> selectorThreads(ThreadGroup group) {
        Thread[] threads = new Thread[group.activeCount() + 16];
        int count = group.enumerate(threads, true);
        List<String> alive = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (threads[i].isAlive() && JDK_HTTP_CLIENT_SELECTOR.matcher(threads[i].getName()).matches()) {
                alive.add(threads[i].getName());
            }
        }
        return alive;
    }

    // the JDK ends a client's selector thread only after the client has been garbage collected; other test
    // classes run in this JVM at the same time, so a collection is asked for at most once a second
    private static List<String> selectorThreadsOnceCollected(ThreadGroup group) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        long collectAt = System.nanoTime();
        while (true) {
            if (System.nanoTime() >= collectAt) {
                System.gc();
                collectAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            }
            List<String> alive = selectorThreads(group);
            if (alive.isEmpty() || System.nanoTime() >= deadline) {
                return alive;
            }
            Thread.sleep(100);
        }
    }
}
