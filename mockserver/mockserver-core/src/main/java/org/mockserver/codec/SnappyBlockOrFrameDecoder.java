package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.compression.DecompressionException;
import io.netty.handler.codec.compression.Snappy;
import io.netty.handler.codec.compression.SnappyFrameDecoder;

import java.util.List;

/**
 * Decodes a {@code Content-Encoding: snappy} body in either Snappy format. A body that starts with the framing
 * format's stream identifier is handed to Netty's {@link SnappyFrameDecoder}, which decodes it chunk by chunk. Any
 * other body is the raw block format that Prometheus remote-write and remote-read, and other HTTP senders, use: it is
 * decoded in one piece once the body ends, after its declared size has been checked against {@code maxDecodedSize},
 * so nothing larger than that is ever allocated.
 */
public class SnappyBlockOrFrameDecoder extends ByteToMessageDecoder {

    private static final byte[] STREAM_IDENTIFIER = {(byte) 0xff, 0x06, 0x00, 0x00, 0x73, 0x4e, 0x61, 0x50, 0x70, 0x59};
    private static final int MAX_PREAMBLE_BYTES = 4;
    private static final long MAX_EXPANSION = 22;

    private final int maxDecodedSize;
    private boolean block;
    private boolean failed;

    /**
     * @param maxDecodedSize the largest decoded block accepted, normally {@code maxRequestBodySize}; zero accepts only
     *                       an empty block, as an aggregator with a limit of zero accepts only an empty body
     */
    public SnappyBlockOrFrameDecoder(int maxDecodedSize) {
        this.maxDecodedSize = maxDecodedSize;
    }

    /**
     * Whether {@code body} is in the Snappy framing format rather than the raw block format.
     */
    public static boolean isFramed(byte[] body) {
        if (body == null || body.length < STREAM_IDENTIFIER.length) {
            return false;
        }
        for (int i = 0; i < STREAM_IDENTIFIER.length; i++) {
            if (body[i] != STREAM_IDENTIFIER[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (failed) {
            in.skipBytes(in.readableBytes());
            return;
        }
        try {
            inspect(ctx, in);
        } catch (RuntimeException e) {
            fail(in);
            throw e;
        }
    }

    @Override
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (failed || !in.isReadable()) {
            in.skipBytes(in.readableBytes());
            return;
        }
        try {
            inspect(ctx, in);
            if (!ctx.isRemoved()) {
                decodeBlock(ctx, in, out);
            }
        } catch (RuntimeException e) {
            fail(in);
            throw e;
        }
    }

    /**
     * Hands a framed body to {@link SnappyFrameDecoder}, or checks a block's size as far as it has arrived.
     */
    private void inspect(ChannelHandlerContext ctx, ByteBuf in) {
        if (!block) {
            int compared = Math.min(in.readableBytes(), STREAM_IDENTIFIER.length);
            for (int i = 0; i < compared; i++) {
                if (in.getByte(in.readerIndex() + i) != STREAM_IDENTIFIER[i]) {
                    block = true;
                    break;
                }
            }
            if (!block) {
                if (compared == STREAM_IDENTIFIER.length) {
                    // the bytes buffered so far are passed on to the frame decoder as this handler is removed
                    ctx.pipeline().replace(this, null, new SnappyFrameDecoder());
                }
                return;
            }
        }
        int declared = declaredSize(in);
        // Snappy expands at most to 32 + n + n / 6 bytes, so more input than that for the declared size is corrupt
        if (declared >= 0 && in.readableBytes() > 32L + declared + declared / 6) {
            throw new DecompressionException("snappy block is longer than its declared decoded size of " + declared + " bytes allows");
        }
    }

    private void decodeBlock(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int declared = declaredSize(in);
        if (declared < 0) {
            throw new DecompressionException("snappy block ends inside its size preamble");
        }
        // Snappy writes at most 64 bytes per 3-byte element, so a short body cannot hold a large declared size:
        // checked before Snappy.decode, which reserves the whole declared size as soon as it reads the preamble
        if ((long) declared > MAX_EXPANSION * in.readableBytes()) {
            throw new DecompressionException("snappy block declares " + declared + " decoded bytes, more than its " + in.readableBytes() + " bytes can hold");
        }
        ByteBuf decoded = ctx.alloc().buffer(declared, declared);
        try {
            new Snappy().decode(in, decoded);
            if (in.isReadable() || decoded.readableBytes() != declared) {
                throw new DecompressionException("snappy block decoded to " + decoded.readableBytes() + " bytes, not its declared " + declared + " bytes");
            }
        } catch (IndexOutOfBoundsException e) {
            decoded.release();
            throw new DecompressionException("snappy block decodes past its declared size of " + declared + " bytes", e);
        } catch (RuntimeException e) {
            decoded.release();
            throw e;
        }
        if (decoded.isReadable()) {
            out.add(decoded);
        } else {
            decoded.release();
        }
    }

    // a failed body is discarded rather than failing again when the decoder is closed
    private void fail(ByteBuf in) {
        failed = true;
        in.skipBytes(in.readableBytes());
    }

    /**
     * The decoded size the block's varint preamble declares, without consuming it, or -1 when the preamble is not yet
     * complete. Rejects a size over {@code maxDecodedSize} before anything is decoded.
     */
    private int declaredSize(ByteBuf in) {
        long size = 0;
        int index = in.readerIndex();
        for (int i = 0; i < MAX_PREAMBLE_BYTES && index + i < in.writerIndex(); i++) {
            int current = in.getUnsignedByte(index + i);
            size |= (long) (current & 0x7f) << (7 * i);
            if ((current & 0x80) == 0) {
                if (size > maxDecodedSize) {
                    throw new DecompressionException("snappy block declares " + size + " decoded bytes, more than the limit of " + maxDecodedSize);
                }
                return (int) size;
            }
        }
        if (in.readableBytes() >= MAX_PREAMBLE_BYTES) {
            throw new DecompressionException("snappy block size preamble is longer than " + MAX_PREAMBLE_BYTES + " bytes");
        }
        return -1;
    }
}
