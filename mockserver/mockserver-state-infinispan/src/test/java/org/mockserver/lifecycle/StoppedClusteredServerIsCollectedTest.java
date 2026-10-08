package org.mockserver.lifecycle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.CrossProtocolEventBus;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.GrpcChaosRegistry;
import org.mockserver.mock.action.http.ServiceChaosRegistry;
import org.mockserver.mock.action.http.TcpChaosRegistry;
import org.mockserver.model.CrossProtocolScenario;
import org.mockserver.netty.MockServer;
import org.mockserver.state.InMemoryStateBackend;
import org.mockserver.state.KeyValueStore;
import org.mockserver.state.StateBackend;
import org.mockserver.state.StateBackendFactory;
import org.mockserver.state.infinispan.InfinispanStateBackendRegistrar;

import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.GrpcChaosProfile.grpcChaosProfile;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;
import static org.mockserver.model.TcpChaosProfile.tcpChaosProfile;

/**
 * A server with a clustered state backend hands that backend's stores to process-wide registries as it starts:
 * once stopped, and with nothing else referring to it, it must not be kept in memory by them, and a clustered
 * server still running in the same JVM must get its own stores back.
 */
@Timeout(120)
class StoppedClusteredServerIsCollectedTest {

    private static final long COLLECTED_WITHIN_SECONDS = 30;

    @AfterEach
    void leaveTheStateBackendFactoryAsFound() {
        // HttpState's auto-discovery registers the Infinispan factory JVM-wide
        InfinispanStateBackendRegistrar.deregister();
    }

    @Test
    void shouldLetAStoppedClusteredServerThatServedARequestBeCollected() throws Exception {
        assertCollected(startServeAndStop(() -> {
        }));
    }

    @Test
    void shouldGiveARunningClusteredServerBackItsStoresWhenANewerOneStops() throws Exception {
        MockServer older = new MockServer(clustered(), 0);
        try {
            assertCollected(startServeAndStop(() -> {
            }));

            StateBackend olderBackend = older.httpState.getStateBackend();
            ServiceChaosRegistry.getInstance().put("older.example.com", httpChaosProfile().withErrorStatus(503));
            TcpChaosRegistry.getInstance().put("older.example.com", tcpChaosProfile().withDown(true));
            GrpcChaosRegistry.getInstance().put("older.Service", grpcChaosProfile().withErrorProbability(1.0));
            CrossProtocolScenario scenario = CrossProtocolScenario.onDnsQuery("older.example.com", "OlderScenario", "Observed");
            CrossProtocolEventBus.getInstance().register(scenario);
            try {
                assertThat(olderBackend.crudEntities("chaos-service").size(), is(1));
                assertThat(olderBackend.crudEntities("chaos-tcp").size(), is(1));
                assertThat(olderBackend.crudEntities("chaos-grpc").size(), is(1));
                assertThat(olderBackend.crudEntities("cross-protocol-bus").size(), is(1));
            } finally {
                ServiceChaosRegistry.getInstance().remove("older.example.com");
                TcpChaosRegistry.getInstance().remove("older.example.com");
                GrpcChaosRegistry.getInstance().remove("older.Service");
                CrossProtocolEventBus.getInstance().unregister(scenario);
            }
        } finally {
            older.stop();
        }
    }

    @Test
    void shouldReleaseTheStoresOfAClusteredServerWhoseConstructorFailedWithoutCreatingMoreAndCloseItsBackend() {
        List<CountingClusteredBackend> backends = new CopyOnWriteArrayList<>();
        StateBackendFactory.register(configuration -> {
            CountingClusteredBackend backend = new CountingClusteredBackend(configuration.maxExpectations());
            backends.add(backend);
            return backend;
        });
        IllegalStateException failure = new IllegalStateException("gRPC configuration failed on purpose");
        Configuration failingLast = new Configuration() {
            @Override
            public Boolean grpcEnabled() {
                throw failure;
            }
        };
        Throwable thrown = null;
        HttpState constructed = null;
        try {
            constructed = new HttpState(failingLast, new MockServerLogger(), null);
        } catch (Throwable throwable) {
            thrown = throwable;
        } finally {
            if (constructed != null) {
                constructed.stop();
            }
            StateBackendFactory.register(null);
        }

        assertThat(thrown, sameInstance(failure));
        assertThat(backends.size(), is(1));
        CountingClusteredBackend backend = backends.get(0);
        for (String namespace : new String[]{"chaos-service", "chaos-tcp", "chaos-grpc", "cross-protocol-bus"}) {
            assertThat("stores asked for in " + namespace, backend.storesAskedFor.get(namespace).get(), is(1));
        }
        assertThat(backend.closed.get(), is(1));
        ServiceChaosRegistry.getInstance().put("failed.example.com", httpChaosProfile().withErrorStatus(503));
        try {
            assertThat("a store of the server whose constructor failed is still in use", backend.crudEntities("chaos-service").size(), is(0));
        } finally {
            ServiceChaosRegistry.getInstance().remove("failed.example.com");
        }
    }

    private static final class CountingClusteredBackend extends InMemoryStateBackend {
        private final Map<String, AtomicInteger> storesAskedFor = new ConcurrentHashMap<>();
        private final AtomicInteger closed = new AtomicInteger();

        CountingClusteredBackend(int maxExpectations) {
            super(maxExpectations);
        }

        @Override
        public boolean isClustered() {
            return true;
        }

        @Override
        public KeyValueStore<ObjectNode> crudEntities(String namespace) {
            storesAskedFor.computeIfAbsent(namespace, ignored -> new AtomicInteger()).incrementAndGet();
            return super.crudEntities(namespace);
        }

        @Override
        public void close() {
            closed.incrementAndGet();
            super.close();
        }
    }

    private static Configuration clustered() {
        return configuration()
            .stateBackend("infinispan")
            .clusterEnabled(true)
            .clusterName("stopped-server-collected-" + System.nanoTime());
    }

    private static WeakReference<HttpState> startServeAndStop(Runnable onceStarted) throws Exception {
        MockServer mockServer = new MockServer(clustered(), 0);
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
        assertThat("a stopped clustered server that nothing refers to is still reachable after " + COLLECTED_WITHIN_SECONDS + "s of garbage collection",
            stopped.get(), is(nullValue()));
    }
}
