package org.mockserver.netty.http3;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static org.mockserver.testing.socket.TestPortFactory.findFreeUdpPort;

/**
 * An HTTP/3 server must not start on a UDP port another application holds on the IPv4 wildcard: on macOS
 * the dual-stack bind succeeds there and that application then receives the server's localhost traffic.
 */
public class Http3ServerIpv4PortConflictTest {

    // only a dual-stack bind on macOS succeeds on such a port; elsewhere the bind fails with its own error
    private static final boolean DUAL_STACK_MAC_OS = System.getProperty("os.name", "").toLowerCase().contains("mac")
        && !Boolean.getBoolean("java.net.preferIPv4Stack");

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

    @Test
    public void shouldReleaseARefusedPort() throws Exception {
        assumeQuicAvailable();
        int port;
        try (DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET)) {
            otherApplication.bind(new InetSocketAddress("0.0.0.0", 0));
            port = ((InetSocketAddress) otherApplication.getLocalAddress()).getPort();
            Http3Server refused = new Http3Server();
            server = refused;
            assertThrows(BindException.class, () -> refused.start(port));
        }

        server.stop();
        server = new Http3Server();
        assertThat(server.start(port), is(port));
    }

    @Test
    public void shouldStartOnAFreeExplicitPort() throws Exception {
        assumeQuicAvailable();
        int port = findFreeUdpPort();
        server = new Http3Server();

        assertThat(server.start(port), is(port));
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

    private static void assumeQuicAvailable() {
        Assume.assumeTrue("native QUIC transport not available on this platform -- skipping HTTP/3 test", Http3Server.isQuicAvailable());
    }
}
