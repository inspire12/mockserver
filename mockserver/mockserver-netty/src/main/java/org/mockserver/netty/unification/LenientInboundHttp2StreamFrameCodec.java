package org.mockserver.netty.unification;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrame;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

import java.util.List;

/**
 * A {@link Http2StreamFrameToHttpObjectCodec} that is lenient on the INBOUND (request) side but
 * strict on the OUTBOUND (response) side.
 * <p>
 * Netty's {@link Http2StreamFrameToHttpObjectCodec} carries a single {@code validateHeaders} flag
 * that governs <em>both</em> directions of the HTTP/1-object &harr; HTTP/2-frame conversion. On the
 * gRPC multiplex re-aggregating chain MockServer needs the two directions to differ:
 * <ul>
 *   <li><strong>Inbound (lenient, {@code validateHeaders=false}):</strong> a request header value
 *       with a leading space, an embedded {@code 0x7F} (DEL), or a control character must be accepted
 *       and reach the matchers rather than being rejected with {@code RST_STREAM(PROTOCOL_ERROR)}
 *       before matching. This mirrors the shared-connection path, which sets
 *       {@code InboundHttp2ToHttpAdapterBuilder.validateHttpHeaders(false)}. The base class applies
 *       this flag in {@code decode}/{@code newMessage}/{@code newFullMessage} via
 *       {@link HttpConversionUtil#toHttpRequest}/{@code toFullHttpRequest}.</li>
 *   <li><strong>Outbound (strict):</strong> response header NAMES and trailer NAMES must still be
 *       validated exactly as Netty validates them by default, matching the shared-connection path
 *       whose {@code AbstractHttp2ConnectionHandlerBuilder.isValidateHeaders()} defaults to
 *       {@code true}. Passing {@code false} to the base class also silently relaxed this outbound
 *       name validation, which this subclass restores.</li>
 * </ul>
 * <p>
 * Only header/trailer NAMES are validated outbound — never values. The base class builds outbound
 * headers with {@code HttpConversionUtil.toHttp2Headers(..., validateHeaders)}, which uses the 2-arg
 * {@code DefaultHttp2Headers(validate, arraySizeHint)} constructor: that installs a name validator
 * ({@code HTTP2_NAME_VALIDATOR}) when {@code validate} is true and no value validator either way. (The
 * separate 3-arg {@code DefaultHttp2Headers(validate, validateValues, arraySizeHint)} constructor
 * <em>can</em> install a value validator, but the codec does not use it.) Header names are also
 * lower-cased before validation, so an uppercase name never trips it — only an illegal token character
 * (space, control char, DEL, separator, non-ASCII) does.
 * <p>
 * <strong>How the strict outbound check is re-asserted:</strong> rather than re-implementing the RFC
 * token rule by hand (which would drift from Netty across upgrades), this override performs the exact
 * conversion the base class would have performed with validation on — {@code toHttp2Headers(msg, true)}
 * for a response head and {@code toHttp2Headers(trailingHeaders, true)} for non-empty trailers — and
 * discards the result, letting it throw exactly the {@code Http2Exception} the strict path would throw.
 * The subsequent {@code super.encode} then re-does the conversion with {@code validateHeaders=false};
 * this is one extra header conversion per response head (and per trailer block), a deliberate and
 * acceptable cost to keep the check faithful to Netty. Per-chunk {@link io.netty.handler.codec.http.HttpContent}
 * carries no headers and is not validated.
 * <p>
 * <strong>A response to {@code HEAD}</strong> is sent as its header block alone, which ends the stream: the base class
 * would send its body as well, which RFC 9110 section 9.3.2 does not allow. The header block keeps the
 * {@code content-length} a {@code GET} would be sent, as Netty's {@code HttpServerCodec} does on HTTP/1.1.
 */
public class LenientInboundHttp2StreamFrameCodec extends Http2StreamFrameToHttpObjectCodec {

    // TRUE once the stream's request is HEAD, FALSE once the final response's header block has been written
    private static final AttributeKey<Boolean> HEAD_RESPONSE_PENDING = AttributeKey.valueOf("HTTP2_HEAD_RESPONSE_PENDING");

    public LenientInboundHttp2StreamFrameCodec() {
        // isServer=true; validateHeaders=false -> lenient INBOUND request-header conversion
        super(true, false);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Http2StreamFrame frame, List<Object> out) throws Exception {
        if (frame instanceof Http2HeadersFrame && HttpMethod.HEAD.asciiName().contentEquals(((Http2HeadersFrame) frame).headers().method())) {
            ctx.channel().attr(HEAD_RESPONSE_PENDING).set(Boolean.TRUE);
        }
        super.decode(ctx, frame, out);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        Attribute<Boolean> headResponsePending = ctx.channel().attr(HEAD_RESPONSE_PENDING);
        Boolean pending = headResponsePending.get();
        if (pending != null && msg instanceof HttpObject) {
            if (!pending) {
                // the header block already ended the stream: the body and trailers have nowhere to go
                ReferenceCountUtil.release(msg);
                promise.trySuccess();
                return;
            }
            if (msg instanceof HttpResponse && !isInterim((HttpResponse) msg)) {
                headResponsePending.set(Boolean.FALSE);
                HttpResponse response = (HttpResponse) msg;
                // no content and no trailers, so the base class ends the stream with the header block
                FullHttpResponse headerBlock = new DefaultFullHttpResponse(response.protocolVersion(), response.status(), Unpooled.EMPTY_BUFFER, response.headers(), EmptyHttpHeaders.INSTANCE);
                ReferenceCountUtil.release(msg);
                msg = headerBlock;
            }
        }
        super.write(ctx, msg, promise);
    }

    private static boolean isInterim(HttpResponse response) {
        return response.status().codeClass() == HttpStatusClass.INFORMATIONAL && response.status().code() != 101;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, HttpObject obj, List<Object> out) throws Exception {
        // Re-assert the strict OUTBOUND name validation the false flag disabled, using Netty's own
        // conversion so the rule (and the resulting Http2Exception) stays identical to the base class
        // with validation on. A FullHttpResponse is both an HttpMessage and a LastHttpContent, so each
        // check runs independently: the head is validated once, and its (usually empty) trailers once.
        if (obj instanceof HttpMessage) {
            // response head names
            HttpConversionUtil.toHttp2Headers((HttpMessage) obj, true);
        }
        if (obj instanceof LastHttpContent) {
            LastHttpContent last = (LastHttpContent) obj;
            if (!last.trailingHeaders().isEmpty()) {
                // trailer names (e.g. gRPC grpc-status / grpc-message)
                HttpConversionUtil.toHttp2Headers(last.trailingHeaders(), true);
            }
        }
        super.encode(ctx, obj, out);
    }

    /**
     * A stream's own error, trailers over {@code maxHeaderSize} among them, reaches the stream as an exception,
     * already logged, and Netty then resets the stream with the error's code. Do not pass it on: a handler further on
     * logs it again and closes the stream itself, which resets it with {@code CANCEL}; for a header block sent after
     * the request ended, on a stream whose whole response has been written, that close sends no reset at all.
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (Http2RequestHeaderLimit.isHeaderListOverLimit(cause)) {
            Http2RequestHeaderLimit.trailersRefused(ctx.channel());
        } else if (Http2StreamFaults.isStreamError(cause)) {
            Http2StreamFaults.errorLogged(ctx.channel());
        } else {
            super.exceptionCaught(ctx, cause);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        Http2StreamFaults.noteCancelled(ctx.channel(), evt);
        super.userEventTriggered(ctx, evt);
    }
}
