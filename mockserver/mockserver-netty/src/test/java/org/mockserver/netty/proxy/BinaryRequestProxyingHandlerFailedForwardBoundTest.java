package org.mockserver.netty.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.NotSslRecordException;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.BinaryMessage;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;

/**
 * A binary forward that fails because the upstream's answer was not a TLS record is logged with the count of the
 * bytes, not a hex dump of every one of them, and with no stack trace, whether or not the forward waits for its
 * response.
 */
public class BinaryRequestProxyingHandlerFailedForwardBoundTest {

    private static final int BYTES_READ = 60_000;

    private final NettyHttpClient httpClient = mock(NettyHttpClient.class);
    private final MockServerLogger mockServerLogger = mock(MockServerLogger.class);

    private static CompletableFuture<BinaryMessage> notATlsRecord() {
        CompletableFuture<BinaryMessage> response = new CompletableFuture<>();
        response.completeExceptionally(new DecoderException(new NotSslRecordException("not an SSL/TLS record: " + "41".repeat(BYTES_READ))));
        return response;
    }

    private LogEntry failedForwardEntry(Configuration configuration) {
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        when(httpClient.sendRequest(any(BinaryMessage.class), anyBoolean(), any(InetSocketAddress.class), anyLong())).thenAnswer(invocation -> notATlsRecord());
        when(httpClient.sendRequest(any(BinaryMessage.class), anyBoolean(), any(InetSocketAddress.class), any(), any())).thenAnswer(invocation -> notATlsRecord());
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(REMOTE_SOCKET).set(new InetSocketAddress("127.0.0.1", 1234));
        // synchronous: the response is handled on the calling thread
        channel.pipeline().addLast(new BinaryRequestProxyingHandler(configuration, mockServerLogger, new Scheduler(configuration, mockServerLogger, true), httpClient, mock(HttpState.class)));

        channel.writeInbound(Unpooled.copiedBuffer("binary request", StandardCharsets.UTF_8));
        channel.runPendingTasks();

        ArgumentCaptor<LogEntry> entries = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, atLeast(0)).logEvent(entries.capture());
        List<LogEntry> warnings = entries.getAllValues().stream().filter(entry -> entry.getLogLevel() == Level.WARN).collect(Collectors.toList());
        assertThat(warnings, hasSize(1));
        assertThat("the connection is closed", channel.isOpen(), is(false));
        channel.finishAndReleaseAll();
        return warnings.get(0);
    }

    private static void assertBounded(LogEntry entry) {
        String text = entry.getMessage(configuration());
        assertThat(text, containsString("not an SSL/TLS record: " + BYTES_READ + " bytes"));
        assertThat(text, not(containsString("4141")));
        assertThat(text.length(), lessThan(BYTES_READ));
        assertThat("a TLS failure of the upstream connection is named, without a stack trace", entry.getThrowable(), is(nullValue()));
    }

    @Test
    public void shouldLogAForwardThatWaitedForAnAnswerThatWasNotATlsRecordWithTheCountOfItsBytes() {
        assertBounded(failedForwardEntry(configuration().forwardBinaryRequestsUseSingleConnection(false).forwardBinaryRequestsWithoutWaitingForResponse(false).maxSocketTimeoutInMillis(2_000L)));
    }

    @Test
    public void shouldLogAForwardThatDidNotWaitForAnAnswerThatWasNotATlsRecordWithTheCountOfItsBytes() {
        assertBounded(failedForwardEntry(configuration().forwardBinaryRequestsUseSingleConnection(false).forwardBinaryRequestsWithoutWaitingForResponse(true).maxSocketTimeoutInMillis(2_000L)));
    }
}
