package org.mockserver.netty;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import org.mockserver.client.MockServerClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;

/**
 * A forwarded response is aggregated in direct memory before MockServer sends it on. Under the direct-memory
 * limit a 512 MiB container gets (64 MiB), a response just under the default maxResponseBodySize (50 MiB)
 * must still be forwarded, which needs the aggregator not to consolidate its body into a second copy.
 * Runs in a fresh JVM because Netty reads its limit once.
 */
public class DirectMemoryLimitForwardIntegrationTest {

    static final long SIXTY_FOUR_MIB = 64L * 1024 * 1024;
    static final int BODY_SIZE = 49 * 1024 * 1024;

    @Test
    public void shouldForwardAResponseJustUnderTheDefaultMaximumUnderTheSmallestDefaultDirectMemoryLimit() throws Exception {
        List<String> command = Arrays.asList(
            System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
            "-Xmx1g",
            "-Dio.netty.maxDirectMemory=" + SIXTY_FOUR_MIB,
            "-Dmockserver.logLevel=WARN",
            "-cp", System.getProperty("java.class.path"),
            Probe.class.getName(),
            "1"
        );
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        processBuilder.environment().remove("JAVA_TOOL_OPTIONS");
        Process process = processBuilder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            in.transferTo(output);
        }
        assertThat(process.waitFor(120, TimeUnit.SECONDS), is(true));
        String text = output.toString(StandardCharsets.UTF_8);

        assertThat(text, text.contains("request 0: status=200 bytes=" + BODY_SIZE + " intact=true"), is(true));
    }

    /**
     * Forwards {@code args[0]} concurrent requests for a {@link #BODY_SIZE} response through MockServer to a
     * JDK HTTP server and prints what each client saw.
     */
    public static class Probe {
        public static void main(String[] arguments) throws Exception {
            int concurrent = Integer.parseInt(arguments[0]);
            byte[] body = new byte[BODY_SIZE];
            for (int i = 0; i < body.length; i++) {
                body[i] = (byte) ((i * 31 + i / 251) % 251);
            }
            HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstream.createContext("/big", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            upstream.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
            upstream.start();
            MockServer mockServer = new MockServer(0);
            try {
                System.out.println("netty maxDirectMemory=" + io.netty.util.internal.PlatformDependent.maxDirectMemory());
                new MockServerClient("127.0.0.1", mockServer.getLocalPort())
                    .when(request().withPath("/big"))
                    .forward(forward().withHost("127.0.0.1").withPort(upstream.getAddress().getPort()));
                HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
                List<CompletableFuture<String>> results = new ArrayList<>();
                for (int i = 0; i < concurrent; i++) {
                    int index = i;
                    results.add(client.sendAsync(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mockServer.getLocalPort() + "/big")).timeout(java.time.Duration.ofSeconds(60)).build(),
                        HttpResponse.BodyHandlers.ofByteArray()
                    ).handle((response, throwable) -> throwable != null
                        ? "request " + index + ": " + throwable
                        : "request " + index + ": status=" + response.statusCode() + " bytes=" + response.body().length + " intact=" + Arrays.equals(response.body(), body)));
                }
                for (CompletableFuture<String> result : results) {
                    System.out.println(result.get(90, TimeUnit.SECONDS));
                }
            } finally {
                mockServer.stop();
                upstream.stop(0);
                System.exit(0);
            }
        }
    }
}
