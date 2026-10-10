package org.mockserver.closurecallback.websocketclient;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.ssl.NotSslRecordException;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockserver.closurecallback.websocketclient.WebSocketClient.REGISTRATION_FUTURE;

/**
 * The callback client's TLS handler is the JDK's, so a server that answers with bytes that are not a TLS record fails
 * the connection with a hex dump of them: its entry holds their count instead, with the stack traces.
 */
public class WebSocketClientHandlerFaultLogEntryBoundTest {

    private static final int BYTES_READ = 20_000;

    private final List<LogEntry> logged = new ArrayList<>();
    private final MockServerLogger mockServerLogger = new MockServerLogger(WebSocketClientHandlerFaultLogEntryBoundTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return true;
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry);
        }
    };

    @Test
    public void shouldLogAServerThatIsNotTlsWithTheCountOfItsBytes() throws Exception {
        NotSslRecordException notTls = new NotSslRecordException("not an SSL/TLS record: " + "41".repeat(BYTES_READ));
        Throwable fault = new DecoderException(notTls);
        EmbeddedChannel channel = new EmbeddedChannel(new HttpClientCodec(), new HttpObjectAggregator(1024),
            new WebSocketClientHandler(mockServerLogger, "clientId", new InetSocketAddress("localhost", 1080), "", mock(WebSocketClient.class), true));
        CompletableFuture<String> registration = new CompletableFuture<>();
        channel.attr(REGISTRATION_FUTURE).set(registration);
        logged.clear();

        channel.pipeline().fireExceptionCaught(fault);

        List<LogEntry> caught = logged.stream().filter(entry -> "web socket client caught exception".equals(entry.getMessageFormat())).collect(java.util.stream.Collectors.toList());
        assertThat(caught, hasSize(1));
        Throwable attached = caught.get(0).getThrowable();
        assertThat(attached.toString(), is("io.netty.handler.codec.DecoderException: io.netty.handler.ssl.NotSslRecordException: not an SSL/TLS record: " + BYTES_READ + " bytes"));
        assertThat(attached.getCause().toString(), is("io.netty.handler.ssl.NotSslRecordException: not an SSL/TLS record: " + BYTES_READ + " bytes"));
        assertThat(attached.getStackTrace(), is(fault.getStackTrace()));
        assertThat("registration still fails with the exception itself", registration.isCompletedExceptionally(), is(true));
        channel.finishAndReleaseAll();
    }
}
