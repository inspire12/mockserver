package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;

/**
 * On the relay's end of an HTTP/1.1 loopback, before its codec: takes the bytes of each response MockServer wrote as
 * raw bytes out of what the codec is given and hands them on as {@link RawResponseBytes}. The codec would hold bytes
 * that are not a whole response and fail on bytes that are not HTTP; a client on a direct connection is sent both.
 * MockServer's end announces each such response, as an offset and a length in the bytes it writes, before it writes
 * it ({@link LoopbackExchangeEndedHandler}), so an announcement is here before its bytes are read. One per loopback.
 */
final class LoopbackRawResponseSplitter extends ChannelInboundHandlerAdapter {

    private static final AttributeKey<LoopbackRawResponseSplitter> OF_TUNNEL = AttributeKey.valueOf("mockserver.relayRawResponseSplitter");

    // each a start and an end offset, in order: added on MockServer's end's event loop, taken on this one's
    private final Queue<long[]> announced = new ConcurrentLinkedQueue<>();
    private volatile ChannelHandlerContext ctx;
    // confined to the loopback's event loop
    private long read;

    private LoopbackRawResponseSplitter() {
    }

    // kept on the proxy client's channel, which MockServer's end of the loopback can look up
    static LoopbackRawResponseSplitter forTunnel(Channel proxyClient) {
        LoopbackRawResponseSplitter splitter = new LoopbackRawResponseSplitter();
        proxyClient.attr(OF_TUNNEL).set(splitter);
        return splitter;
    }

    // null if the tunnel's loopback is not HTTP/1.1
    static LoopbackRawResponseSplitter of(Channel proxyClient) {
        return proxyClient.attr(OF_TUNNEL).get();
    }

    /**
     * Must be called before the first of the bytes is written to the loopback. Safe from any thread.
     * The offset is how many bytes MockServer's end had written from its codec's position before these.
     */
    void announce(long offset, int length) {
        announced.add(new long[]{offset, offset + length});
        ChannelHandlerContext ctx = this.ctx;
        if (length == 0 && ctx != null) {
            // no bytes will arrive to mark where it falls
            try {
                ctx.executor().execute(() -> relayEmptyResponses(ctx));
            } catch (RejectedExecutionException eventLoopShutDown) {
                // the loopback is closing with its event loop
            }
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        ByteBuf in = (ByteBuf) msg;
        if (announced.isEmpty()) {
            // no raw bytes can be in this read: passed on whole, so the codec's cumulation need not copy it
            read += in.readableBytes();
            ctx.fireChannelRead(in);
            return;
        }
        try {
            while (in.isReadable()) {
                long[] raw = announced.peek();
                long encoded = raw == null ? in.readableBytes() : Math.min(in.readableBytes(), raw[0] - read);
                if (encoded > 0) {
                    read += encoded;
                    ctx.fireChannelRead(in.readRetainedSlice((int) encoded));
                } else {
                    int length = (int) Math.min(in.readableBytes(), raw[1] - read);
                    read += length;
                    boolean endsResponse = read == raw[1];
                    if (endsResponse) {
                        announced.remove();
                    }
                    ctx.fireChannelRead(new RawResponseBytes(in.readRetainedSlice(length), endsResponse));
                }
                relayEmptyResponses(ctx);
            }
        } finally {
            in.release();
        }
    }

    // a response of no bytes, announced at the offset read so far: its exchange ends with nothing relayed
    private void relayEmptyResponses(ChannelHandlerContext ctx) {
        for (long[] raw = announced.peek(); raw != null && raw[1] <= read && !ctx.isRemoved(); raw = announced.peek()) {
            announced.remove();
            ctx.fireChannelRead(new RawResponseBytes(Unpooled.EMPTY_BUFFER, true));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        announced.clear();
        ctx.fireChannelInactive();
    }
}
