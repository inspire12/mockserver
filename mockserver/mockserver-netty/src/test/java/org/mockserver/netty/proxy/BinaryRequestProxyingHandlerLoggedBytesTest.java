package org.mockserver.netty.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.model.BinaryMessage;
import org.mockserver.model.BinaryRequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.model.BinaryRequestDefinition.binaryRequest;
import static org.mockserver.model.BinaryResponse.binaryResponse;

/**
 * Each log entry that shows a binary message, forwarded or mocked, shows at most its first maxLoggedBodyBytes bytes
 * and its whole length; and a forward that fails is logged once, in one entry after the message's own record.
 */
public class BinaryRequestProxyingHandlerLoggedBytesTest {

    private static final int LENGTH = 1_000;
    private static final int LOGGED = 16;
    private static final byte[] REQUEST = filled('A');
    private static final byte[] RESPONSE = filled('B');

    private final NettyHttpClient httpClient = mock(NettyHttpClient.class);
    private final MockServerLogger mockServerLogger = mock(MockServerLogger.class);
    private final HttpState httpState = mock(HttpState.class);
    private EmbeddedChannel channel;

    private static byte[] filled(char character) {
        byte[] bytes = new byte[LENGTH];
        Arrays.fill(bytes, (byte) character);
        return bytes;
    }

    @After
    public void releaseChannel() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    private Configuration bounded() {
        return configuration().maxLoggedBodyBytes(LOGGED).forwardBinaryRequestsUseSingleConnection(false).maxSocketTimeoutInMillis(2_000L);
    }

    private void receive(Configuration configuration, boolean forwarded) {
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        channel = new EmbeddedChannel();
        if (forwarded) {
            channel.attr(REMOTE_SOCKET).set(new InetSocketAddress("127.0.0.1", 1234));
        }
        // synchronous: what is scheduled runs on the calling thread
        channel.pipeline().addLast(new BinaryRequestProxyingHandler(configuration, mockServerLogger, new Scheduler(configuration, mockServerLogger, true), httpClient, httpState));
        channel.writeInbound(Unpooled.copiedBuffer(REQUEST));
        channel.runPendingTasks();
    }

    private void upstreamAnswers(CompletableFuture<BinaryMessage> response) {
        when(httpClient.sendRequest(any(BinaryMessage.class), anyBoolean(), any(InetSocketAddress.class), any())).thenReturn(response);
        when(httpClient.sendRequest(any(BinaryMessage.class), anyBoolean(), any(InetSocketAddress.class), any(), any())).thenReturn(response);
    }

    private static CompletableFuture<BinaryMessage> failedWith(Throwable failure) {
        CompletableFuture<BinaryMessage> response = new CompletableFuture<>();
        response.completeExceptionally(failure);
        return response;
    }

