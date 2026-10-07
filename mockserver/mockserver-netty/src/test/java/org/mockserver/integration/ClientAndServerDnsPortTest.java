package org.mockserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.model.DnsRecord;
import org.mockserver.model.DnsResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.DnsRequestDefinition.dnsRequest;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.testing.socket.DnsQuery.addressAnsweredFor;

/**
 * The DNS port MockServer chose for the default {@code dnsPort} of 0 can be read from {@link ClientAndServer} and from
 * the answer to a status request.
 */
public class ClientAndServerDnsPortTest {

    private static final String SERVED_NAME = "served.client-and-server-dns-port.example.";
    private static final String SERVED_ADDRESS = "10.9.8.5";

    @Test
    public void shouldReportTheDnsPortItChose() throws Exception {
        ClientAndServer clientAndServer = startClientAndServer(configuration().dnsEnabled(true).dnsPort(0));
        try {
            clientAndServer.when(dnsRequest(SERVED_NAME)).respondWithDns(new DnsResponse().withAnswerRecords(DnsRecord.aRecord(SERVED_NAME, SERVED_ADDRESS)));

            assertThat(clientAndServer.getDnsPort(), greaterThan(0));
            assertThat("the answer to a query sent to the port reported", addressAnsweredFor(SERVED_NAME, clientAndServer.getDnsPort()), is(SERVED_ADDRESS));
            JsonNode status = status(clientAndServer.getPort());
            assertThat(status.get("dnsPort").asInt(), is(clientAndServer.getDnsPort()));
            assertThat("the TCP ports are unchanged", status.get("ports").get(0).asInt(), is(clientAndServer.getPort()));
        } finally {
            stopQuietly(clientAndServer);
        }
    }

    @Test
    public void shouldReportNoDnsPortWithoutDnsMocking() throws Exception {
        ClientAndServer clientAndServer = startClientAndServer(configuration().dnsEnabled(false));
        try {
            assertThat(clientAndServer.getDnsPort(), is(-1));
            assertThat("left out of the status", status(clientAndServer.getPort()).has("dnsPort"), is(false));
        } finally {
            stopQuietly(clientAndServer);
        }
    }

    private static JsonNode status(int port) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mockserver/status"))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode(), is(200));
        return new ObjectMapper().readTree(response.body());
    }
}
