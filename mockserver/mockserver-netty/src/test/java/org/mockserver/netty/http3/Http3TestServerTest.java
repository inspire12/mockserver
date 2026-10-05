package org.mockserver.netty.http3;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;
import org.mockserver.testing.socket.TestPortFactory;

import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;

public class Http3TestServerTest {

    private final List<MockServer> started = new ArrayList<>();
    private Http3Server http3Server;

    @After
    public void stopServers() {
        started.forEach(MockServer::stop);
        if (http3Server != null) {
            http3Server.stop();
        }
    }

    private MockServer start(Configuration configuration) {
        MockServer server = new MockServer(configuration, 0);
        started.add(server);
        return server;
    }

    private static void assumeQuicAvailable() {
        assumeTrue("native QUIC not available on this platform", Http3Server.isQuicAvailable());
    }

    // the port a find-then-bind loses: held by another IPv4 socket when the server comes to bind it
    private static DatagramChannel heldUdpPort() throws Exception {
        return DatagramChannel.open(StandardProtocolFamily.INET).bind(new InetSocketAddress(0));
    }

    private static int port(DatagramChannel holder) throws Exception {
        return ((InetSocketAddress) holder.getLocalAddress()).getPort();
    }

    private static IntSupplier candidates(List<Integer> asked, int... first) {
        return () -> {
            int candidate = asked.size() < first.length ? first[asked.size()] : TestPortFactory.findFreeUdpPort();
            asked.add(candidate);
            return candidate;
        };
    }

    @Test
    public void aUdpPortTakenAfterItWasFoundMustBeReplacedByTheNextCandidate() throws Exception {
        assumeQuicAvailable();
        try (DatagramChannel holder = heldUdpPort()) {
            int taken = port(holder);
            List<Integer> asked = new ArrayList<>();
            // one Configuration for every attempt, as startWithHttp3(Configuration) uses it
            Configuration configuration = configuration();

            List<RuntimeException> refusals = new ArrayList<>();

            MockServer server = startWithHttp3(candidates(asked, taken), udpPort -> {
                try {
                    return start(configuration.http3Port(udpPort));
                } catch (RuntimeException refused) {
                    refusals.add(refused);
                    throw refused;
                }
            }, MockServer::getHttp3Port, MockServer::stop);

            assertThat("the held port, then one more", asked, hasSize(2));
            assertThat("HTTP/3 must have started on the second candidate", server.getHttp3Port(), is(asked.get(1)));
            assertThat("on a port no other socket holds", server.getHttp3Port(), not(taken));
            assertThat(configuration.http3Port(), is(server.getHttp3Port()));
            assertThat("the server refused the held port rather than starting without HTTP/3", refusals, contains(instanceOf(Http3StartupException.class)));
            assertThat(((Http3StartupException) refusals.get(0)).isPortUnavailable(), is(true));
            assertThat("only the server that got its port was started", started, contains(server));
        }
    }

    @Test
    public void aConfigurationMustBeStartedWithHttp3OnThePortItIsLeftWith() {
        assumeQuicAvailable();
        Configuration configuration = configuration();

        MockServer server = startWithHttp3(configuration);
        started.add(server);

        assertThat(server.getHttp3Port(), is(configuration.http3Port()));
        assertThat(server.getLocalPort(), is(not(0)));
    }

    @Test
    public void aServerWithoutHttp3OnAFreePortMustFailAtOnce() {
        List<Integer> asked = new ArrayList<>();

        // no http3Port: what a start that silently left HTTP/3 off looks like
        AssertionError failure = assertThrows(AssertionError.class,
            () -> startWithHttp3(candidates(asked), udpPort -> start(configuration()), MockServer::getHttp3Port, MockServer::stop));

        assertThat("no second candidate", asked, hasSize(1));
        assertThat(failure.getMessage(), is("HTTP/3 must have started on UDP port " + asked.get(0) + ", but the server started without HTTP/3"));
        assertThat("the server without HTTP/3 was stopped", started.get(0).isRunning(), is(false));
    }

    @Test
    public void aServerWithoutHttp3MustFailAtOnceEvenWhenItsPortIsHeld() throws Exception {
        try (DatagramChannel holder = heldUdpPort()) {
            int taken = port(holder);
            List<Integer> asked = new ArrayList<>();

            // a server that cannot bind its HTTP/3 port refuses to start, so one that started without HTTP/3 was never asked for it
            AssertionError failure = assertThrows(AssertionError.class,
                () -> startWithHttp3(candidates(asked, taken), udpPort -> start(configuration()), MockServer::getHttp3Port, MockServer::stop));

            assertThat("no second candidate", asked, contains(taken));
            assertThat(failure.getMessage(), is("HTTP/3 must have started on UDP port " + taken + ", but the server started without HTTP/3"));
            assertThat("the server without HTTP/3 was stopped", started.get(0).isRunning(), is(false));
        }
    }

    @Test
    public void aServerRefusedAHeldPortMustBeStartedOnTheNextCandidate() throws Exception {
        try (DatagramChannel holder = heldUdpPort()) {
            int taken = port(holder);
            List<Integer> asked = new ArrayList<>();

            // the "server" is the port it serves HTTP/3 on, and refuses the held port as a forked server reports it
            int served = startWithHttp3(candidates(asked, taken), udpPort -> {
                if (udpPort == taken) {
                    throw new UncheckedIOException(new BindException("refused UDP port " + udpPort));
                }
                return udpPort;
            }, Integer::intValue, stopped -> {
                throw new AssertionError("a server that started on its port must not be stopped");
            });

            assertThat("the held port, then one more", asked, hasSize(2));
            assertThat(served, is(asked.get(1)));
        }
    }

