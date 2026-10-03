package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpExpectationFailedEvent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;
import org.slf4j.event.Level;

import java.util.concurrent.TimeUnit;

/**
 * Bounds what an HTTP/1.1 server codec buffers while it reads a chunked request body.
 * <p>
 * Netty's decoder limits a chunk-size line (the hex size and any chunk extensions) with the same setting as the
 * request line, {@code maxInitialLineLength}, and limits the trailer section with {@code maxHeaderSize}. MockServer
 * leaves both unbounded by default, so without this a client could make the decoder buffer one endless chunk-size
 * line. The codec is final and does not say which line it is parsing, so the limit is applied around it: one handler
 * {@link #beforeCodec() before the codec} counts the bytes handed to it, one {@link #afterCodec() after the codec}
 * resets the count whenever the codec decodes anything. Inside a chunked body the decoder passes chunk data on as it
 * arrives, so bytes that pile up with nothing decoded are an unfinished chunk-size line or trailer section.
 * That premise needs Netty's default {@code allowPartialChunks=true}: do not wrap a codec built with it off.
 * <p>
 * A line or trailer section of up to {@link #MAX_CHUNK_LINE_BYTES} is never rejected. A longer one is rejected once
 * the bytes waiting for its end pass the limit, which is on a later socket read than the one it started in, so the
 * codec holds at most the limit plus the reads either side. A rejected request is answered with {@code 400} when no
 * other response is owed or under way on the connection, which is then closed.
 */
public final class HttpChunkLineLimiter {

    /**
     * The same size Tomcat allows for chunk extensions and for trailers; signed uploads use about 100 bytes.
     */
    public static final int MAX_CHUNK_LINE_BYTES = 8192;

    static final long CLOSE_DELAY_MILLIS = 1000;

    // the CRLF ending the previous chunk can be counted with the line that follows it
    private static final int CHUNK_DELIMITER_BYTES = 2;

    private final MockServerLogger mockServerLogger;
    private final ChannelHandler beforeCodec = new BeforeCodec();
    private final AfterCodec afterCodec = new AfterCodec();

    private boolean inChunkedBody;
    private long bytesSinceLastDecoded;
    private boolean rejected;
    private int requestsAwaitingResponse;
    private boolean responseStarted;
    private ChannelHandlerContext afterCodecContext;

    public HttpChunkLineLimiter(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

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

    private void reject(Channel channel) {
        rejected = true;
        inChunkedBody = false;
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("closing connection from:{}because a chunk-size line (with its chunk extensions) or trailer section in a chunked request body is longer than " + MAX_CHUNK_LINE_BYTES + " bytes")
                    .setArguments(channel.remoteAddress())
            );
        }
        boolean canRespond = afterCodecContext != null && requestsAwaitingResponse == 1 && !responseStarted && channel.isActive();
        if (!canRespond) {
            channel.close();
            return;
        }
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST, Unpooled.EMPTY_BUFFER);
        response.headers()
            .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
            .set(HttpHeaderNames.CONTENT_LENGTH, 0);
        // closing with request bytes unread resets the connection, which can discard the response before the client
        // reads it, so keep reading (and dropping) for a moment
        channel.config().setAutoRead(true);
        afterCodecContext.writeAndFlush(response).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess() && channel.isActive()) {
                channel.eventLoop().schedule(() -> channel.close(), CLOSE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
            } else {
                channel.close();
            }
        });
    }

    private final class BeforeCodec extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (rejected) {
                ReferenceCountUtil.release(msg);
                return;
            }
            if (inChunkedBody && msg instanceof ByteBuf) {
                bytesSinceLastDecoded += ((ByteBuf) msg).readableBytes();
                ctx.fireChannelRead(msg);
                if (inChunkedBody && bytesSinceLastDecoded > MAX_CHUNK_LINE_BYTES + CHUNK_DELIMITER_BYTES) {
                    reject(ctx.channel());
                }
            } else {
                ctx.fireChannelRead(msg);
            }
        }
    }

    private final class AfterCodec extends ChannelDuplexHandler {

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            afterCodecContext = ctx;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof HttpObject) {
                bytesSinceLastDecoded = 0;
                if (msg instanceof HttpRequest) {
                    requestsAwaitingResponse++;
                    inChunkedBody = ((HttpRequest) msg).decoderResult().isSuccess() && HttpUtil.isTransferEncodingChunked((HttpRequest) msg);
                }
                if (msg instanceof LastHttpContent) {
                    inChunkedBody = false;
                }
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt == HttpExchangeEndedEvent.INSTANCE) {
                responseEnded();
            }
            if (evt instanceof HttpExpectationFailedEvent) {
                // the decoder has reset to read a new request, abandoning the body without a LastHttpContent
                inChunkedBody = false;
                bytesSinceLastDecoded = 0;
            }
            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            boolean interim = msg instanceof HttpResponse && isInterim(((HttpResponse) msg).status());
            if (!interim) {
                if (msg instanceof HttpResponse) {
                    responseStarted = true;
                }
                if (msg instanceof LastHttpContent) {
                    responseEnded();
                }
            }
            super.write(ctx, msg, promise);
        }

        private void responseEnded() {
            responseStarted = false;
            if (requestsAwaitingResponse > 0) {
                requestsAwaitingResponse--;
            }
        }

        private boolean isInterim(HttpResponseStatus status) {
            return status.codeClass() == HttpStatusClass.INFORMATIONAL && status.code() != HttpResponseStatus.SWITCHING_PROTOCOLS.code();
        }
    }
}
