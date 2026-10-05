package org.mockserver.netty.proxy.relay;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Stream;

/**
 * Writes each {@link StreamedHttp2ResponsePart} to the proxy client's HTTP/2 connection through its encoder, on the
 * stream the part names: {@code HttpToHttp2ConnectionHandler} writes content to the stream it wrote headers to last.
 * A part its stream can no longer take is released and failed as that stream's alone; the encoder would report it as
 * a connection error. Sits anywhere after the connection's {@link Http2ConnectionHandler}.
 */
final class StreamedHttp2ResponseWriter extends ChannelOutboundHandlerAdapter {

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!(msg instanceof StreamedHttp2ResponsePart)) {
            ctx.write(msg, promise);
            return;
        }
        StreamedHttp2ResponsePart part = (StreamedHttp2ResponsePart) msg;
        ChannelHandlerContext codecCtx = ctx.pipeline().context(Http2ConnectionHandler.class);
        Http2ConnectionHandler codec = (Http2ConnectionHandler) codecCtx.handler();
        Http2Stream stream = codec.connection().stream(part.streamId());
        if (!takes(stream, part)) {
            part.release();
            promise.tryFailure(Http2Exception.streamError(part.streamId(), Http2Error.STREAM_CLOSED, "stream %d closed before the response streamed to it was written", part.streamId()));
            return;
        }
        // however the write ends: the loopback stream must not wait on bytes that will never be written
        promise.addListener(future -> part.written());
        Http2ConnectionEncoder encoder = codec.encoder();
        if (part.headers() != null) {
            encoder.writeHeaders(codecCtx, part.streamId(), part.headers(), 0, part.endOfStream(), promise);
        } else {
            encoder.writeData(codecCtx, part.streamId(), part.content(), 0, part.endOfStream(), promise);
        }
    }

    // the encoder reports a frame for a stream that has gone or ended, or a second response's headers, as a connection error
    private static boolean takes(Http2Stream stream, StreamedHttp2ResponsePart part) {
        if (stream == null || stream.isResetSent() || !stream.state().localSideOpen()) {
            return false;
        }
        // after the response's headers, a header block is its trailers
        return part.headers() == null || !stream.isHeadersSent() || part.endOfStream();
    }
}
