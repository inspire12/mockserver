package org.mockserver.lifecycle;

import org.junit.After;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
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
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.client.LlmMockBuilder.llmMock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpSseResponse.sseResponse;
import static org.mockserver.model.HttpWebSocketResponse.webSocketResponse;
import static org.mockserver.model.Provider.OPENAI;
import static org.mockserver.model.SseEvent.sseEvent;
import static org.mockserver.model.WebSocketMessage.webSocketMessage;

/**
 * Responses that action handlers write straight to the channel (SSE, streamed LLM, WebSocket, raw-bytes
 * errors) and the CONNECT replies must leave the graceful-shutdown drain once they have ended, not when
 * their keep-alive connection closes: otherwise {@code stop()} waits out the whole {@code stopDrainMillis}.
 * Each connection is left open across {@code stop()}, so only the end of the response can release it.
 */
public class StopDrainDirectResponseIntegrationTest {

    private static final long DRAIN_MILLIS = 30_000L;
    // far below the drain budget, and loose enough for a slow CI agent to stop an idle server
    private static final long PROMPT_STOP_MILLIS = 5_000L;

    private MockServer mockServer;

    @After
    public void stopServer() {
        if (mockServer != null && mockServer.isRunning()) {
            mockServer.stop();
        }
    }

    private int startServer(Configuration configuration) {
        mockServer = new MockServer(configuration.stopDrainMillis(DRAIN_MILLIS), 0);
        return mockServer.getLocalPort();
    }

    @Test
    public void stopIsPromptAfterSseResponseOnKeepAliveConnection() throws Exception {
        int port = startServer(configuration());
        new MockServerClient("localhost", port).when(request().withPath("/sse"))
            .respondWithSse(sseResponse().withEvents(sseEvent().withData("first"), sseEvent().withData("last")));

        try (Socket socket = connect(port)) {
            send(socket, "GET /sse HTTP/1.1\r\nHost: localhost:" + port + "\r\nAccept: text/event-stream\r\n\r\n");
            String stream = readUntil(socket.getInputStream(), "0\r\n\r\n");
            assertThat(stream, startsWith("HTTP/1.1 200 "));
            assertThat(stream, containsString("connection: keep-alive"));
            assertThat(stream, containsString("data: last"));

            assertStopIsPrompt();
        }
    }

