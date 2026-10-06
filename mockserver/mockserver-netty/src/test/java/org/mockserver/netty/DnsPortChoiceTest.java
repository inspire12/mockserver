package org.mockserver.netty;

import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.Ipv4UdpPortProbe;
import org.mockserver.model.DnsRecord;
import org.mockserver.model.DnsResponse;

import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.DnsRequestDefinition.dnsRequest;
import static org.mockserver.netty.dns.DnsQueries.addressAnsweredFor;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * With a {@code dnsPort} of 0 MockServer chooses the port, and must choose one whose localhost queries reach it: on
 * macOS a dual-stack bind succeeds on a port another socket holds on {@code 0.0.0.0}, which then gets the queries.
 */
public class DnsPortChoiceTest {

    private static final String SERVED_NAME = "served.dns-port-choice.example.";
    private static final String SERVED_ADDRESS = "10.9.8.6";

    @Test
    public void shouldPassOverAChosenPortAnotherSocketHoldsOnTheIpv4Wildcard() throws Exception {
        try (DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET)) {
            otherApplication.bind(new InetSocketAddress("0.0.0.0", 0));
            int heldPort = port(otherApplication);

            assertServedOnAnotherPort(heldPort);
        }
    }

    // a socket on 127.0.0.1 fails the server's bind of that port on every platform
    @Test
    public void shouldPassOverAChosenPortItCannotBind() throws Exception {
        try (DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET)) {
            otherApplication.bind(new InetSocketAddress("127.0.0.1", 0));
            int heldPort = port(otherApplication);

            assertServedOnAnotherPort(heldPort);
        }
    }

    // what the operating system would choose for a dual-stack bind of port 0 is not asked
    @Test
    public void shouldBindTheCandidateTheIpv4AllocatorChose() throws Exception {
        int candidate = Ipv4UdpPortProbe.portFreeOnIpv4();
        MockServer server = new OffersFirst(configuration().dnsEnabled(true).dnsPort(0), candidate);
        try {
            assertThat(server.getDnsPort(), is(candidate));
        } finally {
            server.stop();
        }
    }

    private static void assertServedOnAnotherPort(int heldPort) throws Exception {
        MockServer server = new OffersFirst(configuration().dnsEnabled(true).dnsPort(0), heldPort);
        MockServerClient client = new MockServerClient("127.0.0.1", server.getLocalPort());
        try {
            assertThat("the port another socket holds", server.getDnsPort(), is(not(heldPort)));
            assertThat(server.getDnsPort(), greaterThan(0));
            client.when(dnsRequest(SERVED_NAME)).respondWithDns(new DnsResponse().withAnswerRecords(DnsRecord.aRecord(SERVED_NAME, SERVED_ADDRESS)));

            assertThat("the answer to a query sent to the port the server reported", addressAnsweredFor(SERVED_NAME, server.getDnsPort()), is(SERVED_ADDRESS));
        } finally {
            stopQuietly(client);
            server.stop();
        }
    }

    private static int port(DatagramChannel channel) throws Exception {
        return ((InetSocketAddress) channel.getLocalAddress()).getPort();
    }

    // offers the given ports as the first candidates for the DNS port, then those MockServer would choose
    private static final class OffersFirst extends MockServer {

        private static final ThreadLocal<Deque<Integer>> FIRST_CANDIDATES = new ThreadLocal<>();

        private OffersFirst(Configuration configuration, Integer... firstCandidates) {
            super(offering(configuration, firstCandidates), 0);
        }

        private static Configuration offering(Configuration configuration, Integer... firstCandidates) {
            FIRST_CANDIDATES.set(new ArrayDeque<>(Arrays.asList(firstCandidates)));
            return configuration;
        }

        @Override
        int nextDnsPortCandidate() {
            Integer first = FIRST_CANDIDATES.get().poll();
            return first != null ? first : super.nextDnsPortCandidate();
        }
    }
}
