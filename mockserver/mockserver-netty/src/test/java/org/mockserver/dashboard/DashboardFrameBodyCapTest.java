package org.mockserver.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.junit.Test;
import org.mockserver.dashboard.model.DashboardBodyCap;
import org.mockserver.dashboard.model.DashboardLogEntryDTO;
import org.mockserver.dashboard.serializers.DashboardLogEntryDTOGroupSerializer;
import org.mockserver.dashboard.serializers.DashboardLogEntryDTOSerializer;
import org.mockserver.dashboard.serializers.DescriptionProcessor;
import org.mockserver.dashboard.serializers.DescriptionSerializer;
import org.mockserver.dashboard.serializers.ThrowableSerializer;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang3.StringUtils.repeat;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_MATCHED;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A dashboard update carried every body whole, about eight times per row, so a few large bodies made one
 * update hundreds of megabytes. Bodies are now cut to a fixed length, with what the dashboard needs to load
 * one whole, and an update stops adding older rows once it reaches its size ceiling, and says so.
 */
public class DashboardFrameBodyCapTest {

    private static final int CAP = DashboardBodyCap.MAX_BODY_CHARACTERS;
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createObjectMapper(
        new DashboardLogEntryDTOSerializer(),
        new DashboardLogEntryDTOGroupSerializer(),
        new DescriptionSerializer(),
        new ThrowableSerializer()
    );
    private static final ObjectWriter WRITER = MAPPER.writer();

    private static String body(String word, int length) {
        return repeat(word, length / word.length() + 1).substring(0, length);
    }

