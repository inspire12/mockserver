package org.mockserver.netty.telemetry;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.llm.client.LlmProviderSniffer;
import org.mockserver.mock.action.http.LlmCostBudgetMonitor;
import org.mockserver.netty.MockServer;
import org.mockserver.telemetry.GenAiSpanExporter;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;

/**
 * End-to-end: a real forwarding {@link MockServer} relays an LLM response that a stub upstream
 * streams, and the call is costed. The stub is a raw socket so the test decides where the stream
 * is split, whether it is compressed, and whether it is cut off.
 *
 * <p>The upstream is on localhost, so the provider comes from {@code mockserver.llmProvider} and
 * the LLM-looking request path, as for any OpenAI-compatible endpoint that is not a known host.
 *
 * <p>Mutates process-wide state (the GenAI tracer, the cost budget monitor and configuration
 * properties), restored in {@link #tearDown()}; mockserver-netty surefire runs classes one at a
 * time in a single fork.
 */
public class ForwardPathStreamedLlmCostBudgetTest {

    private static final String CHUNK_PREFIX = "data: {\"id\":\"chatcmpl-AJ2tUOPcPOlvJmFjy3nVbuLxKs1Yu\",\"object\":\"chat.completion.chunk\",\"created\":1728933352,\"model\":\"gpt-4o-2024-08-06\",\"service_tier\":\"default\",\"system_fingerprint\":\"fp_6b68a8204b\",";
    private static final String CONTENT_CHUNKS = ""
        + CHUNK_PREFIX + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"refusal\":null},\"logprobs\":null,\"finish_reason\":null}],\"usage\":null}\n\n"
        + CHUNK_PREFIX + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"logprobs\":null,\"finish_reason\":null}],\"usage\":null}\n\n"
        + CHUNK_PREFIX + "\"choices\":[{\"index\":0,\"delta\":{},\"logprobs\":null,\"finish_reason\":\"stop\"}],\"usage\":null}\n\n";
    private static final String USAGE_CHUNK = CHUNK_PREFIX
        + "\"choices\":[],\"usage\":{\"prompt_tokens\":19,\"completion_tokens\":10,\"total_tokens\":29,\"prompt_tokens_details\":{\"cached_tokens\":0,\"audio_tokens\":0},\"completion_tokens_details\":{\"reasoning_tokens\":0,\"audio_tokens\":0,\"accepted_prediction_tokens\":0,\"rejected_prediction_tokens\":0}}}\n\n";
    private static final String DONE = "data: [DONE]\n\n";
    /** OpenAI Chat Completions with {@code stream_options.include_usage}: 19 prompt and 10 completion tokens. */
    private static final String OPENAI_STREAM_WITH_USAGE = CONTENT_CHUNKS + USAGE_CHUNK + DONE;
    /** The same stream as OpenAI sends it without {@code stream_options.include_usage}. */
    private static final String OPENAI_STREAM_WITHOUT_USAGE = (CONTENT_CHUNKS + DONE).replace(",\"usage\":null", "");
    /** An Anthropic Messages stream that stops after {@code message_start}: 25 input tokens, 1 output token so far. */
    private static final String ANTHROPIC_STREAM_CUT_OFF = ""
        + "event: message_start\n"
        + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1nZdL29xx5MUA1yADyHTEsnR8uuvGzszyY\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-4-5-20250929\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}\n\n"
        + "event: content_block_start\n"
        + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
        + "event: content_block_delta\n"
        + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n";
    /** 19 prompt tokens at $2.50 and 10 completion tokens at $10 per million, for gpt-4o. */
    private static final double OPENAI_STREAM_COST_USD = 0.0001475;
    private static final double USD_TOLERANCE = 0.000001;
    private static final long SETTLE_MILLIS = 300;
    private static final String REQUEST_BODY = "{\"model\":\"gpt-4o\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";

    private GenAiSpanExporter exporter;
    private InMemorySpanExporter spans;
    private StubUpstream upstream;
    private MockServer forwardServer;
    private String previousLlmProvider;
    private double previousBudget;
    private Level previousLogLevel;

    @Before
    public void setUp() {
        previousLlmProvider = ConfigurationProperties.llmProvider();
        previousBudget = ConfigurationProperties.llmCostBudgetUsd();
        previousLogLevel = ConfigurationProperties.logLevel();
        // the build runs tests at ERROR; the usage warnings asserted here are logged at WARN
        ConfigurationProperties.logLevel("WARN");
        ConfigurationProperties.llmProvider("OPENAI");
        LlmCostBudgetMonitor.getInstance().reset();
        spans = InMemorySpanExporter.create();
        exporter = GenAiSpanExporter.startWithProcessor(SimpleSpanProcessor.create(spans));
    }

    @After
    public void tearDown() throws IOException {
        exporter.stop();
        if (forwardServer != null) {
            forwardServer.stop();
        }
        if (upstream != null) {
            upstream.close();
        }
        ConfigurationProperties.llmProvider(previousLlmProvider == null ? "" : previousLlmProvider);
        ConfigurationProperties.llmCostBudgetUsd(previousBudget);
        ConfigurationProperties.logLevel(previousLogLevel == null ? "INFO" : previousLogLevel.name());
        LlmCostBudgetMonitor.getInstance().reset();
        LlmProviderSniffer.resetConfiguration();
    }

    @Test
    public void shouldTripTheCostBudgetAfterAStreamedForwardWhoseUsageIsSplitAcrossChunks() throws Exception {
        ConfigurationProperties.llmCostBudgetUsd(0.0001);
        int split = OPENAI_STREAM_WITH_USAGE.indexOf("\"completion_tokens\":10") + 8;
        startForwarding(StubUpstream.streaming(false,
            OPENAI_STREAM_WITH_USAGE.substring(0, split), OPENAI_STREAM_WITH_USAGE.substring(split)));

        Exchange first = post("/v1/chat/completions");

        assertThat(first.status, is(200));
        assertThat("the client receives the upstream's stream unchanged", first.body, is(OPENAI_STREAM_WITH_USAGE));
        SpanData span = awaitSingleGenAiSpan();
        assertThat(span.getAttributes().get(stringKey("gen_ai.request.model")), is("gpt-4o-2024-08-06"));
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.input_tokens")), is(19L));
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.output_tokens")), is(10L));
        assertThat(awaitRecordedCostUsd(), closeTo(OPENAI_STREAM_COST_USD, USD_TOLERANCE));

        Exchange second = post("/v1/chat/completions");

        assertThat(second.status, is(429));
        assertThat(second.body, containsString("cost_budget_exceeded"));
        assertThat("the blocked call never reaches the upstream", upstream.requests(), is(1));
    }

    @Test
    public void shouldCountAStreamTheUpstreamCompressed() throws Exception {
        startForwarding(StubUpstream.streaming(true, OPENAI_STREAM_WITH_USAGE));

        Exchange exchange = post("/v1/chat/completions");

        assertThat(exchange.status, is(200));
        assertThat(exchange.body, is(OPENAI_STREAM_WITH_USAGE));
        SpanData span = awaitSingleGenAiSpan();
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.input_tokens")), is(19L));
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.output_tokens")), is(10L));
        assertThat(awaitRecordedCostUsd(), closeTo(OPENAI_STREAM_COST_USD, USD_TOLERANCE));
    }

    @Test
    public void shouldCountUsageThatArrivesAfterMoreOfTheStreamThanIsCapturedForTheLog() throws Exception {
        StringBuilder longStream = new StringBuilder();
        String contentChunk = CHUNK_PREFIX + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\" and more of the answer\"},\"logprobs\":null,\"finish_reason\":null}],\"usage\":null}\n\n";
        while (longStream.length() < 2 * ConfigurationProperties.maxStreamingCaptureBytes()) {
            longStream.append(contentChunk);
        }
        String stream = longStream + USAGE_CHUNK + DONE;
        startForwarding(StubUpstream.streaming(false, stream));

        Exchange exchange = post("/v1/chat/completions");

        assertThat(exchange.body.length(), is(stream.length()));
        SpanData span = awaitSingleGenAiSpan();
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.input_tokens")), is(19L));
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.output_tokens")), is(10L));
    }

    @Test
    public void shouldCountNothingAndSayWhyWhenTheStreamCarriesNoUsage() throws Exception {
        ConfigurationProperties.llmCostBudgetUsd(0.0001);
        startForwarding(StubUpstream.streaming(false, OPENAI_STREAM_WITHOUT_USAGE));

        Exchange exchange = post("/v1/chat/completions");

        assertThat(exchange.body, is(OPENAI_STREAM_WITHOUT_USAGE));
        SpanData span = awaitSingleGenAiSpan();
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.input_tokens")), is(nullValue()));
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.output_tokens")), is(nullValue()));
        Thread.sleep(SETTLE_MILLIS);
        assertThat(LlmCostBudgetMonitor.getInstance().getCumulativeCostUsd(), is(0.0));
        String log = new MockServerClient("localhost", forwardServer.getLocalPort()).retrieveLogMessages(null);
        assertThat(log, containsString("no token usage in the streamed"));
        assertThat(log, containsString("stream_options.include_usage"));
        assertThat("an uncounted call cannot trip the budget", post("/v1/chat/completions").status, is(200));
    }

    @Test
    public void shouldCountWhatWasReportedBeforeTheUpstreamCutTheStreamOff() throws Exception {
        ConfigurationProperties.llmProvider("ANTHROPIC");
        ConfigurationProperties.llmCostBudgetUsd(1.0);
        startForwarding(StubUpstream.cutOffAfter(ANTHROPIC_STREAM_CUT_OFF));

        Exchange exchange = post("/v1/messages");

        assertThat("what the upstream sent before it closed is still relayed", exchange.body, containsString("text_delta"));
        SpanData span = awaitSingleGenAiSpan();
        assertThat(span.getAttributes().get(stringKey("gen_ai.system")), is("anthropic"));
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.input_tokens")), is(25L));
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.output_tokens")), is(1L));
        assertThat(awaitRecordedCostUsd(), greaterThan(0.0));
        String log = new MockServerClient("localhost", forwardServer.getLocalPort()).retrieveLogMessages(null);
        assertThat(log, containsString("ended before its final usage event"));
    }

    @Test
    public void shouldNotCountAnEventStreamForwardedOnAPathThatIsNotAnLlmEndpoint() throws Exception {
        startForwarding(StubUpstream.streaming(false, OPENAI_STREAM_WITH_USAGE));

        Exchange notLlm = post("/notifications/events");
        Exchange llm = post("/v1/chat/completions");

        assertThat(notLlm.body, is(OPENAI_STREAM_WITH_USAGE));
        assertThat(llm.body, is(OPENAI_STREAM_WITH_USAGE));
        // one span and one call's cost: the LLM call's, which completed after the other stream
        assertThat(awaitRecordedCostUsd(), closeTo(OPENAI_STREAM_COST_USD, USD_TOLERANCE));
        Thread.sleep(SETTLE_MILLIS);
        awaitSingleGenAiSpan();
        assertThat(LlmCostBudgetMonitor.getInstance().getCumulativeCostUsd(), closeTo(OPENAI_STREAM_COST_USD, USD_TOLERANCE));
    }

    @Test
    public void shouldCountAStreamRelayedByTheUnmatchedProxyPath() throws Exception {
        ConfigurationProperties.llmCostBudgetUsd(0.0001);
        upstream = StubUpstream.streaming(false, OPENAI_STREAM_WITH_USAGE);
        // no expectation: the client uses MockServer as its HTTP proxy, as a coding CLI does
        forwardServer = new MockServer();

        Exchange first = postThroughProxy("/v1/chat/completions");

        assertThat(first.status, is(200));
        assertThat(first.body, is(OPENAI_STREAM_WITH_USAGE));
        SpanData span = awaitSingleGenAiSpan();
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.input_tokens")), is(19L));
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.output_tokens")), is(10L));
        assertThat(awaitRecordedCostUsd(), closeTo(OPENAI_STREAM_COST_USD, USD_TOLERANCE));
        assertThat(postThroughProxy("/v1/chat/completions").status, is(429));
    }

    @Test
    public void shouldCountUsagePastTheCaptureLimitOnTheUnmatchedProxyPath() throws Exception {
        StringBuilder longStream = new StringBuilder();
        String contentChunk = CHUNK_PREFIX + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\" and more of the answer\"},\"logprobs\":null,\"finish_reason\":null}],\"usage\":null}\n\n";
        while (longStream.length() < 2 * ConfigurationProperties.maxStreamingCaptureBytes()) {
            longStream.append(contentChunk);
        }
        String stream = longStream + USAGE_CHUNK + DONE;
        upstream = StubUpstream.streaming(false, stream);
        forwardServer = new MockServer();

        Exchange exchange = postThroughProxy("/v1/chat/completions");

        assertThat(exchange.body.length(), is(stream.length()));
        SpanData span = awaitSingleGenAiSpan();
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.input_tokens")), is(19L));
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.output_tokens")), is(10L));
    }

    private void startForwarding(StubUpstream stub) {
        upstream = stub;
        forwardServer = new MockServer();
        new MockServerClient("localhost", forwardServer.getLocalPort())
            .when(request().withMethod("POST"))
            .forward(forward().withHost("127.0.0.1").withPort(upstream.port()));
    }

    private Exchange post(String path) throws Exception {
        return exchange((HttpURLConnection) new URL("http://localhost:" + forwardServer.getLocalPort() + path).openConnection());
    }

    private Exchange postThroughProxy(String path) throws Exception {
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", forwardServer.getLocalPort()));
        return exchange((HttpURLConnection) new URL("http://127.0.0.1:" + upstream.port() + path).openConnection(proxy));
    }

    private static Exchange exchange(HttpURLConnection connection) throws Exception {
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(REQUEST_BODY.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                byte[] buffer = new byte[8192];
                for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
                    body.write(buffer, 0, read);
                }
            } catch (IOException cutOff) {
                // a stream the upstream cut off ends the client's response early too
            }
            return new Exchange(status, body.toString(StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Poll for up to 5s for exactly one GenAI span (the exporter also receives request spans,
     * which carry no {@code gen_ai.*} attributes).
     */
    private SpanData awaitSingleGenAiSpan() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        List<SpanData> genAiSpans;
        do {
            genAiSpans = spans.getFinishedSpanItems().stream()
                .filter(span -> span.getAttributes().get(stringKey("gen_ai.system")) != null)
                .collect(Collectors.toList());
            if (!genAiSpans.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        assertThat("exactly one GenAI span must be emitted for the streamed forward", genAiSpans.size(), is(1));
        return genAiSpans.get(0);
    }

    /** Cost is recorded just after the span is exported, so wait for it rather than read it at once. */
    private static double awaitRecordedCostUsd() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (LlmCostBudgetMonitor.getInstance().getCumulativeCostUsd() == 0.0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return LlmCostBudgetMonitor.getInstance().getCumulativeCostUsd();
    }

    private static final class Exchange {
        private final int status;
        private final String body;

        private Exchange(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /**
     * A one-response-per-connection HTTP/1.1 upstream that streams a chunked
     * {@code text/event-stream} body, one HTTP chunk per part with a pause between them.
     */
    private static final class StubUpstream {
        private final ServerSocket serverSocket;
        private final Thread acceptor;
        private final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();

        private StubUpstream(boolean gzip, boolean cutOff, String... parts) throws IOException {
            serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try (Socket socket = serverSocket.accept()) {
                        readRequest(socket.getInputStream());
                        requests.incrementAndGet();
                        respond(socket.getOutputStream(), gzip, cutOff, parts);
                    } catch (IOException | InterruptedException e) {
                        // the socket was closed, by the test or by the proxy
                    }
                }
            }, "stub-llm-upstream");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        static StubUpstream streaming(boolean gzip, String... parts) {
            try {
                return new StubUpstream(gzip, false, parts);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        static StubUpstream cutOffAfter(String... parts) {
            try {
                return new StubUpstream(false, true, parts);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        int requests() {
            return requests.get();
        }

        void close() throws IOException {
            serverSocket.close();
        }

        private static void readRequest(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                int next = in.read();
                if (next == -1) {
                    throw new IOException("request ended before its headers");
                }
                head.write(next);
            }
            int contentLength = 0;
            for (String line : head.toString(StandardCharsets.ISO_8859_1).split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
                }
            }
            if (in.readNBytes(contentLength).length != contentLength) {
                throw new IOException("request ended before its body");
            }
        }

        private static void respond(OutputStream out, boolean gzip, boolean cutOff, String... parts) throws IOException, InterruptedException {
            out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream\r\n"
                + (gzip ? "Content-Encoding: gzip\r\n" : "")
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            GZIPOutputStream gzipStream = gzip ? new GZIPOutputStream(compressed, true) : null;
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                if (gzipStream != null) {
                    gzipStream.write(bytes);
                    gzipStream.flush();
                    bytes = compressed.toByteArray();
                    compressed.reset();
                }
                writeChunk(out, bytes);
                Thread.sleep(50);
            }
            if (cutOff) {
                // no terminating chunk: the connection just closes
                return;
            }
            if (gzipStream != null) {
                gzipStream.finish();
                writeChunk(out, compressed.toByteArray());
            }
            out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        private static void writeChunk(OutputStream out, byte[] bytes) throws IOException {
            if (bytes.length == 0) {
                return;
            }
            out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }
    }
}
