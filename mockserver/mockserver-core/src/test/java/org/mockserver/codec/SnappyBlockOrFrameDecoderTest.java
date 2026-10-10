package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.DecompressionException;
import io.netty.handler.codec.compression.SnappyFrameEncoder;
import org.junit.Test;
import org.mockserver.metrics.remotewrite.SnappyBlock;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;

public class SnappyBlockOrFrameDecoderTest {

    private static final byte[] PLAIN = plain(200_000);

    @Test
    public void shouldDecodeARawBlockFedInPieces() {
        byte[] block = SnappyBlock.compress(PLAIN);

        assertThat(decode(block, 1000, Integer.MAX_VALUE), is(PLAIN));
    }

    @Test
    public void shouldDecodeAFramedStreamWhoseIdentifierArrivesOneByteAtATime() {
        byte[] framed = framed(PLAIN);

        assertThat(SnappyBlockOrFrameDecoder.isFramed(framed), is(true));
        assertThat(decode(framed, 1, Integer.MAX_VALUE), is(PLAIN));
    }

    @Test
    public void shouldNotTreatARawBlockAsFramed() {
        assertThat(SnappyBlockOrFrameDecoder.isFramed(SnappyBlock.compress(PLAIN)), is(false));
        assertThat(SnappyBlockOrFrameDecoder.isFramed(null), is(false));
        assertThat(SnappyBlockOrFrameDecoder.isFramed(new byte[]{(byte) 0xff, 0x06}), is(false));
    }

    @Test
    public void shouldDecodeAnEmptyBlock() {
        assertThat(decode(new byte[]{0x00}, 1, Integer.MAX_VALUE), is(new byte[0]));
        assertThat(decode(new byte[0], 1, Integer.MAX_VALUE), is(new byte[0]));
    }

    @Test
    public void shouldRejectABlockDeclaringMoreThanTheLimitBeforeTheBodyEnds() {
        byte[] block = SnappyBlock.compress(PLAIN);
        EmbeddedChannel channel = new EmbeddedChannel(new SnappyBlockOrFrameDecoder(PLAIN.length - 1));
        try {
            // only the first piece is written: the size preamble alone is enough to reject it
            DecompressionException rejected = assertThrows(DecompressionException.class, () -> channel.writeInbound(Unpooled.wrappedBuffer(block, 0, 16)));
            assertThat(rejected.getMessage(), containsString("more than the limit of " + (PLAIN.length - 1)));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldRejectAShortBlockDeclaringMoreThanItCanHoldBeforeAllocating() {
        // declares 1,000,000 decoded bytes (varint c0 84 3d) in a 6-byte body, under the limit
        byte[] block = {(byte) 0xc0, (byte) 0x84, 0x3d, 0x00, 'x', 0x00};

        DecompressionException rejected = assertThrows(DecompressionException.class, () -> decode(block, 100, Integer.MAX_VALUE));

        assertThat(rejected.getMessage(), containsString("declares 1000000 decoded bytes, more than its 6 bytes can hold"));
    }

    @Test
    public void shouldDecodeABlockOfExactlyTheLimit() {
        assertThat(decode(SnappyBlock.compress(PLAIN), 4096, PLAIN.length), is(PLAIN));
    }

    @Test
    public void shouldDecodeOnlyAnEmptyBlockAtALimitOfZero() {
        assertThat(decode(new byte[]{0x00}, 1, 0), is(new byte[0]));

        DecompressionException rejected = assertThrows(DecompressionException.class, () -> decode(SnappyBlock.compress(new byte[]{'x'}), 1, 0));

        assertThat(rejected.getMessage(), containsString("declares 1 decoded bytes, more than the limit of 0"));
        assertThrows(DecompressionException.class, () -> decode(new byte[]{0x00}, 1, -1));
    }

    @Test
    public void shouldRejectATruncatedBlock() {
        byte[] block = SnappyBlock.compress(PLAIN);

        DecompressionException rejected = assertThrows(DecompressionException.class, () -> decode(Arrays.copyOf(block, block.length / 2), 4096, Integer.MAX_VALUE));

        assertThat(rejected.getMessage(), containsString("not its declared " + PLAIN.length + " bytes"));
    }

    @Test
    public void shouldRejectABlockWithTrailingBytes() {
        byte[] block = SnappyBlock.compress("hello".getBytes(StandardCharsets.UTF_8));
        byte[] trailing = Arrays.copyOf(block, block.length + 3);

        assertThrows(DecompressionException.class, () -> decode(trailing, 4096, Integer.MAX_VALUE));
    }

    @Test
    public void shouldRejectABlockLongerThanItsDeclaredSizeAllows() {
        // declares 1 decoded byte, then far more input than Snappy could ever need for it
        byte[] block = new byte[100];
        block[0] = 0x01;

        DecompressionException rejected = assertThrows(DecompressionException.class, () -> decode(block, 100, Integer.MAX_VALUE));

        assertThat(rejected.getMessage(), containsString("longer than its declared decoded size"));
    }

    @Test
    public void shouldRejectASizePreambleLongerThanFourBytes() {
        byte[] block = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01, 0x00};

        DecompressionException rejected = assertThrows(DecompressionException.class, () -> decode(block, 100, Integer.MAX_VALUE));

        assertThat(rejected.getMessage(), containsString("preamble is longer than 4 bytes"));
    }

    @Test
    public void shouldRejectABlockThatEndsInsideItsPreamble() {
        assertThrows(DecompressionException.class, () -> decode(new byte[]{(byte) 0x80}, 100, Integer.MAX_VALUE));
    }

    @Test
    public void shouldRejectABlockThatDecodesPastItsDeclaredSize() {
        // declares 2 bytes, then a 5-byte literal
        byte[] block = {0x02, 0x10, 'h', 'e', 'l', 'l', 'o'};

        DecompressionException rejected = assertThrows(DecompressionException.class, () -> decode(block, 100, Integer.MAX_VALUE));

        assertThat(rejected.getMessage(), containsString("past its declared size of 2 bytes"));
    }

    private static byte[] decode(byte[] body, int pieceSize, int maxDecodedSize) {
        EmbeddedChannel channel = new EmbeddedChannel(new SnappyBlockOrFrameDecoder(maxDecodedSize));
        try {
            for (int offset = 0; offset < body.length; offset += pieceSize) {
                channel.writeInbound(Unpooled.copiedBuffer(body, offset, Math.min(pieceSize, body.length - offset)));
            }
            channel.finish();
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            ByteBuf piece;
            while ((piece = channel.readInbound()) != null) {
                try {
                    byte[] bytes = new byte[piece.readableBytes()];
                    piece.readBytes(bytes);
                    decoded.write(bytes, 0, bytes.length);
                } finally {
                    piece.release();
                }
            }
            return decoded.toByteArray();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static byte[] framed(byte[] plain) {
        EmbeddedChannel channel = new EmbeddedChannel(new SnappyFrameEncoder());
        try {
            channel.writeOutbound(Unpooled.wrappedBuffer(plain));
            ByteArrayOutputStream framed = new ByteArrayOutputStream();
            ByteBuf piece;
            while ((piece = channel.readOutbound()) != null) {
                byte[] bytes = new byte[piece.readableBytes()];
                piece.readBytes(bytes);
                framed.write(bytes, 0, bytes.length);
                piece.release();
            }
            return framed.toByteArray();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static byte[] plain(int length) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; text.length() < length; i++) {
            text.append("{\"series\":").append(i).append(",\"name\":\"http_requests_total\"}");
        }
        return text.substring(0, length).getBytes(StandardCharsets.UTF_8);
    }
}
