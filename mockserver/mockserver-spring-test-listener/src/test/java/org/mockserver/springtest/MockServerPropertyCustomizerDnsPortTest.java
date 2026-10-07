package org.mockserver.springtest;

import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.DnsRecord;
import org.mockserver.model.DnsResponse;
import org.springframework.context.support.GenericApplicationContext;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.DnsRequestDefinition.dnsRequest;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.testing.socket.DnsQuery.addressAnsweredFor;

/**
 * A Spring test reads the DNS port MockServer chose for the default {@code dnsPort} of 0 from
 * {@code mockServerDnsPort}. The listener's own server is shared by every test in the JVM, so this one is given a
 * server of its own.
 */
public class MockServerPropertyCustomizerDnsPortTest {

    private static final String SERVED_NAME = "served.spring-dns-port.example.";
    private static final String SERVED_ADDRESS = "10.9.8.2";

    @Test
    public void shouldExposeTheDnsPortMockServerChose() throws Exception {
        ClientAndServer server = ClientAndServer.startClientAndServer(configuration().dnsEnabled(true).dnsPort(0));
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            server.when(dnsRequest(SERVED_NAME)).respondWithDns(new DnsResponse().withAnswerRecords(DnsRecord.aRecord(SERVED_NAME, SERVED_ADDRESS)));

            MockServerPropertyCustomizer.exposePorts(context, server, Collections.singletonList("resolver.address=127.0.0.1:${mockServerDnsPort}"));

            int dnsPort = context.getEnvironment().getRequiredProperty("mockServerDnsPort", Integer.class);
            assertThat(dnsPort, greaterThan(0));
            assertThat(dnsPort, is(server.getDnsPort()));
            assertThat(context.getEnvironment().getProperty("resolver.address"), is("127.0.0.1:" + dnsPort));
            assertThat("the answer to a query sent to the port exposed", addressAnsweredFor(SERVED_NAME, dnsPort), is(SERVED_ADDRESS));
        } finally {
            stopQuietly(server);
        }
    }

    @Test
    public void shouldExposeNoDnsPortWithoutDnsMocking() {
        ClientAndServer server = ClientAndServer.startClientAndServer(configuration().dnsEnabled(false));
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            MockServerPropertyCustomizer.exposePorts(context, server, Collections.emptyList());

            assertThat(context.getEnvironment().getRequiredProperty("mockServerDnsPort", Integer.class), is(-1));
            assertThat(context.getEnvironment().getRequiredProperty("mockServerPort", Integer.class), is(server.getPort()));
        } finally {
            stopQuietly(server);
        }
    }
}
