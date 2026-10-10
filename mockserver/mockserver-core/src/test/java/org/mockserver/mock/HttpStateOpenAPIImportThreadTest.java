package org.mockserver.mock;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * PUT /mockserver/openapi fetches a spec URL with blocking I/O, so on Netty it must not hold the calling (event-loop)
 * thread while it does; a servlet container has no event loop and needs the response before handle() returns.
 */
public class HttpStateOpenAPIImportThreadTest {

    private static final String SPEC = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Thread\",\"version\":\"1\"}," +
        "\"paths\":{\"/thread-pets\":{\"get\":{\"operationId\":\"listThreadPets\",\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}";

    private final CountDownLatch specRequested = new CountDownLatch(1);
    private final CountDownLatch releaseSpec = new CountDownLatch(1);
    private HttpServer specServer;
    private ScheduledExecutorService schedulerExecutor;
    private ExecutorService caller;
    private HttpState httpState;

    private static class RecordingResponseWriter extends ResponseWriter {
        final CompletableFuture<HttpResponse> response = new CompletableFuture<>();
        volatile Thread writer;

        RecordingResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            writer = Thread.currentThread();
            this.response.complete(response);
        }
    }

    @Before
    public void setUp() throws Exception {
        specServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("localhost"), 0), 0);
        specServer.setExecutor(Executors.newCachedThreadPool());
        specServer.createContext("/", exchange -> {
            specRequested.countDown();
            try {
                releaseSpec.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = SPEC.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        specServer.start();
        Scheduler scheduler = mock(Scheduler.class);
        schedulerExecutor = Executors.newScheduledThreadPool(1);
        when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);
        httpState = new HttpState(configuration(), new MockServerLogger(HttpStateOpenAPIImportThreadTest.class), scheduler);
        caller = Executors.newSingleThreadExecutor();
    }

    @After
    public void tearDown() {
        releaseSpec.countDown();
        specServer.stop(0);
        caller.shutdownNow();
        schedulerExecutor.shutdownNow();
        httpState.stop();
    }

    private HttpRequest importSpecUrl() {
        String specUrl = "http://localhost:" + specServer.getAddress().getPort() + "/" + UUID.randomUUID() + "/openapi.json";
        return request("/mockserver/openapi").withMethod("PUT").withBody("{\"specUrlOrPayload\":\"" + specUrl + "\"}");
    }

    @Test
    public void handleReturnsWhileTheSpecUrlIsStillBeingFetched() throws Exception {
        RecordingResponseWriter responseWriter = new RecordingResponseWriter();

        CompletableFuture<Boolean> handled = CompletableFuture.supplyAsync(() -> httpState.handle(importSpecUrl(), responseWriter, false), caller);

        if (!specRequested.await(20, TimeUnit.SECONDS)) {
            fail("the spec URL was never fetched");
        }
        assertThat(handled.get(10, TimeUnit.SECONDS), is(true));
        assertThat(responseWriter.response.isDone(), is(false));

        releaseSpec.countDown();
        assertThat(responseWriter.response.get(20, TimeUnit.SECONDS).getStatusCode(), is(201));
    }

    @Test
    public void servletDeploymentWritesTheResponseBeforeHandleReturns() {
        releaseSpec.countDown();
        RecordingResponseWriter responseWriter = new RecordingResponseWriter();

        assertThat(httpState.handle(importSpecUrl(), responseWriter, true), is(true));

        assertThat(responseWriter.response.isDone(), is(true));
        assertThat(responseWriter.writer, sameInstance(Thread.currentThread()));
        assertThat(responseWriter.response.join().getStatusCode(), is(201));
    }
}
