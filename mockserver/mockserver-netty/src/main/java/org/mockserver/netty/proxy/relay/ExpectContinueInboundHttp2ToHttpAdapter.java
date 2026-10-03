package org.mockserver.netty.proxy.relay;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter;
import io.netty.util.AsciiString;

/**
 * The proxy client's side of the CONNECT/SOCKS relay's HTTP/2 tunnel, handing each request on once, with its whole body.
 * Netty's {@link InboundHttp2ToHttpAdapter} hands a request carrying {@code Expect} on as soon as its headers arrive,
 * with no body, then again with the body: MockServer answered the headers alone and never saw the body. Here the relay
 * meets the expectation itself, as {@code HttpObjectAggregator} does on MockServer's own HTTP/2 streams and on an
 * HTTP/1.1 tunnel, and removes the {@code Expect} header, so MockServer does not answer it again:
 * <ul>
 *   <li>{@code 100-continue} with no {@code content-length} over {@code maxContentLength}: a {@code 100} response, then
 *       the request is handed on when its body is complete;</li>
 *   <li>{@code 100-continue} with a larger {@code content-length}: {@code 413}, and the request is not handed on;</li>
 *   <li>any other expectation: {@code 417}, and the request is not handed on.</li>
 * </ul>
 * After a {@code 413} or {@code 417} the stream is reset with {@code NO_ERROR}, so the client stops uploading (RFC 9113
 * section 8.1). The reset is also what keeps the tunnel up: DATA still arriving on a stream left open would reach the
 * adapter with no request to add it to, a connection error. A request whose headers end the stream is complete already, so is handed on as it is, {@code Expect}
 * included, as {@code HttpObjectAggregator} passes on a request that needs no aggregation.
 */
public class ExpectContinueInboundHttp2ToHttpAdapter extends InboundHttp2ToHttpAdapter {

    private final int maxContentLength;

    private ExpectContinueInboundHttp2ToHttpAdapter(Http2Connection connection, int maxContentLength) {
        super(connection, maxContentLength, false, true);
        this.maxContentLength = maxContentLength;
    }

    /**
     * An adapter listening to the connection, as Netty's builder makes one: a request still being uploaded is released
     * when its stream is removed.
     */
    public static ExpectContinueInboundHttp2ToHttpAdapter forConnection(Http2Connection connection, int maxContentLength) {
        ExpectContinueInboundHttp2ToHttpAdapter adapter = new ExpectContinueInboundHttp2ToHttpAdapter(connection, maxContentLength);
        connection.addListener(adapter);
        return adapter;
    }

    @Override
    protected FullHttpMessage processHeadersBegin(ChannelHandlerContext ctx, Http2Stream stream, Http2Headers headers, boolean endOfStream, boolean allowAppend, boolean appendToTrailer) throws Http2Exception {
        CharSequence expectation = !endOfStream && getMessage(stream) == null ? headers.get(HttpHeaderNames.EXPECT) : null;
        if (expectation != null) {
            // removed before the request is built, so the adapter does not hand it on at once
            headers.remove(HttpHeaderNames.EXPECT);
            HttpResponseStatus answer = answer(expectation, headers);
            Http2ConnectionHandler handler = (Http2ConnectionHandler) ctx.handler();
            Http2Headers response = new DefaultHttp2Headers().status(answer.codeAsText());
            if (answer == HttpResponseStatus.CONTINUE) {
                handler.encoder().writeHeaders(ctx, stream.id(), response, 0, false, ctx.newPromise());
                ctx.flush();
            } else {
                response.setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
                handler.encoder().writeHeaders(ctx, stream.id(), response, 0, true, ctx.newPromise());
                // do not remove: without it the client's DATA in flight is a connection error that closes the tunnel
                handler.resetStream(ctx, stream.id(), Http2Error.NO_ERROR.code(), ctx.newPromise());
                ctx.flush();
                return null;
            }
        }
        return super.processHeadersBegin(ctx, stream, headers, endOfStream, allowAppend, appendToTrailer);
    }

    private HttpResponseStatus answer(CharSequence expectation, Http2Headers headers) {
        if (!AsciiString.contentEqualsIgnoreCase(expectation, HttpHeaderValues.CONTINUE)) {
            return HttpResponseStatus.EXPECTATION_FAILED;
        }
        Long contentLength = headers.getLong(HttpHeaderNames.CONTENT_LENGTH);
        return contentLength != null && contentLength > maxContentLength ? HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE : HttpResponseStatus.CONTINUE;
    }
}
