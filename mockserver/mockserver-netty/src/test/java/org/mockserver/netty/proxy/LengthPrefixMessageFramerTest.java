package org.mockserver.netty.proxy;

import org.junit.After;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.netty.proxy.FramerUnderTest.joined;

/**
 * Messages that carry their own length, in every size, byte order and offset the framing allows: however they are
 * read, each is passed on whole and alone, a declared length over the limit, or one shorter than the bytes it must
 * count, closes the connection, and nothing is kept or leaked.
 */
public class LengthPrefixMessageFramerTest {

    private static final int LIMIT = 1024;

    private final MockServerLogger logger = mock(MockServerLogger.class);
    private final List<FramerUnderTest> connections = new ArrayList<>();

    public LengthPrefixMessageFramerTest() {
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
    }

    @After
    public void releaseEverything() {
        for (FramerUnderTest connection : connections) {
            connection.releaseEverything();
        }
    }

    private FramerUnderTest connection(int offset, int prefixBytes, ByteOrder byteOrder, boolean includesPrefix) {
        FramerUnderTest connection = new FramerUnderTest(new LengthPrefixMessageFramer(LIMIT, logger, offset, prefixBytes, byteOrder, includesPrefix));
        connections.add(connection);
        return connection;
    }

    /** A message: offset bytes of 'h', the length field, then a body of 'b', its length counted as configured. */
    private static byte[] message(int offset, int prefixBytes, ByteOrder byteOrder, boolean includesPrefix, int bodyBytes) {
        long length = includesPrefix ? offset + prefixBytes + bodyBytes : bodyBytes;
        return message(offset, prefixBytes, byteOrder, length, bodyBytes);
    }

    private static byte[] message(int offset, int prefixBytes, ByteOrder byteOrder, long declaredLength, int bodyBytes) {
        ByteBuffer message = ByteBuffer.allocate(offset + prefixBytes + bodyBytes).order(byteOrder);
        for (int i = 0; i < offset; i++) {
            message.put((byte) 'h');
        }
        switch (prefixBytes) {
            case 1:
                message.put((byte) declaredLength);
                break;
            case 2:
                message.putShort((short) declaredLength);
                break;
            case 4:
                message.putInt((int) declaredLength);
                break;
            default:
                message.putLong(declaredLength);
        }
        while (message.hasRemaining()) {
            message.put((byte) 'b');
        }
        return message.array();
    }

    @Test
    public void shouldFrameEverySizeByteOrderAndOffsetWhetherOrNotTheLengthCountsThePrefix() {
        for (int prefixBytes : new int[]{1, 2, 4, 8}) {
            for (ByteOrder byteOrder : new ByteOrder[]{BIG_ENDIAN, LITTLE_ENDIAN}) {
                for (int offset : new int[]{0, 3}) {
                    for (boolean includesPrefix : new boolean[]{false, true}) {
                        String description = prefixBytes + " bytes " + byteOrder + " offset " + offset + (includesPrefix ? " including the prefix" : "");
                        FramerUnderTest connection = connection(offset, prefixBytes, byteOrder, includesPrefix);
                        byte[] first = message(offset, prefixBytes, byteOrder, includesPrefix, 200);
                        byte[] empty = message(offset, prefixBytes, byteOrder, includesPrefix, 0);
                        byte[] third = message(offset, prefixBytes, byteOrder, includesPrefix, 37);
                        byte[] all = joined(first, empty, third);

                        connection.readsOneByteAtATime(Arrays.copyOf(all, first.length - 1));
                        assertThat(description, connection.messages, is(empty()));
                        connection.reads(all, first.length - 1, all.length);

                        assertThat(description, connection.messages, contains(first, empty, third));
                    }
                }
            }
        }
    }

    @Test
    public void shouldReadTheLengthInTheByteOrderGiven() {
        // 0x0102 big-endian is 258 bytes, little-endian 513: only the right reading ends the message here
        byte[] bigEndian = message(0, 2, BIG_ENDIAN, false, 258);
        byte[] littleEndian = message(0, 2, LITTLE_ENDIAN, false, 513);
        assertThat(Arrays.copyOf(bigEndian, 2), is(new byte[]{1, 2}));
        assertThat(Arrays.copyOf(littleEndian, 2), is(new byte[]{1, 2}));

        FramerUnderTest big = connection(0, 2, BIG_ENDIAN, false);
        big.reads(bigEndian);
        FramerUnderTest little = connection(0, 2, LITTLE_ENDIAN, false);
        little.reads(littleEndian);

        assertThat(big.messages, contains(bigEndian));
        assertThat(little.messages, contains(littleEndian));
    }

    @Test
    public void shouldReadTheLengthAsUnsigned() {
        FramerUnderTest connection = connection(0, 1, BIG_ENDIAN, false);
        byte[] message = message(0, 1, BIG_ENDIAN, false, 200);

        connection.reads(message);

        assertThat("a length byte of 200 is not -56", connection.messages, contains(message));
    }

