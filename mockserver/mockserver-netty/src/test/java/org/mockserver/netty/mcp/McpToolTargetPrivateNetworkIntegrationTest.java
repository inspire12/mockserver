package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.socket.PortFactory;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.verify.VerificationTimes.atLeast;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * forwardProxyBlockPrivateNetworks applies to the MCP tools that send requests to a URL the caller gives
 * (run_contract_test, run_resiliency_test, run_mcp_contract_test): with it on a loopback target is refused with an
 * error result and one warning and is sent nothing; with it off the requests are sent. The target is a MockServer on
 * localhost.
 */
public class McpToolTargetPrivateNetworkIntegrationTest {

    private static final String BLOCKED = "Forward to loopback address blocked";

    private static final String SPEC = "{\n" +
        "  \"openapi\": \"3.0.0\",\n" +
        "  \"info\": {\"title\": \"Test API\", \"version\": \"1.0\"},\n" +
        "  \"paths\": {\n" +
        "    \"/items\": {\n" +
        "      \"post\": {\n" +
        "        \"operationId\": \"createItem\",\n" +
        "        \"requestBody\": {\n" +
        "          \"required\": true,\n" +
        "          \"content\": {\"application/json\": {\"schema\": {\n" +
        "            \"type\": \"object\", \"required\": [\"name\"],\n" +
        "            \"properties\": {\"name\": {\"type\": \"string\", \"example\": \"item\"}}\n" +
        "          }}}\n" +
        "        },\n" +
        "        \"responses\": {\"201\": {\"description\": \"Created\"}, \"400\": {\"description\": \"Bad request\"}}\n" +
        "      }\n" +
        "    }\n" +
        "  }\n" +
        "}";

    private static ClientAndServer target;
    private static int targetPort;

    private final List<HttpState> httpStates = new ArrayList<>();
    private final List<LogEntry> warnings = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = ObjectMapperFactory.buildObjectMapperWithoutRemovingEmptyValues();

    @BeforeClass
    public static void startTarget() {
        target = ClientAndServer.startClientAndServer(0);
        targetPort = target.getLocalPort();
    }

    @AfterClass
    public static void stopTarget() {
        if (target != null) {
            target.stop();
        }
    }

    @Before
    public void resetTarget() {
        target.reset();
        target.when(request()).respond(response().withStatusCode(201).withHeader("content-type", "application/json").withBody("{}"));
    }

    @After
    public void stopHttpStates() {
        httpStates.forEach(HttpState::stop);
    }

    @Test
    public void contractTestIsRefusedALoopbackTargetOnlyWithTheSettingOn() {
        assertRefusedOnlyWithTheSettingOn("run_contract_test", baseUrlParams());
    }

    @Test
    public void resiliencyTestIsRefusedALoopbackTargetOnlyWithTheSettingOn() {
        assertRefusedOnlyWithTheSettingOn("run_resiliency_test", baseUrlParams());
    }

