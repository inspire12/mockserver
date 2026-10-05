package org.mockserver.lifecycle;

import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ClientConfiguration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.netty.MockServer;

import java.io.IOException;
import java.lang.ref.Reference;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.ClientConfiguration.clientConfiguration;

/**
 * A JVM that starts and stops MockServer again and again, as one running a test suite does, must get back
 * the threads and file descriptors of each server and client: after a stop, after a start that was refused,
 * and for as long as the stopped server is still referenced.
 */
public class StartAndStopLeaveNothingBehindTest {

    private static final int REPEATS = 10;

    // a refusal takes seconds where the bind succeeds and only the loopback probe finds the other listener
    private static final int REFUSED_TCP_STARTS = 3;

    @Test
    public void shouldLeaveNoJdkClientThreadAndNoDescriptorBehindStoppedServers() throws Exception {
        new MockServer(0).stop();
        long baseline = LeftBehind.settledDescriptors();
        ThreadGroup group = new ThreadGroup("stopped-servers");
        List<MockServer> stopped = new CopyOnWriteArrayList<>();

        LeftBehind.in(group, () -> {
            for (int i = 0; i < REPEATS; i++) {
                MockServer mockServer = new MockServer(0);
                mockServer.stop();
                stopped.add(mockServer);
            }
        });

        // each stopped server is still referenced, as one held in a static field of a test class is
        assertThat(LeftBehind.jdkHttpClientThreadsStillAlive(group), is(empty()));
        LeftBehind.assertDescriptorsGivenBack(baseline, REPEATS);
        Reference.reachabilityFence(stopped);
    }

    @Test
    public void shouldLeaveNothingBehindWhenClientAndServerIsRefusedAHeldTcpPort() throws Exception {
        try (ServerSocket otherApplication = new ServerSocket()) {
            otherApplication.bind(new InetSocketAddress("127.0.0.1", 0));
            int heldPort = otherApplication.getLocalPort();
            assertRefusedAHeldPort(heldPort);
            long baseline = LeftBehind.settledDescriptors();
            ThreadGroup group = new ThreadGroup("refused-tcp-port");

            LeftBehind.in(group, () -> {
                for (int i = 0; i < REFUSED_TCP_STARTS; i++) {
                    assertRefusedAHeldPort(heldPort);
                }
            });

            assertThat(LeftBehind.threadsStillAlive(group), is(empty()));
            LeftBehind.assertDescriptorsGivenBack(baseline, REFUSED_TCP_STARTS);
        }
    }

    // the operating system refuses the bind, or the bind succeeds and the loopback probe finds the other listener
    private static void assertRefusedAHeldPort(int heldPort) {
        RuntimeException refused = assertThrows(RuntimeException.class, () -> ClientAndServer.startClientAndServer(heldPort).stop());
        assertThat(refused.getMessage(), containsString("Exception while binding MockServer to port " + heldPort));
        assertThat(refused.getCause(), instanceOf(IOException.class));
    }

    @Test
    public void shouldLeaveNoDescriptorBehindClientAndServerHoweverItIsStopped() throws Exception {
        ClientAndServer.startClientAndServer(0).stop();
        long baseline = LeftBehind.settledDescriptors();

        for (int i = 0; i < REPEATS / 2; i++) {
            ClientAndServer stoppedTwice = ClientAndServer.startClientAndServer(0);
            stoppedTwice.stop();
            stoppedTwice.stop();
            try (ClientAndServer closed = ClientAndServer.startClientAndServer(0)) {
                assertThat(closed.hasStarted(), is(true));
            }
        }

        LeftBehind.assertDescriptorsGivenBack(baseline, REPEATS);
    }

    @Test
    public void shouldLeaveNoDescriptorBehindAClientStoppedBeforeItsPortWasKnown() throws Exception {
        ClientConfiguration portNeverKnown = clientConfiguration().maxFutureTimeoutInMillis(50L);
        new MockServerClient(portNeverKnown, new CompletableFuture<>()).stop();
        long baseline = LeftBehind.settledDescriptors();

        for (int i = 0; i < REPEATS / 2; i++) {
            CompletableFuture<Integer> portKnownTooLate = new CompletableFuture<>();
            MockServerClient client = new MockServerClient(portNeverKnown, portKnownTooLate);
            RuntimeException noPort = assertThrows(RuntimeException.class, () -> client.stop(true));
            assertThat(noPort.getCause(), instanceOf(TimeoutException.class));
            assertThat("the client has stopped, so there is nothing more to wait for", client.stop(true).isDone(), is(true));
            portKnownTooLate.complete(1);
            IllegalStateException stopped = assertThrows(IllegalStateException.class, () -> client.retrieveRecordedRequests(null));
            assertThat(stopped.getMessage(), containsString("has already been stopped"));

            CompletableFuture<Integer> serverDidNotStart = new CompletableFuture<>();
            serverDidNotStart.completeExceptionally(new IllegalStateException("the server did not start"));
            try (MockServerClient closed = new MockServerClient(portNeverKnown, serverDidNotStart)) {
                assertThat(closed.contextPath(), is(""));
            }
        }

        LeftBehind.assertDescriptorsGivenBack(baseline, REPEATS);
    }
}