    @Test
    public void shouldPassOnAMessageExactlyAtTheLimit() {
        FramerUnderTest connection = connection(2, 4, BIG_ENDIAN, true);
        byte[] message = message(2, 4, BIG_ENDIAN, true, LIMIT - 6);

        connection.reads(message);

        assertThat(connection.messages, contains(message));
        assertThat(connection.channel.isOpen(), is(true));
    }

    @Test
    public void shouldCloseAConnectionThatDeclaresAMessageOverTheLimitWithoutWaitingForIt() {
        for (boolean includesPrefix : new boolean[]{false, true}) {
            FramerUnderTest connection = connection(2, 4, BIG_ENDIAN, includesPrefix);
            byte[] tooLong = message(2, 4, BIG_ENDIAN, includesPrefix, LIMIT - 5);

            connection.reads(tooLong, 0, 6);

            assertThat("refused on its header alone", connection.channel.isOpen(), is(false));
            assertThat(connection.messages, is(empty()));
        }
        verify(logger, org.mockito.Mockito.times(2)).logEvent(argThat(entry -> entry.getLogLevel() == Level.WARN && entry.getMessage().contains("a message of " + (LIMIT + 1) + " bytes, over the limit of " + LIMIT + " bytes (maxRequestBodySize)")));
    }

    @Test
    public void shouldCloseAConnectionThatDeclaresAnEightByteLengthOverTheLargestSignedNumber() {
        FramerUnderTest connection = connection(0, 8, BIG_ENDIAN, false);

        connection.reads(new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xfe});

        assertThat(connection.channel.isOpen(), is(false));
        verify(logger).logEvent(argThat(entry -> entry.getMessage().contains("a length of 18446744073709551614 bytes")));
    }

    @Test
    public void shouldCloseAConnectionWhoseLengthIsShorterThanTheBytesItMustCount() {
        FramerUnderTest connection = connection(3, 2, BIG_ENDIAN, true);
        byte[] valid = message(3, 2, BIG_ENDIAN, true, 0);
        connection.reads(valid);

        connection.reads(message(3, 2, BIG_ENDIAN, 4L, 10));

        assertThat("a length that counts the prefix is at least the prefix", connection.channel.isOpen(), is(false));
        assertThat(connection.messages, contains(valid));
        verify(logger).logEvent(argThat(entry -> entry.getMessage().contains("a length of 4, less than the 5 bytes before the body that it counts")));
    }

    @Test
    public void shouldDropWhatFollowsARefusedHeaderInTheSameReadAndPassOnNothingMore() {
        FramerUnderTest connection = connection(0, 4, BIG_ENDIAN, false);

        connection.reads(joined(Arrays.copyOf(message(0, 4, BIG_ENDIAN, false, LIMIT), 4), message(0, 4, BIG_ENDIAN, false, 10)));

        assertThat(connection.channel.isOpen(), is(false));
        assertThat(connection.messages, is(empty()));
    }

    @Test
    public void shouldCloseAConnectionThatSendsMoreThanTheLimitBeforeItsLengthField() {
        FramerUnderTest connection = new FramerUnderTest(new LengthPrefixMessageFramer(16, logger, 20, 4, BIG_ENDIAN, false));
        connections.add(connection);

        connection.reads(new byte[17]);

        assertThat(connection.channel.isOpen(), is(false));
        verify(logger).logEvent(argThat(entry -> entry.getMessage().contains("more than 16 bytes (maxRequestBodySize) without the end of a message")));
    }

    @Test
    public void shouldReleaseAPartMessageWhenTheConnectionCloses() {
        FramerUnderTest connection = connection(0, 4, BIG_ENDIAN, false);
        connection.reads(message(0, 4, BIG_ENDIAN, false, 500), 0, 200);

        connection.channel.close();

        assertThat(connection.messages, is(empty()));
        // releaseEverything asserts that the part held was released
    }

    @Test
    public void shouldNotLogWhenNothingIsRefused() {
        FramerUnderTest connection = connection(1, 2, LITTLE_ENDIAN, false);

        connection.reads(joined(message(1, 2, LITTLE_ENDIAN, false, 10), message(1, 2, LITTLE_ENDIAN, false, 0)));

        verify(logger, never()).logEvent(any(LogEntry.class));
        assertThat(connection.messages, hasSize(2));
    }

    @Test
    public void shouldRefuseAPrefixSizeOrOffsetTheFramingDoesNotHave() {
        assertThrows(IllegalArgumentException.class, () -> new LengthPrefixMessageFramer(LIMIT, logger, 0, 3, BIG_ENDIAN, false));
        assertThrows(IllegalArgumentException.class, () -> new LengthPrefixMessageFramer(LIMIT, logger, -1, 4, BIG_ENDIAN, false));
    }
}
