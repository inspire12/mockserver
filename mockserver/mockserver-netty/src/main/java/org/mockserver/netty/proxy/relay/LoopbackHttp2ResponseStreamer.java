package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameListenerDecorator;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2LocalFlowController;
import io.netty.handler.codec.http2.Http2Stream;

/**
 * Hands a response of undeclared length on from the CONNECT/SOCKS relay's HTTP/2 loopback frame by frame, as a
 * {@link StreamedHttp2ResponsePart} each, instead of holding it whole: server-sent events, a gRPC stream. A DATA
 * frame's bytes go back to the loopback stream's window only once written to the proxy client. Goes before the
 * decompressing listener; one per loopback. See "HTTP/2 loopback: streamed responses" in docs/code/netty-pipeline.md.
 */
final class LoopbackHttp2ResponseStreamer {

    private final Http2Connection connection;
    private final Http2Connection.PropertyKey streamedKey;

    LoopbackHttp2ResponseStreamer(Http2Connection connection) {
        this.connection = connection;
        this.streamedKey = connection.newKey();
    }

    Http2FrameListener relaying(Http2FrameListener aggregating) {
        return new Http2FrameListenerDecorator(aggregating) {
            @Override
            public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding, boolean endOfStream) throws Http2Exception {
                if (relayedAsRead(streamId, headers, endOfStream)) {
                    ctx.fireChannelRead(StreamedHttp2ResponsePart.headers(streamId, headers, endOfStream));
                } else {
                    super.onHeadersRead(ctx, streamId, headers, padding, endOfStream);
                }
            }

            @Override
            public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endOfStream) throws Http2Exception {
                if (relayedAsRead(streamId, headers, endOfStream)) {
                    ctx.fireChannelRead(StreamedHttp2ResponsePart.headers(streamId, headers, endOfStream));
                } else {
                    super.onHeadersRead(ctx, streamId, headers, streamDependency, weight, exclusive, padding, endOfStream);
                }
            }

            @Override
            public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) throws Http2Exception {
                Http2Stream stream = connection.stream(streamId);
                if (!isStreamed(stream)) {
                    return super.onDataRead(ctx, streamId, data, padding, endOfStream);
                }
                int bytes = data.readableBytes();
                ctx.fireChannelRead(StreamedHttp2ResponsePart.data(streamId, data.retainedSlice(), endOfStream, () -> written(ctx, stream, bytes)));
                // the data's bytes are returned to the stream's window when they have been written to the client
                return padding;
            }
        };
    }

    /**
     * Raises the loopback's connection receive window to the largest HTTP/2 allows: at its default, one stream's
     * window, the bytes held for one slow stream would stop every other. Sent with the first request.
     */
    void widenConnectionWindow(ChannelHandlerContext loopbackCtx) {
        Http2Stream connectionStream = connection.connectionStream();
        Http2LocalFlowController flowController = connection.local().flowController();
        int increment = Http2CodecUtil.MAX_INITIAL_WINDOW_SIZE - flowController.initialWindowSize(connectionStream);
        try {
            flowController.incrementWindowSize(connectionStream, increment);
        } catch (Http2Exception failure) {
            ((Http2ConnectionHandler) loopbackCtx.handler()).onError(loopbackCtx, false, failure);
        }
    }

    /**
     * Whether a header block is handed on as it is read: a streamed response's headers and trailers. A response is
     * found to be streamed at its first header block (the decoder refuses a second that does not end the stream).
     */
    private boolean relayedAsRead(int streamId, Http2Headers headers, boolean endOfStream) {
        Http2Stream stream = connection.stream(streamId);
        if (stream == null) {
            return false;
        }
        if (isStreamed(stream)) {
            return true;
        }
        // a whole response, an aggregated one's trailers, or a 1xx: MockServer ends no stream with a 1xx it mocks
        if (endOfStream || HttpStatusClass.valueOf(headers.status()) == HttpStatusClass.INFORMATIONAL) {
            return false;
        }
        // a response with a content coding is decoded, so it is held whole: nothing else bounds what it decodes to
        boolean streamed = !headers.contains(HttpHeaderNames.CONTENT_LENGTH) && !headers.contains(HttpHeaderNames.CONTENT_ENCODING);
        if (streamed) {
            stream.setProperty(streamedKey, Boolean.TRUE);
        }
        return streamed;
    }

    private boolean isStreamed(Http2Stream stream) {
        return stream != null && stream.getProperty(streamedKey) != null;
    }

    private void written(ChannelHandlerContext ctx, Http2Stream stream, int bytes) {
        try {
            // nothing is returned for a stream that has closed: its bytes went back to the connection window then
            if (connection.local().flowController().consumeBytes(stream, bytes)) {
                ctx.flush();
            }
        } catch (Http2Exception failure) {
            ((Http2ConnectionHandler) ctx.handler()).onError(ctx, false, failure);
        }
    }
}
