package org.mockserver.mock.action.http;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import org.mockserver.model.WebSocketMessage;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.ChannelReadPause;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends the reply sets of a bidirectional WebSocket mock on one connection.
 * <p>
 * Each matched inbound frame produces one reply set. A set is sent whole and in order: its frames go out in
 * order of their delay (each measured from the match), keeping list order among equal delays, one after
 * another. Two bounds keep pending sets from growing without limit:
 * <ul>
 * <li>per connection, once more than {@link #PAUSE_READS_ABOVE_PENDING_REPLY_SETS} delayed sets are pending
 * the connection stops reading (a {@link ChannelReadPause} hold), so TCP flow control slows the client; it
 * resumes at {@link #RESUME_READS_AT_PENDING_REPLY_SETS};</li>
 * <li>globally, the scheduler admits or refuses each whole set against {@code maxPendingDelayedResponses}; a
 * refused set closes the WebSocket with 1013 (Try Again Later) rather than send part of a reply.</li>
 * </ul>
 * Frames already decoded from one socket read are still processed after a pause, so a connection can exceed
 * the per-connection threshold by one read's worth of sets.
 */
class WebSocketReplySender implements BidirectionalWebSocketFrameHandler.FrameSender {

    static final int PAUSE_READS_ABOVE_PENDING_REPLY_SETS = 128;
    static final int RESUME_READS_AT_PENDING_REPLY_SETS = PAUSE_READS_ABOVE_PENDING_REPLY_SETS / 2;

    /**
     * Writes one reply frame now (on whichever thread its delay elapsed).
     */
    interface FrameWriter {
        void write(ChannelHandlerContext ctx, WebSocketMessage message);
    }

    private final Channel channel;
    private final Scheduler scheduler;
    private final WebSocketServerHandshaker handshaker;
    private final FrameWriter frameWriter;
    private final AtomicInteger pendingReplySets = new AtomicInteger();
    // written only on the channel's event loop; read by scheduler threads when a set finishes
    private volatile boolean readsPaused;
    private volatile boolean closing;

    WebSocketReplySender(Channel channel, Scheduler scheduler, WebSocketServerHandshaker handshaker, FrameWriter frameWriter) {
        this.channel = channel;
        this.scheduler = scheduler;
        this.handshaker = handshaker;
        this.frameWriter = frameWriter;
    }

    @Override
    public void send(ChannelHandlerContext ctx, WebSocketMessage message) {
        sendAll(ctx, Collections.singletonList(message));
    }

    @Override
    public void sendAll(ChannelHandlerContext ctx, List<WebSocketMessage> messages) {
        if (closing || !channel.isActive()) {
            return;
        }
        List<WebSocketMessage> frames = new ArrayList<>(messages.size());
        for (WebSocketMessage message : messages) {
            if (message != null && (message.getBinary() != null || message.getText() != null)) {
                frames.add(message);
            }
        }
        if (frames.isEmpty()) {
            return;
        }
        long[] sampledDelays = new long[frames.size()];
        boolean ordered = true;
        for (int i = 0; i < sampledDelays.length; i++) {
            sampledDelays[i] = frames.get(i).getDelay() != null ? Math.max(0, frames.get(i).getDelay().sampleValueMillis()) : 0;
            ordered &= i == 0 || sampledDelays[i] >= sampledDelays[i - 1];
        }
        Integer[] sendOrder = new Integer[sampledDelays.length];
        for (int i = 0; i < sendOrder.length; i++) {
            sendOrder[i] = i;
        }
        if (!ordered) {
            // stable, so frames with equal delays keep their configured order
            Arrays.sort(sendOrder, Comparator.comparingLong(i -> sampledDelays[i]));
        }
        List<Runnable> writes = new ArrayList<>(sendOrder.length);
        long[] delaysMillis = new long[sendOrder.length];
        for (int i = 0; i < sendOrder.length; i++) {
            WebSocketMessage frame = frames.get(sendOrder[i]);
            writes.add(() -> frameWriter.write(ctx, frame));
            delaysMillis[i] = sampledDelays[sendOrder[i]];
        }
        pendingReplySets.incrementAndGet();
        boolean admitted = scheduler.scheduleReplySet(writes, delaysMillis,
            () -> closing || !channel.isActive(),
            this::refused,
            this::replySetFinished);
        if (admitted) {
            pauseReadsIfTooManyPending();
        }
    }

    private void refused() {
        pendingReplySets.decrementAndGet();
        if (!closing) {
            closing = true;
            if (channel.isActive()) {
                handshaker.close(channel, new CloseWebSocketFrame(WebSocketCloseStatus.TRY_AGAIN_LATER));
            }
        }
    }

    private void pauseReadsIfTooManyPending() {
        if (!readsPaused && pendingReplySets.get() > PAUSE_READS_ABOVE_PENDING_REPLY_SETS) {
            readsPaused = true;
            scheduler.recordWebSocketReadPause();
            ChannelReadPause.pause(channel);
            // sets may have finished between the count and the flag being set, without seeing the flag
            resumeReadsIfDrained();
        }
    }

    private void replySetFinished() {
        if (pendingReplySets.decrementAndGet() <= RESUME_READS_AT_PENDING_REPLY_SETS && readsPaused) {
            if (channel.eventLoop().inEventLoop()) {
                resumeReadsIfDrained();
            } else {
                channel.eventLoop().execute(this::resumeReadsIfDrained);
            }
        }
    }

    private void resumeReadsIfDrained() {
        if (readsPaused && pendingReplySets.get() <= RESUME_READS_AT_PENDING_REPLY_SETS) {
            readsPaused = false;
            ChannelReadPause.resume(channel);
        }
    }

    int pendingReplySets() {
        return pendingReplySets.get();
    }
}
