package org.mockserver.codec;

import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.compression.ZstdDecoder;
import io.netty.handler.codec.http.HttpContentDecompressor;

import static io.netty.handler.codec.http.HttpHeaderValues.SNAPPY;
import static io.netty.handler.codec.http.HttpHeaderValues.ZSTD;

/**
 * Netty's {@link HttpContentDecompressor}, except that:
 * <ul>
 * <li>a {@code snappy} body may be in either Snappy format (see {@link SnappyBlockOrFrameDecoder}): Netty's own decoder
 * accepts only the framing format, so it rejects the raw block format that Prometheus remote-write sends;</li>
 * <li>a {@code zstd} decoder allocates at most {@link #ZSTD_MAX_ALLOCATION} bytes at a time. Netty's
 * {@code ZstdDecoder(0)} allocates whatever content size a frame header declares in one buffer, so a few bytes could
 * demand gigabytes. The aggregator after this handler bounds the total.</li>
 * </ul>
 * Installed on every inbound protocol (HTTP/1.1, HTTP/2 and HTTP/3) so they decode the same encodings in the same way.
 */
public class MockServerHttpContentDecompressor extends HttpContentDecompressor {

    /**
     * The largest buffer a {@code zstd} decoder allocates at once; each one is passed on as soon as it is full.
     */
    public static final int ZSTD_MAX_ALLOCATION = 64 * 1024;

    private final int maxDecodedSnappyBlockSize;

    /**
     * @param maxDecodedSnappyBlockSize the largest raw Snappy block accepted, normally {@code maxRequestBodySize}
     */
    public MockServerHttpContentDecompressor(int maxDecodedSnappyBlockSize) {
        super(0);
        this.maxDecodedSnappyBlockSize = maxDecodedSnappyBlockSize;
    }

    @Override
    protected EmbeddedChannel newContentDecoder(String contentEncoding) throws Exception {
        if (SNAPPY.contentEqualsIgnoreCase(contentEncoding)) {
            return decoderChannel(new SnappyBlockOrFrameDecoder(maxDecodedSnappyBlockSize));
        }
        if (ZSTD.contentEqualsIgnoreCase(contentEncoding) && Zstd.isAvailable()) {
            return decoderChannel(new ZstdDecoder(ZSTD_MAX_ALLOCATION));
        }
        return super.newContentDecoder(contentEncoding);
    }

    private EmbeddedChannel decoderChannel(ChannelHandler decoder) {
        return EmbeddedChannel.builder()
            .channelId(ctx.channel().id())
            .hasDisconnect(ctx.channel().metadata().hasDisconnect())
            .config(ctx.channel().config())
            .handlers(decoder)
            .build();
    }
}
