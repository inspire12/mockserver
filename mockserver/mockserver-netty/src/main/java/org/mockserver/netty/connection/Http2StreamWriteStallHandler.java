package org.mockserver.netty.connection;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2RemoteFlowController;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.Http2WindowUpdateFrame;
import io.netty.util.concurrent.ScheduledFuture;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Resets an HTTP/2 stream whose response has data waiting in the connection's flow controller that the client has
 * taken none of for {@code responseWriteStallTimeoutMillis}. A client can stop granting one stream flow-control window
 * while it keeps reading the connection (an application that stops consuming one response body), so a stalled stream
 * is invisible at the connection level; {@link WriteStallTimeoutHandler} covers a connection whose socket stalls.
 * <p>
 * A stream progresses when its send window changes (the controller sent some of its data) or the client sends a
 * {@code WINDOW_UPDATE} for it (the client consumed some of it). Between those the window cannot change, so an
 * unchanged window with no update means nothing moved. A stream whose own window is still open is waiting its turn
 * for the connection's window, behind other streams by weight or priority, so it also counts as progressing while any
 * stream (or the connection window) does, and while the connection's socket is not writable: the flow controller then
 * writes nothing to any stream, and a socket the client is not taking is {@link WriteStallTimeoutHandler}'s to time,
 * which tolerates the gaps in which a slow reader's kernel frees its send buffer. The reset ({@code CANCEL}) closes the
 * stream's child channel, which ends its response incomplete and fails the writes still queued for it.
 * <p>
 * Sits between {@link Http2FrameCodec} and {@code Http2MultiplexHandler}, the only place that sees stream window
 * updates, which the multiplex handler drops. A timer runs only while the connection has active streams.
 */
public final class Http2StreamWriteStallHandler extends ChannelDuplexHandler implements Runnable {

    private final long timeoutMillis;
    private final long timeoutNanos;
    private final long checkIntervalMillis;
    private final MockServerLogger mockServerLogger;
    private final List<Http2Stream> unmovedStreams = new ArrayList<>();
    private final List<Integer> stalledStreamIds = new ArrayList<>();
    private ChannelHandlerContext ctx;
    private ChannelHandlerContext codecCtx;
    private Http2FrameCodec codec;
    private Http2Connection connection;
    private Http2Connection.PropertyKey progressKey;
    private ScheduledFuture<?> check;
    private long now;
    private int lastConnectionWindow;
    private boolean anyProgress;

    public Http2StreamWriteStallHandler(long timeoutMillis, MockServerLogger mockServerLogger) {
        this.timeoutMillis = timeoutMillis;
        this.timeoutNanos = MILLISECONDS.toNanos(timeoutMillis);
        this.checkIntervalMillis = WriteStallTimeoutHandler.checkIntervalMillis(timeoutMillis);
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        this.codecCtx = ctx.pipeline().context(Http2FrameCodec.class);
        if (codecCtx == null) {
            throw new IllegalStateException(Http2StreamWriteStallHandler.class.getSimpleName() + " must be added after an " + Http2FrameCodec.class.getSimpleName());
        }
        this.codec = (Http2FrameCodec) codecCtx.handler();
        this.connection = codec.connection();
        this.progressKey = connection.newKey();
        this.lastConnectionWindow = connection.remote().flowController().windowSize(connection.connectionStream());
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (check != null) {
            check.cancel(false);
            check = null;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2WindowUpdateFrame) {
            Http2FrameStream frameStream = ((Http2WindowUpdateFrame) msg).stream();
            Http2Stream stream = frameStream != null ? connection.stream(frameStream.id()) : null;
            StreamWriteProgress progress = stream != null ? stream.getProperty(progressKey) : null;
            if (progress != null) {
                progress.windowUpdates++;
            }
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        ctx.flush();
        if (check == null && connection.numActiveStreams() > 0) {
            check = ctx.executor().schedule(this, checkIntervalMillis, MILLISECONDS);
        }
    }

    @Override
    public void run() {
        check = null;
        if (!ctx.channel().isActive() || connection.numActiveStreams() == 0) {
            return;
        }
        now = System.nanoTime();
        Http2RemoteFlowController flowController = connection.remote().flowController();
        int connectionWindow = flowController.windowSize(connection.connectionStream());
        anyProgress = connectionWindow != lastConnectionWindow;
        lastConnectionWindow = connectionWindow;
        try {
            connection.forEachActiveStream(stream -> {
                checkStream(stream, flowController);
                return true;
            });
        } catch (Http2Exception unexpected) {
            // the visitor throws nothing; anything else leaves the streams as they are until the next check
        }
        // the connection's own channel: its socket is what the flow controller waits on to write an open-windowed stream;
        // a socket stall is left to WriteStallTimeoutHandler only where one is watching this connection
        boolean socketStallWatched = !ctx.channel().isWritable() && ctx.pipeline().get(WriteStallTimeoutHandler.class) != null;
        for (Http2Stream stream : unmovedStreams) {
            StreamWriteProgress progress = stream.getProperty(progressKey);
            if (progress.window > 0 && (anyProgress || socketStallWatched)) {
                // its own window is open, so it is queued behind streams the client is still taking, or behind a
                // socket the client is not taking, which is WriteStallTimeoutHandler's to time
                progress.lastProgressNanos = now;
            } else if (now - progress.lastProgressNanos >= timeoutNanos) {
                stalledStreamIds.add(stream.id());
            }
        }
        unmovedStreams.clear();
        for (int streamId : stalledStreamIds) {
            reset(streamId);
        }
        if (!stalledStreamIds.isEmpty()) {
            stalledStreamIds.clear();
            codecCtx.flush();
        }
        check = ctx.executor().schedule(this, checkIntervalMillis, MILLISECONDS);
    }

    private void checkStream(Http2Stream stream, Http2RemoteFlowController flowController) {
        StreamWriteProgress progress = stream.getProperty(progressKey);
        // a stream already reset stays active until its RST_STREAM is written, which a stalled socket delays
        if (stream.isResetSent() || !flowController.hasFlowControlled(stream)) {
            if (progress != null) {
                stream.removeProperty(progressKey);
            }
            return;
        }
        int window = flowController.windowSize(stream);
        if (progress == null) {
            stream.setProperty(progressKey, new StreamWriteProgress(window, now));
        } else if (progress.windowUpdates != progress.windowUpdatesSeen || window != progress.window) {
            progress.windowUpdatesSeen = progress.windowUpdates;
            progress.window = window;
            progress.lastProgressNanos = now;
            anyProgress = true;
        } else {
            unmovedStreams.add(stream);
        }
    }

    private void reset(int streamId) {
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("resetting HTTP/2 stream:{}from:{}because its client took none of the response waiting for it for:{}ms (responseWriteStallTimeoutMillis), so the response ends incomplete")
                    .setArguments(streamId, ctx.channel().remoteAddress(), timeoutMillis)
            );
        }
        Metrics.incrementResponseWriteStalls(Metrics.ResponseWriteStall.HTTP2_STREAM);
        codec.resetStream(codecCtx, streamId, Http2Error.CANCEL.code(), codecCtx.newPromise());
    }

    private static final class StreamWriteProgress {
        private int window;
        private long windowUpdates;
        private long windowUpdatesSeen;
        private long lastProgressNanos;

        private StreamWriteProgress(int window, long lastProgressNanos) {
            this.window = window;
            this.lastProgressNanos = lastProgressNanos;
        }
    }
}
