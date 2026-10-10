package org.mockserver.netty.integration.mock;

import org.junit.Test;
import org.mockserver.netty.MockServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The shared scheduler's queue depth and delayed backlog are exported on {@code /mockserver/metrics}, and a
 * scenario re-PUT with a timed transition replaces the queued transition instead of adding one.
 */
public class SchedulerQueueMetricsIntegrationTest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test(timeout = 60_000)
    public void shouldExportSchedulerGaugesAndHoldOneQueuedTransitionPerScenario() throws Exception {
        MockServer mockServer = new MockServer(configuration().metricsEnabled(true), 0);
        try {
            int port = mockServer.getLocalPort();
            for (int i = 0; i < 1_000; i++) {
                HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mockserver/scenario/rePut"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"state\":\"Step" + i + "\",\"transitionAfterMs\":60000,\"nextState\":\"Done\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode(), is(200));
            }

            String metrics = httpClient.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mockserver/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();

            assertThat(gauge(metrics, "mock_server_pending_delayed_tasks"), is(1.0));
            // the one transition, plus at most a few short-lived housekeeping timers
            assertThat(gauge(metrics, "mock_server_scheduler_queued_tasks"), allOf(greaterThanOrEqualTo(1.0), lessThan(10.0)));
            assertThat(metrics, containsString("mock_server_websocket_read_pauses_total"));
            assertThat(mockServer.getScheduler().getQueuedTaskCount(), lessThan(10));
        } finally {
            mockServer.stop();
        }
    }

    private static double gauge(String metrics, String name) {
        for (String line : metrics.split("\n")) {
            if (line.startsWith(name + " ")) {
                return Double.parseDouble(line.substring(name.length() + 1).trim());
            }
        }
        throw new AssertionError(name + " not exported in:\n" + metrics);
    }
}