    @Test
    public void mcpContractTestIsRefusedALoopbackTargetOnlyWithTheSettingOn() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("targetUrl", "http://localhost:" + targetPort + "/mcp");
        assertRefusedOnlyWithTheSettingOn("run_mcp_contract_test", params);
    }

    /**
     * Each request is checked again as it is sent, so a target whose name no longer passes the check after the
     * tool's first check (its DNS answer changed) is sent nothing. The second evaluation of the setting stands in for
     * that second answer.
     */
    @Test
    public void aTargetRefusedAfterTheFirstCheckIsSentNothing() {
        Configuration configuration = spy(configuration());
        McpToolRegistry registry = registry(configuration);
        doReturn(false, true).when(configuration).forwardProxyBlockPrivateNetworks();

        JsonNode result = registry.callTool("run_contract_test", baseUrlParams());

        assertThat(result.toString(), result.path("error").asBoolean(), is(false));
        assertThat(result.toString(), result.path("failed").asInt(), is(1));
        assertThat(result.path("results").get(0).path("statusCode").asInt(), is(0));
        target.verify(request(), exactly(0));
    }

    @Test
    public void aRedirectIsNotFollowedWithTheSettingOn() {
        target.reset();
        target.when(request().withPath("/moved")).respond(response().withStatusCode(302).withHeader("Location", "http://localhost:" + targetPort + "/landed"));
        target.when(request().withPath("/landed")).respond(response().withStatusCode(200));

        HttpResponse blocking = registry(configuration().forwardProxyBlockPrivateNetworks(true))
            .sendHttpRequest(request().withMethod("GET").withPath("/moved"), new InetSocketAddress("localhost", targetPort), false, 5000);

        assertThat(blocking.getStatusCode(), is(302));
        target.verify(request().withPath("/landed"), exactly(0));

        HttpResponse allowing = registry(configuration().forwardProxyBlockPrivateNetworks(false))
            .sendHttpRequest(request().withMethod("GET").withPath("/moved"), new InetSocketAddress("localhost", targetPort), false, 5000);

        assertThat(allowing.getStatusCode(), is(200));
        target.verify(request().withPath("/landed"), exactly(1));
    }

    /** A spec path that does not begin with "/" cannot name another host: the request goes to the checked one. */
    @Test
    public void aRequestPathCannotChangeTheHostConnectedTo() {
        int closedPort = PortFactory.findFreePort();
        String spec = SPEC.replace("\"/items\"", "\"@127.0.0.1:" + closedPort + "/items\"");
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", spec);
        params.put("baseUrl", "http://localhost:" + targetPort);

        JsonNode result = registry(configuration().forwardProxyBlockPrivateNetworks(false)).callTool("run_contract_test", params);

        assertThat(result.toString(), result.path("totalOperations").asInt(), is(1));
        assertThat(result.toString(), result.path("results").get(0).path("statusCode").asInt(), is(201));
        target.verify(request(), exactly(1));
    }

    private void assertRefusedOnlyWithTheSettingOn(String tool, ObjectNode params) {
        JsonNode refused = registry(configuration().forwardProxyBlockPrivateNetworks(true)).callTool(tool, params);

        assertThat(refused.toString(), refused.path("error").asBoolean(), is(true));
        assertThat(refused.path("message").asText(), containsString(tool + " blocked by SSRF policy: " + BLOCKED + ": localhost"));
        target.verify(request(), exactly(0));
        assertThat(blockedWarnings(), hasSize(1));
        assertThat(String.valueOf(blockedWarnings().get(0).getArguments()[0]), is(tool));
        assertThat(otherWarnings(), is(empty()));

        warnings.clear();
        JsonNode allowed = registry(configuration().forwardProxyBlockPrivateNetworks(false)).callTool(tool, params);

        assertThat(allowed.toString(), allowed.path("error").isMissingNode(), is(true));
        target.verify(request(), atLeast(1));
        assertThat(blockedWarnings(), is(empty()));
    }

    private ObjectNode baseUrlParams() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", SPEC);
        params.put("baseUrl", "http://localhost:" + targetPort);
        return params;
    }

    private List<LogEntry> blockedWarnings() {
        return warnings.stream()
            .filter(entry -> String.valueOf(entry.getMessageFormat()).contains("blocked by SSRF policy"))
            .filter(entry -> entry.getArguments() != null && entry.getArguments().length > 1 && String.valueOf(entry.getArguments()[1]).contains(BLOCKED))
            .collect(Collectors.toList());
    }

    private List<LogEntry> otherWarnings() {
        List<LogEntry> blocked = blockedWarnings();
        return warnings.stream().filter(entry -> !blocked.contains(entry)).collect(Collectors.toList());
    }

    private McpToolRegistry registry(Configuration configuration) {
        LifeCycle server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        when(server.getLocalPorts()).thenReturn(Collections.singletonList(1080));
        when(server.isRunning()).thenReturn(true);
        HttpState httpState = new HttpState(configuration, new RecordingLogger(warnings), mock(Scheduler.class));
        httpStates.add(httpState);
        return new McpToolRegistry(httpState, server);
    }

    /** Keeps a copy of each warning: the event log clears the entry it is handed once it has copied it. */
    private static class RecordingLogger extends MockServerLogger {
        private final List<LogEntry> warnings;

        RecordingLogger(List<LogEntry> warnings) {
            this.warnings = warnings;
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            if (logEntry.getLogLevel() == Level.WARN) {
                warnings.add(logEntry.clone());
            }
            super.logEvent(logEntry);
        }
    }
}
