package org.mockserver.lifecycle;

import org.junit.After;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpSseResponse.sseResponse;
import static org.mockserver.model.SseEvent.sseEvent;

/**
 * An SSE stream with a request pipelined behind it on the same HTTP/1.1 connection: however that later exchange
 * ends, {@code stop()} still waits for the stream to be written whole. Only the stream's own end may release it.
 */
public class StopDrainPipelinedDirectResponseIntegrationTest {

    private static final long DRAIN_MILLIS = 30_000L;
    // long enough to answer the pipelined request and start stopping while the stream is still open
    private static final long LAST_EVENT_DELAY_MILLIS = 3_000L;

    private MockServer mockServer;

    @After
    public void stopServer() {
        if (mockServer != null && mockServer.isRunning()) {
            mockServer.stop();
        }
    }

    @Test
    public void stopWaitsForSseStreamWhenAPipelinedRequestBehindItIsAnswered() throws Exception {
        int port = startServer();
        MockServerClient client = new MockServerClient("localhost", port);
        client.when(request().withPath("/plain")).respond(response("plain"));

        assertStopWaitsForStreamWithPipelinedRequest(client, port, "/plain");
    }

    @Test
    public void stopWaitsForSseStreamWhenAPipelinedRequestBehindItEndsWithNoAnswer() throws Exception {
        int port = startServer();
        MockServerClient client = new MockServerClient("localhost", port);
        client.when(request().withPath("/nothing")).error(error());

        assertStopWaitsForStreamWithPipelinedRequest(client, port, "/nothing");
    }

    private int startServer() {
        mockServer = new MockServer(configuration().stopDrainMillis(DRAIN_MILLIS), 0);
        return mockServer.getLocalPort();
    }

    private void assertStopWaitsForStreamWithPipelinedRequest(MockServerClient client, int port, String pipelinedPath) throws Exception {
        client.when(request().withPath("/sse")).respondWithSse(sseResponse().withEvents(
            sseEvent().withData("first"),
            sseEvent().withData("last").withDelay(TimeUnit.MILLISECONDS, LAST_EVENT_DELAY_MILLIS)));

        try (Socket socket = connect(port)) {
            send(socket, "GET /sse HTTP/1.1\r\nHost: localhost:" + port + "\r\nAccept: text/event-stream\r\n\r\n");
            assertThat(readUntil(socket.getInputStream(), "data: first"), startsWith("HTTP/1.1 200 "));
            // sent before the stream has ended, so it is pipelined behind it
            send(socket, "GET " + pipelinedPath + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n");
            awaitPipelinedExchangeEnded(client, pipelinedPath);
            assertThat("only the stream is still in flight", mockServer.getRequestsInFlight(), is(1));

            long stopStart = System.currentTimeMillis();
            CompletableFuture<String> stopped = mockServer.stopAsync();
            String rest = readUntil(socket.getInputStream(), "0\r\n\r\n");
            stopped.get(DRAIN_MILLIS, TimeUnit.MILLISECONDS);
            long stopMillis = System.currentTimeMillis() - stopStart;

            assertThat("the stream is written whole before the server stops", rest, containsString("data: last"));
            assertThat("stop did not wait out the drain budget, took " + stopMillis + "ms", stopMillis, is(lessThan(DRAIN_MILLIS / 2)));
        }
    }

    /**
     * Waits until the pipelined request has been received and its exchange has left the in-flight count, leaving
     * only the stream's.
     */
    private void awaitPipelinedExchangeEnded(MockServerClient client, String path) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (client.retrieveRecordedRequests(request().withPath(path)).length == 0 || mockServer.getRequestsInFlight() > 1) {
            assertThat("the pipelined request is received and ends", System.currentTimeMillis() < deadline, is(true));
            Thread.sleep(10);
        }
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(15_000);
        return socket;
    }

    private static void send(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static String readUntil(InputStream inputStream, String terminator) throws IOException {
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        while (!read.toString(StandardCharsets.ISO_8859_1).endsWith(terminator)) {
            int next = inputStream.read();
            if (next == -1) {
                throw new IOException("connection closed before \"" + terminator + "\", having read: " + read.toString(StandardCharsets.ISO_8859_1));
            }
            read.write(next);
        }
        return read.toString(StandardCharsets.ISO_8859_1);
    }
}
