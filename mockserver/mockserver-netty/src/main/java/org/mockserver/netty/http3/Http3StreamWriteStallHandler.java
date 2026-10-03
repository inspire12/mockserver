package org.mockserver.netty.http3;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.concurrent.ScheduledFuture;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.connection.WriteStallTimeoutHandler;
import org.slf4j.event.Level;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Resets an HTTP/3 request stream ({@code RESET_STREAM H3_INTERNAL_ERROR}) when writes have been waiting on it for
 * {@code responseWriteStallTimeoutMillis} and none has completed, so a client that stops reading a response (and so
 * stops granting the stream flow-control credit) cannot hold it, or a streamed response's upstream, indefinitely.
 * <p>
 * A QUIC stream write completes once QUIC has taken all of it, which it can only do as the client grants credit, so a
 * completed write is progress. {@link Http3ResponseWriter} writes a large body as several DATA frames for the same
 * reason: one frame of a whole body would complete only at the end. Installed first in each request stream's pipeline,
 * so it sees what the HTTP/3 frame codec writes; a timer runs only while writes are waiting.
 */
public final class Http3StreamWriteStallHandler extends ChannelOutboundHandlerAdapter implements Runnable {

    private final long timeoutMillis;
    private final long timeoutNanos;
    private final long checkIntervalMillis;
    private final MockServerLogger mockServerLogger;
    private final ChannelFutureListener writeCompleted = future -> writeCompleted();
    private ChannelHandlerContext ctx;
    private ScheduledFuture<?> check;
    private int pendingWrites;
    private long lastProgressNanos;

    public Http3StreamWriteStallHandler(long timeoutMillis, MockServerLogger mockServerLogger) {
        this.timeoutMillis = timeoutMillis;
        this.timeoutNanos = MILLISECONDS.toNanos(timeoutMillis);
        this.checkIntervalMillis = WriteStallTimeoutHandler.checkIntervalMillis(timeoutMillis);
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (check != null) {
            check.cancel(false);
            check = null;
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (pendingWrites++ == 0) {
            lastProgressNanos = System.nanoTime();
        }
        promise = promise.unvoid();
        promise.addListener(writeCompleted);
        ctx.write(msg, promise);
        if (pendingWrites > 0 && check == null) {
            check = ctx.executor().schedule(this, checkIntervalMillis, MILLISECONDS);
        }
    }

    private void writeCompleted() {
        pendingWrites--;
        lastProgressNanos = System.nanoTime();
    }

    @Override
    public void run() {
        check = null;
        Channel channel = ctx.channel();
        if (!channel.isActive() || pendingWrites <= 0) {
            return;
        }
        if (System.nanoTime() - lastProgressNanos >= timeoutNanos) {
            stalled(channel);
            return;
        }
        check = ctx.executor().schedule(this, checkIntervalMillis, MILLISECONDS);
    }

    private void stalled(Channel channel) {
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("resetting HTTP/3 stream:{}because its client took none of the response waiting for it for:{}ms (responseWriteStallTimeoutMillis), so the response ends incomplete")
                    .setArguments(channel, timeoutMillis)
            );
        }
        if (channel instanceof QuicStreamChannel) {
            ((QuicStreamChannel) channel).shutdownOutput(Http3ErrorCode.H3_INTERNAL_ERROR.code());
        }
        // closing fails, and so releases, the writes QUIC has not taken
        channel.close();
    }
}