    private List<LogEntry> logged() {
        ArgumentCaptor<LogEntry> entries = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, atLeast(0)).logEvent(entries.capture());
        return entries.getAllValues();
    }

    private LogEntry loggedEntry(String messageFormatStart) {
        List<LogEntry> found = logged().stream().filter(entry -> entry.getMessageFormat().startsWith(messageFormatStart)).collect(Collectors.toList());
        assertThat(messageFormatStart, found, hasSize(1));
        return found.get(0);
    }

    /** Every message in the entry is cut to its first bytes, each followed by its whole length. */
    private static void assertBounded(LogEntry entry, int messagesShown) {
        String text = entry.getMessage(configuration());
        String leftOut = "...(" + LENGTH + " bytes, only the first " + LOGGED + " logged, maxLoggedBodyBytes)";
        assertThat(text, text.split(java.util.regex.Pattern.quote(leftOut), -1).length - 1, is(messagesShown));
        for (String notLogged : new String[]{"41".repeat(LOGGED + 1), "42".repeat(LOGGED + 1), "A".repeat(LOGGED + 1), "B".repeat(LOGGED + 1)}) {
            assertThat(text, not(containsString(notLogged)));
        }
    }

    @Test
    public void shouldBoundAMessageThatMatchesNoExpectationInEachEntry() {
        receive(bounded(), false);

        assertBounded(loggedEntry("received binary request"), 1);
        assertBounded(loggedEntry("no matching binary expectation"), 1);
        LogEntry unknownFormat = loggedEntry("unknown message format");
        assertBounded(unknownFormat, 2);
        assertThat("its text too", unknownFormat.getMessage(configuration()), containsString("A".repeat(LOGGED) + "...(" + LENGTH + " bytes"));
    }

    @Test
    public void shouldBoundAMockedMessageAndItsResponse() {
        when(httpState.firstMatchingExpectation(any(BinaryRequestDefinition.class))).thenReturn(new Expectation(binaryRequest(REQUEST)).thenRespondWithBinary(binaryResponse(RESPONSE)));

        receive(bounded(), false);

        assertBounded(loggedEntry("returning binary mock response"), 2);
    }

    @Test
    public void shouldBoundAMockedMessageThatHasNoReply() {
        when(httpState.firstMatchingExpectation(any(BinaryRequestDefinition.class))).thenReturn(new Expectation(binaryRequest(REQUEST)).thenRespondWithBinary(binaryResponse(new byte[0])));

        receive(bounded(), false);

        assertBounded(loggedEntry("returning nothing"), 1);
    }

    @Test
    public void shouldBoundAForwardedMessageAndItsResponseWhetherOrNotItWaits() {
        for (boolean withoutWaiting : new boolean[]{false, true}) {
            upstreamAnswers(CompletableFuture.completedFuture(BinaryMessage.bytes(RESPONSE)));

            receive(bounded().forwardBinaryRequestsWithoutWaitingForResponse(withoutWaiting), true);

            assertBounded(loggedEntry("received binary request"), 1);
            assertBounded(loggedEntry("returning binary response"), 2);
            channel.finishAndReleaseAll();
            org.mockito.Mockito.clearInvocations(mockServerLogger);
        }
    }

    @Test
    public void shouldLogEveryByteWithoutALimit() {
        receive(bounded().maxLoggedBodyBytes(0), false);

        assertThat(loggedEntry("received binary request").getMessage(configuration()), containsString("41".repeat(LENGTH)));
    }

    /** The message's own record, then the failure, and nothing else. */
    private LogEntry failedOnce() {
        List<LogEntry> entries = logged();
        assertThat(entries.get(0).getType(), is(RECEIVED_REQUEST));
        assertThat(entries.toString(), entries, hasSize(2));
        assertThat("the client is told by its connection closing", channel.isOpen(), is(false));
        LogEntry failure = entries.get(1);
        assertThat(failure.getLogLevel(), is(Level.WARN));
        assertThat(failure.getMessageFormat(), containsString("closing connection"));
        assertBounded(failure, 1);
        return failure;
    }

    @Test
    public void shouldLogAForwardThatFailsOnItsUpstreamConnectionOnceWithoutAStackTrace() {
        for (boolean withoutWaiting : new boolean[]{false, true}) {
            upstreamAnswers(failedWith(new IOException("Connection reset by peer")));

            receive(bounded().forwardBinaryRequestsWithoutWaitingForResponse(withoutWaiting), true);

            LogEntry failure = failedOnce();
            assertThat(failure.getArguments()[0], is("java.io.IOException: Connection reset by peer"));
            assertThat(failure.getThrowable(), is(nullValue()));
            channel.finishAndReleaseAll();
            org.mockito.Mockito.clearInvocations(mockServerLogger);
        }
    }

    @Test
    public void shouldLogAForwardThatHadNoResponseInTimeOnceWithoutAStackTrace() {
        upstreamAnswers(new CompletableFuture<>());

        receive(bounded().maxSocketTimeoutInMillis(50L), true);

        LogEntry failure = failedOnce();
        assertThat(String.valueOf(failure.getArguments()[0]), containsString("Response was not received after 50 milliseconds"));
        assertThat(failure.getThrowable(), is(nullValue()));
    }

    @Test
    public void shouldKeepTheStackTraceOfAFailureThatIsNotItsUpstreamConnections() {
        upstreamAnswers(failedWith(new IllegalStateException("not expected")));

        receive(bounded(), true);

        LogEntry failure = failedOnce();
        assertThat(failure.getArguments()[0], is("java.lang.IllegalStateException: not expected"));
        assertThat(failure.getThrowable(), is(notNullValue()));
    }
}
