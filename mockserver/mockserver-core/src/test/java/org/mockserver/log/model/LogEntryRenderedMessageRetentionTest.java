package org.mockserver.log.model;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.slf4j.event.Level;

import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.log.model.LogEntryMessages.RECEIVED_REQUEST_MESSAGE_FORMAT;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A retained entry whose message quotes a request or response must not keep its rendered message: that text
 * repeats the body (escaped, so several times its size) and the event log's byte budget does not count it.
 */
public class LogEntryRenderedMessageRetentionTest {

    private static boolean collected(WeakReference<String> reference, long seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (reference.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.yield();
        }
        return reference.get() == null;
    }

    private static WeakReference<String> renderedMessageOf(LogEntry entry) {
        return new WeakReference<>(entry.getMessage());
    }

    @Test
    public void shouldNotRetainTheRenderedMessageOfAnEntryQuotingARequest() {
        HttpRequest request = request().withMethod("POST").withPath("/upload").withBody("body");
        LogEntry entry = new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setLogLevel(Level.INFO)
            .setHttpRequest(request)
            .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
            .setArguments(request);

        WeakReference<String> message = renderedMessageOf(entry);

        assertThat(collected(message, 20), is(true));
        assertThat(entry.getMessage().contains("\"body\" : \"body\""), is(true));
    }

    @Test
    public void shouldNotRetainTheRenderedMessageOfAnEntryQuotingAResponseOrCurl() {
        HttpRequest request = request().withMethod("POST").withPath("/upload").withBody("body");
        LogEntry entry = new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(Level.INFO)
            .setHttpRequest(request)
            .setMessageFormat("returning response:{}for forwarded request in curl:{}")
            .setArguments(response("ok"), DeferredLogArgument.curl(new HttpRequestToCurlSerializer(new MockServerLogger()), request, new InetSocketAddress("localhost", 1080)));

        WeakReference<String> message = renderedMessageOf(entry);

        assertThat(collected(message, 20), is(true));
    }

    @Test
    public void shouldKeepTheRenderedMessageOfAnEntryWithOnlyTextArguments() {
        LogEntry entry = new LogEntry()
            .setLogLevel(Level.INFO)
            .setMessageFormat("started on port:{}")
            .setArguments("1080");

        WeakReference<String> message = renderedMessageOf(entry);

        // the control: a memoised message stays reachable, so the two tests above do observe retention
        assertThat(collected(message, 2), is(false));
    }
}
