package org.mockserver.netty.unification;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http2.Http2StreamChannel;
import org.mockserver.codec.CoalescingHttpObjectAggregator;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.NettyMessageForLog;
import org.slf4j.event.Level;

/**
 * What MockServer logs when an HTTP/1.1 connection closes, by its client's close or reset or otherwise, while a
 * request's body is still arriving: one {@code INFO} entry with the request's method, path, query string and headers
 * where the aggregator still holds them, and no stack trace, as an HTTP/2 stream that ends with its connection is
 * logged by {@link Http2StreamFaults}. HTTP/1.1 has no way to cancel an upload short of closing the connection.
 */
public final class Http1RequestCutShort {

    static final String ENDED_WITH_CONNECTION = "HTTP/1.1 request from:{}ended with its connection before it was complete";
    static final String REQUEST_ENDED_WITH_CONNECTION = "HTTP/1.1 request from:{}ended with its connection before it was complete:{}";

    private Http1RequestCutShort() {
    }

    /**
     * Whether {@code cause} is the aggregator of an HTTP/1.1 connection that has closed reporting the part of a request
     * it held, which this logs. A connection the request-line, header or chunk limits rejected is
     * {@code HttpChunkLineLimiter}'s to recognise first, and an HTTP/2 stream {@link Http2StreamFaults}'.
     */
    public static boolean isRequestCutShort(MockServerLogger mockServerLogger, ChannelHandlerContext ctx, Throwable cause) {
        if (!(cause instanceof PrematureChannelClosureException) || ctx.channel() instanceof Http2StreamChannel || ctx.channel().isActive()) {
            return false;
        }
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            CoalescingHttpObjectAggregator aggregator = ctx.pipeline().get(CoalescingHttpObjectAggregator.class);
            HttpRequest head = aggregator != null ? aggregator.requestBeingAggregated() : null;
            org.mockserver.model.HttpRequest request = NettyMessageForLog.request(head);
            LogEntry logEntry = new LogEntry().setLogLevel(Level.INFO);
            if (request != null) {
                logEntry
                    .setHttpRequest(request)
                    .setMessageFormat(REQUEST_ENDED_WITH_CONNECTION)
                    .setArguments(ctx.channel().remoteAddress(), request);
            } else {
                logEntry
                    .setMessageFormat(ENDED_WITH_CONNECTION)
                    .setArguments(ctx.channel().remoteAddress());
            }
            mockServerLogger.logEvent(logEntry);
        }
        return true;
    }
}
