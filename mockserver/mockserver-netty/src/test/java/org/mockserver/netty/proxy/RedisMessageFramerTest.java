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
import static org.mockserver.netty.proxy.FramerUnderTest.ascii;
import static org.mockserver.netty.proxy.FramerUnderTest.joined;
import static org.mockserver.netty.proxy.RedisMessageFramer.MAX_DEPTH;

/**
 * A Redis client's values, cut where RESP says each top-level value ends: however they are read, each is passed on
 * whole and alone, nested values included; an inline command is its line; a declared length over the limit, nesting
 * past the bound or bytes RESP does not allow close the connection; and nothing is kept or leaked.
 */
public class RedisMessageFramerTest {

    private static final int LIMIT = 1024;

    private final MockServerLogger logger = mock(MockServerLogger.class);
    private FramerUnderTest connection;
    private RedisMessageFramer framer;

    public RedisMessageFramerTest() {
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        framer = new RedisMessageFramer(LIMIT, logger);
        connection = new FramerUnderTest(framer);
    }

    @After
    public void releaseEverything() {
        connection.releaseEverything();
    }

    private static byte[] command(String... arguments) {
        StringBuilder command = new StringBuilder("*" + arguments.length + "\r\n");
        for (String argument : arguments) {
            command.append('$').append(argument.length()).append("\r\n").append(argument).append("\r\n");
        }
        return ascii(command.toString());
    }

    private void readsInEveryPossibleSplit(byte[] message) {
        for (int split = 1; split < message.length; split++) {
            FramerUnderTest another = new FramerUnderTest(new RedisMessageFramer(LIMIT, logger));
            another.reads(message, 0, split);
            assertThat("nothing after " + split + " of " + message.length + " bytes", another.messages, is(empty()));
            another.reads(message, split, message.length);
            assertThat("split after " + split, another.messages, contains(message));
            another.releaseEverything();
        }
    }

    @Test
    public void shouldPassOnACommandReadOneByteAtATimeAsOneMessage() {
        byte[] set = command("SET", "key", "a value with\r\nCRLF inside");

        connection.readsOneByteAtATime(Arrays.copyOf(set, set.length - 1));
        assertThat("nothing until the value is whole", connection.messages, is(empty()));
        assertThat(framer.atMessageBoundary(), is(false));
        connection.reads(set, set.length - 1, set.length);

        assertThat(connection.messages, contains(set));
        assertThat(framer.atMessageBoundary(), is(true));
    }

    @Test
    public void shouldPassOnPipelinedCommandsReadTogetherAsSeparateMessages() {
        byte[] multi = command("MULTI");
        byte[] incr = command("INCR", "counter");
        byte[] get = command("GET", "counter");
        byte[] exec = command("EXEC");

        connection.reads(joined(multi, incr, get, exec));

        assertThat(connection.messages, contains(multi, incr, get, exec));
    }

    @Test
    public void shouldPassOnEveryRespTwoTypeWhole() {
        byte[][] values = {
            ascii("+OK\r\n"),
            ascii("-ERR unknown command\r\n"),
            ascii(":-42\r\n"),
            ascii("$5\r\nhello\r\n"),
            ascii("$0\r\n\r\n"),
            ascii("$-1\r\n"),
            ascii("*-1\r\n"),
            ascii("*0\r\n"),
            ascii("*3\r\n:1\r\n*2\r\n+a\r\n$1\r\nb\r\n-E\r\n"),
        };

        connection.reads(joined(values));

        assertThat(connection.messages, contains(values));
        for (byte[] value : values) {
            readsInEveryPossibleSplit(value);
        }
    }

    @Test
    public void shouldPassOnEveryRespThreeTypeWhole() {
        byte[][] values = {
            ascii("_\r\n"),
            ascii("#t\r\n"),
            ascii(",3.14\r\n"),
            ascii("(3492890328409238509324850943850943825024385\r\n"),
            ascii("!21\r\nSYNTAX invalid syntax\r\n"),
            ascii("=15\r\ntxt:Some string\r\n"),
            ascii("%2\r\n+first\r\n:1\r\n+second\r\n:2\r\n"),
            ascii("~2\r\n+a\r\n+b\r\n"),
            ascii(">2\r\n+pubsub\r\n+message\r\n"),
            ascii("|1\r\n+key-popularity\r\n%2\r\n$1\r\na\r\n,0.19\r\n$1\r\nb\r\n,0.54\r\n*2\r\n:2039123\r\n:9543892\r\n"),
            ascii("*2\r\n|1\r\n+ttl\r\n:3600\r\n+value\r\n:7\r\n"),
            ascii("$?\r\n;4\r\nHell\r\n;5\r\no wor\r\n;1\r\nd\r\n;0\r\n"),
            ascii("*?\r\n:1\r\n:2\r\n*?\r\n+nested\r\n.\r\n.\r\n"),
            ascii("%?\r\n+a\r\n:1\r\n.\r\n"),
            ascii("%0\r\n"),
        };

        connection.reads(joined(values));

        assertThat(connection.messages, contains(values));
        for (byte[] value : values) {
            readsInEveryPossibleSplit(value);
        }
    }

