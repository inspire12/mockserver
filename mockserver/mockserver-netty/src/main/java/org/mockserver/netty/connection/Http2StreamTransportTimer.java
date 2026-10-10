package org.mockserver.netty.connection;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.mockserver.metrics.Metrics;

/**
 * Times one HTTP/2 stream for {@code mock_server_request_transport_duration_seconds}: from its first
 * HEADERS frame reaching the stream's channel to the write of the frame that ends the response stream
 * completing. That write completes only once HTTP/2 flow control lets the frame out, so a slow reader is
 * included.
 * <p>
 * Installed on each stream child channel ahead of the frame-to-HTTP codec, only when metrics are enabled.
 * A stream carries one request, so each stream is recorded at most once; a reset stream is not recorded.
 */
public final class Http2StreamTransportTimer extends ChannelDuplexHandler {

    private static final long NOT_STARTED = -1L;
    private static final long RECORDED = -2L;

    private long startNanos = NOT_STARTED;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (startNanos == NOT_STARTED && msg instanceof Http2HeadersFrame) {
            startNanos = System.nanoTime();
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (startNanos >= 0 && endsStream(msg)) {
            long start = startNanos;
            startNanos = RECORDED;
            promise = promise.unvoid();
            promise.addListener(future -> {
                if (future.isSuccess()) {
                    Metrics.observeRequestTransportDurationSeconds((System.nanoTime() - start) / 1_000_000_000.0);
                }
            });
        }
        ctx.write(msg, promise);
    }

    private static boolean endsStream(Object msg) {
        return msg instanceof Http2DataFrame && ((Http2DataFrame) msg).isEndStream()
            || msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream();
    }
}
