package org.mockserver.junit;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.DnsRecord;
import org.mockserver.model.DnsResponse;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.DnsRequestDefinition.dnsRequest;
import static org.mockserver.testing.socket.DnsQuery.addressAnsweredFor;

/**
 * The rule hands out the DNS port MockServer chose for the default {@code dnsPort} of 0.
 */
public class MockServerRuleDnsPortTest {

    private static final String SERVED_NAME = "served.junit-rule-dns-port.example.";
    private static final String SERVED_ADDRESS = "10.9.8.4";

    private MockServerClient mockServerClient;

    @Test
    public void shouldHandOutTheDnsPortMockServerChose() throws Throwable {
        MockServerRule rule = new MockServerRule(this, false);
        startingWithDnsMocking(rule);
        assertThat("before the server starts", rule.getDnsPort(), nullValue());
        AtomicReference<String> answered = new AtomicReference<>();

        rule.apply(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                mockServerClient.when(dnsRequest(SERVED_NAME)).respondWithDns(new DnsResponse().withAnswerRecords(DnsRecord.aRecord(SERVED_NAME, SERVED_ADDRESS)));
                assertThat(rule.getDnsPort(), greaterThan(0));
                answered.set(addressAnsweredFor(SERVED_NAME, rule.getDnsPort()));
            }
        }, Description.EMPTY).evaluate();

        assertThat("the answer to a query sent to the port the rule handed out", answered.get(), is(SERVED_ADDRESS));
    }

    @Test
    public void shouldHandOutNoDnsPortWithoutDnsMocking() throws Throwable {
        MockServerRule rule = new MockServerRule(this, false);
        AtomicReference<Integer> dnsPort = new AtomicReference<>();

        rule.apply(new Statement() {
            @Override
            public void evaluate() {
                dnsPort.set(rule.getDnsPort());
            }
        }, Description.EMPTY).evaluate();

        assertThat(dnsPort.get(), is(-1));
    }

    // a server of its own with DNS mocking on, rather than turning it on for every server in this JVM
    private static void startingWithDnsMocking(MockServerRule rule) throws Exception {
        Field factory = MockServerRule.class.getDeclaredField("clientAndServerFactory");
        factory.setAccessible(true);
        factory.set(rule, new MockServerRule.ClientAndServerFactory() {
            @Override
            public ClientAndServer newClientAndServer() {
                return ClientAndServer.startClientAndServer(configuration().dnsEnabled(true).dnsPort(0));
            }
        });
    }
}
