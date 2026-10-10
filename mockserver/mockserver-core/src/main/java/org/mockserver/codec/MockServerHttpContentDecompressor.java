package org.mockserver.codec;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContentDecompressor;

import static io.netty.handler.codec.http.HttpHeaderValues.SNAPPY;

/**
 * Netty's {@link HttpContentDecompressor}, except that:
 * <ul>
 * <li>a {@code snappy} body may be in either Snappy format (see {@link SnappyBlockOrFrameDecoder}): Netty's own decoder
 * accepts only the framing format, so it rejects the raw block format that Prometheus remote-write sends;</li>
 * <li>a {@code zstd} decoder allocates at most {@link #ZSTD_MAX_ALLOCATION} bytes at a time (see
 * {@link BoundedZstdHttpContentDecompressor}). The aggregator after this handler bounds the total.</li>
 * </ul>
 * Installed on every inbound protocol (HTTP/1.1, HTTP/2 and HTTP/3) so they decode the same encodings in the same way.
 */
public class MockServerHttpContentDecompressor extends BoundedZstdHttpContentDecompressor {

    private final int maxDecodedSnappyBlockSize;

    /**
     * @param maxDecodedSnappyBlockSize the largest raw Snappy block accepted, normally {@code maxRequestBodySize}
     */
    public MockServerHttpContentDecompressor(int maxDecodedSnappyBlockSize) {
        this.maxDecodedSnappyBlockSize = maxDecodedSnappyBlockSize;
    }

    @Override
    protected EmbeddedChannel newContentDecoder(String contentEncoding) throws Exception {
        if (SNAPPY.contentEqualsIgnoreCase(contentEncoding)) {
            return decoderChannel(ctx.channel(), new SnappyBlockOrFrameDecoder(maxDecodedSnappyBlockSize));
        }
        return super.newContentDecoder(contentEncoding);
    }
}
