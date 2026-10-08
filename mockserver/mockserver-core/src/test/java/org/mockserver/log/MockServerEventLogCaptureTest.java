package org.mockserver.log;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.DeferredLogArgument;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Verifies the three composed OOM-guard behaviours in {@link MockServerEventLog#processLogEntry}:
 * the recorded-request disk hook sees FULL bodies, {@code maxLoggedBodyBytes} truncates the retained
 * in-memory copy (without mutating the shared live objects passed to the disk hook), and the byte
 * budget caps the total retained size.
 */
public class MockServerEventLogCaptureTest {

    private final List<MockServerEventLog> eventLogs = new ArrayList<>();

    @After
    public void stopEventLogs() {
        eventLogs.forEach(MockServerEventLog::stop);
    }

    private static String base64(String text) {
        return java.util.Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private MockServerEventLog synchronousEventLog(Configuration configuration) {
        // synchronous processing (false) so add() runs processLogEntry inline and assertions are deterministic
        MockServerEventLog eventLog = new MockServerEventLog(configuration, new MockServerLogger(configuration, MockServerLogger.class), mock(Scheduler.class), false);
        eventLogs.add(eventLog);
        return eventLog;
    }

    private List<LogEntry> retrieveMessageLogEntries(MockServerEventLog log, RequestDefinition requestDefinition) {
        CompletableFuture<List<LogEntry>> future = new CompletableFuture<>();
        log.retrieveMessageLogEntries(requestDefinition, future::complete);
        try {
            return future.get(60, SECONDS);
        } catch (Exception e) {
            fail(e.getMessage());
            return null;
        }
    }

    @Test
    public void shouldPersistFullBodyToHookWhileTruncatingInMemoryCopy() {
        // given
        Configuration configuration = configuration().maxLoggedBodyBytes(5);
        MockServerEventLog log = synchronousEventLog(configuration);
        List<HttpRequest> capturedFullRequests = new ArrayList<>();
        log.setRecordedRequestConsumer(entry -> capturedFullRequests.add((HttpRequest) entry.getHttpRequest()));

        HttpRequest liveRequest = request("/capture").withMethod("POST").withBody("0123456789ABCDEF"); // 16 bytes

        // when
        log.add(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setHttpRequest(liveRequest)
            .setHttpResponse(response().withStatusCode(200).withBody("a-very-long-response-body"))); // 25 bytes

        // then — the disk hook received the FULL (un-truncated) request body
        assertThat(capturedFullRequests.size(), is(1));
        assertThat(capturedFullRequests.get(0).getBodyAsRawBytes().length, is(16));

        // and the live request object was NOT mutated (no truncation header leaked onto it)
        assertThat(liveRequest.getBodyAsRawBytes().length, is(16));
        assertThat(liveRequest.getFirstHeader("x-mockserver-body-truncated"), is(""));

        // and the in-memory retained copy was truncated to maxLoggedBodyBytes with a marker header
        List<LogEntry> entries = retrieveMessageLogEntries(log, null);
        assertThat(entries.size(), is(1));
        LogEntry retained = entries.get(0);
        HttpRequest retainedRequest = (HttpRequest) retained.getHttpRequest();
        assertThat(retainedRequest.getBodyAsRawBytes().length, is(5));
        assertThat(retainedRequest.getFirstHeader("x-mockserver-body-truncated"), is("16"));
        assertThat(retained.getHttpResponse().getBodyAsRawBytes().length, is(5));
        assertThat(retained.getHttpResponse().getFirstHeader("x-mockserver-body-truncated"), is("25"));
    }

    @Test
    public void shouldTruncateTheRequestAndResponseQuotedAsArgumentsToo() {
        // given
        MockServerEventLog log = synchronousEventLog(configuration().maxLoggedBodyBytes(5));
        HttpRequest liveRequest = request("/capture").withMethod("POST").withBody("0123456789ABCDEF");
        HttpResponse liveResponse = response().withStatusCode(200).withBody("a-very-long-response-body");

        // when
        log.add(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setHttpRequest(liveRequest)
            .setHttpResponse(liveResponse)
            .setMessageFormat("returning response:{}for forwarded request:{}")
            .setArguments(liveResponse, liveRequest));

        // then - the arguments quote the truncated copies, so the retained entry holds no full body
        LogEntry retained = retrieveMessageLogEntries(log, null).get(0);
        Object[] arguments = retained.getArguments();
        // a truncated body is kept as bytes, so it renders as base64
        assertThat(((HttpResponse) arguments[0]).getBodyAsString(), is(base64("a-ver")));
        assertThat(((HttpRequest) arguments[1]).getBodyAsString(), is(base64("01234")));
        assertThat(retained.getMessage().contains("0123456789ABCDEF"), is(false));
        assertThat(liveRequest.getBodyAsRawBytes().length, is(16));
    }

    @Test
    public void shouldTruncateTheExpectationsAnEntryNamesOrQuotesAndALongBecause() {
        // given
        MockServerEventLog log = synchronousEventLog(configuration().maxLoggedBodyBytes(5));
        Expectation live = new Expectation(request("/match").withBody("0123456789ABCDEF"))
            .withId("live-expectation")
            .thenRespond(response().withBody("a-very-long-response-body"));
        HttpRequest request = request("/match").withBody("FEDCBA9876543210");

        // when
        log.add(new LogEntry()
            .setType(EXPECTATION_NOT_MATCHED)
            .setHttpRequest(request)
            .setExpectation(live)
            .setMessageFormat("request:{}didn't match expectation:{}because:{}")
            .setArguments(request, live.clone(), "body didn't match")
            .setBecause("body didn't match: 0123456789ABCDEF"));

        // then - the retained entry quotes no whole body, and the live expectation is untouched
        LogEntry retained = retrieveMessageLogEntries(log, null).get(0);
        assertThat(((HttpRequest) retained.getExpectation().getHttpRequest()).getBodyAsRawBytes().length, is(5));
        assertThat(retained.getExpectation().getHttpResponse().getBodyAsRawBytes().length, is(5));
        assertThat(retained.getExpectation().getId(), is("live-expectation"));
        Expectation quoted = (Expectation) retained.getArguments()[1];
        assertThat(((HttpRequest) quoted.getHttpRequest()).getBodyAsRawBytes().length, is(5));
        assertThat(quoted.getHttpResponse().getFirstHeader("x-mockserver-body-truncated"), is("25"));
        // one cut copy, the one the weigher charges with the named expectation
        assertThat(quoted.getHttpRequest() == retained.getExpectation().getHttpRequest(), is(true));
        assertThat(quoted.getHttpResponse() == retained.getExpectation().getHttpResponse(), is(true));
        assertThat(retained.getArguments()[2], is("body ... (12 more characters not logged)"));
        assertThat(retained.getBecause(), is("body ... (30 more characters not logged)"));
        assertThat(retained.getMessage().contains("0123456789ABCDEF"), is(false));
        assertThat(((HttpRequest) live.getHttpRequest()).getBodyAsRawBytes().length, is(16));
        assertThat(live.getHttpResponse().getBodyAsRawBytes().length, is(25));
    }

    @Test
    public void shouldTruncateAnActionAndACurlCommandForAnotherRequest() {
        // given
        MockServerEventLog log = synchronousEventLog(configuration().maxLoggedBodyBytes(5));
        HttpResponse action = response().withBody("a-very-long-response-body");
        HttpResponse served = action.clone();
        HttpRequest original = request("/original").withHeader("host", "localhost:1080").withBody("0123456789ABCDEF");
        MockServerLogger logger = new MockServerLogger(MockServerEventLogCaptureTest.class);

        // when
        log.add(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setHttpRequest(request("/forwarded"))
            .setHttpResponse(served)
            .setMessageFormat("returning response:{}for request:{}as curl:{}for action:{}")
            .setArguments(served, original, DeferredLogArgument.curl(new HttpRequestToCurlSerializer(logger), original, null), action));

        // then
        LogEntry retained = retrieveMessageLogEntries(log, null).get(0);
        Object[] arguments = retained.getArguments();
        assertThat(((HttpRequest) arguments[1]).getBodyAsString(), is(base64("01234")));
        assertThat((String) arguments[2], containsString("curl -v 'http://localhost:1080/original'"));
        assertThat(((String) arguments[2]).contains("0123456789ABCDEF"), is(false));
        assertThat(((HttpResponse) arguments[3]).getBodyAsString(), is(base64("a-ver")));
        assertThat(action.getBodyAsRawBytes().length, is(25));
    }

    @Test
    public void shouldWeighAMatchersBecause() {
        LogEntry withoutBecause = new LogEntry().setType(EXPECTATION_NOT_MATCHED).setHttpRequest(request("/match"));
        LogEntry withBecause = new LogEntry().setType(EXPECTATION_NOT_MATCHED).setHttpRequest(request("/match"))
            .setBecause("x".repeat(10_000));

        assertThat(withBecause.estimatedHeapSize() - withoutBecause.estimatedHeapSize(), is(10_000L));
    }

    @Test
    public void shouldKeepExpectationsAndTextWithinMaxLoggedBodyBytesAsTheyAre() {
        // given
        MockServerEventLog log = synchronousEventLog(configuration().maxLoggedBodyBytes(100));
        Expectation live = new Expectation(request("/match").withBody("short")).thenRespond(response().withBody("short"));

        // when
        log.add(new LogEntry()
            .setType(EXPECTATION_NOT_MATCHED)
            .setHttpRequest(request("/match"))
            .setExpectation(live)
            .setMessageFormat("didn't match expectation:{}because:{}")
            .setArguments(live, "short reason")
            .setBecause("short reason"));

        // then
        LogEntry retained = retrieveMessageLogEntries(log, null).get(0);
        assertThat(retained.getExpectation() == live, is(true));
        assertThat(retained.getArguments()[0] == live, is(true));
        assertThat(retained.getBecause(), is("short reason"));
    }

    @Test
    public void shouldCapRetainedEntriesByByteBudget() {
        // given — count bound generous, byte budget sized to hold exactly two entries; full bodies
        // retained (no truncation). The weigher (LogEntry.estimatedHeapSize) is a materially honest
        // estimate — body bytes PLUS a fixed per-entry / per-message structural overhead — not raw body
        // bytes alone, so the budget is derived from a sample entry's real weight rather than hard-coded
        // to the body size. Sizing it at 2.5x the per-entry weight leaves room for two entries and evicts
        // the third, which is the behaviour under test regardless of the exact overhead constants.
        byte[] body = new byte[40];
        long perEntryWeight = new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setHttpRequest(request("/sample"))
            .setHttpResponse(response().withStatusCode(200).withBody(body))
            .estimatedHeapSize();
        long budget = perEntryWeight * 2 + perEntryWeight / 2;
        Configuration configuration = configuration()
            .maxLogEntries(1000)
            .maxEventLogSizeInBytes(budget)
            .maxLoggedBodyBytes(0);
        MockServerEventLog log = synchronousEventLog(configuration);

        // when — 10 forwarded exchanges, each weighing perEntryWeight (well over half the budget)
        for (int i = 0; i < 10; i++) {
            log.add(new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/p" + i))
                .setHttpResponse(response().withStatusCode(200).withBody(body)));
        }

        // then — only the most recent two entries fit the budget; the rest are evicted oldest-first
        assertThat(log.size(), is(2));
        List<LogEntry> entries = retrieveMessageLogEntries(log, null);
        assertThat(entries, notNullValue());
        assertThat(entries.size(), is(2));
        // the retained entries are the newest two (oldest evicted first)
        assertThat(((HttpRequest) entries.get(0).getHttpRequest()).getPath().getValue(), is("/p8"));
        assertThat(((HttpRequest) entries.get(1).getHttpRequest()).getPath().getValue(), is("/p9"));
    }
}
