package org.mockserver.netty.unification;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameListenerDecorator;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.util.AttributeKey;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.net.SocketAddress;

/**
 * What MockServer logs when an HTTP/2 stream ends before it should, on a connection made straight to MockServer and
 * on the client leg of a CONNECT or SOCKS tunnel alike, each once and with no stack trace:
 * <ul>
 *   <li>{@code INFO} for a request its client cancelled, or whose connection closed, before it was complete;</li>
 *   <li>{@code WARN} for an error of the stream's own, which Netty then resets the stream for with the error's code;</li>
 *   <li>{@code WARN} for a connection Netty closes because it had to be sent too many of those resets.</li>
 * </ul>
 * See "HTTP/2 streams cut short" in docs/code/netty-pipeline.md for what bounds the entries of one connection.
 */
public final class Http2StreamFaults {

    static final String CANCELLED = "HTTP/2 stream:{}from:{}was cancelled by its client with:{}before its request was complete";
    static final String ENDED_WITH_CONNECTION = "HTTP/2 stream:{}from:{}ended with its connection before its request was complete";
    static final String STREAM_ERROR = "resetting HTTP/2 stream:{}from:{}for stream error:{}because:{}";
    static final String CLOSING_CONNECTION = "closing HTTP/2 connection from:{}for connection error:{}because:{}";

    private static final AttributeKey<Long> CANCELLED_WITH = AttributeKey.valueOf("HTTP2_STREAM_CANCELLED_WITH");
    private static final AttributeKey<Boolean> ERROR_LOGGED = AttributeKey.valueOf("HTTP2_STREAM_ERROR_LOGGED");

    private Http2StreamFaults() {
    }

    /**
     * Logs an error Netty is about to reset an open stream for, or to close the connection for because it has sent
     * too many resets. Called as the error is raised, on both kinds of connection. A request refused for its header
     * size is {@link Http2RequestHeaderLimit}'s to log, and an error on a stream that has gone has nothing to reset.
     */
    static void logError(MockServerLogger mockServerLogger, ChannelHandlerContext ctx, boolean outbound, Throwable cause) {
        Http2Exception failure = Http2CodecUtil.getEmbeddedHttp2Exception(cause);
        if (failure == null || mockServerLogger == null || !mockServerLogger.isEnabledForInstance(Level.WARN)) {
            return;
        }
        if (outbound) {
            // Netty's limit on the resets it sends, or on the control frames queued for a client that reads none
            if (!Http2Exception.isStreamError(failure) && failure.error() == Http2Error.ENHANCE_YOUR_CALM) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat(CLOSING_CONNECTION)
                        .setArguments(ctx.channel().remoteAddress(), failure.error(), failure.getMessage())
                );
            }
        } else if (isStreamError(failure) && isOpen(ctx, Http2Exception.streamId(failure))) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat(STREAM_ERROR)
                    .setArguments(Http2Exception.streamId(failure), ctx.channel().remoteAddress(), failure.error(), failure.getMessage())
            );
        }
    }

    /**
     * @return whether {@code cause} is itself a stream's own error other than a header list refused for its size, as
     * Netty hands it to the stream of a direct connection after {@link #logError} and before it resets the stream.
     * A stream error wrapped in another exception was raised by one of the stream's handlers, which Netty does not
     * reset the stream for
     */
    static boolean isStreamError(Throwable cause) {
        return cause instanceof Http2Exception.StreamException && !(cause instanceof Http2Exception.HeaderListSizeException);
    }

    /**
     * Marks a direct connection's stream as reset for an error already logged, so that the part of a request it held
     * is not reported as well.
     */
    static void errorLogged(Channel stream) {
        stream.attr(ERROR_LOGGED).set(Boolean.TRUE);
    }

    /**
     * Notes the client's RST_STREAM on a direct connection's stream, which Netty reports just before it closes it.
     */
    static void noteCancelled(Channel stream, Object event) {
        if (event instanceof Http2ResetFrame) {
            stream.attr(CANCELLED_WITH).set(((Http2ResetFrame) event).errorCode());
        }
    }

    /**
     * Whether {@code cause} is the aggregator of a direct connection's stream reporting the part of a request it held
     * when the stream closed, for a reason found here: an error already logged, the client's cancel or the
     * connection's end, each of the last two logged here. Anything else closed the stream and is the caller's to report.
     */
    public static boolean isRequestCutShort(MockServerLogger mockServerLogger, Channel channel, Throwable cause) {
        if (!(cause instanceof PrematureChannelClosureException) || !(channel instanceof Http2StreamChannel)) {
            return false;
        }
        if (channel.hasAttr(ERROR_LOGGED)) {
            return true;
        }
        int streamId = ((Http2StreamChannel) channel).stream().id();
        Long cancelledWith = channel.hasAttr(CANCELLED_WITH) ? channel.attr(CANCELLED_WITH).get() : null;
        if (cancelledWith != null) {
            logCancelled(mockServerLogger, streamId, channel.remoteAddress(), cancelledWith);
            return true;
        }
        if (!channel.parent().isActive()) {
            logEndedWithConnection(mockServerLogger, streamId, channel.remoteAddress());
            return true;
        }
        return false;
    }

    /**
     * Wraps the frame listener of a tunnel's client leg, where the relay holds a request until it is complete, to log
     * one its client cancels before then.
     */
    static Http2FrameListener tunnelFrameListener(MockServerLogger mockServerLogger, Http2Connection connection, Http2FrameListener delegate) {
        return new Http2FrameListenerDecorator(delegate) {
            @Override
            public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) throws Http2Exception {
                Http2Stream stream = connection.stream(streamId);
                if (stream != null && stream.state().remoteSideOpen()) {
                    logCancelled(mockServerLogger, streamId, ctx.channel().remoteAddress(), errorCode);
                }
                super.onRstStreamRead(ctx, streamId, errorCode);
            }
        };
    }

    /**
     * Logs each request a tunnel's client leg was still receiving when its connection closed. Called before Netty
     * closes the connection's streams.
     */
    static void logRequestsEndedWithConnection(MockServerLogger mockServerLogger, ChannelHandlerContext ctx, Http2Connection connection) {
        if (mockServerLogger == null || !mockServerLogger.isEnabledForInstance(Level.INFO) || connection.numActiveStreams() == 0) {
            return;
        }
        SocketAddress client = ctx.channel().remoteAddress();
        try {
            connection.forEachActiveStream(stream -> {
                if (stream.state().remoteSideOpen() && connection.remote().isValidStreamId(stream.id())) {
                    logEndedWithConnection(mockServerLogger, stream.id(), client);
                }
                return true;
            });
        } catch (Http2Exception neverThrownByTheVisitor) {
            // nothing to do: the visitor only logs
        }
    }

    private static boolean isOpen(ChannelHandlerContext ctx, int streamId) {
        return ctx.handler() instanceof Http2ConnectionHandler && ((Http2ConnectionHandler) ctx.handler()).connection().stream(streamId) != null;
    }

    private static void logCancelled(MockServerLogger mockServerLogger, int streamId, SocketAddress client, long errorCode) {
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            Http2Error error = Http2Error.valueOf(errorCode);
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.INFO)
                    .setMessageFormat(CANCELLED)
                    .setArguments(streamId, client, error != null ? error : errorCode)
            );
        }
    }

    private static void logEndedWithConnection(MockServerLogger mockServerLogger, int streamId, SocketAddress client) {
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.INFO)
                    .setMessageFormat(ENDED_WITH_CONNECTION)
                    .setArguments(streamId, client)
            );
        }
    }
}
