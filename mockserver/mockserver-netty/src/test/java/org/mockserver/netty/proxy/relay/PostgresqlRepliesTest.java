package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.Test;
import org.mockserver.model.BinaryMessage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Which forwarded message each byte a PostgreSQL server sends belongs to, and where each reply ends, however the
 * server's bytes are split into reads.
 */
public class PostgresqlRepliesTest {

    private static final byte[] SSL_REQUEST = {0, 0, 0, 8, 4, (byte) 0xd2, 0x16, 0x2f};
    private static final byte[] CANCEL_REQUEST = {0, 0, 0, 16, 4, (byte) 0xd2, 0x16, 0x2e, 0, 0, 0, 1, 0, 0, 0, 2};
    private static final byte[] STARTUP = {0, 0, 0, 23, 0, 3, 0, 0, 'u', 's', 'e', 'r', 0, 'p', 'o', 's', 't', 'g', 'r', 'e', 's', 0, 0};
    private static final byte[] READY = typed('Z', "I");

    private final PostgresqlReplies replies = new PostgresqlReplies();
    private final List<String> events = new ArrayList<>();
    private final PostgresqlReplies.Output output = new PostgresqlReplies.Output() {
        @Override
        public void bytes(PostgresqlReplies.Reply reply, ByteBuf read, int offset, int length) {
            String to = reply == null ? "none" : reply.handling().relayed() ? "relay" : "drop " + reply.correlationId();
            events.add(to + " " + ByteBufUtil.hexDump(read, offset, length));
        }

        @Override
        public void ended(PostgresqlReplies.Reply reply) {
            events.add("end " + reply.correlationId() + (reply.handling().replacement() != null ? " with " + new String(reply.handling().replacement(), StandardCharsets.UTF_8) : ""));
        }
    };

    private static byte[] typed(char type, String body) {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[5 + content.length];
        message[0] = (byte) type;
        int length = 4 + content.length;
        message[1] = (byte) (length >>> 24);
        message[2] = (byte) (length >>> 16);
        message[3] = (byte) (length >>> 8);
        message[4] = (byte) length;
        System.arraycopy(content, 0, message, 5, content.length);
        return message;
    }

