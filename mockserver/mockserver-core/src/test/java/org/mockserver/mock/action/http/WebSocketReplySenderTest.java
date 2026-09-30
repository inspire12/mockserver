package org.mockserver.mock.action.http;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker13;
import org.junit.After;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.WebSocketMessage;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.ChannelReadPause;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.action.http.WebSocketReplySender.PAUSE_READS_ABOVE_PENDING_REPLY_SETS;
import static org.mockserver.mock.action.http.WebSocketReplySender.RESUME_READS_AT_PENDING_REPLY_SETS;
import static org.mockserver.model.WebSocketMessage.webSocketMessage;

/**
 * Per-connection backpressure and whole-set refusal of WebSocket bidi reply sets, driven by a scheduler that
 * holds each admitted set until the test finishes it.
 */
public class WebSocketReplySenderTest {

    private final EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    private final HoldingScheduler scheduler = new HoldingScheduler();
    private final List<String> written = new ArrayList<>();
    private final WebSocketReplySender sender = new WebSocketReplySender(channel, scheduler,
        new WebSocketServerHandshaker13("ws://localhost/ws", null, WebSocketDecoderConfig.newBuilder().build()),
        (ctx, message) -> written.add(message.getText()));

    @After
    public void closeChannel() {
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldPauseReadsAbovePendingThresholdAndResumeWhenDrained() {
        for (int i = 0; i < PAUSE_READS_ABOVE_PENDING_REPLY_SETS; i++) {
            sendDelayedSet();
        }
        assertThat("at the threshold reading continues", channel.config().isAutoRead(), is(true));

        sendDelayedSet();
        assertThat("above the threshold reading stops", channel.config().isAutoRead(), is(false));
        assertThat(scheduler.getWebSocketReadPauseCount(), is(1L));

        sendDelayedSet();
        assertThat("one pause per episode", scheduler.getWebSocketReadPauseCount(), is(1L));

        int pending = PAUSE_READS_ABOVE_PENDING_REPLY_SETS + 2;
        while (pending > RESUME_READS_AT_PENDING_REPLY_SETS + 1) {
            scheduler.finishNext();
            pending--;
        }
        assertThat("not yet drained to the resume point", channel.config().isAutoRead(), is(false));

        scheduler.finishNext();
        channel.runPendingTasks();
        assertThat("drained to the resume point", channel.config().isAutoRead(), is(true));
        assertThat(sender.pendingReplySets(), is(RESUME_READS_AT_PENDING_REPLY_SETS));
    }

    @Test
    public void shouldNotResumeReadsWhileAnotherHolderStillPausesThem() {
        ChannelReadPause.pause(channel);
        for (int i = 0; i <= PAUSE_READS_ABOVE_PENDING_REPLY_SETS; i++) {
            sendDelayedSet();
        }
        while (sender.pendingReplySets() > 0) {
            scheduler.finishNext();
        }
        channel.runPendingTasks();
        assertThat("the other hold keeps reads paused", channel.config().isAutoRead(), is(false));

        ChannelReadPause.resume(channel);
        assertThat(channel.config().isAutoRead(), is(true));
    }

    @Test
    public void shouldSendFramesInDelayOrderKeepingConfiguredOrderAmongEqualDelays() {
        sender.sendAll(channel.pipeline().firstContext(), List.of(
            webSocketMessage("late").withDelay(TimeUnit.MILLISECONDS, 300),
            webSocketMessage("now-1"),
            webSocketMessage("mid-1").withDelay(TimeUnit.MILLISECONDS, 100),
            webSocketMessage("mid-2").withDelay(TimeUnit.MILLISECONDS, 100),
            webSocketMessage("now-2")
        ));

        HoldingScheduler.HeldSet set = scheduler.held.get(0);
        assertThat(toList(set.delaysMillis), contains(0L, 0L, 100L, 100L, 300L));
        set.writes.forEach(Runnable::run);
        assertThat(written, contains("now-1", "now-2", "mid-1", "mid-2", "late"));
    }

    @Test
    public void shouldCloseWith1013AndSendNothingMoreWhenASetIsRefused() {
        sendDelayedSet();
        scheduler.refuse = true;

        sendDelayedSet();

        Object closeFrame = channel.readOutbound();
        assertThat(closeFrame, instanceOf(CloseWebSocketFrame.class));
        assertThat(((CloseWebSocketFrame) closeFrame).statusCode(), is(WebSocketCloseStatus.TRY_AGAIN_LATER.code()));
        ((CloseWebSocketFrame) closeFrame).release();
        assertThat(channel.isOpen(), is(false));
        assertThat("an admitted set still pending stops at its next frame", scheduler.held.get(0).stopped.getAsBoolean(), is(true));
        assertThat(written, empty());
    }

    private void sendDelayedSet() {
        sender.sendAll(channel.pipeline().firstContext(), List.of(
            webSocketMessage("a").withDelay(TimeUnit.SECONDS, 30),
            webSocketMessage("b").withDelay(TimeUnit.SECONDS, 30)));
    }

    private static List<Long> toList(long[] values) {
        List<Long> list = new ArrayList<>();
        for (long value : values) {
            list.add(value);
        }
        return list;
    }

    private static final class HoldingScheduler extends Scheduler {
        private final List<HeldSet> held = new ArrayList<>();
        private int nextToFinish;
        private boolean refuse;

        private HoldingScheduler() {
            super(configuration(), new MockServerLogger(), true);
        }

        @Override
        public boolean scheduleReplySet(List<Runnable> writes, long[] delaysMillis, BooleanSupplier stopped, Runnable onRefused, Runnable onFinished) {
            if (refuse) {
                onRefused.run();
                return false;
            }
            held.add(new HeldSet(writes, delaysMillis, stopped, onFinished));
            return true;
        }

        private void finishNext() {
            held.get(nextToFinish++).onFinished.run();
        }

        private static final class HeldSet {
            private final List<Runnable> writes;
            private final long[] delaysMillis;
            private final BooleanSupplier stopped;
            private final Runnable onFinished;

            private HeldSet(List<Runnable> writes, long[] delaysMillis, BooleanSupplier stopped, Runnable onFinished) {
                this.writes = writes;
                this.delaysMillis = delaysMillis;
                this.stopped = stopped;
                this.onFinished = onFinished;
            }
        }
    }
}
