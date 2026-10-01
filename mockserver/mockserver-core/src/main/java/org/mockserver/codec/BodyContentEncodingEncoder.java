package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.BrotliEncoder;
import io.netty.handler.codec.compression.SnappyFrameEncoder;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.compression.ZstdEncoder;
import org.mockserver.metrics.remotewrite.SnappyBlock;
import org.mockserver.socket.NettyAllocator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Encodes a request body in its {@code Content-Encoding} before it is forwarded, for a body MockServer decoded on the
 * way in and then changed, or one supplied decoded by a client, override or template.
 * <p>
 * Only a {@code Content-Encoding} that {@link MockServerHttpContentDecompressor} decodes is encoded: {@code gzip},
 * {@code x-gzip}, {@code deflate}, {@code x-deflate} or {@code snappy}, or {@code zstd} or {@code br} when their native
 * library is available. Callers pass the first {@code Content-Encoding} value, which is the one the decompressor reads,
 * and it is compared whole, as the decompressor compares it, so a coding list in one value such as {@code gzip, br} is
 * never encoded: MockServer never decodes one, so a body sent with it is still in it.
 */
public class BodyContentEncodingEncoder {

    /**
     * As {@link #encodeBody(byte[], String, boolean)}, writing {@code snappy} in the raw block format.
     */
    public static byte[] encodeBody(byte[] body, String contentEncoding) {
        return encodeBody(body, contentEncoding, false);
    }

    /**
     * @param snappyFramed whether {@code snappy} is written in the framing format rather than the raw block format
     *                     that HTTP senders such as Prometheus remote-write use
     * @return the encoded body, or {@code body} itself when the {@code Content-Encoding} is not one that is encoded
     */
    public static byte[] encodeBody(byte[] body, String contentEncoding, boolean snappyFramed) {
        if (body == null || body.length == 0 || contentEncoding == null) {
            return body;
        }
        switch (contentEncoding.trim().toLowerCase(Locale.ROOT)) {
            case "gzip":
            case "x-gzip":
                return gzipCompress(body);
            case "deflate":
            case "x-deflate":
                return deflateCompress(body);
            case "snappy":
                return snappyFramed ? encodeWith(new SnappyFrameEncoder(), body) : SnappyBlock.compress(body);
            case "zstd":
                return Zstd.isAvailable() ? encodeWith(new ZstdEncoder(), body) : body;
            case "br":
                return Brotli.isAvailable() ? encodeWith(new BrotliEncoder(), body) : body;
            default:
                return body;
        }
    }

    private static byte[] gzipCompress(byte[] data) {
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(data.length);
             GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {
            gzipOutputStream.write(data);
            gzipOutputStream.finish();
            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to gzip compress body", e);
        }
    }

    private static byte[] deflateCompress(byte[] data) {
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(data.length);
             DeflaterOutputStream deflaterOutputStream = new DeflaterOutputStream(byteArrayOutputStream)) {
            deflaterOutputStream.write(data);
            deflaterOutputStream.finish();
            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to deflate compress body", e);
        }
    }

    private static byte[] encodeWith(ChannelHandler encoder, byte[] data) {
        // pinned before the encoder is added, since an encoder may allocate as it is added (ZstdEncoder does)
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyAllocator.pin(channel);
        channel.pipeline().addLast(encoder);
        try {
            channel.writeOutbound(Unpooled.wrappedBuffer(data));
            channel.finish();
            ByteArrayOutputStream encoded = new ByteArrayOutputStream(data.length);
            ByteBuf piece;
            while ((piece = channel.readOutbound()) != null) {
                try {
                    byte[] bytes = new byte[piece.readableBytes()];
                    piece.readBytes(bytes);
                    encoded.write(bytes, 0, bytes.length);
                } finally {
                    piece.release();
                }
            }
            return encoded.toByteArray();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

}
