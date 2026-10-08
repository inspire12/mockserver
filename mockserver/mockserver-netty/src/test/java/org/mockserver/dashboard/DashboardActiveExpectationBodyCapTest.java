package org.mockserver.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.After;
import org.junit.Test;
import org.mockserver.dashboard.model.DashboardBodyCap;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.model.JsonBody;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.apache.commons.lang3.StringUtils.repeat;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * The expectations section of a dashboard update sent every expectation whole, so 100 expectations with 1 MiB
 * bodies made every update over 100 MB. Each long body (and any other long string) is now cut, and the item says
 * so, so the dashboard can load the whole expectation by id before it is edited.
 */
public class DashboardActiveExpectationBodyCapTest {

    private static final int CAP = DashboardBodyCap.MAX_BODY_CHARACTERS;
    private static final int MIB = 1024 * 1024;
    private static final int EXPECTATIONS = 100;

    private Scheduler scheduler;
    private HttpState httpState;
    private DashboardWebSocketHandler handler;
    private DashboardWebSocketHandlerTest.MockChannelHandlerContext ctx;

    @After
    public void stop() throws Exception {
        if (handler != null) {
            for (String name : new String[]{"scheduler", "throttleExecutorService"}) {
                Field field = DashboardWebSocketHandler.class.getDeclaredField(name);
                field.setAccessible(true);
                Object executor = field.get(handler);
                if (executor instanceof ExecutorService) {
                    ((ExecutorService) executor).shutdownNow();
                }
            }
        }
        if (ctx != null) {
            ctx.finishAndReleaseAll();
        }
        if (httpState != null) {
            httpState.stop();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private JsonNode frameFor(List<Expectation> expectations) throws Exception {
        MockServerLogger mockServerLogger = new MockServerLogger(DashboardActiveExpectationBodyCapTest.class);
        scheduler = new Scheduler(configuration(), mockServerLogger, true);
        httpState = new HttpState(configuration(), mockServerLogger, scheduler);
        httpState.getRequestMatchers().update(expectations.toArray(new Expectation[0]), MockServerMatcherNotifier.Cause.API);
        handler = new DashboardWebSocketHandler(httpState, false, false).registerListeners();
        ctx = new DashboardWebSocketHandlerTest.MockChannelHandlerContext();
        handler.getClientRegistry().put(ctx, request());
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            handler.sendUpdate(ctx, request());
            Thread.sleep(400);
            if (ctx.textWebSocketFrame != null) {
                String text = ctx.textWebSocketFrame.text();
                System.out.println("dashboard update with " + expectations.size() + " expectations: " + text.length() + " characters");
                return ObjectMapperFactory.createObjectMapper().readTree(text);
            }
        }
        throw new AssertionError("no dashboard frame produced within timeout");
    }

    private static JsonNode item(JsonNode frame, String id) {
        for (JsonNode item : frame.get("activeExpectations")) {
            if (id.equals(item.get("key").asText())) {
                return item;
            }
        }
        throw new AssertionError("no expectation " + id + " in the update");
    }

    @Test
    public void shouldCutLargeExpectationBodiesSoAnUpdateStaysSmall() throws Exception {
        String body = repeat('r', MIB);
        List<Expectation> expectations = new ArrayList<>();
        for (int i = 0; i < EXPECTATIONS; i++) {
            expectations.add(new Expectation(request("/large-" + i)).withId(String.format("large-%03d", i))
                .thenRespond(response(body)));
        }

        JsonNode frame = frameFor(expectations);

        assertThat(frame.get("activeExpectations").size(), is(EXPECTATIONS));
        assertThat(frame.toString().length(), lessThan(EXPECTATIONS * (CAP + 2048)));
        JsonNode item = item(frame, "large-000");
        assertThat(item.get("value").get("httpResponse").get("body").asText(), is(repeat('r', CAP)));
        JsonNode marker = item.get("truncatedExpectation");
        assertThat(marker.get("expectationId").asText(), is("large-000"));
        assertThat(marker.get("part").asText(), is("expectation"));
        assertThat(marker.get("originalLength").asLong(), is((long) MIB));
        assertThat(marker.get("shownLength").asLong(), is((long) CAP));
    }

    @Test
    public void shouldCutRequestMatcherJsonBodiesAndOtherLongStringsAndLeaveSmallExpectationsWhole() throws Exception {
        String json = "{\"items\":[" + repeat("\"abcdefgh\",", CAP / 4) + "\"end\"]}";
        List<Expectation> expectations = new ArrayList<>();
        expectations.add(new Expectation(request("/json").withBody(JsonBody.json(json))).withId("json-body")
            .thenRespond(response("small")));
        expectations.add(new Expectation(request("/template")).withId("long-template")
            .thenRespond(org.mockserver.model.HttpTemplate.template(org.mockserver.model.HttpTemplate.TemplateType.VELOCITY, repeat('t', CAP + 10))));
        expectations.add(new Expectation(request("/small")).withId("small").thenRespond(response("small body")));

        JsonNode frame = frameFor(expectations);

        JsonNode jsonItem = item(frame, "json-body");
        assertThat(jsonItem.get("value").get("httpRequest").get("body").asText().length(), is(CAP));
        assertThat(jsonItem.get("value").get("httpRequest").get("body").asText(), containsString("\"items\""));
        assertThat(jsonItem.get("value").get("httpResponse").get("body").asText(), is("small"));
        assertThat(jsonItem.get("truncatedExpectation"), is(notNullValue()));

        JsonNode templateItem = item(frame, "long-template");
        assertThat(templateItem.get("value").get("httpResponseTemplate").get("template").asText().length(), is(CAP));
        assertThat(templateItem.get("truncatedExpectation").get("originalLength").asLong(), is((long) CAP + 10));

        JsonNode smallItem = item(frame, "small");
        assertThat(smallItem.get("value").get("httpResponse").get("body").asText(), is("small body"));
        assertThat(smallItem.get("truncatedExpectation"), is(nullValue()));
    }
}
