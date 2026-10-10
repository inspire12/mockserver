package org.mockserver.netty.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import org.mockserver.socket.ChannelReadPause;

import java.util.ArrayDeque;
import java.util.Deque;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * The replies MockServer writes itself on one binary connection (a binary expectation's response, and the "unknown
 * message format" text), written in the order of the messages they answer. A reply with a delay is written once
 * that delay, counted from when its message was handled, has passed and every earlier reply has been written; a
 * reply without one is written at once unless an earlier reply is still waiting. The wait is a timer on the
 * connection's event loop, cancelled when the connection closes. Used only on that event loop.
 */
final class BinaryLocalReplies {

    /**
     * The connection is not read while more replies than this wait, and is read again once they have all been
     * written.
     */
    static final int MAX_WAITING_REPLIES = 64;
    private static final AttributeKey<BinaryLocalReplies> LOCAL_REPLIES = AttributeKey.valueOf("BINARY_LOCAL_REPLIES");

    private final ChannelHandlerContext ctx;
    private final Deque<Reply> waiting = new ArrayDeque<>();
    private ScheduledFuture<?> timer;
    private boolean readsPaused;
    private boolean closing;

    private BinaryLocalReplies(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Writes a reply now, or once its delay has passed and the replies before it have been written.
     *
     * @param closeAfter whether to close the connection once the reply has been written
     * @param afterWrite run once the reply has been written, or null
     */
    static void write(ChannelHandlerContext ctx, byte[] reply, long delayMillis, boolean closeAfter, Runnable afterWrite) {
        Channel channel = ctx.channel();
        BinaryLocalReplies replies = channel.hasAttr(LOCAL_REPLIES) ? channel.attr(LOCAL_REPLIES).get() : null;
        if ((replies == null || replies.waiting.isEmpty()) && delayMillis <= 0) {
            writeNow(ctx, reply, closeAfter, afterWrite);
            return;
        }
        if (!channel.isActive()) {
            return;
        }
        if (replies == null) {
            replies = new BinaryLocalReplies(ctx);
            channel.attr(LOCAL_REPLIES).set(replies);
        }
        long due = ctx.executor().ticker().nanoTime() + MILLISECONDS.toNanos(Math.max(0, delayMillis));
        replies.add(new Reply(reply, due, closeAfter, afterWrite));
    }

    /**
     * Whether a reply that closes the connection is waiting: what the client sends after it is not answered.
     */
    static boolean closing(Channel channel) {
        BinaryLocalReplies replies = channel.hasAttr(LOCAL_REPLIES) ? channel.attr(LOCAL_REPLIES).get() : null;
        return replies != null && replies.closing;
    }

    /**
     * Drops the replies still waiting and cancels their timer, once the connection has closed or its handler is gone.
     */
    static void discard(Channel channel) {
        BinaryLocalReplies replies = channel.hasAttr(LOCAL_REPLIES) ? channel.attr(LOCAL_REPLIES).getAndSet(null) : null;
        if (replies != null) {
            if (replies.timer != null) {
                replies.timer.cancel(false);
                replies.timer = null;
            }
            replies.waiting.clear();
            replies.resumeReadsOnceDrained();
        }
    }

    private void add(Reply reply) {
        waiting.add(reply);
        closing |= reply.closeAfter;
        if (waiting.size() == 1) {
            writeDue();
        } else if (!readsPaused && waiting.size() > MAX_WAITING_REPLIES) {
            readsPaused = true;
            ChannelReadPause.pause(ctx.channel());
        }
    }

    private void writeDue() {
        timer = null;
        Reply next;
        while ((next = waiting.peek()) != null) {
            long remaining = next.dueNanos - ctx.executor().ticker().nanoTime();
            if (remaining > 0) {
                timer = ctx.executor().schedule(this::writeDue, remaining, NANOSECONDS);
                return;
            }
            waiting.poll();
            writeNow(ctx, next.bytes, next.closeAfter, next.afterWrite);
            if (next.closeAfter) {
                waiting.clear();
            }
        }
        resumeReadsOnceDrained();
    }

    private void resumeReadsOnceDrained() {
        if (readsPaused && waiting.isEmpty()) {
            readsPaused = false;
            ChannelReadPause.resume(ctx.channel());
        }
    }

    private static void writeNow(ChannelHandlerContext ctx, byte[] reply, boolean closeAfter, Runnable afterWrite) {
        ctx.writeAndFlush(Unpooled.copiedBuffer(reply));
        if (afterWrite != null) {
            afterWrite.run();
        }
        if (closeAfter) {
            ctx.close();
        }
    }

    private static final class Reply {
        private final byte[] bytes;
        private final long dueNanos;
        private final boolean closeAfter;
        private final Runnable afterWrite;

        private Reply(byte[] bytes, long dueNanos, boolean closeAfter, Runnable afterWrite) {
            this.bytes = bytes;
            this.dueNanos = dueNanos;
            this.closeAfter = closeAfter;
            this.afterWrite = afterWrite;
        }
    }
}
