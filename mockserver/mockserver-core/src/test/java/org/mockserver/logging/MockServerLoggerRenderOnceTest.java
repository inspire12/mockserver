package org.mockserver.logging;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.DeferredLogArgument;
import org.mockserver.log.model.LogEntry;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.HttpRequest.request;

/**
 * An entry quoting a request does not memoise its rendered message, so writing it to the console must render it
 * once, not once to test it for blank and again to write it.
 */
public class MockServerLoggerRenderOnceTest {

    @Test
    public void shouldRenderAMessageQuotingARequestOnceWhenWritingIt() {
        HttpRequestToCurlSerializer curl = mock(HttpRequestToCurlSerializer.class);
        when(curl.toCurl(any(), any())).thenReturn("curl-command");
        HttpRequest request = request().withMethod("POST").withPath("/upload").withBody("body");
        LogEntry entry = new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(Level.INFO)
            .setHttpRequest(request)
            .setMessageFormat("forwarded request in curl:{}")
            .setArguments(DeferredLogArgument.curl(curl, request, new InetSocketAddress("localhost", 1080)));
        Configuration configuration = configuration().logLevel(Level.INFO).compactLogFormat(false);
        Logger logger = mock(Logger.class);

        MockServerLogger.writeToSystemOut(logger, entry, configuration);

        verify(logger).info(contains("curl-command"), (Throwable) isNull());
        verify(curl, times(1)).toCurl(any(), any());
    }
}
