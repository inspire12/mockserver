package org.mockserver.netty.connection;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameListenerDecorator;
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
 * stream's child channel, which ends its response incomplete and fails the writes still queued for it. When a stalled
 * stream holds the connection window the others wait for, only it is reset (see {@code selectStalledStreams}).
 * <p>
 * Sits after the connection's {@link Http2ConnectionHandler}. With an {@link Http2FrameCodec} it goes before
 * {@code Http2MultiplexHandler}, the only place that sees stream window updates as frames, which the multiplex handler
 * drops; a connection handler that turns frames into messages itself (the CONNECT relay's client-facing
 * {@code HttpToHttp2ConnectionHandler}) passes no window update down the pipeline, so its frame listener must be
 * wrapped with {@link #frameListener}. A timer runs only while the connection has active streams.
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
    private Http2ConnectionHandler codec;
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
        this.codecCtx = ctx.pipeline().context(Http2ConnectionHandler.class);
        if (codecCtx == null) {
            throw new IllegalStateException(Http2StreamWriteStallHandler.class.getSimpleName() + " must be added after an " + Http2ConnectionHandler.class.getSimpleName());
        }
        this.codec = (Http2ConnectionHandler) codecCtx.handler();
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

    /**
     * Wraps the frame listener of a connection handler that passes no window update down the pipeline as a frame.
     */
    public Http2FrameListener frameListener(Http2FrameListener delegate) {
        return new Http2FrameListenerDecorator(delegate) {
            @Override
            public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) throws Http2Exception {
                windowUpdateRead(streamId);
                super.onWindowUpdateRead(ctx, streamId, windowSizeIncrement);
            }
        };
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2WindowUpdateFrame) {
            Http2FrameStream frameStream = ((Http2WindowUpdateFrame) msg).stream();
            if (frameStream != null) {
                windowUpdateRead(frameStream.id());
            }
        }
        ctx.fireChannelRead(msg);
    }

    private void windowUpdateRead(int streamId) {
        Http2Stream stream = connection != null ? connection.stream(streamId) : null;
        StreamWriteProgress progress = stream != null ? stream.getProperty(progressKey) : null;
        if (progress != null) {
            progress.windowUpdates++;
        }
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
        selectStalledStreams(flowController.initialWindowSize(), connectionWindow, socketStallWatched);
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

    /**
     * Picks the unmoved streams to reset. While the connection window is closed, a reset that should release it (a
     * closed-window stream, or those holding the most of it) gives each of the others one fresh period, and not
     * another until it is refreshed for any other reason. See "A stalled stream holding the connection window" in
     * docs/code/netty-pipeline.md.
     */
    private void selectStalledStreams(int initialWindow, int connectionWindow, boolean socketStallWatched) {
        boolean openWindowsTimed = !anyProgress && !socketStallWatched;
        long mostHeld = Long.MIN_VALUE;
        for (Http2Stream stream : unmovedStreams) {
            StreamWriteProgress progress = stream.getProperty(progressKey);
            progress.resetting = false;
            if (timedOut(progress, openWindowsTimed)) {
                mostHeld = Math.max(mostHeld, held(progress, initialWindow));
            }
        }
        boolean windowReleased = false;
        for (Http2Stream stream : unmovedStreams) {
            StreamWriteProgress progress = stream.getProperty(progressKey);
            if (timedOut(progress, openWindowsTimed)) {
                long held = held(progress, initialWindow);
                // streams holding the same cannot be told apart, so all are reset and the stalled one is among them
                if (progress.window <= 0 || held == mostHeld) {
                    progress.resetting = true;
                    stalledStreamIds.add(stream.id());
                    // a stream sent nothing holds no connection window, so resetting one never extends another's time
                    windowReleased |= connectionWindow <= 0 && held > 0;
                }
            }
        }
        for (Http2Stream stream : unmovedStreams) {
            StreamWriteProgress progress = stream.getProperty(progressKey);
            if (progress.window <= 0 || progress.resetting) {
                continue;
            }
            if (!openWindowsTimed) {
                progress.lastProgressNanos = now;
            } else if (windowReleased && progress.lastProgressNanos != progress.releaseGrantedNanos) {
                progress.lastProgressNanos = progress.releaseGrantedNanos = now;
            } else if (now - progress.lastProgressNanos >= timeoutNanos) {
                stalledStreamIds.add(stream.id());
            }
        }
    }

    private boolean timedOut(StreamWriteProgress progress, boolean openWindowsTimed) {
        return now - progress.lastProgressNanos >= timeoutNanos && (progress.window <= 0 || openWindowsTimed);
    }

    // the stream's data sent beyond the window its client has granted on top of the initial window: what it holds unconsumed
    private static long held(StreamWriteProgress progress, int initialWindow) {
        return (long) initialWindow - progress.window;
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
        private boolean resetting;
        // equal to lastProgressNanos while the stream's current period is the fresh one given for a reset
        private long releaseGrantedNanos;

        private StreamWriteProgress(int window, long lastProgressNanos) {
            this.window = window;
            this.lastProgressNanos = lastProgressNanos;
            this.releaseGrantedNanos = lastProgressNanos - 1;
        }
    }
}
