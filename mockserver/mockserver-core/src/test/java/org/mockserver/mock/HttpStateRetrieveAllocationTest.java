package org.mockserver.mock;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultHttpObject;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.MockServerHttpResponseToFullHttpResponse;
import org.mockserver.model.Format;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.model.RetrieveType;
import org.mockserver.model.StringBody;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A retrieve response is serialised once, into the bytes the frontend writes. Before, it was built
 * in the serialiser's buffers, copied into a String and encoded into a byte array, so a retrieve
 * allocated several times its response. This measures the bytes the retrieving thread allocates to
 * build a large response and map it to Netty's wire form, as a multiple of the response's size.
 */
@RunWith(Parameterized.class)
public class HttpStateRetrieveAllocationTest {

    private static final int BODIES = 8;
    private static final int BODY_CHARACTERS = 1 << 20;
    // the response once, and a little for the rest; a String and its encoded copy would add about four
    private static final double ONCE = 1.5;

    @Parameterized.Parameters(name = "{0} as {1}")
    public static Collection<Object[]> retrieves() {
        return Arrays.asList(new Object[][]{
            {RetrieveType.LOGS, Format.LOG_ENTRIES, ONCE},
            {RetrieveType.REQUESTS, Format.JSON, ONCE},
            {RetrieveType.REQUESTS, Format.JAVA, ONCE},
            // each received request's entry renders its body from the stored bytes once more
            {RetrieveType.REQUESTS, Format.LOG_ENTRIES, ONCE + 1},
            {RetrieveType.REQUESTS, Format.HAR, ONCE},
            {RetrieveType.REQUEST_RESPONSES, Format.JSON, ONCE},
            {RetrieveType.REQUEST_RESPONSES, Format.LOG_ENTRIES, ONCE},
            {RetrieveType.REQUEST_RESPONSES, Format.HAR, ONCE},
            {RetrieveType.RECORDED_EXPECTATIONS, Format.JSON, ONCE},
            {RetrieveType.RECORDED_EXPECTATIONS, Format.HAR, ONCE},
            {RetrieveType.ACTIVE_EXPECTATIONS, Format.JSON, ONCE},
            {RetrieveType.REQUESTS, Format.POSTMAN, ONCE},
            {RetrieveType.REQUEST_RESPONSES, Format.OPENAPI, ONCE},
            {RetrieveType.REQUEST_RESPONSES, Format.POSTMAN, ONCE},
            {RetrieveType.RECORDED_EXPECTATIONS, Format.OPENAPI, ONCE},
            {RetrieveType.RECORDED_EXPECTATIONS, Format.POSTMAN, ONCE},
            {RetrieveType.ACTIVE_EXPECTATIONS, Format.OPENAPI, ONCE},
            {RetrieveType.ACTIVE_EXPECTATIONS, Format.POSTMAN, ONCE},
            // these render each entry or expectation as text of its own first, which costs several
            // times its size; the bound is what that costs plus the response once
            {RetrieveType.LOGS, Format.JSON, 18.5},
            {RetrieveType.RECORDED_EXPECTATIONS, Format.JAVA, 14.5},
            {RetrieveType.ACTIVE_EXPECTATIONS, Format.JAVA, 14.5},
            {RetrieveType.RECORDED_EXPECTATIONS, Format.PYTHON, 7.8},
            {RetrieveType.ACTIVE_EXPECTATIONS, Format.GO, 8.9},
        });
    }

    private final RetrieveType type;
    private final Format format;
    private final double mostAllocatedPerResponseByte;
    private HttpState httpState;
    private ScheduledExecutorService schedulerExecutor;

    public HttpStateRetrieveAllocationTest(RetrieveType type, Format format, double mostAllocatedPerResponseByte) {
        this.type = type;
        this.format = format;
        this.mostAllocatedPerResponseByte = mostAllocatedPerResponseByte;
    }

    @Before
    public void setUp() {
        Configuration configuration = configuration().logLevel(Level.WARN);
        Scheduler scheduler = mock(Scheduler.class);
        schedulerExecutor = Executors.newScheduledThreadPool(2);
        when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler);
    }

    @After
    public void tearDown() {
        if (httpState != null) {
            httpState.stop();
        }
        schedulerExecutor.shutdownNow();
    }

    @Test(timeout = 120_000)
    public void shouldBuildTheResponseOnce() {
        // given
        for (int i = 0; i < BODIES; i++) {
            HttpRequest received = request("/received/" + i).withMethod("POST").withBody(new StringBody(asciiText(i), MediaType.PLAIN_TEXT_UTF_8));
            httpState.log(new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(received)
                .setMessageFormat("received request:{}")
                .setArguments(received));
            HttpRequest forwarded = request("/forwarded/" + i);
            HttpResponse forwardedResponse = response().withBody(asciiText(i), MediaType.PLAIN_TEXT_UTF_8);
            httpState.log(new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(forwarded)
                .setHttpResponse(forwardedResponse)
                .setExpectation(new Expectation(forwarded).thenRespond(forwardedResponse)));
            httpState.add(new Expectation(request("/active/" + i)).thenRespond(response().withBody(asciiText(i), MediaType.PLAIN_TEXT_UTF_8)));
        }
        HttpRequest retrieve = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", type.name())
            .withQueryStringParameter("format", format.name());
        // loads and caches the serialisers, so only the response's own allocation is measured
        retrieveToWire(retrieve);

        // when
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat("this JVM measures the bytes a thread allocates", threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled(), is(true));
        long before = threads.getCurrentThreadAllocatedBytes();
        int responseBytes = retrieveToWire(retrieve);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        // then
        assertThat(responseBytes, greaterThan(BODIES * BODY_CHARACTERS));
        double perResponseByte = (double) allocated / responseBytes;
        assertThat("allocated " + allocated + " bytes for a response of " + responseBytes + " bytes (" + String.format("%.2f", perResponseByte) + " per byte)",
            perResponseByte, lessThan(mostAllocatedPerResponseByte));
    }

    private int retrieveToWire(HttpRequest retrieve) {
        HttpResponse response = httpState.retrieve(retrieve);
        assertThat(response.getStatusCode(), is(200));
        List<DefaultHttpObject> objects = new MockServerHttpResponseToFullHttpResponse(new MockServerLogger()).mapMockServerResponseToNettyResponse(response);
        try {
            ByteBuf content = ((FullHttpResponse) objects.get(0)).content();
            return content.readableBytes();
        } finally {
            objects.forEach(ReferenceCountUtil::release);
        }
    }

    private static String asciiText(int seed) {
        char[] text = new char[BODY_CHARACTERS];
        for (int i = 0; i < text.length; i++) {
            text[i] = (char) ('a' + (i + seed) % 26);
        }
        return new String(text);
    }
}
