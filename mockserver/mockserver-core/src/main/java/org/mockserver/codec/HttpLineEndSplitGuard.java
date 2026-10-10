package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;

import java.util.Map;

/**
 * Keeps the line limits of an HTTP/1.1 codec ({@code maxHeaderSize} for a header or trailer section,
 * {@code maxInitialLineLength} for a request or status line) the same however the bytes are split across reads.
 * <p>
 * Netty's decoder counts a CR whose LF has not yet arrived against what the line may still hold, so a section of
 * exactly the limit is refused when a read ends between its last line's CR and LF, and accepted when it does not.
 * The handler {@link #beforeCodec() before the codec} holds back a CR that ends a read and hands it on at the start of
 * the next read, so the decoder sees each CR with its LF. It does so only while the codec is reading a message head or
 * a chunked body: there more bytes must follow before anything completes. In any other body, or once the connection
 * may stop carrying HTTP/1.1 (a {@code CONNECT} or a {@code 101}) or is undecodable, it passes every byte on at
 * once, as the handler {@link #afterCodec() after the codec} tracks from what the codec decodes and what is written.
 * A held CR is handed on when the connection closes and when the handler is removed.
 */
public final class HttpLineEndSplitGuard {

    private enum State {
        BETWEEN_MESSAGES, CHUNKED_BODY, OTHER_BODY, NOT_HTTP
    }

    private final ChannelHandler codec;
    private final BeforeCodec beforeCodec = new BeforeCodec();
    private final AfterCodec afterCodec = new AfterCodec();
    // confined to the channel's event loop
    private State state = State.BETWEEN_MESSAGES;
    private boolean connectRequested;

    /**
     * @param codec the HTTP/1.1 codec (or decoder) the two handlers are added either side of
     */
    public HttpLineEndSplitGuard(ChannelHandler codec) {
        this.codec = codec;
    }

    /**
     * The handler to add immediately before the codec.
     */
    public ChannelHandler beforeCodec() {
        return beforeCodec;
    }

    /**
     * The handler to add immediately after the codec.
     */
    public ChannelHandler afterCodec() {
        return afterCodec;
    }

    /**
     * Removes this guard's two handlers, if present; a held CR is handed on.
     */
    public void remove(ChannelPipeline pipeline) {
        if (pipeline.context(beforeCodec) != null) {
            pipeline.remove(beforeCodec);
        }
        if (pipeline.context(afterCodec) != null) {
            pipeline.remove(afterCodec);
        }
    }

    /**
     * Removes both handlers of the pipeline's one guard, if present, for when the connection stops carrying HTTP/1.1;
     * a held CR is handed on.
     */
    public static void removeFrom(ChannelPipeline pipeline) {
        if (pipeline.get(BeforeCodec.class) != null) {
            pipeline.remove(BeforeCodec.class);
        }
        if (pipeline.get(AfterCodec.class) != null) {
            pipeline.remove(AfterCodec.class);
        }
    }

    private final class BeforeCodec extends ChannelInboundHandlerAdapter {

        private boolean holdingCr;
        private boolean heldWholeRead;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf)) {
                handOnHeldCr(ctx);
                ctx.fireChannelRead(msg);
                return;
            }
            ByteBuf in = (ByteBuf) msg;
            if (holdingCr) {
                holdingCr = false;
                ByteBuf joined = ctx.alloc().buffer(1 + in.readableBytes());
                joined.writeByte(HttpConstants.CR).writeBytes(in);
                in.release();
                in = joined;
            }
            int readable = in.readableBytes();
            if (readable == 0 || in.getByte(in.readerIndex() + readable - 1) != HttpConstants.CR || state == State.NOT_HTTP) {
                forward(ctx, in);
                return;
            }
            if (readable > 1) {
                forward(ctx, in.readRetainedSlice(readable - 1));
            }
            // decided after the codec has read the rest, which may have ended a head or started a body
            if ((state == State.BETWEEN_MESSAGES || state == State.CHUNKED_BODY) && !ctx.isRemoved() && codecFollows(ctx)) {
                in.release();
                holdingCr = true;
                heldWholeRead |= readable == 1;
            } else {
                forward(ctx, in);
            }
        }

        private void forward(ChannelHandlerContext ctx, ByteBuf bytes) {
            // the codec has a read in this cycle, so asks for the next one itself if it decodes nothing
            heldWholeRead = false;
            ctx.fireChannelRead(bytes);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            // the codec saw nothing of this read, so would not ask for the next one as it does when it decodes nothing
            if (heldWholeRead && !ctx.channel().config().isAutoRead()) {
                ctx.read();
            }
            heldWholeRead = false;
            ctx.fireChannelReadComplete();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            handOnHeldCr(ctx);
            ctx.fireChannelInactive();
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            handOnHeldCr(ctx);
        }

        private void handOnHeldCr(ChannelHandlerContext ctx) {
            if (holdingCr) {
                holdingCr = false;
                ctx.fireChannelRead(ctx.alloc().buffer(1).writeByte(HttpConstants.CR));
            }
        }

        private boolean codecFollows(ChannelHandlerContext ctx) {
            boolean afterThis = false;
            for (Map.Entry<String, ChannelHandler> entry : ctx.pipeline()) {
                if (afterThis) {
                    return entry.getValue() == codec;
                }
                afterThis = entry.getValue() == this;
            }
            return false;
        }
    }

    private final class AfterCodec extends ChannelDuplexHandler {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (state != State.NOT_HTTP && msg instanceof HttpObject) {
                if (((HttpObject) msg).decoderResult().isFailure()) {
                    // the codec discards the rest of the connection's input
                    state = State.NOT_HTTP;
                } else {
                    if (msg instanceof HttpRequest) {
                        state = HttpMethod.CONNECT.equals(((HttpRequest) msg).method()) ? State.NOT_HTTP : bodyState((HttpRequest) msg);
                    } else if (msg instanceof HttpResponse) {
                        state = switchesProtocol(((HttpResponse) msg).status()) ? State.NOT_HTTP : bodyState((HttpResponse) msg);
                    }
                    if (msg instanceof LastHttpContent && state != State.NOT_HTTP) {
                        state = State.BETWEEN_MESSAGES;
                    }
                }
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof HttpRequest && HttpMethod.CONNECT.equals(((HttpRequest) msg).method())) {
                connectRequested = true;
            } else if (msg instanceof HttpResponse && ((HttpResponse) msg).status().code() == HttpResponseStatus.SWITCHING_PROTOCOLS.code()) {
                state = State.NOT_HTTP;
            }
            super.write(ctx, msg, promise);
        }

        private State bodyState(HttpMessage message) {
            return HttpUtil.isTransferEncodingChunked(message) ? State.CHUNKED_BODY : State.OTHER_BODY;
        }

        private boolean switchesProtocol(HttpResponseStatus status) {
            return status.code() == HttpResponseStatus.SWITCHING_PROTOCOLS.code()
                || connectRequested && status.codeClass() == HttpStatusClass.SUCCESS;
        }
    }
}
