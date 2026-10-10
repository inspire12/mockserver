package org.mockserver.codec;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.compression.ZstdDecoder;
import io.netty.handler.codec.http.HttpContentDecompressor;

import static io.netty.handler.codec.http.HttpHeaderValues.BR;
import static io.netty.handler.codec.http.HttpHeaderValues.DEFLATE;
import static io.netty.handler.codec.http.HttpHeaderValues.GZIP;
import static io.netty.handler.codec.http.HttpHeaderValues.SNAPPY;
import static io.netty.handler.codec.http.HttpHeaderValues.X_DEFLATE;
import static io.netty.handler.codec.http.HttpHeaderValues.X_GZIP;
import static io.netty.handler.codec.http.HttpHeaderValues.ZSTD;

/**
 * Netty's {@link HttpContentDecompressor}, except that a {@code zstd} decoder allocates at most
 * {@link #ZSTD_MAX_ALLOCATION} bytes at a time. Netty's {@code ZstdDecoder(0)} allocates whatever content size a
 * frame header declares in one buffer, so a few bytes could demand gigabytes. Each buffer is passed on as soon as it
 * is full; where the response is aggregated, the aggregator after this handler bounds the total. Every other encoding
 * is decoded exactly as Netty's.
 * <p>
 * Used as is for upstream responses; {@link MockServerHttpContentDecompressor} adds raw-block Snappy for requests.
 */
public class BoundedZstdHttpContentDecompressor extends HttpContentDecompressor {

    /**
     * The largest buffer a {@code zstd} decoder allocates at once; each one is passed on as soon as it is full.
     */
    public static final int ZSTD_MAX_ALLOCATION = 64 * 1024;

    public BoundedZstdHttpContentDecompressor() {
        super(0);
    }

    @Override
    protected EmbeddedChannel newContentDecoder(String contentEncoding) throws Exception {
        EmbeddedChannel zstd = boundedZstdDecoder(ctx.channel(), contentEncoding);
        return zstd != null ? zstd : super.newContentDecoder(contentEncoding);
    }

    /**
     * Whether a body in {@code contentEncoding}, one {@code Content-Encoding} value, is decoded by this decompressor and
     * by {@link BoundedZstdDecompressorFrameListener}: compared whole, so a list of codings such as {@code gzip, br} is
     * not, and {@code br} or {@code zstd} only when its native library is on the classpath.
     */
    public static boolean decodes(CharSequence contentEncoding) {
        if (contentEncoding == null) {
            return false;
        }
        String coding = contentEncoding.toString().trim();
        return GZIP.contentEqualsIgnoreCase(coding) || X_GZIP.contentEqualsIgnoreCase(coding)
            || DEFLATE.contentEqualsIgnoreCase(coding) || X_DEFLATE.contentEqualsIgnoreCase(coding)
            || SNAPPY.contentEqualsIgnoreCase(coding)
            || (BR.contentEqualsIgnoreCase(coding) && Brotli.isAvailable())
            || (ZSTD.contentEqualsIgnoreCase(coding) && Zstd.isAvailable());
    }

    /**
     * A decoder channel with a {@code zstd} decoder bounded to {@link #ZSTD_MAX_ALLOCATION}, or null when
     * {@code contentEncoding} is not {@code zstd} or zstd-jni is not on the classpath.
     */
    static EmbeddedChannel boundedZstdDecoder(Channel channel, CharSequence contentEncoding) {
        if (ZSTD.contentEqualsIgnoreCase(contentEncoding) && Zstd.isAvailable()) {
            return decoderChannel(channel, new ZstdDecoder(ZSTD_MAX_ALLOCATION));
        }
        return null;
    }

    /**
     * A decoder channel built as Netty builds its own, so decoded buffers come from {@code channel}'s allocator.
     */
    static EmbeddedChannel decoderChannel(Channel channel, ChannelHandler decoder) {
        return EmbeddedChannel.builder()
            .channelId(channel.id())
            .hasDisconnect(channel.metadata().hasDisconnect())
            .config(channel.config())
            .handlers(decoder)
            .build();
    }
}
