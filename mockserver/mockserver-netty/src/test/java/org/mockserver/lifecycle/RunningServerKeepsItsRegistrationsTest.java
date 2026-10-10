package org.mockserver.lifecycle;

import org.junit.Test;
import org.mockserver.load.LoadProfile;
import org.mockserver.load.LoadScenario;
import org.mockserver.load.LoadStep;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.action.http.LoadScenarioOrchestrator;
import org.mockserver.mock.drift.DriftAlertNotifier;
import org.mockserver.mock.drift.DriftRecord;
import org.mockserver.mock.drift.DriftType;
import org.mockserver.mock.drift.SemanticSeverity;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.model.RequestDefinition;
import org.mockserver.netty.MockServer;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Several servers can run in one JVM. When a newer one stops, an older one still running must go on using
 * the process-wide places it registered itself in: the gauges read its figures, and load scenarios and drift
 * alerts are sent with its client, without it first having to accept a new connection.
 */
public class RunningServerKeepsItsRegistrationsTest {

    private static final long DEADLINE_SECONDS = 30;

    @Test
    public void shouldReadTheRunningServersLiveStateOnceANewerServerStops() throws Exception {
        MockServer older = new MockServer(0);
        try {
            older.httpState.getRequestMatchers().add(new Expectation(request("/older-one")).thenRespond(response()), MockServerMatcherNotifier.Cause.API);
            older.httpState.getRequestMatchers().add(new Expectation(request("/older-two")).thenRespond(response()), MockServerMatcherNotifier.Cause.API);
            assertThat(statusOfARequestTo(older.getLocalPort(), "/older-one"), is(200));

            startAddOneExpectationAndStop();

            assertThat(Metrics.getActiveExpectationCountByType(), is(Collections.singletonMap("RESPONSE", 2)));
            assertThat(Metrics.getExpectationStoreStats().totalBytes, is(older.httpState.getRequestMatchers().getExpectationBytes()));
            assertThat(Metrics.getExpectationStoreStats().totalBytes, is(not(0L)));
            awaitEventLogGaugeReadsTheEventLogOf(older);
        } finally {
            older.stop();
        }
        assertThat(Metrics.getActiveExpectationCountByType(), is(Collections.emptyMap()));
    }

    @Test
    public void shouldSendTheRunningServersLoadScenariosAndDriftAlertsOnceANewerServerStops() throws Exception {
        MockServer older = new MockServer(0);
        try {
            older.httpState.getRequestMatchers().add(new Expectation(request("/load-target")).thenRespond(response()), MockServerMatcherNotifier.Cause.API);
            older.httpState.getRequestMatchers().add(new Expectation(request("/drift-hook")).thenRespond(response()), MockServerMatcherNotifier.Cause.API);
            // a connection to the older server installs its request sender; no connection to it follows
            assertThat(statusOfARequestTo(older.getLocalPort(), "/load-target"), is(200));

            MockServer newer = new MockServer(0);
            try {
                assertThat(statusOfARequestTo(newer.getLocalPort(), "/newer"), is(404));
            } finally {
                newer.stop();
            }

            DriftAlertNotifier.getInstance().configure(true, "http://127.0.0.1:" + older.getLocalPort() + "/drift-hook", SemanticSeverity.BREAKING, 0);
            DriftAlertNotifier.getInstance().onDriftStored(new DriftRecord()
                .setExpectationId("running-server")
                .setDriftType(DriftType.STATUS)
                .setField("statusCode")
                .setExpectedValue("200")
                .setActualValue("500")
                .setConfidence(1.0)
                .setEpochTimeMs(System.currentTimeMillis()));
            String loadScenarioError = LoadScenarioOrchestrator.getInstance().start(new LoadScenario()
                .withName("running-server")
                .withProfile(LoadProfile.constant(1, 1_000L))
                .withSteps(new LoadStep().withRequest(request().withPath("/load-target").withHeader("Host", "127.0.0.1:" + older.getLocalPort()))), null);

            assertThat(loadScenarioError, is((String) null));
            awaitReceivedBy(older, "/drift-hook");
            awaitReceivedBy(older, "/load-target", 2);
        } finally {
            LoadScenarioOrchestrator.getInstance().stop("running-server");
            DriftAlertNotifier.getInstance().configure(false, "", SemanticSeverity.BREAKING, 60_000);
            DriftAlertNotifier.getInstance().reset();
            older.stop();
        }
    }

    private static void startAddOneExpectationAndStop() throws Exception {
        MockServer newer = new MockServer(0);
        try {
            newer.httpState.getRequestMatchers().add(new Expectation(request("/newer")).thenRespond(response()), MockServerMatcherNotifier.Cause.API);
            assertThat(statusOfARequestTo(newer.getLocalPort(), "/newer"), is(200));
            assertThat(Metrics.getActiveExpectationCountByType(), is(Collections.singletonMap("RESPONSE", 1)));
        } finally {
            newer.stop();
        }
    }

    // the event log takes its entries asynchronously, so its count may still move while it is read
    private static void awaitEventLogGaugeReadsTheEventLogOf(MockServer server) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        long gauge = Metrics.getEventLogRingStats().retainedEntries;
        long retained = server.httpState.getMockServerLog().getRetainedEntryCount();
        while ((gauge != retained || retained == 0) && System.nanoTime() < deadline) {
            Thread.sleep(50);
            gauge = Metrics.getEventLogRingStats().retainedEntries;
            retained = server.httpState.getMockServerLog().getRetainedEntryCount();
        }
        assertThat(retained, is(not(0L)));
        assertThat("the event-log gauge reads the event log of the server still running", gauge, is(retained));
    }

    private static void awaitReceivedBy(MockServer server, String path) throws Exception {
        awaitReceivedBy(server, path, 1);
    }

    private static void awaitReceivedBy(MockServer server, String path, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        int received = 0;
        while (System.nanoTime() < deadline) {
            CompletableFuture<List<RequestDefinition>> requests = new CompletableFuture<>();
            server.httpState.getMockServerLog().retrieveRequests(request(path), requests::complete);
            received = requests.get(DEADLINE_SECONDS, TimeUnit.SECONDS).size();
            if (received >= count) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat("requests to " + path + " received by the server still running within " + DEADLINE_SECONDS + "s", received, is(count));
    }

    private static int statusOfARequestTo(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }
}
