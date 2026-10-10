package org.mockserver.netty.proxy;

import org.junit.After;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.netty.proxy.FramerUnderTest.joined;
import static org.mockserver.netty.proxy.MysqlMessageFramer.CLIENT_SSL;
import static org.mockserver.netty.proxy.MysqlMessageFramer.MAX_PACKET_PAYLOAD;

/**
 * A MySQL client's packets, cut where the protocol says each ends: however they are read, each is passed on whole and
 * alone, a payload sent as several packets is one message, a declared length over the limit closes the connection,
 * a TLS handshake is looked for only after an SSLRequest, and nothing is kept or leaked.
 */
public class MysqlMessageFramerTest {

    private static final int LIMIT = 1024;

    private final MockServerLogger logger = mock(MockServerLogger.class);
    private FramerUnderTest connection;
    private MysqlMessageFramer framer;

    public MysqlMessageFramerTest() {
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        limit(LIMIT);
    }

    private void limit(int limit) {
        framer = new MysqlMessageFramer(limit, logger);
        connection = new FramerUnderTest(framer);
    }

    @After
    public void releaseEverything() {
        connection.releaseEverything();
    }

    /** A packet: 3-byte little-endian payload length, sequence id, then a payload of the length given. */
    static byte[] packet(int sequence, int payloadLength, byte fill) {
        byte[] packet = new byte[4 + payloadLength];
        packet[0] = (byte) payloadLength;
        packet[1] = (byte) (payloadLength >> 8);
        packet[2] = (byte) (payloadLength >> 16);
        packet[3] = (byte) sequence;
        Arrays.fill(packet, 4, packet.length, fill);
        return packet;
    }

    static byte[] sslRequest() {
        byte[] request = packet(1, 32, (byte) 0);
        request[4] = (byte) 0x0d;
        request[5] = (byte) ((CLIENT_SSL >> 8) | 0xa2);
        return request;
    }

    @Test
    public void shouldPassOnAPacketReadOneByteAtATimeAsOneMessage() {
        byte[] query = packet(0, 41, (byte) 'q');

        connection.readsOneByteAtATime(Arrays.copyOf(query, query.length - 1));
        assertThat("nothing until the packet is whole", connection.messages, is(empty()));
        assertThat(framer.atMessageBoundary(), is(false));
        connection.reads(query, query.length - 1, query.length);

        assertThat(connection.messages, contains(query));
        assertThat(framer.atMessageBoundary(), is(true));
    }

    @Test
    public void shouldPassOnPacketsReadTogetherAsSeparateMessages() {
        byte[] prepare = packet(0, 30, (byte) 'p');
        byte[] execute = packet(0, 20, (byte) 'e');
        byte[] empty = packet(3, 0, (byte) 0);
        byte[] quit = packet(0, 1, (byte) 1);

        connection.reads(joined(prepare, execute, empty, quit));

        assertThat(connection.messages, contains(prepare, execute, empty, quit));
    }

    @Test
    public void shouldPassOnTheWholePacketsOfAReadAndHoldTheRest() {
        byte[] first = packet(0, 40, (byte) 'a');
        byte[] second = packet(0, 60, (byte) 'b');
        byte[] both = joined(first, second);

        connection.reads(both, 0, 46);
        assertThat(connection.messages, contains(first));
        connection.reads(both, 46, both.length);

        assertThat(connection.messages, contains(first, second));
    }

    @Test
    public void shouldPassOnAPacketExactlyAtTheLimit() {
        byte[] query = packet(0, LIMIT - 4, (byte) 'q');

        connection.reads(query);

        assertThat(connection.messages, contains(query));
        assertThat(connection.channel.isOpen(), is(true));
    }

    @Test
    public void shouldCloseAConnectionThatDeclaresAPacketOverTheLimitWithoutWaitingForIt() {
        byte[] tooLong = packet(0, LIMIT - 3, (byte) 'q');

        connection.reads(tooLong, 0, 4);

        assertThat("refused on its header alone", connection.channel.isOpen(), is(false));
        assertThat(connection.messages, is(empty()));
        verify(logger).logEvent(argThat(entry -> entry.getLogLevel() == Level.WARN && entry.getMessage().contains("a message of " + (LIMIT + 1) + " bytes, over the limit of " + LIMIT + " bytes (maxRequestBodySize)")));
    }

    @Test
    public void shouldCloseAConnectionThatDeclaresAMultiPacketPayloadOverTheLimitOnItsFirstHeader() {
        connection.reads(new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, 0});

