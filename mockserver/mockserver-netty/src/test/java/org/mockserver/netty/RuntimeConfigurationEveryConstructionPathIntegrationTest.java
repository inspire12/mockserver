package org.mockserver.netty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.metrics.Metrics;
import org.mockserver.proxyconfiguration.ProxyConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assume.assumeTrue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;

/**
 * A runtime {@code PUT /mockserver/configuration} must take effect on EVERY way a server can be built.
 *
 * <p>The CLI and Docker image start the server with {@code new MockServer(ports)}, which passes no
 * {@link Configuration}. {@code LifeCycle} then built one instance for {@code HttpState} and the event
 * log while {@code MockServer} built a second for the request handlers. The PUT mutated the handlers'
 * copy (so it returned 200 and GET echoed it) while enforcement and capacity resizing read the other:
 * enabling control-plane authentication at runtime left the control plane open. Every check below
 * reads its subject through {@code HttpState}, so it goes red if any construction path splits again.
 */
@RunWith(Parameterized.class)
public class RuntimeConfigurationEveryConstructionPathIntegrationTest {

    private static final String SENSITIVE_CREDENTIAL = "Bearer runtime-config-split-secret";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** A started server, whichever API built it. {@code liveConfiguration} is null where none is exposed. */
    private static final class Started {
        final int port;
        final Configuration liveConfiguration;
        final Runnable stop;

        Started(int port, Configuration liveConfiguration, Runnable stop) {
            this.port = port;
            this.liveConfiguration = liveConfiguration;
            this.stop = stop;
        }

        static Started of(MockServer mockServer) {
            return new Started(mockServer.getLocalPort(), mockServer.getConfiguration(), mockServer::stop);
        }

        static Started of(ClientAndServer clientAndServer) {
            return new Started(clientAndServer.getLocalPort(), null, clientAndServer::stop);
        }
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> constructionPaths() {
        return Arrays.asList(new Object[][]{
            {"MockServer(Integer...) [CLI, Docker, maven plugin]", (Supplier<Started>) () -> Started.of(new MockServer(0))},
            {"MockServer(remotePort, remoteHost, Integer...) [CLI -proxyRemotePort]", (Supplier<Started>) () -> Started.of(new MockServer(unusedPort(), "localhost", 0))},
            {"MockServer(ProxyConfiguration, Integer...)", (Supplier<Started>) () -> Started.of(new MockServer(httpProxy(), 0))},
            {"MockServer(Configuration, Integer...)", (Supplier<Started>) () -> Started.of(new MockServer(configuration(), 0))},
            {"MockServer(null Configuration, List<ProxyConfiguration>, Integer...)", (Supplier<Started>) () -> Started.of(new MockServer((Configuration) null, ImmutableList.<ProxyConfiguration>of(), 0))},
            {"MockServer(Configuration, remotePort, remoteHost, Integer...)", (Supplier<Started>) () -> Started.of(new MockServer(configuration(), unusedPort(), "localhost", 0))},
            {"MockServer(Configuration, ProxyConfiguration, remoteHost, remotePort, Integer...)", (Supplier<Started>) () -> Started.of(new MockServer(configuration(), httpProxy(), "localhost", unusedPort(), 0))},
            {"MockServer(null Configuration, List<ProxyConfiguration>, remoteHost, remotePort, Integer...)", (Supplier<Started>) () -> Started.of(new MockServer((Configuration) null, ImmutableList.<ProxyConfiguration>of(), "localhost", unusedPort(), 0))},
            {"ClientAndServer.startClientAndServer(Integer...) [MockServerRule, MockServerExtension]", (Supplier<Started>) () -> Started.of(ClientAndServer.startClientAndServer(0))},
            {"ClientAndServer.startClientAndServer(Configuration, Integer...) [Spring]", (Supplier<Started>) () -> Started.of(ClientAndServer.startClientAndServer(configuration(), 0))},
            {"ClientAndServer.startClientAndServer(remoteHost, remotePort, Integer...)", (Supplier<Started>) () -> Started.of(ClientAndServer.startClientAndServer("localhost", unusedPort(), 0))},
            {"new ClientAndServer(Integer...)", (Supplier<Started>) () -> Started.of(new ClientAndServer(0))},
        });
    }

    private final Supplier<Started> construction;
    private Started server;

    public RuntimeConfigurationEveryConstructionPathIntegrationTest(String name, Supplier<Started> construction) {
        this.construction = construction;
    }

    @Before
    public void startServer() {
        server = construction.get();
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.stop.run();
        }
    }

    @Test
    public void enablingControlPlaneMutualTlsAtRuntimeMustRejectAnUnauthenticatedControlPlaneCall() throws Exception {
        assertThat("baseline: the control plane is open by default",
            send("PUT", "/mockserver/clear", "").statusCode, is(200));

        Response put = send("PUT", "/mockserver/configuration", "{\"controlPlaneTLSMutualAuthenticationRequired\": true}");
        assertThat(put.body, put.statusCode, is(200));

        assertThat("PUT /mockserver/configuration reported control-plane mTLS as enabled, so a control-plane "
                + "call without a client certificate must now be rejected",
            send("PUT", "/mockserver/clear", "").statusCode, is(401));
        assertThat("an expectation must not be creatable without a client certificate either",
            send("PUT", "/mockserver/expectation", expectation("/after-lock")).statusCode, is(401));
    }

