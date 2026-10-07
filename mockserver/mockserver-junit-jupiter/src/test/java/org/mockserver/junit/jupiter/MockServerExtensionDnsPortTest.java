package org.mockserver.junit.jupiter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.DnsRecord;
import org.mockserver.model.DnsResponse;
import org.mockserver.test.TestLoggerExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.DnsRequestDefinition.dnsRequest;
import static org.mockserver.testing.socket.DnsQuery.addressAnsweredFor;

/**
 * A test given the {@link ClientAndServer} reads the DNS port MockServer chose for the default {@code dnsPort} of 0.
 */
@ExtendWith(TestLoggerExtension.class)
class MockServerExtensionDnsPortTest {

    private static final String SERVED_NAME = "served.junit-jupiter-dns-port.example.";
    private static final String SERVED_ADDRESS = "10.9.8.3";

    @RegisterExtension
    static final MockServerExtension MOCK_SERVER = new MockServerExtension(ClientAndServer.startClientAndServer(configuration().dnsEnabled(true).dnsPort(0)));

    @Test
    void handsOutTheDnsPortMockServerChose(ClientAndServer client) throws Exception {
        client.when(dnsRequest(SERVED_NAME)).respondWithDns(new DnsResponse().withAnswerRecords(DnsRecord.aRecord(SERVED_NAME, SERVED_ADDRESS)));

        assertThat(client.getDnsPort(), greaterThan(0));
        assertThat("the answer to a query sent to the port handed out", addressAnsweredFor(SERVED_NAME, client.getDnsPort()), is(SERVED_ADDRESS));
    }
}
