package org.mockserver.netty.integration.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.RawHttp1Connection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * An HTTP/1.1 request body sent as one-byte chunks passes the connection aggregator's component limit (1,024 at the
 * 256 KiB {@code maxRequestBodySize} used here) hundreds of times; the aggregator merges only what arrived since its
 * last merge. Bodies must still arrive unchanged, one per request on a keep-alive connection and across concurrent
 * connections, and the limit must still answer 413. A raw socket puts each chunk on the wire as written; each body is
 * compared with the request MockServer recorded, since an expectation holding it would itself be over the limit.
 */
public class Http1TinyChunksIntegrationTest {

    private static final int MAX_REQUEST_BODY_SIZE = 256 * 1024;
    private static final int[] ONE_BYTE = {1};
    // 16 one-byte chunks, a 1 KiB chunk, five 10-byte chunks and a 1 KiB chunk: about 11 chunks per KiB
    private static final int[] MIXED = mixed();
    // a hang guard, not a speed limit: the build's leak detector records a stack trace for every chunk read
    private static final long HANG_GUARD_MILLIS = 300_000;

    private MockServer mockServer;
    private MockServerClient mockServerClient;

    @Before
    public void startServer() {
        mockServer = new MockServer(configuration().maxRequestBodySize(MAX_REQUEST_BODY_SIZE), 0);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        mockServerClient
            .when(request().withMethod("POST").withPath("/upload/.*"))
            .respond(response().withStatusCode(200).withBody("received"));
    }

    @After
    public void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldReceiveABodyOfOneByteChunksUnchanged() throws Exception {
        String body = body("one-byte", MAX_REQUEST_BODY_SIZE);
        try (RawHttp1Connection connection = new RawHttp1Connection(mockServer.getLocalPort())) {
            connection.sendChunked("/upload/one_byte", body, ONE_BYTE);
            assertThat(connection.readResponse().status, is(200));
        }
        assertReceived("/upload/one_byte", body);
    }

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldReceiveEachRequestOnAKeepAliveConnectionUnchanged() throws Exception {
        // one aggregator serves every request on the connection, so its merge state must start again at each one
        List<String> bodies = new ArrayList<>();
        try (RawHttp1Connection connection = new RawHttp1Connection(mockServer.getLocalPort())) {
            for (int i = 0; i < 4; i++) {
                String body = body("keep-alive-" + i, 100_000 + i);
                bodies.add(body);
                connection.sendChunked("/upload/keep_alive/" + i, body, i % 2 == 0 ? ONE_BYTE : MIXED);
                assertThat("request " + i, connection.readResponse().status, is(200));
            }
        }
        for (int i = 0; i < bodies.size(); i++) {
            assertReceived("/upload/keep_alive/" + i, bodies.get(i));
        }
    }

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldReceiveBodiesOnConcurrentConnectionsWithoutCrossTalk() throws Exception {
        // merged components and the reads they are made from all come from the pooled allocator, so a buffer freed
        // too early would surface as another connection's bytes in a body
        int connections = 8;
        int requestsPerConnection = 2;
        ExecutorService executor = Executors.newFixedThreadPool(connections);
        try {
            List<Future<List<Integer>>> results = new ArrayList<>();
            for (int c = 0; c < connections; c++) {
                int connectionIndex = c;
                results.add(executor.submit(() -> {
                    List<Integer> statuses = new ArrayList<>();
                    try (RawHttp1Connection connection = new RawHttp1Connection(mockServer.getLocalPort())) {
                        for (int r = 0; r < requestsPerConnection; r++) {
                            connection.sendChunked("/upload/concurrent/" + connectionIndex + "/" + r, concurrentBody(connectionIndex, r), MIXED);
                            statuses.add(connection.readResponse().status);
                        }
                    }
                    return statuses;
                }));
            }
            for (int c = 0; c < connections; c++) {
                assertThat("connection " + c, results.get(c).get(90, TimeUnit.SECONDS), is(List.of(200, 200)));
            }
        } finally {
            executor.shutdownNow();
        }
        for (int c = 0; c < connections; c++) {
            for (int r = 0; r < requestsPerConnection; r++) {
                assertReceived("/upload/concurrent/" + c + "/" + r, concurrentBody(c, r));
            }
        }
    }

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldAcceptABodyAtTheLimitAndAnswer413OneByteOver() throws Exception {
        String atLimit = body("at-limit", MAX_REQUEST_BODY_SIZE);
        try (RawHttp1Connection connection = new RawHttp1Connection(mockServer.getLocalPort())) {
            connection.sendChunked("/upload/at_limit", atLimit, MIXED);
            assertThat(connection.readResponse().status, is(200));
        }
        assertReceived("/upload/at_limit", atLimit);
        try (RawHttp1Connection connection = new RawHttp1Connection(mockServer.getLocalPort())) {
            connection.sendChunked("/upload/over_limit", body("over-limit", MAX_REQUEST_BODY_SIZE + 1), MIXED);
            RawHttp1Connection.Response response = connection.readResponse();
            assertThat(response.head, startsWith("HTTP/1.1 413"));
            assertThat("a chunked body cut off by the limit cannot be resumed", connection.isClosedByServer(), is(true));
        }
        assertThat(mockServerClient.retrieveRecordedRequests(request().withPath("/upload/over_limit")), arrayWithSize(0));
        // the server is still healthy
        try (RawHttp1Connection connection = new RawHttp1Connection(mockServer.getLocalPort())) {
            connection.sendChunked("/upload/after_limit", atLimit, ONE_BYTE);
            assertThat(connection.readResponse().status, is(200));
        }
        assertReceived("/upload/after_limit", atLimit);
    }

    private void assertReceived(String path, String body) {
        HttpRequest[] recorded = mockServerClient.retrieveRecordedRequests(request().withPath(path));
        assertThat(path, recorded, arrayWithSize(1));
        String received = recorded[0].getBodyAsString();
        assertThat(path + " body length", received.length(), is(body.length()));
        assertThat(path + " body unchanged", received.equals(body), is(true));
    }

    private static String concurrentBody(int connection, int request) {
        return body("connection-" + connection + "-request-" + request, MAX_REQUEST_BODY_SIZE - 1_000 - connection);
    }

    /**
     * ASCII letters, offset by the marker so every request's body is different, after the marker itself.
     */
    private static String body(String marker, int length) {
        StringBuilder body = new StringBuilder(length).append(marker);
        int offset = marker.hashCode() & 0xff;
        for (int i = body.length(); i < length; i++) {
            body.append((char) ('a' + (i + offset) % 26));
        }
        return body.substring(0, length);
    }

    private static int[] mixed() {
        int[] pattern = new int[16 + 1 + 5 + 1];
        java.util.Arrays.fill(pattern, 0, 16, 1);
        pattern[16] = 1024;
        java.util.Arrays.fill(pattern, 17, 22, 10);
        pattern[22] = 1024;
        return pattern;
    }
}