    @Test
    public void aServerRefusedAPortThatIsFreeMustFailAtOnceWithTheRefusalAsTheCause() {
        List<Integer> asked = new ArrayList<>();
        UncheckedIOException refusal = new UncheckedIOException(new BindException("refused a free port"));

        AssertionError failure = assertThrows(AssertionError.class,
            () -> startWithHttp3(candidates(asked), udpPort -> {
                throw refusal;
            }, Integer::intValue, stopped -> {
                throw new AssertionError("nothing started, so nothing to stop");
            }));

        assertThat("no second candidate", asked, hasSize(1));
        assertThat(failure.getMessage(), is("HTTP/3 must have started: UDP port " + asked.get(0) + " is free"));
        assertThat(failure.getCause(), is(sameInstance(refusal)));
    }

    @Test
    public void aServerOnAnotherPortMustFailAtOnceNamingBothPorts() {
        List<Integer> asked = new ArrayList<>();
        List<Integer> stopped = new ArrayList<>();

        AssertionError failure = assertThrows(AssertionError.class,
            () -> startWithHttp3(candidates(asked), udpPort -> udpPort + 1, Integer::intValue, stopped::add));

        assertThat("no second candidate", asked, hasSize(1));
        assertThat(stopped, contains(asked.get(0) + 1));
        assertThat(failure.getMessage(), is("HTTP/3 must have started on UDP port " + asked.get(0) + ", but the server started with HTTP/3 on UDP port " + (asked.get(0) + 1)));
    }

    @Test
    public void aServerOnAnotherPortMustFailAtOnceEvenWhenItsPortIsHeld() throws Exception {
        try (DatagramChannel holder = heldUdpPort()) {
            int taken = port(holder);
            List<Integer> asked = new ArrayList<>();
            List<Integer> stopped = new ArrayList<>();

            AssertionError failure = assertThrows(AssertionError.class,
                () -> startWithHttp3(candidates(asked, taken), udpPort -> udpPort + 1, Integer::intValue, stopped::add));

            assertThat("no second candidate", asked, contains(taken));
            assertThat(stopped, contains(taken + 1));
            assertThat(failure.getMessage(), is("HTTP/3 must have started on UDP port " + taken + ", but the server started with HTTP/3 on UDP port " + (taken + 1)));
        }
    }

    @Test
    public void aStartThatThrowsMustFailAtOnceWithItsOwnError() {
        List<Integer> asked = new ArrayList<>();
        IllegalStateException startFailure = new IllegalStateException("could not start");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> startWithHttp3(candidates(asked), udpPort -> {
                throw startFailure;
            }, MockServer::getHttp3Port, MockServer::stop));

        assertThat(thrown, is(sameInstance(startFailure)));
        assertThat("no second candidate", asked, hasSize(1));
    }

    @Test
    public void everyCandidateBeingHeldMustFailNamingThePorts() throws Exception {
        List<DatagramChannel> holders = new ArrayList<>();
        try {
            int[] taken = new int[5];
            for (int i = 0; i < taken.length; i++) {
                holders.add(heldUdpPort());
                taken[i] = port(holders.get(i));
            }
            List<Integer> asked = new ArrayList<>();

            AssertionError failure = assertThrows(AssertionError.class,
                () -> startWithHttp3(candidates(asked, taken), udpPort -> {
                    throw new UncheckedIOException(new BindException("refused UDP port " + udpPort));
                }, Integer::intValue, stopped -> {
                    throw new AssertionError("nothing started, so nothing to stop");
                }));

            assertThat(asked, contains(taken[0], taken[1], taken[2], taken[3], taken[4]));
            assertThat(failure.getMessage(), is("HTTP/3 must have started, but another socket held each of the UDP ports " + asked));
        } finally {
            for (DatagramChannel holder : holders) {
                holder.close();
            }
        }
    }

    @Test
    public void anHttp3ServerRefusedAHeldPortMustBeStartedOnTheNextCandidate() throws Exception {
        assumeQuicAvailable();
        try (DatagramChannel holder = heldUdpPort()) {
            int taken = port(holder);
            List<Integer> asked = new ArrayList<>();
            http3Server = new Http3Server();

            int port = startWithHttp3(candidates(asked, taken), http3Server);

            assertThat("the held port, then one more", asked, hasSize(2));
            assertThat(port, is(asked.get(1)));
            assertThat(http3Server.getPort(), is(port));
        }
    }

    @Test
    public void anHttp3ServerThatCannotBindAFreePortMustFailAtOnce() {
        List<Integer> asked = new ArrayList<>();
        BindException bindFailure = new BindException("cannot bind");
        Http3Server neverBinds = new Http3Server() {
            @Override
            public int start(int port) throws Exception {
                throw bindFailure;
            }
        };

        AssertionError failure = assertThrows(AssertionError.class, () -> startWithHttp3(candidates(asked), neverBinds));

        assertThat("no second candidate", asked, hasSize(1));
        assertThat(failure.getMessage(), is("HTTP/3 must have started: UDP port " + asked.get(0) + " is free"));
        assertThat(failure.getCause(), is(sameInstance(bindFailure)));
    }

    @Test
    public void anHttp3ServerThatFailsToStartMustFailAtOnceWithItsOwnError() {
        List<Integer> asked = new ArrayList<>();
        IllegalStateException startFailure = new IllegalStateException("could not start");
        Http3Server neverStarts = new Http3Server() {
            @Override
            public int start(int port) {
                throw startFailure;
            }
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> startWithHttp3(candidates(asked), neverStarts));

        assertThat(thrown, is(sameInstance(startFailure)));
        assertThat("no second candidate", asked, hasSize(1));
        assertThat(started, is(empty()));
    }
}
