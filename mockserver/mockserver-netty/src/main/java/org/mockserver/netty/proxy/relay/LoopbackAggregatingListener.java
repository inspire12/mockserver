package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameListenerDecorator;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapterBuilder;
import org.mockserver.codec.BoundedZstdDecompressorFrameListener;

/**
 * Reads a response the CONNECT/SOCKS relay's HTTP/2 loopback holds whole: decoded, then aggregated by
 * {@code InboundHttp2ToHttpAdapter}. Netty's decompressing listener empties a stream's decompressor when the stream is
 * removed, which can throw again for a body that failed to decode, and then hands one last empty DATA frame for the
 * removed stream to the adapter, which throws a {@code NullPointerException}; Netty logs either at {@code ERROR}. A
 * removed stream has nothing left to report to, so both are dropped here.
 */
final class LoopbackAggregatingListener {

    private LoopbackAggregatingListener() {
    }

    static Http2FrameListener of(Http2Connection connection, int maxContentLength) {
        Http2FrameListener aggregating = new InboundHttp2ToHttpAdapterBuilder(connection)
            .maxContentLength(maxContentLength)
            .propagateSettings(true)
            .validateHttpHeaders(false)
            .build();
        return new BoundedZstdDecompressorFrameListener(connection, new RemovedStreamDataFilter(connection, aggregating)) {
            @Override
            protected EmbeddedChannel newContentDecompressor(ChannelHandlerContext ctx, CharSequence contentEncoding) throws Http2Exception {
                EmbeddedChannel decompressor = super.newContentDecompressor(ctx, contentEncoding);
                if (decompressor != null) {
                    decompressor.pipeline().addLast(new QuietOnceClosed());
                }
                return decompressor;
            }
        };
    }

    /**
     * Drops DATA for a stream the connection does not have: the decoder hands on no other.
     */
    private static final class RemovedStreamDataFilter extends Http2FrameListenerDecorator {
        private final Http2Connection connection;

        private RemovedStreamDataFilter(Http2Connection connection, Http2FrameListener aggregating) {
            super(aggregating);
            this.connection = connection;
        }

        @Override
        public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) throws Http2Exception {
            if (connection.stream(streamId) == null) {
                return data.readableBytes() + padding;
            }
            return super.onDataRead(ctx, streamId, data, padding, endOfStream);
        }
    }

    /**
     * After the decoder, in a decompressor's own channel: drops what the decoder throws once the channel is closing,
     * which happens when its stream has been removed, or ended. A fault decoding a DATA frame still resets the stream.
     */
    private static final class QuietOnceClosed extends ChannelInboundHandlerAdapter {
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (ctx.channel().isActive()) {
                ctx.fireExceptionCaught(cause);
            }
        }
    }
}
