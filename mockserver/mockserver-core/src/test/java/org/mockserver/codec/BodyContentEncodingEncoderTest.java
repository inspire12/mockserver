package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.SnappyFrameDecoder;
import io.netty.handler.codec.compression.Zstd;
import org.junit.Test;
import org.xerial.snappy.Snappy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;

public class BodyContentEncodingEncoderTest {

    @Test
    public void shouldGzipCompressBody() throws IOException {
        byte[] input = "test-data".getBytes(UTF_8);

        byte[] compressed = BodyContentEncodingEncoder.encodeBody(input, "gzip");

        assertThat(compressed, is(not(input)));
        assertThat(decompress(compressed, "gzip"), is(input));
    }

    @Test
    public void shouldDeflateCompressBody() throws IOException {
        byte[] input = "test-data".getBytes(UTF_8);

        byte[] compressed = BodyContentEncodingEncoder.encodeBody(input, "deflate");

        assertThat(compressed, is(not(input)));
        assertThat(decompress(compressed, "deflate"), is(input));
    }

    @Test
    public void shouldReturnBodyUnchangedForAnEncodingThatIsNotDecoded() {
        byte[] input = "test-data".getBytes(UTF_8);

        assertThat(BodyContentEncodingEncoder.encodeBody(input, "compress"), is(input));
        assertThat(BodyContentEncodingEncoder.encodeBody(input, "identity"), is(input));
        assertThat(BodyContentEncodingEncoder.encodeBody(input, "gzipped"), is(input));
        if (!Brotli.isAvailable()) {
            assertThat(BodyContentEncodingEncoder.encodeBody(input, "br"), is(input));
        }
        if (!Zstd.isAvailable()) {
            assertThat(BodyContentEncodingEncoder.encodeBody(input, "zstd"), is(input));
        }
    }

    @Test
    public void shouldReturnBodyUnchangedForACodingList() {
        byte[] input = "test-data".getBytes(UTF_8);

        // MockServer never decodes a coding list, so a body sent with one is still in it
        assertThat(BodyContentEncodingEncoder.encodeBody(input, "gzip, br"), is(input));
        assertThat(BodyContentEncodingEncoder.encodeBody(input, "gzip, deflate"), is(input));
        assertThat(BodyContentEncodingEncoder.encodeBody(input, "deflate,gzip"), is(input));
        assertThat(BodyContentEncodingEncoder.encodeBody(input, "gzip,"), is(input));
    }

    @Test
    public void shouldEncodeXGzipAndXDeflate() throws IOException {
        byte[] input = "test-data".getBytes(UTF_8);

        assertThat(decompress(BodyContentEncodingEncoder.encodeBody(input, "x-gzip"), "gzip"), is(input));
        assertThat(decompress(BodyContentEncodingEncoder.encodeBody(input, " X-Deflate "), "deflate"), is(input));
    }

    @Test
    public void shouldSnappyCompressARawBlockByDefault() throws IOException {
        byte[] input = repeated(100_000);

        byte[] compressed = BodyContentEncodingEncoder.encodeBody(input, "snappy");

        assertThat(SnappyBlockOrFrameDecoder.isFramed(compressed), is(false));
        assertThat(Snappy.uncompress(compressed), is(input));
    }

    @Test
    public void shouldSnappyCompressTheFramingFormatWhenAsked() {
        byte[] input = repeated(100_000);

        byte[] compressed = BodyContentEncodingEncoder.encodeBody(input, "SNAPPY", true);

        assertThat(SnappyBlockOrFrameDecoder.isFramed(compressed), is(true));
        EmbeddedChannel channel = new EmbeddedChannel(new SnappyFrameDecoder());
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(compressed));
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            ByteBuf piece;
            while ((piece = channel.readInbound()) != null) {
                byte[] bytes = new byte[piece.readableBytes()];
                piece.readBytes(bytes);
                decoded.write(bytes, 0, bytes.length);
                piece.release();
            }
            assertThat(decoded.toByteArray(), is(input));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static byte[] repeated(int length) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; text.length() < length; i++) {
            text.append("sample ").append(i).append(' ');
        }
        return text.substring(0, length).getBytes(UTF_8);
    }

    @Test
    public void shouldReturnNullBodyUnchanged() {
        assertThat(BodyContentEncodingEncoder.encodeBody(null, "gzip"), is((byte[]) null));
    }

    @Test
    public void shouldReturnEmptyBodyUnchanged() {
        byte[] result = BodyContentEncodingEncoder.encodeBody(new byte[0], "gzip");

        assertThat(result, is(new byte[0]));
    }

    @Test
    public void shouldReturnBodyUnchangedWhenNoContentEncoding() {
        byte[] input = "test-data".getBytes(UTF_8);

        assertThat(BodyContentEncodingEncoder.encodeBody(input, null), is(input));
        assertThat(BodyContentEncodingEncoder.encodeBody(input, ""), is(input));
        assertThat(BodyContentEncodingEncoder.encodeBody(input, "  "), is(input));
    }

    @Test
    public void shouldHandleCaseInsensitiveGzip() throws IOException {
        byte[] input = "test-data".getBytes(UTF_8);

        assertThat(decompress(BodyContentEncodingEncoder.encodeBody(input, "GZIP"), "gzip"), is(input));
        assertThat(decompress(BodyContentEncodingEncoder.encodeBody(input, "Gzip"), "gzip"), is(input));
    }

    private byte[] decompress(byte[] data, String encoding) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if ("gzip".equalsIgnoreCase(encoding)) {
            try (GZIPInputStream gis = new GZIPInputStream(bais)) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = gis.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
            }
        } else if ("deflate".equalsIgnoreCase(encoding)) {
            try (InflaterInputStream iis = new InflaterInputStream(bais)) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = iis.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
            }
        }
        return baos.toByteArray();
    }

}