    @Test
    public void shrinkingTheEventLogAtRuntimeMustMoveTheGaugesAndEvict() throws Exception {
        createExpectation(expectation("/log-probe"));
        String body = repeat('x', 500);
        for (int i = 0; i < 40; i++) {
            assertThat(send("POST", "/log-probe", body).statusCode, is(200));
        }
        assertThat("baseline: all 40 probes are retained under the startup event-log limits",
            awaitRecordedRequestCount(count -> count >= 40), greaterThanOrEqualTo(40));
        assertThat("baseline: the retained-entries cap gauge is above the value about to be applied",
            Metrics.getEventLogRingStats().maxRetainedEntries > 10, is(true));

        Response put = send("PUT", "/mockserver/configuration", "{\"maxEventLogSizeInBytes\": 5000, \"maxLogEntries\": 10}");
        assertThat(put.body, put.statusCode, is(200));

        Metrics.RingStats stats = Metrics.getEventLogRingStats();
        assertThat("mock_server_event_log_max_retained_entries must report the runtime maxLogEntries",
            stats.maxRetainedEntries, is(10L));
        assertThat("mock_server_event_log_max_retained_bytes must report the runtime maxEventLogSizeInBytes",
            stats.maxRetainedBytes, is(5000L));
        assertThat("mock_server_event_log_max_in_flight_bytes must report the runtime maxEventLogSizeInBytes",
            stats.maxInFlightBytes, is(5000L));
        assertThat("the event log must have evicted down to the runtime maxLogEntries",
            stats.retainedEntries, lessThanOrEqualTo(10L));
        assertThat("the event log must have evicted down to the runtime maxEventLogSizeInBytes",
            stats.retainedBytes, lessThanOrEqualTo(5000L));
        assertThat("retrieval must see the evicted log, not the startup-sized one",
            recordedRequestCount(), lessThanOrEqualTo(10));
    }

    @Test
    public void enablingSecretRedactionAtRuntimeMustMaskCredentialsInTheRetrievedLog() throws Exception {
        createExpectation(expectation("/redaction-probe"));

        assertThat(send("POST", "/redaction-probe", "{}", SENSITIVE_CREDENTIAL).statusCode, is(200));
        assertThat("baseline: with redactSecretsInLog off the credential is retrievable verbatim",
            retrieveRecordedRequests(), containsString(SENSITIVE_CREDENTIAL));

        Response put = send("PUT", "/mockserver/configuration", "{\"redactSecretsInLog\": true}");
        assertThat(put.body, put.statusCode, is(200));

        String retrieved = retrieveRecordedRequests();
        assertThat("redactSecretsInLog=true set at runtime must mask the recorded credential",
            retrieved, not(containsString(SENSITIVE_CREDENTIAL)));
        assertThat(retrieved, containsString("***REDACTED***"));
    }

    @Test
    public void theConfigurationTheServerExposesMustBeTheOneTheControlPlaneServes() throws Exception {
        assumeTrue("this construction API does not expose the server's Configuration",
            server.liveConfiguration != null);

        server.liveConfiguration.maxExpectations(4321);

        JsonNode served = OBJECT_MAPPER.readTree(send("GET", "/mockserver/configuration", "").body);
        assertThat("GET /mockserver/configuration must serve the same instance HttpState runs on",
            served.path("maxExpectations").asInt(), is(4321));
    }

    // ---------------------------------------------------------------------------------------------

    private static ProxyConfiguration httpProxy() {
        return proxyConfiguration(ProxyConfiguration.Type.HTTP, "127.0.0.1:" + unusedPort());
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String expectation(String path) {
        return "{\"httpRequest\": {\"path\": \"" + path + "\"}, \"httpResponse\": {\"statusCode\": 200, \"body\": \"ok\"}}";
    }

    private void createExpectation(String expectationJson) throws IOException {
        Response response = send("PUT", "/mockserver/expectation", expectationJson);
        assertThat(response.body, response.statusCode, is(201));
    }

    private String retrieveRecordedRequests() throws IOException {
        return send("PUT", "/mockserver/retrieve?type=REQUESTS&format=JSON", "").body;
    }

    private int recordedRequestCount() throws IOException {
        JsonNode requests = OBJECT_MAPPER.readTree(retrieveRecordedRequests());
        return requests.isArray() ? requests.size() : 0;
    }

    /** The event log is fed asynchronously through the ring, so wait for the probes to be processed. */
    private int awaitRecordedRequestCount(IntPredicate condition) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        int count = recordedRequestCount();
        while (!condition.test(count) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            count = recordedRequestCount();
        }
        return count;
    }

    private Response send(String method, String path, String body) throws IOException {
        return send(method, path, body, null);
    }

    private Response send(String method, String path, String body, String authorization) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + server.port + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            if (authorization != null) {
                connection.setRequestProperty("Authorization", authorization);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 0) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int statusCode = connection.getResponseCode();
            InputStream stream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(statusCode, stream == null ? "" : new String(drain(stream), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static final class Response {
        final int statusCode;
        final String body;

        Response(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }
}
