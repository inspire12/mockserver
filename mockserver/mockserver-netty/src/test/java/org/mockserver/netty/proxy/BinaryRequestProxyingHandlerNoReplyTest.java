package org.mockserver.netty.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Test;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.model.BinaryRequestDefinition;
import org.mockserver.model.BinaryResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockito.ArgumentCaptor;
import org.slf4j.event.Level;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.BinaryRequestDefinition.binaryRequest;
import static org.mockserver.model.BinaryResponse.binaryResponse;

/**
 * A binary expectation whose response has no data, or empty data, is for a message that has no reply: nothing is
 * written and the connection stays open.
 */
public class BinaryRequestProxyingHandlerNoReplyTest {

    private static final byte[] NO_REPLY_EXPECTED = {'H', 0, 0, 0, 4};
    private static final byte[] SYNC = {'S', 0, 0, 0, 4};
    private static final byte[] READY = {'Z', 0, 0, 0, 5, 'I'};

    private final MockServerLogger logger = mock(MockServerLogger.class);
    private final HttpState httpState = mock(HttpState.class);
    private final EmbeddedChannel channel = new EmbeddedChannel();

    @After
    public void releaseChannel() {
        channel.finishAndReleaseAll();
    }

    private void connectionToAMockServerThatAnswers(BinaryResponse toTheMessageWithNoReply) {
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        when(httpState.firstMatchingExpectation(any(BinaryRequestDefinition.class))).thenAnswer(invocation -> {
            byte[] received = invocation.<BinaryRequestDefinition>getArgument(0).getBinaryData();
            if (Arrays.equals(received, NO_REPLY_EXPECTED)) {
                return new Expectation(binaryRequest(NO_REPLY_EXPECTED)).thenRespondWithBinary(toTheMessageWithNoReply);
            } else if (Arrays.equals(received, SYNC)) {
                return new Expectation(binaryRequest(SYNC)).thenRespondWithBinary(binaryResponse(READY));
            }
            return null;
        });
        channel.pipeline().addLast(new BinaryRequestProxyingHandler(configuration(), logger, mock(Scheduler.class), mock(NettyHttpClient.class), httpState));
    }

    private String written() {
        ByteBuf written = channel.readOutbound();
        if (written == null) {
            return null;
        }
        try {
            return ByteBufUtil.hexDump(written);
        } finally {
            written.release();
        }
    }

    private List<String> logged() {
        ArgumentCaptor<LogEntry> entries = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger, atLeastOnce()).logEvent(entries.capture());
        return entries.getAllValues().stream().map(LogEntry::getMessageFormat).collect(Collectors.toList());
    }

    private void assertNothingIsWrittenAndTheConnectionStaysOpen() {
        channel.writeInbound(Unpooled.copiedBuffer(NO_REPLY_EXPECTED));

        // rethrows what the handler threw, if it did
        channel.checkException();
        assertThat("nothing is written", written(), is(nullValue()));
        assertThat("the connection stays open", channel.isOpen(), is(true));
        assertThat(logged(), hasItem("returning nothing, as the binary mock response is empty, for binary request:{}"));
        assertThat("no reply is not no match", logged(), not(hasItem("no matching binary expectation for binary request:{}")));

        channel.writeInbound(Unpooled.copiedBuffer(SYNC));
        assertThat("and the next message is answered", written(), is(ByteBufUtil.hexDump(READY)));
        assertThat(channel.isOpen(), is(true));
    }

    @Test
    public void shouldWriteNothingWhenTheResponseHasNoData() {
        connectionToAMockServerThatAnswers(binaryResponse());

        assertNothingIsWrittenAndTheConnectionStaysOpen();
    }

    @Test
    public void shouldWriteNothingWhenTheResponseHasEmptyData() {
        connectionToAMockServerThatAnswers(binaryResponse(new byte[0]));

        assertNothingIsWrittenAndTheConnectionStaysOpen();
    }

    @Test
    public void shouldStillCloseAConnectionWhoseMessageMatchesNoExpectation() {
        connectionToAMockServerThatAnswers(binaryResponse());

        channel.writeInbound(Unpooled.copiedBuffer(new byte[]{'?'}));

        assertThat(new String(ByteBufUtil.decodeHexDump(written())), is("unknown message format, only HTTP requests are supported for mocking or HTTP & binary requests for proxying, but request is not being proxied and request is not valid HTTP"));
        assertThat(channel.isOpen(), is(false));
    }
}
