package org.mockserver.netty.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.argThat;
import static org.mockserver.netty.proxy.PostgresqlMessageFramer.CANCEL_REQUEST_CODE;
import static org.mockserver.netty.proxy.PostgresqlMessageFramer.GSSENC_REQUEST_CODE;
import static org.mockserver.netty.proxy.PostgresqlMessageFramer.SSL_REQUEST_CODE;

/**
 * A PostgreSQL client's messages, cut where the protocol says each ends: however they are read, each is passed on
 * whole and alone, a declared length over the limit or below the protocol's minimum closes the connection, and
 * nothing is kept or leaked.
 */
public class PostgresqlMessageFramerTest {

    private static final int LIMIT = 1024;

    private final MockServerLogger logger = mock(MockServerLogger.class);
    private final List<byte[]> messages = new ArrayList<>();
    private final List<ByteBuf> reads = new ArrayList<>();
    private final EmbeddedChannel channel;
    private final PostgresqlMessageFramer framer = new PostgresqlMessageFramer(LIMIT, logger);

    public PostgresqlMessageFramerTest() {
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        channel = new EmbeddedChannel(framer, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object message) {
                ByteBuf bytes = (ByteBuf) message;
                messages.add(ByteBufUtil.getBytes(bytes));
                bytes.release();
            }
        });
    }

    @After
    public void releaseEverything() {
        channel.finishAndReleaseAll();
        for (ByteBuf read : reads) {
            assertThat("every read is released", read.refCnt(), is(0));
        }
    }

    private static byte[] untyped(int code, int length) {
        ByteBuffer message = ByteBuffer.allocate(length);
        message.putInt(length).putInt(code);
        while (message.hasRemaining()) {
            message.put((byte) 'u');
        }
        return message.array();
    }

    private static byte[] startup(int length) {
        return untyped(196608, length);
    }

    private static byte[] typed(char type, int length) {
        ByteBuffer message = ByteBuffer.allocate(length);
        message.put((byte) type).putInt(length - 1);
        while (message.hasRemaining()) {
            message.put((byte) Character.toLowerCase(type));
        }
        return message.array();
    }

    private static byte[] joined(byte[]... parts) {
        ByteBuffer all = ByteBuffer.allocate(Arrays.stream(parts).mapToInt(part -> part.length).sum());
        for (byte[] part : parts) {
            all.put(part);
        }
        return all.array();
    }

    private void reads(byte[] bytes, int from, int to) {
        ByteBuf read = Unpooled.copiedBuffer(bytes, from, to - from);
        reads.add(read);
        channel.writeInbound(read);
    }

    private void reads(byte[] bytes) {
        reads(bytes, 0, bytes.length);
    }

    private void readsOneByteAtATime(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            reads(bytes, i, i + 1);
        }
    }

    @Test
    public void shouldPassOnAStartupMessageReadOneByteAtATimeAsOneMessage() {
        byte[] startup = startup(41);

        readsOneByteAtATime(Arrays.copyOf(startup, startup.length - 1));
        assertThat("nothing until the message is whole", messages, is(empty()));
        assertThat(framer.atMessageBoundary(), is(false));
        reads(startup, startup.length - 1, startup.length);

        assertThat(messages, contains(startup));
        assertThat(framer.atMessageBoundary(), is(true));
    }

    @Test
    public void shouldPassOnATypedMessageReadInPiecesAsOneMessage() {
        byte[] startup = startup(23);
        byte[] query = typed('Q', 900);
        reads(startup);

        reads(query, 0, 3);
        reads(query, 3, 5);
        reads(query, 5, 600);
        reads(query, 600, 900);

        assertThat(messages, contains(startup, query));
    }

    @Test
    public void shouldPassOnMessagesReadTogetherAsSeparateMessages() {
        byte[] startup = startup(23);
        byte[] parse = typed('P', 30);
        byte[] bind = typed('B', 20);
        byte[] execute = typed('E', 10);
        byte[] sync = typed('S', 5);

        reads(joined(startup, parse, bind, execute, sync));

        assertThat(messages, contains(startup, parse, bind, execute, sync));
    }

    @Test
    public void shouldPassOnTheWholeMessagesOfAReadAndHoldTheRest() {
        byte[] startup = startup(23);
        byte[] first = typed('Q', 40);
        byte[] second = typed('Q', 60);
        reads(startup);
        byte[] both = joined(first, second);

        reads(both, 0, 50);
        assertThat(messages, contains(startup, first));
        reads(both, 50, both.length);

        assertThat(messages, contains(startup, first, second));
    }

    @Test
    public void shouldExpectAnotherUntypedMessageAfterAnSslOrGssEncRequest() {
        byte[] sslRequest = untyped(SSL_REQUEST_CODE, 8);
        byte[] gssEncRequest = untyped(GSSENC_REQUEST_CODE, 8);
        byte[] startup = startup(30);
        byte[] query = typed('Q', 12);

        reads(joined(sslRequest, gssEncRequest, startup, query));

        assertThat(messages, contains(sslRequest, gssEncRequest, startup, query));
    }

    @Test
    public void shouldTakeACancelRequestAsOneUntypedMessage() {
        byte[] cancel = untyped(CANCEL_REQUEST_CODE, 16);

        reads(cancel, 0, 7);
        reads(cancel, 7, 16);

        assertThat(messages, contains(cancel));
        assertThat(framer.atMessageBoundary(), is(true));
    }

    @Test
    public void shouldPassOnAMessageExactlyAtTheLimit() {
        byte[] startup = startup(LIMIT);
        byte[] query = typed('Q', LIMIT);

        reads(joined(startup, query));

        assertThat(messages, contains(startup, query));
        assertThat(channel.isOpen(), is(true));
    }

    @Test
    public void shouldCloseAConnectionThatDeclaresAMessageOverTheLimitWithoutWaitingForIt() {
        byte[] startup = startup(23);
        reads(startup);
        byte[] tooLong = typed('Q', LIMIT + 1);

        reads(tooLong, 0, 5);

        assertThat("refused on its header alone", channel.isOpen(), is(false));
        assertThat(messages, contains(startup));
        assertThat(framer.atMessageBoundary(), is(false));
        verify(logger).logEvent(argThat(entry -> entry.getLogLevel() == Level.WARN && entry.getMessage().contains("a message of " + (LIMIT + 1) + " bytes, over the limit of " + LIMIT + " bytes (maxRequestBodySize)")));
    }

    @Test
    public void shouldCloseAConnectionThatDeclaresAStartupMessageOverTheLimit() {
        reads(new byte[]{0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff});

        assertThat(channel.isOpen(), is(false));
        assertThat(messages, is(empty()));
    }

    @Test
    public void shouldCloseAConnectionThatDeclaresALengthTheProtocolDoesNotAllow() {
        byte[][] notAllowed = {
            {0, 0, 0, 7},
            {(byte) 0x80, 0, 0, 0},
        };
        for (byte[] read : notAllowed) {
            PostgresqlMessageFramer another = new PostgresqlMessageFramer(LIMIT, logger);
            EmbeddedChannel connection = new EmbeddedChannel(another);
            connection.writeInbound(Unpooled.copiedBuffer(read));
            assertThat(Arrays.toString(read), connection.isOpen(), is(false));
            assertThat(Arrays.toString(read), connection.inboundMessages(), is(empty()));
            connection.finishAndReleaseAll();
        }

        reads(startup(23));
        reads(new byte[]{'Q', 0, 0, 0, 3});

        assertThat("a typed length counts itself, so is at least 4", channel.isOpen(), is(false));
        verify(logger).logEvent(argThat(entry -> entry.getMessage().contains("a length of 3 after type byte 81")));
    }

    @Test
    public void shouldDropWhatFollowsARefusedHeaderInTheSameReadAndPassOnNothingMore() {
        byte[] startup = startup(23);
        reads(startup);

        reads(joined(Arrays.copyOf(typed('Q', LIMIT + 1), 5), typed('Q', 10), typed('S', 5)));

        assertThat(channel.isOpen(), is(false));
        assertThat("whole messages after the refused header are not passed on", messages, contains(startup));
    }

    @Test
    public void shouldReleaseAPartMessageWhenTheConnectionCloses() {
        byte[] query = typed('Q', 500);
        reads(startup(23));
        reads(query, 0, 200);

        channel.close();

        assertThat("a part of a message is not a message", messages, hasSize(1));
        // releaseEverything asserts that the part held was released
    }

    @Test
    public void shouldNotLogWhenNothingIsRefused() {
        reads(joined(startup(23), typed('Q', 10)));

        verify(logger, never()).logEvent(any(LogEntry.class));
        assertThat(messages, hasSize(2));
    }
}