    private static byte[] authentication(int code) {
        return new byte[]{'R', 0, 0, 0, 8, (byte) (code >>> 24), (byte) (code >>> 16), (byte) (code >>> 8), (byte) code};
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            joined.write(part, 0, part.length);
        }
        return joined.toByteArray();
    }

    private static String hex(byte[]... parts) {
        return ByteBufUtil.hexDump(join(parts));
    }

    private void forwarded(byte[] message, String name, UpstreamReply handling) {
        replies.forwarded(BinaryMessage.bytes(message), handling, name, 0, output);
    }

    private void read(byte[]... parts) {
        read(Unpooled.wrappedBuffer(join(parts)));
    }

    /** As the relay hands it over: a buffer whose reader index need not be 0. */
    private void read(ByteBuf buffer) {
        ByteBuf offset = Unpooled.buffer().writeBytes(new byte[]{'x', 'x', 'x'});
        offset.skipBytes(3);
        offset.writeBytes(buffer);
        replies.read(offset, output);
        assertThat("the read is not consumed", offset.readerIndex(), is(3));
        offset.release();
    }

    private void readByteByByte(byte[]... parts) {
        for (byte b : join(parts)) {
            read(Unpooled.wrappedBuffer(new byte[]{b}));
        }
    }

    @Test
    public void shouldDropAQueryReplyUpToItsReadyForQueryAcrossReads() {
        byte[] rows = join(typed('T', "row description"), typed('D', "row"));
        byte[] complete = typed('C', "SELECT 1");
        forwarded(typed('Q', "SELECT 1"), "q", UpstreamReply.drop());

        read(rows);
        read(complete, READY, typed('N', "notice"));

        assertThat(events, contains(
            "drop q " + hex(rows),
            "drop q " + hex(complete, READY),
            "end q",
            "none " + hex(typed('N', "notice"))
        ));
        assertThat(replies.notRelayed(), is(0));
    }

    @Test
    public void shouldFollowMessagesSplitAtEveryByte() {
        byte[] reply = join(typed('C', "SELECT 1"), READY);
        forwarded(typed('Q', "SELECT 1"), "q", UpstreamReply.replaceWith("x".getBytes(StandardCharsets.UTF_8), 0));

        readByteByByte(reply, READY);

        assertThat(events.get(reply.length), is("end q with x"));
        assertThat("the ReadyForQuery after it answers nothing tracked", events.subList(reply.length + 1, events.size()).stream().allMatch(event -> event.startsWith("none ")), is(true));
    }

    @Test
    public void shouldGiveEachPipelinedQueryItsOwnReply() {
        forwarded(typed('Q', "one"), "one", UpstreamReply.RELAY);
        forwarded(typed('Q', "two"), "two", UpstreamReply.drop());
        forwarded(typed('Q', "three"), "three", UpstreamReply.RELAY);
        byte[] first = join(typed('C', "1"), READY);
        byte[] second = join(typed('C', "2"), READY);
        byte[] third = join(typed('C', "3"), READY);

        read(first, second, third);

        assertThat(events, contains(
            "relay " + hex(first),
            "drop two " + hex(second),
            "end two",
            "relay " + hex(third)
        ));
    }

    @Test
    public void shouldShareOneReplyAcrossExtendedQueryMessagesUpToTheirSync() {
        forwarded(typed('P', "parse"), "parse", UpstreamReply.replaceWith("canned".getBytes(StandardCharsets.UTF_8), 0));
        forwarded(typed('B', "bind"), "bind", UpstreamReply.RELAY);
        forwarded(typed('E', "execute"), "execute", UpstreamReply.RELAY);
        forwarded(typed('S', ""), "sync", UpstreamReply.RELAY);
        forwarded(typed('Q', "after"), "after", UpstreamReply.RELAY);
        byte[] batch = join(typed('1', ""), typed('2', ""), typed('D', "row"), typed('C', "SELECT 1"), READY);
        byte[] after = join(typed('C', "after"), READY);

        read(batch, after);

        assertThat(events, contains(
            "drop parse " + hex(batch),
            "end parse with canned",
            "relay " + hex(after)
        ));
    }

    @Test
    public void shouldSplitABatchCountedWithRelayedRepliesWhenALaterMessageOfItIsReplaced() {
        forwarded(typed('Q', "before"), "before", UpstreamReply.RELAY);
        forwarded(typed('P', "parse"), "parse", UpstreamReply.RELAY);
        forwarded(typed('B', "bind"), "bind", UpstreamReply.drop());
        forwarded(typed('S', ""), "sync", UpstreamReply.RELAY);
        byte[] before = join(typed('C', "before"), READY);
        byte[] batch = join(typed('1', ""), typed('2', ""), READY);

        read(before, batch);

        assertThat(events, contains(
            "relay " + hex(before),
            "drop bind " + hex(batch),
            "end bind"
        ));
    }

    @Test
    public void shouldJoinReplacementsOfOneBatchInMessageOrder() {
        forwarded(typed('P', "parse"), "parse", UpstreamReply.replaceWith("a".getBytes(StandardCharsets.UTF_8), 0));
        forwarded(typed('E', "execute"), "execute", UpstreamReply.replaceWith("b".getBytes(StandardCharsets.UTF_8), 0));
        forwarded(typed('S', ""), "sync", UpstreamReply.RELAY);

        read(typed('1', ""), READY);

        assertThat(events.get(events.size() - 1), is("end parse with ab"));
    }

    @Test
    public void shouldGiveAQuerySentBeforeAnySyncTheBatchReply() {
        forwarded(typed('P', "parse"), "parse", UpstreamReply.RELAY);
        forwarded(typed('Q', "query"), "query", UpstreamReply.drop());
        forwarded(typed('Q', "next"), "next", UpstreamReply.RELAY);
        byte[] shared = join(typed('1', ""), typed('C', "query"), READY);
        byte[] next = join(typed('C', "next"), READY);

        read(shared, next);

        assertThat(events, contains(
            "drop query " + hex(shared),
            "end query",
            "relay " + hex(next)
        ));
    }

    @Test
    public void shouldTakeOneByteAsTheAnswerToAnSslRequest() {
        forwarded(SSL_REQUEST, "ssl", UpstreamReply.replaceWith("N".getBytes(StandardCharsets.UTF_8), 0));
        forwarded(STARTUP, "startup", UpstreamReply.RELAY);
        byte[] startupReply = join(authentication(0), typed('S', "server_version\u000017\u0000"), READY);

        read(new byte[]{'S'}, startupReply);

        assertThat(events, contains(
            "drop ssl 53",
            "end ssl with N",
            "relay " + hex(startupReply)
        ));
    }

    @Test
    public void shouldEndAnAuthenticationReplyWhereTheServerAsksTheClientEvenWithItsCodeSplitAcrossReads() {
        forwarded(STARTUP, "startup", UpstreamReply.drop());
        byte[] sasl = join(authentication(10), "SCRAM-SHA-256\u0000\u0000".getBytes(StandardCharsets.UTF_8));
        sasl[4] = (byte) (sasl.length - 1);

        read(java.util.Arrays.copyOfRange(sasl, 0, 7));
        read(java.util.Arrays.copyOfRange(sasl, 7, sasl.length), typed('N', "unprompted"));

        assertThat(events.get(2), is("end startup"));
        assertThat(events.get(3), is("none " + hex(typed('N', "unprompted"))));
    }

    @Test
    public void shouldCarryAnAuthenticationReplyPastAuthenticationOkAndSaslFinalToReadyForQuery() {
        forwarded(typed('p', "sasl response"), "password", UpstreamReply.drop());
        byte[] reply = join(authentication(12), authentication(0), typed('K', "12345678"), READY);

        read(reply);

        assertThat(events, contains("drop password " + hex(reply), "end password"));
    }

    @Test
    public void shouldEndAnAuthenticationReplyAtAnErrorResponse() {
        forwarded(typed('p', "wrong password"), "password", UpstreamReply.drop());

        read(typed('E', "SFATAL"), typed('N', "after"));

        assertThat(events, contains(
            "drop password " + hex(typed('E', "SFATAL")),
            "end password",
            "none " + hex(typed('N', "after"))
        ));
    }

    @Test
    public void shouldLeaveWhatACopyBringsWithTheQueryThatStartedIt() {
        forwarded(typed('Q', "COPY t FROM STDIN"), "copy", UpstreamReply.drop());
        byte[] copyIn = typed('G', "\u0000\u0000\u0000");
        read(copyIn);
        forwarded(typed('d', "1\n"), "data", UpstreamReply.RELAY);
        forwarded(typed('c', ""), "done", UpstreamReply.RELAY);
        byte[] complete = join(typed('C', "COPY 1"), READY);

        read(complete);

        assertThat(events, contains("drop copy " + hex(copyIn), "drop copy " + hex(complete), "end copy"));
    }

    @Test
    public void shouldEndAReplacedMessageWithoutAReplyOnceTheRepliesBeforeItHave() {
        forwarded(typed('Q', "first"), "first", UpstreamReply.RELAY);
        forwarded(CANCEL_REQUEST, "cancel", UpstreamReply.replaceWith("x".getBytes(StandardCharsets.UTF_8), 0));
        assertThat("waits for the reply before it", events, is(empty()));

        byte[] first = join(typed('C', "first"), READY);
        read(first);

        assertThat(events, contains("relay " + hex(first), "end cancel with x"));
    }

    @Test
    public void shouldEndAReplacedMessageWithoutAReplyAtOnceWhenNothingIsPending() {
        forwarded(typed('d', "data"), "data", UpstreamReply.replaceWith("x".getBytes(StandardCharsets.UTF_8), 0));

        assertThat(events, contains("end data with x"));
    }

    @Test
    public void shouldNotEndAReplacedMessageWithoutAReplyInTheMiddleOfAnUnpromptedMessage() {
        byte[] notice = typed('N', "a notice");
        read(java.util.Arrays.copyOfRange(notice, 0, 4));
        forwarded(typed('d', "data"), "data", UpstreamReply.replaceWith("x".getBytes(StandardCharsets.UTF_8), 0));
        assertThat(events, contains("none " + ByteBufUtil.hexDump(notice, 0, 4)));

        read(java.util.Arrays.copyOfRange(notice, 4, notice.length));

        assertThat(events, contains(
            "none " + ByteBufUtil.hexDump(notice, 0, 4),
            "none " + ByteBufUtil.hexDump(notice, 4, notice.length - 4),
            "end data with x"
        ));
    }

    @Test
    public void shouldNotTrackAMessageWithoutAReplyThatIsOnlyDropped() {
        forwarded(typed('X', ""), "terminate", UpstreamReply.drop());

        assertThat(replies.notRelayed(), is(0));
        assertThat(events, is(empty()));
    }

    @Test
    public void shouldRelayEverythingOnceAMessageLengthIsMalformed() {
        forwarded(typed('Q', "one"), "one", UpstreamReply.drop());
        forwarded(typed('Q', "two"), "two", UpstreamReply.drop());
        byte[] malformed = {'C', 0, 0, 0, 2};

        read(malformed, READY);
        forwarded(typed('Q', "three"), "three", UpstreamReply.drop());
        read(READY);

        assertThat(replies.lost(), is(true));
        assertThat(replies.notRelayed(), is(0));
        assertThat(events, contains(
            "drop one " + hex(malformed),
            "none " + hex(READY),
            "none " + hex(READY)
        ));
    }

    @Test
    public void shouldCountTheRepliesToBeDroppedOrReplaced() {
        forwarded(typed('Q', "one"), "one", UpstreamReply.drop());
        forwarded(typed('Q', "two"), "two", UpstreamReply.RELAY);
        forwarded(typed('Q', "three"), "three", UpstreamReply.replaceWith("x".getBytes(StandardCharsets.UTF_8), 0));
        assertThat(replies.notRelayed(), is(2));

        read(READY, READY);

        assertThat(replies.notRelayed(), is(1));
    }
}
