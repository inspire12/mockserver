package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

/**
 * On MockServer's side of an HTTP/1.1 relay loopback, immediately before its codec: counts the bytes written from
 * the codec's position, encoded responses and raw bytes alike. The relay's end counts what it reads at the same
 * position ({@link LoopbackRawResponseSplitter}), so the count is an offset both ends agree on. The handlers nearer
 * the socket must write what they are given, in order, and nothing of their own (TLS and pacing do).
 */
final class LoopbackWrittenBytes extends ChannelOutboundHandlerAdapter {

    // confined to the channel's event loop
    private long count;

    long count() {
        return count;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        // beneath HttpServerCodec everything written is a ByteBuf
        if (msg instanceof ByteBuf) {
            count += ((ByteBuf) msg).readableBytes();
        }
        ctx.write(msg, promise);
    }
}