    @Test
    public void shouldPassOnAnInlineCommandAsItsLine() {
        byte[] ping = ascii("PING\r\n");
        byte[] set = ascii("SET key value\n");
        byte[] array = command("GET", "key");

        connection.reads(joined(ping, set, array), 0, 3);
        assertThat(connection.messages, is(empty()));
        connection.reads(joined(ping, set, array), 3, ping.length + set.length + array.length);

        assertThat(connection.messages, contains(ping, set, array));
    }

    @Test
    public void shouldPassOnAMessageExactlyAtTheLimit() {
        String header = "$" + (LIMIT - 9) + "\r\n";
        byte[] bulk = new byte[LIMIT];
        Arrays.fill(bulk, (byte) 'v');
        System.arraycopy(ascii(header), 0, bulk, 0, header.length());
        bulk[LIMIT - 2] = '\r';
        bulk[LIMIT - 1] = '\n';

        connection.reads(bulk);

        assertThat(connection.messages, contains(bulk));
        assertThat(connection.channel.isOpen(), is(true));
    }

    @Test
    public void shouldCloseAConnectionThatDeclaresABulkStringOverTheLimitWithoutWaitingForIt() {
        connection.reads(ascii("*2\r\n$3\r\nGET\r\n$" + (LIMIT - 21) + "\r\n"));

        assertThat("refused on its header alone", connection.channel.isOpen(), is(false));
        assertThat(connection.messages, is(empty()));
        verify(logger).logEvent(argThat(entry -> entry.getLogLevel() == Level.WARN && entry.getMessage().contains("a bulk string of " + (LIMIT - 21) + " bytes, so a message of at least " + (LIMIT + 1) + " bytes, over the limit of " + LIMIT + " bytes (maxRequestBodySize)")));
    }

    @Test
    public void shouldCloseAConnectionThatDeclaresMoreValuesThanTheLimitCanHold() {
        connection.reads(ascii("*400\r\n"));

        assertThat(connection.channel.isOpen(), is(false));
        verify(logger).logEvent(argThat(entry -> entry.getMessage().contains("an aggregate of 400 values, so a message of at least 1206 bytes")));
    }

    @Test
    public void shouldCloseAConnectionThatSendsALineLongerThanTheLimit() {
        byte[] line = new byte[LIMIT + 1];
        Arrays.fill(line, (byte) 'x');

        connection.reads(line, 0, LIMIT);
        assertThat("a line of the limit may still end", connection.channel.isOpen(), is(true));
        connection.reads(line, LIMIT, LIMIT + 1);

        assertThat(connection.channel.isOpen(), is(false));
        verify(logger).logEvent(argThat(entry -> entry.getMessage().contains("more than " + LIMIT + " bytes (maxRequestBodySize) without the end of a message")));
    }

    @Test
    public void shouldBoundHowDeeplyValuesNest() {
        StringBuilder nested = new StringBuilder();
        for (int i = 0; i < MAX_DEPTH; i++) {
            nested.append("*1\r\n");
        }
        byte[] deepest = ascii(nested + ":1\r\n");
        connection.reads(deepest);
        assertThat(connection.messages, contains(deepest));

        connection.reads(ascii(nested + "*1\r\n"));

        assertThat(connection.channel.isOpen(), is(false));
        verify(logger).logEvent(argThat(entry -> entry.getMessage().contains("values nested more than " + MAX_DEPTH + " deep")));
    }

    @Test
    public void shouldCloseAConnectionThatSendsBytesRespDoesNotAllow() {
        String[] notAllowed = {
            "*1\r\nGET\r\n",
            "*1\r\n$3\r\nGETX\r\n",
            "$abc\r\n",
            "$-2\r\n",
            "!-1\r\n",
            "$1234567890123456789\r\n",
            "+OK\n",
            "*2\r\n.\r\n",
            "$?\r\n:1\r\n",
            "*x\r\n",
            "*\r\n",
        };
        for (String bytes : notAllowed) {
            FramerUnderTest another = new FramerUnderTest(new RedisMessageFramer(LIMIT, logger));
            another.reads(ascii(bytes));
            assertThat(bytes, another.channel.isOpen(), is(false));
            assertThat(bytes, another.messages, is(empty()));
            another.releaseEverything();
        }
    }

    @Test
    public void shouldDropWhatFollowsARefusedValueInTheSameReadAndPassOnNothingMore() {
        byte[] ping = command("PING");

        connection.reads(joined(ping, ascii("*1\r\n?\r\n"), ping));

        assertThat(connection.channel.isOpen(), is(false));
        assertThat(connection.messages, contains(ping));
    }

    @Test
    public void shouldReleaseAPartValueWhenTheConnectionCloses() {
        byte[] set = command("SET", "key", "value");
        connection.reads(set, 0, 12);

        connection.channel.close();

        assertThat(connection.messages, is(empty()));
        // releaseEverything asserts that the part held was released
    }

    @Test
    public void shouldNotLogWhenNothingIsRefused() {
        connection.reads(joined(command("PING"), ascii("PING\r\n")));

        verify(logger, never()).logEvent(any(LogEntry.class));
        assertThat(connection.messages, hasSize(2));
    }
}
