package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.model.BinaryMessage;
import org.mockserver.netty.proxy.BinaryRequestProxyingHandler;
import org.mockserver.netty.proxy.PostgresqlMessageFramer;
import org.mockserver.socket.ChannelReadPause;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockserver.netty.proxy.relay.BinaryRelayHarness.TARGET;
import static org.mockserver.netty.proxy.relay.BinaryRelayHarness.isReading;

/**
 * A relay on a PostgreSQL-framed connection, step by step: an upstream reply dropped, or replaced once it has ended
 * and its delay has passed, with what the upstream sends after a waiting replacement held behind it.
 */
public class BinaryRelayUpstreamReplyTest {

    private static final byte[] READY = typed('Z', "I");

    private final BinaryRelayHarness harness = new BinaryRelayHarness(true);
    private EmbeddedChannel client;
    private ChannelHandlerContext ctx;

    @Before
    public void connectClient() {
        client = harness.clientConnection();
        client.pipeline().addFirst(new PostgresqlMessageFramer(1024 * 1024, harness.mockServerLogger));
        client.freezeTime();
        ctx = client.pipeline().context(BinaryRequestProxyingHandler.class);
    }

    @After
    public void finish() {
        harness.finish();
    }

    private static byte[] typed(char type, String body) {
        byte[] message = new byte[5 + body.length()];
        message[0] = (byte) type;
        int length = 4 + body.length();
        message[3] = (byte) (length >>> 8);
        message[4] = (byte) length;
        for (int i = 0; i < body.length(); i++) {
            message[5 + i] = (byte) body.charAt(i);
        }
        return message;
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

    private void forward(byte[] message, UpstreamReply reply) {
        BinaryRelay.forward(ctx, BinaryMessage.bytes(message), "id-" + (char) message[0] + message.length, reply, TARGET, harness.configuration, harness.mockServerLogger, harness.scheduler, harness.httpClient, null);
        client.runPendingTasks();
    }

    /** @return the read, to check that nothing of it is held once it should not be */
    private ByteBuf upstreamSends(byte[]... parts) {
        ByteBuf read = Unpooled.copiedBuffer(join(parts));
        harness.upstream.writeInbound(read);
        return read;
    }

    private String receivedByClient() {
        StringBuilder received = new StringBuilder();
        for (ByteBuf written; (written = client.readOutbound()) != null; ) {
            received.append(ByteBufUtil.hexDump(written));
            written.release();
        }
        return received.toString();
    }

    private void timePasses(long millis) {
        client.advanceTimeBy(millis, MILLISECONDS);
        client.runScheduledPendingTasks();
    }

    private List<LogEntry> logged(String containing) {
        return harness.logged().stream().filter(entry -> String.valueOf(entry.getMessageFormat()).contains(containing)).collect(Collectors.toList());
    }

    @Test
    public void shouldTrackRepliesOnlyWithPostgresqlFraming() {
        forward(typed('Q', "one"), UpstreamReply.RELAY);
        assertThat(BinaryRelay.tracksReplies(client), is(true));

        BinaryRelayHarness other = new BinaryRelayHarness(true);
        try {
            EmbeddedChannel unframed = other.clientConnection();
            BinaryRelay.forward(unframed.pipeline().context(BinaryRequestProxyingHandler.class), BinaryMessage.bytes(typed('Q', "one")), "id", UpstreamReply.RELAY, TARGET, other.configuration, other.mockServerLogger, other.scheduler, other.httpClient, null);
            assertThat(BinaryRelay.tracksReplies(unframed), is(false));
        } finally {
            other.finish();
        }
    }

    @Test
    public void shouldDropAReplyAndRelayTheNext() {
        byte[] dropped = join(typed('T', "rows"), typed('C', "SELECT 1"), READY);
        byte[] relayed = join(typed('C', "SELECT 2"), READY);
        forward(typed('Q', "one"), UpstreamReply.drop());
        forward(typed('Q', "two"), UpstreamReply.RELAY);

        ByteBuf first = upstreamSends(java.util.Arrays.copyOfRange(dropped, 0, 7));
        ByteBuf second = upstreamSends(java.util.Arrays.copyOfRange(dropped, 7, dropped.length), relayed);

        assertThat(receivedByClient(), is(hex(relayed)));
        assertThat(logged("dropping binary response"), hasSize(2));
        assertThat("a dropped piece is never retained", first.refCnt(), is(0));
        assertThat("the relayed piece was written and released by the client", second.refCnt(), is(0));
    }

    @Test
    public void shouldHoldWhatFollowsADelayedReplacementAndTheUpstreamUntilItIsWritten() {
        byte[] replacement = typed('C', "canned");
        byte[] next = join(typed('C', "next"), READY);
        forward(typed('Q', "one"), UpstreamReply.replaceWith(replacement, 100));
        forward(typed('Q', "two"), UpstreamReply.RELAY);

        ByteBuf read = upstreamSends(typed('C', "real"), READY, next);

        assertThat("nothing before the replacement is due", receivedByClient(), is(""));
        assertThat("only the relayed piece is held", read.refCnt(), is(1));
        assertThat("the upstream is not read while the replacement waits", isReading(harness.upstream), is(false));

        timePasses(99);
        assertThat(receivedByClient(), is(""));

        timePasses(1);
        assertThat(receivedByClient(), is(hex(replacement, next)));
        assertThat(isReading(harness.upstream), is(true));
        assertThat(logged("in place of the response from"), hasSize(1));
        assertThat(read.refCnt(), is(0));
    }

    @Test
    public void shouldWriteAReplacementAtOnceWhenItsDelayHasPassedByTheEndOfItsReply() {
        byte[] replacement = typed('C', "canned");
        forward(typed('Q', "one"), UpstreamReply.replaceWith(replacement, 100));

        timePasses(150);
        upstreamSends(typed('C', "real"), READY);

        assertThat(receivedByClient(), is(hex(replacement)));
        assertThat(isReading(harness.upstream), is(true));
    }

    @Test
    public void shouldWriteWhatIsHeldAtOnceWhenTheUpstreamCloses() {
        byte[] replacement = typed('C', "canned");
        byte[] next = join(typed('C', "next"), READY);
        forward(typed('Q', "one"), UpstreamReply.replaceWith(replacement, 10_000));
        forward(typed('Q', "two"), UpstreamReply.RELAY);
        ByteBuf read = upstreamSends(READY, next);

        harness.upstream.close();
        client.runPendingTasks();

        assertThat(receivedByClient(), is(hex(replacement, next)));
        assertThat(read.refCnt(), is(0));
        assertThat(client.isActive(), is(false));
    }

    @Test
    public void shouldDropWhatIsHeldAndReadTheUpstreamAgainWhenTheClientCloses() {
        forward(typed('Q', "one"), UpstreamReply.replaceWith(typed('C', "canned"), 10_000));
        forward(typed('Q', "two"), UpstreamReply.RELAY);
        ByteBuf read = upstreamSends(READY, READY);
        assertThat(ChannelReadPause.holds(harness.upstream), is(1));
        assertThat("the second ReadyForQuery is held, retained", read.refCnt(), is(1));

        // the upstream's output is then never ended, so it stays open and only the client's close can release it
        harness.upstreamFlushGate.blocked = true;
        client.close();
        client.runPendingTasks();
        assertThat(harness.upstream.isActive(), is(true));
        assertThat("read again at once, so that the upstream's close is seen", ChannelReadPause.holds(harness.upstream), is(0));

        assertThat("what was held is released once", read.refCnt(), is(0));
        ByteBuf afterClose = upstreamSends(READY);
        assertThat("a relayed read after the client closed is released, not kept", afterClose.refCnt(), is(0));

        timePasses(10_000);
        assertThat(receivedByClient(), is(""));
    }

    @Test
    public void shouldStopReadingTheClientWhileTooManyRepliesWaitToBeDroppedOrReplaced() {
        for (int i = 0; i <= BinaryRelay.MAX_PENDING_REPLACED_REPLIES; i++) {
            forward(typed('Q', "q"), UpstreamReply.drop());
        }
        assertThat(isReading(client), is(false));

        byte[][] readies = new byte[BinaryRelay.MAX_PENDING_REPLACED_REPLIES / 2][];
        java.util.Arrays.fill(readies, READY);
        ByteBuf dropped = upstreamSends(readies);
        assertThat("more than half still wait", isReading(client), is(false));

        upstreamSends(READY);
        assertThat(isReading(client), is(true));
        assertThat(receivedByClient(), is(""));
        assertThat(dropped.refCnt(), is(0));
    }

    @Test
    public void shouldRelayEverythingAndSaySoOnceAnUpstreamMessageIsMalformed() {
        forward(typed('Q', "one"), UpstreamReply.drop());
        byte[] malformed = {'C', 0, 0, 0, 1};

        ByteBuf read = upstreamSends(malformed, READY);

        assertThat(receivedByClient(), is(hex(READY)));
        assertThat(read.refCnt(), is(0));
        assertThat(BinaryRelay.tracksReplies(client), is(false));
        assertThat(harness.logged(Level.WARN).stream().filter(entry -> entry.getMessageFormat().contains("cannot read")).count(), is(1L));
    }

    @Test
    public void shouldWriteAndFlushAReplacementForAMessageWithoutAReplyAtOnce() {
        byte[] replacement = typed('C', "copied");

        forward(typed('d', "row"), UpstreamReply.replaceWith(replacement, 0));

        assertThat(receivedByClient(), is(hex(replacement)));
    }

    @Test
    public void shouldKeepWhatFollowsAMalformedMessageBehindAReplacementStillWaiting() {
        byte[] replacement = typed('C', "canned");
        byte[] malformed = {'C', 0, 0, 0, 1};
        forward(typed('Q', "one"), UpstreamReply.replaceWith(replacement, 100));
        forward(typed('Q', "two"), UpstreamReply.RELAY);

        ByteBuf first = upstreamSends(READY, malformed);
        ByteBuf second = upstreamSends(READY);
        assertThat(BinaryRelay.tracksReplies(client), is(false));
        assertThat("nothing overtakes the replacement", receivedByClient(), is(""));

        timePasses(100);
        assertThat(receivedByClient(), is(hex(replacement, malformed, READY)));
        assertThat(first.refCnt(), is(0));
        assertThat(second.refCnt(), is(0));
    }
}