    // Newest first, as the dashboard walks the log: for each exchange its response entry, the match, then the request.
    private static List<DashboardLogEntryDTO> exchanges(int count, int bodyLength) {
        List<DashboardLogEntryDTO> reverse = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            String correlationId = "exchange-" + i;
            HttpRequest request = request("/exchange-" + i).withBody(body("request-" + i + "-", bodyLength));
            HttpResponse response = response().withBody(body("response-" + i + "-", bodyLength));
            Expectation expectation = new Expectation(request("/exchange-" + i)).thenRespond(response);
            reverse.add(dto(new LogEntry().setType(EXPECTATION_RESPONSE).setCorrelationId(correlationId)
                .setHttpRequest(request).setHttpResponse(response)
                .setMessageFormat("returning response:{}for request:{}").setArguments(response, request)));
            reverse.add(dto(new LogEntry().setType(EXPECTATION_MATCHED).setCorrelationId(correlationId)
                .setHttpRequest(request).setExpectation(expectation)
                .setMessageFormat("request:{}matched expectation:{}").setArguments(request, expectation)));
            reverse.add(dto(new LogEntry().setType(RECEIVED_REQUEST).setCorrelationId(correlationId)
                .setHttpRequest(request)
                .setMessageFormat("received request:{}").setArguments(request)));
        }
        return reverse;
    }

    private static DashboardLogEntryDTO dto(LogEntry logEntry) {
        return new DashboardLogEntryDTO(logEntry, configuration());
    }

    private static final class Frame {
        private final String text;
        private final JsonNode json;
        private final boolean limitReached;

        private Frame(String text, boolean limitReached) throws Exception {
            this.text = text;
            this.json = MAPPER.readTree(text);
            this.limitReached = limitReached;
        }
    }

    private static Frame render(List<DashboardLogEntryDTO> reverse, long maxCharacters) throws Exception {
        List<Object> logMessages = new LinkedList<>();
        List<Map<String, Object>> recordedRequests = new LinkedList<>();
        List<Map<String, Object>> proxiedRequests = new LinkedList<>();
        DashboardWebSocketHandler.FrameBudget budget = new DashboardWebSocketHandler.FrameBudget(maxCharacters);
        DashboardWebSocketHandler.populateLogSections(
            reverse.stream(), true, 100, budget,
            logMessages, recordedRequests, proxiedRequests,
            new DescriptionProcessor(), new DescriptionProcessor(), new DescriptionProcessor());
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("logMessages", logMessages);
        sections.put("recordedRequests", recordedRequests);
        sections.put("proxiedRequests", proxiedRequests);
        return new Frame(WRITER.writeValueAsString(sections), budget.limitReached());
    }

    @Test
    public void shouldCutLargeBodiesInRowsAndSayWhereToLoadThemWhole() throws Exception {
        List<DashboardLogEntryDTO> reverse = exchanges(3, 200_000);

        Frame frame = render(reverse, DashboardWebSocketHandler.MAX_UPDATE_CHARACTERS);

        JsonNode newest = frame.json.get("recordedRequests").get(0);
        assertThat(newest.get("value").get("httpRequest").get("path").asText(), is("/exchange-2"));
        String requestBody = newest.get("value").get("httpRequest").get("body").asText();
        String responseBody = newest.get("value").get("httpResponse").get("body").asText();
        assertThat(requestBody.length(), is(CAP));
        assertThat(requestBody, is(body("request-2-", CAP)));
        assertThat(responseBody.length(), is(CAP));

        JsonNode requestMarker = newest.get("truncatedBodies").get("httpRequest");
        assertThat(requestMarker.get("logEntryId").asText(), is(reverse.get(2).getId()));
        assertThat(requestMarker.get("part").asText(), is("request"));
        assertThat(requestMarker.get("originalLength").asLong(), is(200_000L));
        assertThat(requestMarker.get("shownLength").asLong(), is((long) CAP));
        JsonNode responseMarker = newest.get("truncatedBodies").get("httpResponse");
        assertThat("the response loads from the entry that holds it", responseMarker.get("logEntryId").asText(), is(reverse.get(0).getId()));
        assertThat(responseMarker.get("part").asText(), is("response"));

        // nothing in the update holds a body whole
        assertThat(frame.text, not(containsString(body("request-2-", CAP + 1))));
        assertThat(frame.text, not(containsString(body("response-2-", CAP + 1))));
        assertThat(frame.limitReached, is(false));
    }

    @Test
    public void shouldMarkCutBodiesInLogMessageArguments() throws Exception {
        Frame frame = render(exchanges(1, 200_000), DashboardWebSocketHandler.MAX_UPDATE_CHARACTERS);

        List<JsonNode> markers = new ArrayList<>();
        frame.json.get("logMessages").findValues("truncatedBody").forEach(markers::add);
        // response and request of the response entry, request and expectation of the match, request of the received entry
        assertThat(markers.size(), is(5));
        long expectationMarkers = markers.stream().filter(marker -> marker.get("part").asText().equals("expectation")).count();
        assertThat(expectationMarkers, is(1L));
        for (JsonNode marker : markers) {
            boolean expectation = marker.get("part").asText().equals("expectation");
            assertThat("only the entry's own request or response loads: " + marker, marker.get("loadable").asBoolean(), is(!expectation));
        }
        assertThat(frame.text, not(containsString(body("response-0-", CAP + 1))));
    }

    @Test
    public void shouldLeaveBodiesUnderTheCapWhole() throws Exception {
        Frame frame = render(exchanges(2, CAP), DashboardWebSocketHandler.MAX_UPDATE_CHARACTERS);

        JsonNode newest = frame.json.get("recordedRequests").get(0);
        assertThat(newest.get("value").get("httpRequest").get("body").asText(), is(body("request-1-", CAP)));
        assertThat(newest.get("truncatedBodies"), nullValue());
        assertThat(frame.json.get("logMessages").findValues("truncatedBody").size(), is(0));
    }

    @Test
    public void shouldDropTheOldestRowsAtTheCeilingAndSayItDid() throws Exception {
        List<DashboardLogEntryDTO> reverse = exchanges(40, 200_000);

        Frame frame = render(reverse, 4L * 1024 * 1024);

        assertThat(frame.limitReached, is(true));
        int recorded = frame.json.get("recordedRequests").size();
        assertThat(recorded, greaterThan(0));
        assertThat(recorded, lessThan(40));
        assertThat("the newest rows are the ones kept", frame.json.get("recordedRequests").get(0).get("value").get("httpRequest").get("path").asText(), is("/exchange-39"));
        assertThat("the update stays near its ceiling", (long) frame.text.length(), lessThan(5L * 1024 * 1024));
    }

    @Test
    public void shouldSendCutBodiesAndTheCeilingFlagInTheUpdateItself() throws Exception {
        MockServerLogger logger = new MockServerLogger(DashboardFrameBodyCapTest.class);
        Configuration configuration = configuration().disableSystemOut(true);
        Scheduler scheduler = new Scheduler(configuration, logger, true);
        HttpState httpState = new HttpState(configuration, logger, scheduler);
        DashboardWebSocketHandler handler = null;
        CapturingChannel channel = new CapturingChannel();
        try {
            for (int i = 0; i < 60; i++) {
                String correlationId = "live-" + i;
                HttpRequest request = request("/live-" + i).withBody(body("request-" + i + "-", 100_000));
                HttpResponse response = response().withBody(body("response-" + i + "-", 100_000));
                httpState.getMockServerLog().add(new LogEntry().setType(RECEIVED_REQUEST).setCorrelationId(correlationId)
                    .setHttpRequest(request).setMessageFormat("received request:{}").setArguments(request));
                httpState.getMockServerLog().add(new LogEntry().setType(EXPECTATION_RESPONSE).setCorrelationId(correlationId)
                    .setHttpRequest(request).setHttpResponse(response)
                    .setMessageFormat("returning response:{}for request:{}").setArguments(response, request));
            }
            CompletableFuture<Integer> recorded = new CompletableFuture<>();
            httpState.getMockServerLog().retrieveMessageLogEntries(null, entries -> recorded.complete(entries.size()));
            assertThat(recorded.get(30, SECONDS) >= 120, is(true));
            handler = new DashboardWebSocketHandler(httpState, false, false).registerListeners();

            long deadline = System.currentTimeMillis() + 20_000;
            while (channel.frame == null && System.currentTimeMillis() < deadline) {
                handler.sendUpdate(channel, request());
                Thread.sleep(500);
            }

            assertThat("an update was sent", channel.frame, org.hamcrest.Matchers.notNullValue());
            String text = channel.frame.text();
            JsonNode frame = MAPPER.readTree(text);
            assertThat(frame.get("frameLimitReached").asBoolean(), is(true));
            assertThat(frame.get("recordedRequests").size(), lessThan(60));
            assertThat(frame.get("recordedRequests").get(0).get("truncatedBodies").get("httpRequest").get("originalLength").asLong(), is(100_000L));
            assertThat((long) text.length(), lessThan(DashboardWebSocketHandler.MAX_UPDATE_CHARACTERS + 2L * 1024 * 1024));
            assertThat(text, not(containsString(body("request-59-", CAP + 1))));
        } finally {
            if (handler != null) {
                stopExecutors(handler);
            }
            if (channel.frame != null) {
                channel.frame.release();
            }
            channel.finishAndReleaseAll();
            httpState.stop();
            scheduler.shutdown();
        }
    }

    private static final class CapturingChannel extends io.netty.channel.embedded.EmbeddedChannel {
        private volatile io.netty.handler.codec.http.websocketx.TextWebSocketFrame frame;

        @Override
        public io.netty.channel.ChannelFuture writeAndFlush(Object msg) {
            if (msg instanceof io.netty.handler.codec.http.websocketx.TextWebSocketFrame && frame == null) {
                frame = (io.netty.handler.codec.http.websocketx.TextWebSocketFrame) msg;
            } else {
                io.netty.util.ReferenceCountUtil.release(msg);
            }
            return newSucceededFuture();
        }
    }

    private static void stopExecutors(DashboardWebSocketHandler handler) throws Exception {
        for (String name : new String[]{"scheduler", "throttleExecutorService"}) {
            java.lang.reflect.Field field = DashboardWebSocketHandler.class.getDeclaredField(name);
            field.setAccessible(true);
            Object executor = field.get(handler);
            if (executor instanceof java.util.concurrent.ExecutorService) {
                ((java.util.concurrent.ExecutorService) executor).shutdownNow();
            }
        }
    }

    @Test
    public void shouldCountSmallJsonBodiesByTheirSizeNotTheCap() throws Exception {
        for (boolean proxied : new boolean[]{false, true}) {
            List<DashboardLogEntryDTO> reverse = new ArrayList<>();
            for (int i = 99; i >= 0; i--) {
                String correlationId = "json-" + i;
                HttpRequest request = request("/json-" + i).withMethod("POST").withBody(org.mockserver.model.JsonBody.json("{\"a\":" + i + "}"));
                HttpResponse response = response().withBody(org.mockserver.model.JsonBody.json("{\"b\":" + i + "}"));
                reverse.add(dto(new LogEntry().setType(proxied ? FORWARDED_REQUEST : EXPECTATION_RESPONSE).setCorrelationId(correlationId)
                    .setHttpRequest(request).setHttpResponse(response)
                    .setMessageFormat("returning response:{}for request:{}").setArguments(response, request)));
                reverse.add(dto(new LogEntry().setType(RECEIVED_REQUEST).setCorrelationId(correlationId)
                    .setHttpRequest(request).setMessageFormat("received request:{}").setArguments(request)));
            }

            Frame frame = render(reverse, DashboardWebSocketHandler.MAX_UPDATE_CHARACTERS);

            assertThat("proxied=" + proxied + ": no rows dropped", frame.limitReached, is(false));
            assertThat(frame.json.get("recordedRequests").size(), is(100));
            if (proxied) {
                assertThat(frame.json.get("proxiedRequests").size(), is(100));
            }
        }
    }

    @Test
    public void shouldKeepEveryRowWhenUnderTheCeiling() throws Exception {
        Frame frame = render(exchanges(40, 1_000), DashboardWebSocketHandler.MAX_UPDATE_CHARACTERS);

        assertThat(frame.limitReached, is(false));
        assertThat(frame.json.get("recordedRequests").size(), is(40));
    }

    @Test
    public void shouldCutAJsonBodyByItsText() throws Exception {
        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 20_000; i++) {
            json.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append("}");
        }
        json.append("]}");
        DashboardLogEntryDTO dto = dto(new LogEntry().setType(FORWARDED_REQUEST)
            .setHttpRequest(request("/json").withBody(org.mockserver.model.JsonBody.json(json.toString())))
            .setHttpResponse(response("ok"))
            .setMessageFormat("forwarded"));

        HttpRequest shown = (HttpRequest) dto.getHttpRequest();

        assertThat(String.valueOf(shown.getBody().getValue()).length(), is(CAP));
        assertThat(dto.originalBodyLength(shown), is((long) json.length()));
    }

    @Test
    public void shouldCutAfterRedactingSoACutNeverShowsARedactedValue() throws Exception {
        // the redacted field sits inside the first 64 KiB of a JSON body longer than the cap
        StringBuilder json = new StringBuilder("{\"password\":\"BODY-SECRET-9\",\"items\":[");
        for (int i = 0; json.length() < 200_000; i++) {
            json.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append("}");
        }
        json.append("]}");
        HttpRequest secret = request("/secret").withMethod("POST").withBody(org.mockserver.model.JsonBody.json(json.toString()));
        LogEntry logEntry = new LogEntry().setType(RECEIVED_REQUEST)
            .setHttpRequest(secret)
            .setMessageFormat("received request:{}")
            .setArguments(secret);
        Configuration redacting = configuration().redactSecretsInLog(true).fixtureBodyRedactFields("password");

        DashboardLogEntryDTO redacted = new DashboardLogEntryDTO(logEntry, redacting);
        String shown = WRITER.writeValueAsString(redacted) + MAPPER.writeValueAsString(redacted.getHttpRequest());
        DashboardLogEntryDTO plain = new DashboardLogEntryDTO(logEntry, configuration());
        String shownPlain = WRITER.writeValueAsString(plain) + MAPPER.writeValueAsString(plain.getHttpRequest());

        assertThat("the body was cut", redacted.originalBodyLength(redacted.getHttpRequest()), is(notNullValue()));
        assertThat(shown, not(containsString("BODY-SECRET-9")));
        assertThat("without redaction the cut shows the field, so the check above is not vacuous", shownPlain, containsString("BODY-SECRET-9"));
    }

    @Test
    public void shouldNotCutASurrogatePairInHalf() throws Exception {
        String body = repeat("a", CAP - 1) + "\uD83D\uDE00" + repeat("b", 100);
        DashboardLogEntryDTO dto = dto(new LogEntry().setType(RECEIVED_REQUEST)
            .setHttpRequest(request("/emoji").withBody(body))
            .setMessageFormat("received"));

        String shown = String.valueOf(((HttpRequest) dto.getHttpRequest()).getBody().getValue());

        assertThat(shown.length(), is(CAP - 1));
        assertThat(Character.isHighSurrogate(shown.charAt(shown.length() - 1)), is(false));
    }

    @Test
    public void shouldOfferToLoadAnArgumentOnlyWhenItIsTheEntrysOwnMessage() throws Exception {
        HttpRequest own = request("/own").withBody(body("own-", 100_000));
        HttpRequest other = request("/own").withBody(body("own-", 100_000));
        DashboardLogEntryDTO dto = dto(new LogEntry().setType(RECEIVED_REQUEST)
            .setHttpRequest(own)
            .setMessageFormat("received request:{}and a copy:{}")
            .setArguments(own, other));

        JsonNode parts = MAPPER.readTree(WRITER.writeValueAsString(dto)).get("value").get("messageParts");
        List<JsonNode> markers = new ArrayList<>();
        parts.findValues("truncatedBody").forEach(markers::add);

        assertThat(markers.size(), is(2));
        assertThat("the entry's own request", markers.get(0).get("loadable").asBoolean(), is(true));
        assertThat("an equal copy is not the entry's own request", markers.get(1).get("loadable").asBoolean(), is(false));
    }
}
