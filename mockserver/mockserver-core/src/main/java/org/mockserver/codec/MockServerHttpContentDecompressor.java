package org.mockserver.codec;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContentDecompressor;

import static io.netty.handler.codec.http.HttpHeaderValues.SNAPPY;

/**
 * Netty's {@link HttpContentDecompressor}, except that a {@code snappy} body may be in either Snappy format (see
 * {@link SnappyBlockOrFrameDecoder}): Netty's own decoder accepts only the framing format, so it rejects the raw block
 * format that Prometheus remote-write sends. Installed on every inbound protocol (HTTP/1.1, HTTP/2 and HTTP/3) so they
 * decode the same encodings in the same way.
 */
public class MockServerHttpContentDecompressor extends HttpContentDecompressor {

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
            Channel channel = ctx.channel();
            return EmbeddedChannel.builder()
                .channelId(channel.id())
                .hasDisconnect(channel.metadata().hasDisconnect())
                .config(channel.config())
                .handlers(new SnappyBlockOrFrameDecoder(maxDecodedSnappyBlockSize))
                .build();
        }
        return super.newContentDecoder(contentEncoding);
    }
}
