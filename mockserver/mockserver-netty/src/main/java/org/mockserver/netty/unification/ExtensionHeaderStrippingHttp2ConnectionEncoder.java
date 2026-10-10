package org.mockserver.netty.unification;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.DecoratingHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.util.AsciiString;

/**
 * Leaves Netty's {@code x-http2-} extension headers ({@link HttpConversionUtil.ExtensionHeaderNames}) out of every
 * header block an {@code HttpToHttp2ConnectionHandler} sends. That handler takes the stream's priority from them, but
 * its conversion drops only the stream id, scheme, path and protocol, so a message read through
 * {@code InboundHttp2ToHttpAdapter}, which sets the stream weight on every message, would be relayed with
 * {@code x-http2-stream-weight} as a header field. The priority itself is still sent, as the frame's priority fields.
 */
public final class ExtensionHeaderStrippingHttp2ConnectionEncoder extends DecoratingHttp2ConnectionEncoder {

    private static final AsciiString[] EXTENSION_HEADER_NAMES = extensionHeaderNames();

    public ExtensionHeaderStrippingHttp2ConnectionEncoder(Http2ConnectionEncoder delegate) {
        super(delegate);
    }

    @Override
    public ChannelFuture writeHeaders(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding, boolean endStream, ChannelPromise promise) {
        return super.writeHeaders(ctx, streamId, withoutExtensionHeaders(headers), padding, endStream, promise);
    }

    @Override
    public ChannelFuture writeHeaders(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endStream, ChannelPromise promise) {
        return super.writeHeaders(ctx, streamId, withoutExtensionHeaders(headers), streamDependency, weight, exclusive, padding, endStream, promise);
    }

    static Http2Headers withoutExtensionHeaders(Http2Headers headers) {
        if (!headers.isEmpty()) {
            for (AsciiString name : EXTENSION_HEADER_NAMES) {
                // looked up first, so a read-only block without them (a header block relayed as it was read) is untouched
                if (headers.contains(name)) {
                    headers.remove(name);
                }
            }
        }
        return headers;
    }

    private static AsciiString[] extensionHeaderNames() {
        HttpConversionUtil.ExtensionHeaderNames[] values = HttpConversionUtil.ExtensionHeaderNames.values();
        AsciiString[] names = new AsciiString[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].text();
        }
        return names;
    }
}
