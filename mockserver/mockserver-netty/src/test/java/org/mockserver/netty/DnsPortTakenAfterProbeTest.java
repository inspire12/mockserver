package org.mockserver.netty;

import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.Ipv4UdpPortProbe;
import org.mockserver.model.DnsRecord;
import org.mockserver.model.DnsResponse;
import org.mockserver.netty.dns.DnsStartupException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.lifecycle.RefusedStart.refusedStart;
import static org.mockserver.model.DnsRequestDefinition.dnsRequest;
import static org.mockserver.netty.dns.DnsQueries.addressAnsweredFor;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.testing.socket.TestPortFactory.findFreeUdpPort;

/**
 * Another application can take the DNS port on {@code 0.0.0.0} after MockServer's probe found it free and before
 * MockServer binds it. On macOS that bind then succeeds and the other socket gets the queries sent to localhost, so
 * the port must be refused after the bind; on Linux the bind itself fails.
 */
public class DnsPortTakenAfterProbeTest {

    private static final boolean DUAL_STACK_MAC_OS = System.getProperty("os.name", "").toLowerCase().contains("mac")
        && !Boolean.getBoolean("java.net.preferIPv4Stack");

    private static final String SERVED_NAME = "served.dns-port-taken.example.";
    private static final String SERVED_ADDRESS = "10.9.8.5";

    @Test
    public void shouldRefuseAnExplicitPortTakenOnTheIpv4WildcardAfterTheProbe() throws Exception {
        int dnsPort = portFreeOnBothStacks();
        try (DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET)) {

            Throwable refused = refusedStart(() -> new TakenAfterTheFirstProbe(configuration().dnsEnabled(true).dnsPort(dnsPort), otherApplication));

            assertThat("the other application took the port", otherApplication.getLocalAddress(), is(new InetSocketAddress("0.0.0.0", dnsPort)));
            assertThat(refused, instanceOf(DnsStartupException.class));
            assertThat(((DnsStartupException) refused).isPortUnavailable(), is(true));
            assertThat(refused.getMessage(), startsWith("DNS mocking is enabled (dnsEnabled=true, dnsPort=" + dnsPort + ") but UDP port " + dnsPort + " could not be opened or bound"));
            if (DUAL_STACK_MAC_OS) {
                assertThat(refused.getMessage(), endsWith("BindException: UDP port " + dnsPort + " is already in use by another socket listening on 0.0.0.0:" + dnsPort
                    + ", so DNS queries to localhost:" + dnsPort + " would reach that socket instead of MockServer"
                    + "; stop the application that holds it or choose a different dnsPort (to find it run: lsof -nP -iUDP:" + dnsPort + "))"));
            }
        }
    }

    @Test
    public void shouldPassOverAChosenPortTakenOnTheIpv4WildcardAfterTheProbe() throws Exception {
        try (DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET)) {
            MockServer server = new TakenAfterTheFirstProbe(configuration().dnsEnabled(true).dnsPort(0), otherApplication);
            MockServerClient client = new MockServerClient("127.0.0.1", server.getLocalPort());
            try {
                int takenPort = ((InetSocketAddress) otherApplication.getLocalAddress()).getPort();
                assertThat("the other application took the first port probed", takenPort, greaterThan(0));
                assertThat("the port the other application took", server.getDnsPort(), is(not(takenPort)));
                client.when(dnsRequest(SERVED_NAME)).respondWithDns(new DnsResponse().withAnswerRecords(DnsRecord.aRecord(SERVED_NAME, SERVED_ADDRESS)));

                assertThat("the answer to a query sent to the port the server reported", addressAnsweredFor(SERVED_NAME, server.getDnsPort()), is(SERVED_ADDRESS));
                otherApplication.close();
                assertThat("the server must hold no socket on the port it passed over", Ipv4UdpPortProbe.dualStackBindSucceeds(takenPort), is(true));
            } finally {
                stopQuietly(client);
                server.stop();
            }
        }
    }

    /**
     * A port free on IPv4 and for a dual-stack bind: on macOS another process occasionally holds an IPv4-allocated
     * port on IPv6, which would fail the server's bind for a reason of its own.
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

    // binds the other application's socket on 0.0.0.0 just after MockServer's first probe of a DNS port
    private static final class TakenAfterTheFirstProbe extends MockServer {

        private static final ThreadLocal<DatagramChannel> OTHER_APPLICATION = new ThreadLocal<>();

        private TakenAfterTheFirstProbe(Configuration configuration, DatagramChannel otherApplication) {
            super(takenBy(configuration, otherApplication), 0);
        }

        private static Configuration takenBy(Configuration configuration, DatagramChannel otherApplication) {
            OTHER_APPLICATION.set(otherApplication);
            return configuration;
        }

        @Override
        boolean dnsPortShadowedOnIpv4(InetSocketAddress listenerAddress) {
            boolean shadowed = super.dnsPortShadowedOnIpv4(listenerAddress);
            DatagramChannel otherApplication = OTHER_APPLICATION.get();
            if (otherApplication != null) {
                OTHER_APPLICATION.remove();
                try {
                    otherApplication.bind(new InetSocketAddress("0.0.0.0", listenerAddress.getPort()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return shadowed;
        }
    }
}
