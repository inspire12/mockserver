package org.mockserver.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DelegatingDecompressorFrameListener;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameListener;

import static org.mockserver.codec.BoundedZstdHttpContentDecompressor.boundedZstdDecoder;

/**
 * Netty's HTTP/2 {@link DelegatingDecompressorFrameListener} (strict, no allocation limit, as its two-argument
 * constructor builds it), except that a {@code zstd} decoder allocates at most
 * {@link BoundedZstdHttpContentDecompressor#ZSTD_MAX_ALLOCATION} bytes at a time instead of the content size the frame
 * header declares. Each buffer goes to the delegate listener as it fills, whose content-length limit bounds the total.
 */
public class BoundedZstdDecompressorFrameListener extends DelegatingDecompressorFrameListener {

    public BoundedZstdDecompressorFrameListener(Http2Connection connection, Http2FrameListener listener) {
        super(connection, listener, true, 0);
    }

    @Override
    protected EmbeddedChannel newContentDecompressor(ChannelHandlerContext ctx, CharSequence contentEncoding) throws Http2Exception {
        EmbeddedChannel zstd = boundedZstdDecoder(ctx.channel(), contentEncoding);
        return zstd != null ? zstd : super.newContentDecompressor(ctx, contentEncoding);
    }
}
