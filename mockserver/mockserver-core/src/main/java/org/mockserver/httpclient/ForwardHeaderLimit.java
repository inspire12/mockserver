package org.mockserver.httpclient;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.TooLongHttpHeaderException;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Message;
import org.slf4j.event.Level;

import java.util.concurrent.CompletableFuture;

import static org.mockserver.httpclient.NettyHttpClient.REMOTE_SOCKET;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

/**
 * Fails a forward whose upstream response has headers or trailers larger than {@code maxHeaderSize} with a
 * {@link HeaderLimitExceededException} that says which, and logs each once. Netty's codecs enforce the limit; the
 * handlers here turn what they report into that failure.
 * <p>
 * The size is the one each protocol counts: over HTTP/1.1 the header lines without their line ends, over HTTP/2 each
 * field's name and value plus 32 bytes after HPACK decoding (RFC 9113 section 6.5.2).
 */
final class ForwardHeaderLimit {

    static final String RESPONSE_HEADERS = "upstream response headers are";
    static final String RESPONSE_TRAILERS = "upstream response trailers are";
    static final String RESPONSE_HEADERS_AND_TRAILERS = "upstream response headers and trailers are together";
    static final String RESPONSE_HEADER_BLOCK = "an upstream response header block is more than a quarter";
    static final String CONNECT_RESPONSE_HEADERS = "the upstream proxy's CONNECT response headers are";

    /**
     * The start of Netty's message for a header block over the limit plus a quarter, which is a plain connection
     * error: {@code Http2CodecUtil.headerListSizeExceeded(long)}.
     */
    private static final String HEADER_BLOCK_TOO_LARGE = "Header size exceeded max allowed size";

    private static final AttributeKey<Boolean> REFUSED = AttributeKey.valueOf("FORWARD_HEADER_LIMIT_REFUSED");

    private ForwardHeaderLimit() {
    }

    /**
     * Logs the refusal and marks the upstream connection, which is not used again.
     *
     * @param what what was too large, one of this class's constants
     */
    static HeaderLimitExceededException responseOverLimit(MockServerLogger mockServerLogger, Channel channel, String what, int maxHeaderSize) {
        return refuse(mockServerLogger, channel, what + " larger than maxHeaderSize (" + maxHeaderSize + " bytes)");
    }

    /**
     * Whether {@code cause} needs no log entry of its own: it is a refusal, or the aggregator reporting the part of a
     * response it held when the connection was closed for one.
     */
    static boolean isAlreadyLogged(Channel channel, Throwable cause) {
        return cause instanceof HeaderLimitExceededException
            || cause instanceof PrematureChannelClosureException && (channel.hasAttr(REFUSED) || channel.parent() != null && channel.parent().hasAttr(REFUSED));
    }

    private static HeaderLimitExceededException refuse(MockServerLogger mockServerLogger, Channel channel, String reason) {
        HeaderLimitExceededException refusal = new HeaderLimitExceededException(reason);
        Channel connection = channel.parent() != null ? channel.parent() : channel;
        connection.attr(REFUSED).set(Boolean.TRUE);
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("failing forward to:{}because:{}")
                    .setArguments(connection.attr(REMOTE_SOCKET).get(), reason)
            );
        }
        return refusal;
    }

    private static void failForward(Channel channel, HeaderLimitExceededException refusal) {
        CompletableFuture<Message> responseFuture = channel.attr(RESPONSE_FUTURE).get();
        if (responseFuture != null) {
            responseFuture.completeExceptionally(refusal);
        }
    }

    /**
     * Directly after the HTTP/1.1 client codec, which hands on a response head or last content it stopped decoding
     * with the failure attached and then discards the connection's input. It counts a response's trailers on top of
     * its headers.
     */
    static final class Http1Response extends ChannelInboundHandlerAdapter {

        private final MockServerLogger mockServerLogger;
        private final int maxHeaderSize;

        Http1Response(MockServerLogger mockServerLogger, int maxHeaderSize) {
            this.mockServerLogger = mockServerLogger;
            this.maxHeaderSize = maxHeaderSize;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof HttpObject && ((HttpObject) msg).decoderResult().cause() instanceof TooLongHttpHeaderException) {
                ReferenceCountUtil.release(msg);
                ctx.fireExceptionCaught(responseOverLimit(mockServerLogger, ctx.channel(), msg instanceof HttpResponse ? RESPONSE_HEADERS : RESPONSE_HEADERS_AND_TRAILERS, maxHeaderSize));
                ctx.close();
            } else {
                ctx.fireChannelRead(msg);
            }
        }
    }

    /**
     * First on each HTTP/2 stream: Netty resets a stream whose header list is over the limit and reports it here.
     */
    static final class Http2Stream extends ChannelInboundHandlerAdapter {

        private final MockServerLogger mockServerLogger;
        private final int maxHeaderSize;
        private boolean headersRead;

        Http2Stream(MockServerLogger mockServerLogger, int maxHeaderSize) {
            this.mockServerLogger = mockServerLogger;
            this.maxHeaderSize = maxHeaderSize;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame) {
                CharSequence status = ((Http2HeadersFrame) msg).headers().status();
                headersRead |= status == null || HttpStatusClass.valueOf(status) != HttpStatusClass.INFORMATIONAL;
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Http2Exception failure = Http2CodecUtil.getEmbeddedHttp2Exception(cause);
            if (failure instanceof Http2Exception.HeaderListSizeException && ((Http2Exception.HeaderListSizeException) failure).duringDecode()) {
                ctx.fireExceptionCaught(responseOverLimit(mockServerLogger, ctx.channel(), headersRead ? RESPONSE_TRAILERS : RESPONSE_HEADERS, maxHeaderSize));
            } else {
                ctx.fireExceptionCaught(cause);
            }
        }
    }

    /**
     * On an HTTP/2 connection, after the multiplex handler: a header block of more than the limit plus a quarter is a
     * connection error, which Netty reports here before it sends GOAWAY and closes the connection. Any other
     * exception is passed on to {@link Http2ForwardConnectionExceptionHandler}.
     */
    static final class Http2Connection extends ChannelInboundHandlerAdapter {

        private final MockServerLogger mockServerLogger;
        private final int maxHeaderSize;

        Http2Connection(MockServerLogger mockServerLogger, int maxHeaderSize) {
            this.mockServerLogger = mockServerLogger;
            this.maxHeaderSize = maxHeaderSize;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Http2Exception failure = Http2CodecUtil.getEmbeddedHttp2Exception(cause);
            if (failure != null
                && !Http2Exception.isStreamError(failure)
                && failure.error() == Http2Error.PROTOCOL_ERROR
                && failure.getMessage() != null
                && failure.getMessage().startsWith(HEADER_BLOCK_TOO_LARGE)) {
                failForward(ctx.channel(), responseOverLimit(mockServerLogger, ctx.channel(), RESPONSE_HEADER_BLOCK, maxHeaderSize));
            } else {
                ctx.fireExceptionCaught(cause);
            }
        }
    }
}
