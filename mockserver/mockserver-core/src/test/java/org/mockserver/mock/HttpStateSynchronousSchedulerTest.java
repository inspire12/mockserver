package org.mockserver.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.file.FileReader;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A synchronous {@link Scheduler} has no executor to offload work onto, so the control-plane endpoints that run
 * their blocking work off the event loop run it on the calling thread instead, which blocks for the response anyway.
 */
public class HttpStateSynchronousSchedulerTest {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    private final Configuration configuration = configuration();
    private final Scheduler scheduler = new Scheduler(configuration, new MockServerLogger(), true);
    private final HttpState httpState = new HttpState(configuration, new MockServerLogger(), scheduler);

    @After
    public void stop() {
        httpState.stop();
        scheduler.shutdown();
    }

    @Test
    public void shouldRunAContractTestWithoutAnExecutor() throws Exception {
        // given - a service under test that conforms to listPets
        String spec = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");
        httpState.setReplayHandler(outbound -> CompletableFuture.completedFuture(response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("[{\"id\":1,\"name\":\"Fido\"}]")));
        CapturingResponseWriter responseWriter = new CapturingResponseWriter(configuration);

        // when
        boolean handled = httpState.handle(request("/mockserver/contractTest")
            .withMethod("PUT")
            .withBody("{\"spec\":" + OBJECT_MAPPER.writeValueAsString(spec) + ",\"baseUrl\":\"http://localhost:1080\",\"operationId\":\"listPets\"}"), responseWriter, true);

        // then - answered before handle returned
        assertThat(handled, is(true));
        assertThat(responseWriter.response, notNullValue());
        assertThat(responseWriter.response.getBodyAsString(), responseWriter.response.getStatusCode(), is(200));
        JsonNode report = OBJECT_MAPPER.readTree(responseWriter.response.getBodyAsString());
        assertThat(report.get("totalOperations").asInt(), is(1));
        assertThat(report.get("allPassed").asBoolean(), is(true));
    }

    @Test
    public void shouldValidateTrafficWithoutAnExecutor() throws Exception {
        // given - one recorded exchange that conforms to GET /pets
        String spec = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");
        httpState.log(
            new LogEntry()
                .setType(EXPECTATION_RESPONSE)
                .setHttpRequest(request("/pets").withMethod("GET"))
                .setHttpResponse(response()
                    .withStatusCode(200)
                    .withHeader("content-type", "application/json")
                    .withBody("[{\"id\":1,\"name\":\"Fido\"}]"))
        );
        CapturingResponseWriter responseWriter = new CapturingResponseWriter(configuration);

        // when
        boolean handled = httpState.handle(request("/mockserver/trafficValidate")
            .withMethod("PUT")
            .withBody("{\"spec\":" + OBJECT_MAPPER.writeValueAsString(spec) + "}"), responseWriter, true);

        // then - answered before handle returned
        assertThat(handled, is(true));
        assertThat(responseWriter.response, notNullValue());
        assertThat(responseWriter.response.getBodyAsString(), responseWriter.response.getStatusCode(), is(200));
        JsonNode report = OBJECT_MAPPER.readTree(responseWriter.response.getBodyAsString());
        assertThat(report.get("totalRequests").asInt(), is(1));
        assertThat(report.get("allPassed").asBoolean(), is(true));
    }

    private static class CapturingResponseWriter extends ResponseWriter {
        private volatile HttpResponse response;

        private CapturingResponseWriter(Configuration configuration) {
            super(configuration, new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
        }
    }
}