        assertThat(connection.channel.isOpen(), is(false));
        verify(logger).logEvent(argThat(entry -> entry.getMessage().contains("a message of more than " + (MAX_PACKET_PAYLOAD + 4) + " bytes")));
    }

    @Test
    public void shouldPassOnAPayloadSentAsSeveralPacketsAsOneMessage() {
        connection.releaseEverything();
        limit(2 * MAX_PACKET_PAYLOAD + 64);
        byte[] full = packet(0, MAX_PACKET_PAYLOAD, (byte) 'x');
        byte[] last = packet(1, 10, (byte) 'y');
        byte[] next = packet(0, 5, (byte) 'n');
        byte[] all = joined(full, last, next);

        connection.reads(all, 0, 1000);
        connection.reads(all, 1000, full.length + 2);
        assertThat("the full packet alone is not a message", connection.messages, is(empty()));
        connection.reads(all, full.length + 2, all.length);

        assertThat(connection.messages, hasSize(2));
        assertThat(Arrays.equals(connection.messages.get(0), joined(full, last)), is(true));
        assertThat(connection.messages.get(1), is(next));
    }

    @Test
    public void shouldEndAPayloadOfExactlyOneFullPacketWithAnEmptyPacket() {
        connection.releaseEverything();
        limit(MAX_PACKET_PAYLOAD + 64);
        byte[] full = packet(4, MAX_PACKET_PAYLOAD, (byte) 'x');
        byte[] empty = packet(5, 0, (byte) 0);

        connection.reads(joined(full, empty));

        assertThat(connection.messages, hasSize(1));
        assertThat(connection.messages.get(0).length, is(full.length + empty.length));
    }

    @Test
    public void shouldCloseAConnectionWhoseContinuationPacketIsOutOfSequence() {
        connection.releaseEverything();
        limit(MAX_PACKET_PAYLOAD + 64);
        byte[] full = packet(255, MAX_PACKET_PAYLOAD, (byte) 'x');

        connection.reads(joined(full, packet(0, 3, (byte) 'y')));
        assertThat("sequence ids wrap from 255 to 0", connection.messages, hasSize(1));
        connection.reads(joined(packet(7, MAX_PACKET_PAYLOAD, (byte) 'x'), packet(9, 3, (byte) 'y')));

        assertThat(connection.channel.isOpen(), is(false));
        assertThat(connection.messages, hasSize(1));
        verify(logger).logEvent(argThat(entry -> entry.getMessage().contains("a continuation packet with sequence id 9 after sequence id 7")));
    }

    @Test
    public void shouldLookForATlsHandshakeOnlyStraightAfterAnSslRequest() {
        assertThat("not before the client has sent anything", framer.tlsMayStartHere(), is(false));

        connection.reads(sslRequest());
        assertThat(framer.tlsMayStartHere(), is(true));

        connection.reads(packet(0, 5, (byte) 3));
        assertThat("not after any other packet", framer.tlsMayStartHere(), is(false));
    }

    @Test
    public void shouldNotTakeAHandshakeResponseOrACommandForAnSslRequest() {
        byte[] handshakeResponse = packet(1, 60, (byte) 0);
        handshakeResponse[5] = (byte) (CLIENT_SSL >> 8);
        byte[] commandOfTheSameLength = sslRequest();
        commandOfTheSameLength[3] = 0;
        byte[] withoutClientSsl = packet(1, 32, (byte) 0);

        for (byte[] notAnSslRequest : new byte[][]{handshakeResponse, commandOfTheSameLength, withoutClientSsl}) {
            connection.reads(notAnSslRequest);
            assertThat(framer.tlsMayStartHere(), is(false));
        }
        assertThat(connection.messages, hasSize(3));
    }

    @Test
    public void shouldNotLookForATlsHandshakeInTheMiddleOfAPacket() {
        byte[] request = sslRequest();
        connection.reads(request, 0, 10);

        assertThat(framer.tlsMayStartHere(), is(false));
    }

    @Test
    public void shouldReleaseAPartPacketWhenTheConnectionCloses() {
        byte[] query = packet(0, 500, (byte) 'q');
        connection.reads(query, 0, 200);

        connection.channel.close();

        assertThat("a part of a packet is not a message", connection.messages, is(empty()));
        // releaseEverything asserts that the part held was released
    }

    @Test
    public void shouldNotLogWhenNothingIsRefused() {
        connection.reads(joined(packet(0, 10, (byte) 'a'), packet(0, 20, (byte) 'b')));

        verify(logger, never()).logEvent(any(LogEntry.class));
        assertThat(connection.messages, hasSize(2));
    }
}
