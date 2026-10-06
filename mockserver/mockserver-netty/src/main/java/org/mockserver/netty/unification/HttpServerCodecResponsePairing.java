package org.mockserver.netty.unification;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;
import org.mockserver.responsewriter.ResponseWrittenBeneathCodecEvent;

/**
 * Keeps {@link HttpServerCodec} pairing each response with the request it answers when an exchange ends without its
 * response passing the codec's encoder. The codec queues each request's method as it decodes the request and takes
 * the oldest off as it encodes a final response, which it encodes for that method (no body for {@code HEAD}).
 * A response written as raw bytes beneath the codec ({@link ResponseWrittenBeneathCodecEvent}), no response, or a
 * final {@code 1xx} ({@link HttpExchangeEndedEvent#INSTANCE}) takes none off, so every later response would be
 * encoded for the method of the request before its own.
 * <p>
 * The codec is final and its queue private, so on each of those events the handler {@link #afterCodec() after the
 * codec} has it encode an empty response of its own, which takes the entry off, and the handler
 * {@link #beforeCodec() before the codec} writes an empty buffer in place of what the encoder wrote, with the same
 * promise: nothing reaches the client, and a close the codec chains on that write still follows what came before.
 */
public final class HttpServerCodecResponsePairing {

    private final BeforeCodec beforeCodec = new BeforeCodec();
    private final AfterCodec afterCodec = new AfterCodec();
    // confined to the channel's event loop
    private boolean encodingStandIn;

    /**
     * The handler to add immediately before the HTTP server codec.
     */
    public ChannelHandler beforeCodec() {
        return beforeCodec;
    }

    /**
     * The handler to add immediately after the HTTP server codec.
     */
    public ChannelHandler afterCodec() {
        return afterCodec;
    }

    /**
     * Where to add a handler that must see what is written from beneath the codec, raw bytes and encoded responses
     * alike, but not the stand-in responses: before this pairing's handler before the codec, or the codec if it has
     * none. Null if the pipeline has no codec.
     */
    public static ChannelHandlerContext beneathCodec(ChannelPipeline pipeline) {
        ChannelHandlerContext beforeCodec = pipeline.context(BeforeCodec.class);
        return beforeCodec != null ? beforeCodec : pipeline.context(HttpServerCodec.class);
    }

    /**
     * Removes both handlers, if present, for when the connection stops carrying HTTP/1.1 (a CONNECT or SOCKS tunnel).
     */
    public static void removeFrom(ChannelPipeline pipeline) {
        if (pipeline.get(BeforeCodec.class) != null) {
            pipeline.remove(BeforeCodec.class);
        }
        if (pipeline.get(AfterCodec.class) != null) {
            pipeline.remove(AfterCodec.class);
        }
    }

    private final class AfterCodec extends ChannelInboundHandlerAdapter {

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if ((evt == ResponseWrittenBeneathCodecEvent.INSTANCE || evt == HttpExchangeEndedEvent.INSTANCE)
                && ctx.pipeline().get(HttpServerCodec.class) != null) {
                encodingStandIn = true;
                try {
                    // a write that fails (the channel has closed) fails its own promise only
                    ctx.write(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT, Unpooled.EMPTY_BUFFER), ctx.newPromise());
                } finally {
                    encodingStandIn = false;
                }
                // not flushed while replacing: the flush completes earlier writes, whose listeners may write for real
                ctx.flush();
            }
            ctx.fireUserEventTriggered(evt);
        }
    }

    private final class BeforeCodec extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (encodingStandIn) {
                ReferenceCountUtil.release(msg);
                ctx.write(Unpooled.EMPTY_BUFFER, promise);
            } else {
                ctx.write(msg, promise);
            }
        }
    }
}
