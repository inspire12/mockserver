package org.mockserver.netty.dns;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.model.DnsRecord;
import org.mockserver.model.DnsResponse;
import org.mockserver.netty.MockServer;
import org.mockserver.testing.socket.TestPortFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.DnsRequestDefinition.dnsRequest;
import static org.mockserver.netty.LocalBoundIpAddresses.anotherAddressOfThisHost;
import static org.mockserver.netty.dns.DnsQueries.addressAnsweredFor;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * {@code localBoundIP} keeps the DNS listener on that address too, as it does the TCP listeners.
 */
public class DnsLocalBoundIpTest {

    private static final String SERVED_NAME = "served.dns-local-bound-ip.example.";
    private static final String SERVED_ADDRESS = "10.9.8.7";
    private static final int FOUND_PORT_CANDIDATES = 5;

    @Test
    public void shouldAnswerOnlyOnTheLocalBoundIpOnAPortItChose() throws Exception {
        assertAnsweredOnlyOnTheLoopback(configuration().localBoundIP("127.0.0.1").dnsEnabled(true).dnsPort(0));
    }

    @Test
    public void shouldAnswerOnlyOnTheLocalBoundIpOnAnExplicitPort() throws Exception {
        InetAddress anotherAddress = anotherAddressOfThisHost();
        MockServer server = startOnAFoundDnsPort(configuration().localBoundIP("127.0.0.1").dnsEnabled(true));
        assertAnsweredOnlyOnTheLoopback(server, anotherAddress);
    }

    @Test
    public void shouldTryAnotherFoundPortWhenTheOneFoundIsTakenOnEitherTransport() {
        assertThat("NIO", portTaken(DnsStartupException.portCouldNotBeOpenedOrBound(53, new BindException("Address already in use"))), is(true));
        assertThat("epoll", portTaken(DnsStartupException.portCouldNotBeOpenedOrBound(53, new IOException("bind(..) failed: Address already in use"))), is(true));
        assertThat(portTaken(DnsStartupException.portCouldNotBeOpenedOrBound(53, new IOException("bind(..) failed: Permission denied"))), is(false));
        assertThat(portTaken(new RuntimeException("refused")), is(false));
    }

    @Test
    public void shouldAnswerOnEveryAddressWithoutALocalBoundIp() throws Exception {
        InetAddress anotherAddress = anotherAddressOfThisHost();
        MockServer server = new MockServer(configuration().dnsEnabled(true).dnsPort(0), 0);
        MockServerClient client = new MockServerClient("127.0.0.1", server.getLocalPort());
        try {
            client.when(dnsRequest(SERVED_NAME)).respondWithDns(new DnsResponse().withAnswerRecords(DnsRecord.aRecord(SERVED_NAME, SERVED_ADDRESS)));

            assertThat(addressAnsweredFor(SERVED_NAME, new InetSocketAddress(anotherAddress, server.getDnsPort())), is(SERVED_ADDRESS));
        } finally {
            stopQuietly(client);
            server.stop();
        }
    }

    private static void assertAnsweredOnlyOnTheLoopback(Configuration configuration) throws Exception {
        InetAddress anotherAddress = anotherAddressOfThisHost();
        assertAnsweredOnlyOnTheLoopback(new MockServer(configuration, 0), anotherAddress);
    }

    // a found port can be taken before the server binds it, so another is tried, as Http3TestServer does
    private static MockServer startOnAFoundDnsPort(Configuration configuration) {
        RuntimeException lastRefusal = null;
        for (int attempt = 0; attempt < FOUND_PORT_CANDIDATES; attempt++) {
            try {
                return new MockServer(configuration.dnsPort(TestPortFactory.findFreeUdpPort()), 0);
            } catch (RuntimeException refused) {
                if (!portTaken(refused)) {
                    throw refused;
                }
                lastRefusal = refused;
            }
        }
        throw new AssertionError("another socket took each of the UDP ports found", lastRefusal);
    }

    // NIO reports a taken port as a BindException, epoll as a plain IOException (Errors.NativeIoException)
    static boolean portTaken(Throwable refused) {
        return ExceptionUtils.getThrowableList(refused).stream()
            .anyMatch(cause -> cause instanceof BindException || String.valueOf(cause.getMessage()).contains("Address already in use"));
    }

    private static void assertAnsweredOnlyOnTheLoopback(MockServer server, InetAddress anotherAddress) throws Exception {
        MockServerClient client = new MockServerClient("127.0.0.1", server.getLocalPort());
        try {
            client.when(dnsRequest(SERVED_NAME)).respondWithDns(new DnsResponse().withAnswerRecords(DnsRecord.aRecord(SERVED_NAME, SERVED_ADDRESS)));

            assertThat("a query to the local bound IP", addressAnsweredFor(SERVED_NAME, new InetSocketAddress("127.0.0.1", server.getDnsPort())), is(SERVED_ADDRESS));
            assertThat("a query to " + anotherAddress.getHostAddress(), addressAnsweredFor(SERVED_NAME, new InetSocketAddress(anotherAddress, server.getDnsPort())), startsWith("nothing"));
        } finally {
            stopQuietly(client);
            server.stop();
        }
    }
}