    @Test
    public void stopIsPromptAfterStreamedLlmResponseOnKeepAliveConnection() throws Exception {
        int port = startServer(configuration());
        llmMock("/v1/chat/completions")
            .withProvider(OPENAI)
            .withModel("gpt-4o")
            .respondingWith(completion().withText("streamed").withStreaming(true))
            .applyTo(new MockServerClient("localhost", port));

        try (Socket socket = connect(port)) {
            String body = "{\"model\":\"gpt-4o\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
            send(socket, "POST /v1/chat/completions HTTP/1.1\r\nHost: localhost:" + port
                + "\r\nContent-Type: application/json\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body);
            String stream = readUntil(socket.getInputStream(), "0\r\n\r\n");
            assertThat(stream, startsWith("HTTP/1.1 200 "));
            assertThat(stream, containsString("text/event-stream"));

            assertStopIsPrompt();
        }
    }

    @Test
    public void stopIsPromptWithOpenMockedWebSocket() throws Exception {
        int port = startServer(configuration());
        new MockServerClient("localhost", port).when(request().withPath("/ws"))
            .respondWithWebSocket(webSocketResponse().withMessage(webSocketMessage("hello")).withCloseConnection(false));

        try (Socket socket = connect(port)) {
            send(socket, "GET /ws HTTP/1.1\r\nHost: localhost:" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n");
            assertThat(readUntil(socket.getInputStream(), "\r\n\r\n"), startsWith("HTTP/1.1 101 "));
            assertThat(readUntil(socket.getInputStream(), "hello"), endsWith("hello"));

            assertStopIsPrompt();
        }
    }

    @Test
    public void stopIsPromptAfterRawBytesErrorOnKeepAliveConnection() throws Exception {
        int port = startServer(configuration());
        new MockServerClient("localhost", port).when(request().withPath("/raw"))
            .error(error().withResponseBytes("HTTP/1.1 200 OK\r\ncontent-length: 3\r\n\r\nraw".getBytes(StandardCharsets.US_ASCII)));

        try (Socket socket = connect(port)) {
            send(socket, "GET /raw HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n");
            assertThat(readUntil(socket.getInputStream(), "\r\n\r\nraw"), startsWith("HTTP/1.1 200 OK"));

            assertStopIsPrompt();
        }
    }

    @Test
    public void stopIsPromptAfterErrorThatAnswersNothingOnOpenConnection() throws Exception {
        int port = startServer(configuration());
        MockServerClient client = new MockServerClient("localhost", port);
        client.when(request().withPath("/nothing")).error(error());

        try (Socket socket = connect(port)) {
            send(socket, "GET /nothing HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n");
            // never answered, and the connection stays open
            awaitReceived(client, "/nothing");

            assertStopIsPrompt();
        }
    }

    @Test
    public void stopIsPromptAfterProxyAuthenticationRefusedOnKeepAliveConnection() throws Exception {
        int port = startServer(configuration().proxyAuthenticationUsername("user").proxyAuthenticationPassword("password"));

        try (Socket socket = connect(port)) {
            send(socket, "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n");
            assertThat(readUntil(socket.getInputStream(), "\r\n\r\n"), startsWith("HTTP/1.1 407 "));

            assertStopIsPrompt();
        }
    }

    @Test
    public void stopIsPromptWithOpenConnectTunnel() throws Exception {
        int port = startServer(configuration());

        try (Socket socket = connect(port)) {
            send(socket, "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n");
            assertThat(readUntil(socket.getInputStream(), "\r\n\r\n"), startsWith("HTTP/1.1 200 "));

            assertStopIsPrompt();
        }
    }

    @Test
    public void stopWaitsForSseResponseStillBeingWritten() throws Exception {
        int port = startServer(configuration());
        long lastEventDelayMillis = 1_500L;
        new MockServerClient("localhost", port).when(request().withPath("/sse"))
            .respondWithSse(sseResponse().withEvents(
                sseEvent().withData("first"),
                sseEvent().withData("last").withDelay(TimeUnit.MILLISECONDS, lastEventDelayMillis)));

        try (Socket socket = connect(port)) {
            send(socket, "GET /sse HTTP/1.1\r\nHost: localhost:" + port + "\r\nAccept: text/event-stream\r\n\r\n");
            assertThat(readUntil(socket.getInputStream(), "data: first"), startsWith("HTTP/1.1 200 "));

            long stopStart = System.currentTimeMillis();
            CompletableFuture<String> stopped = mockServer.stopAsync();
            String rest = readUntil(socket.getInputStream(), "0\r\n\r\n");
            stopped.get(DRAIN_MILLIS, TimeUnit.MILLISECONDS);
            long stopMillis = System.currentTimeMillis() - stopStart;

            assertThat("the stream is written whole before the server stops", rest, containsString("data: last"));
            assertThat("stop waited for the last event, took " + stopMillis + "ms", stopMillis, is(greaterThanOrEqualTo(lastEventDelayMillis / 2)));
            assertThat("stop did not wait out the drain budget, took " + stopMillis + "ms", stopMillis, is(lessThan(DRAIN_MILLIS / 2)));
        }
    }

    private void assertStopIsPrompt() {
        long start = System.currentTimeMillis();
        mockServer.stop();
        long stopMillis = System.currentTimeMillis() - start;
        assertThat("stop must not wait for a response that has already ended, took " + stopMillis + "ms",
            stopMillis, is(lessThan(PROMPT_STOP_MILLIS)));
        assertThat(mockServer.getRequestsInFlight(), is(0));
    }

    private static void awaitReceived(MockServerClient client, String path) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (client.retrieveRecordedRequests(request().withPath(path)).length == 0) {
            assertThat("the request reaches MockServer", System.currentTimeMillis() < deadline, is(true));
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
