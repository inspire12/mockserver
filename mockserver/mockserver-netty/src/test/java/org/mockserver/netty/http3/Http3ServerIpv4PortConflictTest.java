package org.mockserver.netty.http3;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.mockserver.lifecycle.Ipv4UdpPortProbe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.testing.socket.TestPortFactory.findFreeUdpPort;

/**
 * An HTTP/3 server must not start on a UDP port another application holds on the IPv4 wildcard: on macOS
 * the dual-stack bind succeeds there and that application then receives the server's localhost traffic.
 */
public class Http3ServerIpv4PortConflictTest {

    // only a dual-stack bind on macOS succeeds on such a port; elsewhere the bind fails with its own error
    private static final boolean DUAL_STACK_MAC_OS = System.getProperty("os.name", "").toLowerCase().contains("mac")
        && !Boolean.getBoolean("java.net.preferIPv4Stack");

    // a refusal or stop that leaves the socket to the event loop fails these on almost every iteration but the first
    // QUIC bind in the JVM, so several iterations keep them red on any order of test execution
    private static final int REBIND_ITERATIONS = 25;

    private Http3Server server;

    @After
    public void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    @Test
    public void shouldRefuseAPortHeldOnTheIpv4Wildcard() throws Exception {
        assumeQuicAvailable();
        try (DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET)) {
            otherApplication.bind(new InetSocketAddress("0.0.0.0", 0));
            int port = ((InetSocketAddress) otherApplication.getLocalAddress()).getPort();
            Http3Server conflicting = new Http3Server();

            Exception thrown = assertThrows(Exception.class, () -> {
                // only reached if start wrongly succeeds; stops it so the port is not leaked
                server = conflicting;
                conflicting.start(port);
            });

            assertThat(thrown, instanceOf(BindException.class));
            assertThat(conflicting.getPort(), is(-1));
            if (DUAL_STACK_MAC_OS) {
                assertThat(thrown.getMessage(), is("UDP port " + port + " is already in use by another socket listening on 0.0.0.0:" + port
                    + ", so HTTP/3 requests to localhost:" + port + " would reach that socket instead of MockServer"
                    + "; stop the application that holds it or choose a different http3Port (to find it run: lsof -nP -iUDP:" + port + ")"));
            }
        }
    }

    // another application can take the port between the probe and the bind, and on macOS the bind then succeeds
    @Test
    public void shouldRefuseAPortTakenOnTheIpv4WildcardAfterTheProbe() throws Exception {
        assumeQuicAvailable();
        int port = portFreeOnBothStacks();
        try (DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET)) {
            Http3Server taken = new Http3Server() {
                @Override
                boolean shadowedOnIpv4(InetSocketAddress probed) {
                    boolean shadowed = super.shadowedOnIpv4(probed);
                    try {
                        otherApplication.bind(new InetSocketAddress("0.0.0.0", probed.getPort()));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return shadowed;
                }
            };
            server = taken;

            Exception thrown = assertThrows(Exception.class, () -> taken.start(port));

            assertThat("the other application took the port", otherApplication.getLocalAddress(), is(new InetSocketAddress("0.0.0.0", port)));
            assertThat(thrown, instanceOf(BindException.class));
            assertThat(taken.getPort(), is(-1));
            if (DUAL_STACK_MAC_OS) {
                assertThat(thrown.getMessage(), is("UDP port " + port + " is already in use by another socket listening on 0.0.0.0:" + port
                    + ", so HTTP/3 requests to localhost:" + port + " would reach that socket instead of MockServer"
                    + "; stop the application that holds it or choose a different http3Port (to find it run: lsof -nP -iUDP:" + port + ")"));
            }
            otherApplication.close();
            assertThat("the refused server must hold no socket on UDP port " + port, Ipv4UdpPortProbe.dualStackBindSucceeds(port), is(true));
        }
    }

    @Test
    public void shouldReleaseARefusedPort() throws Exception {
        assumeQuicAvailable();
        for (int i = 0; i < REBIND_ITERATIONS; i++) {
            int port = portFreeOnBothStacks();
            try (DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET)) {
                otherApplication.bind(new InetSocketAddress("0.0.0.0", port));
                Http3Server refused = new Http3Server();
                server = refused;
                assertThrows(BindException.class, () -> refused.start(port));
            }

            server = new Http3Server();
            assertThat("iteration " + i, server.start(port), is(port));
            server.stop();
            server = null;
        }
    }

    @Test
    public void shouldReleaseAStoppedPortForAnImmediateRestart() throws Exception {
        assumeQuicAvailable();
        for (int i = 0; i < REBIND_ITERATIONS; i++) {
            int port = portFreeOnBothStacks();
            server = new Http3Server();
            server.start(port);
            server.stop();

            server = new Http3Server();
            assertThat("iteration " + i, server.start(port), is(port));
            server.stop();
            server = null;
        }
    }

    @Test
    public void shouldStartOnAFreeExplicitPort() throws Exception {
        assumeQuicAvailable();
        server = new Http3Server();

        int port = startWithHttp3(server);

        assertThat(server.getPort(), is(port));
    }

    @Test
    public void shouldStartOnAnEphemeralPort() throws Exception {
        assumeQuicAvailable();
        server = new Http3Server();

        int port = server.start(0);

        assertThat(port, greaterThan(0));
        assertThat(server.getPort(), is(port));
    }

    @Test
    public void shouldReportAPortHeldOnIpv4() throws Exception {
        try (DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET)) {
            otherApplication.bind(new InetSocketAddress("0.0.0.0", 0));
            int port = ((InetSocketAddress) otherApplication.getLocalAddress()).getPort();

            assertThat(Ipv4UdpPortProbe.heldOnIpv4(port), is(true));
        }
    }

    @Test
    public void shouldReportAFreePortAsNotHeldOnIpv4() {
        assertThat(Ipv4UdpPortProbe.heldOnIpv4(findFreeUdpPort()), is(false));
    }

    /**
     * A port free on IPv4 and for a dual-stack bind, so a failure in these loops is the server's own: on macOS
     * another process occasionally holds an IPv4-allocated port on IPv6.
     */
    private static int portFreeOnBothStacks() {
        for (int attempt = 0; attempt < 20; attempt++) {
            int port = findFreeUdpPort();
            if (!Ipv4UdpPortProbe.heldOnIpv4(port) && Ipv4UdpPortProbe.dualStackBindSucceeds(port)) {
                return port;
            }
        }
        throw new IllegalStateException("no UDP port free on both IPv4 and a dual-stack bind in 20 attempts");
    }

    private static void assumeQuicAvailable() {
        Assume.assumeTrue("native QUIC transport not available on this platform -- skipping HTTP/3 test", Http3Server.isQuicAvailable());
    }
}
