package org.mockserver.netty.http3;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;

public class Http3RequestDecompressorTest {

    private static final int MAX_BODY = 1024 * 1024;
    // DEFLATE cannot expand its input by more than about 1,032 times
    private static final long MAX_EXPANSION_OF_ONE_SLICE = Http3RequestDecompressor.SLICE_BYTES * 1032L;

    @Test
    public void shouldStopDecompressingABombWithinOneSliceOfTheLimit() {
        byte[] bomb = gzip(new byte[64 * MAX_BODY]);
        Http3RequestDecompressor decompressor = decompressor("gzip");
        ByteBuf content = Unpooled.wrappedBuffer(bomb);
        try {
            assertThat("compressed body is under the limit", bomb.length < MAX_BODY, is(true));

            assertThat(decompressor.decompress(content), is(false));

            assertThat(decompressor.decompressedSize(), lessThanOrEqualTo(MAX_BODY + MAX_EXPANSION_OF_ONE_SLICE));
            assertThat("the decompressed body is released once over the limit", decompressor.body(), nullValue());
            assertThat(decompressor.finish(), is(false));
        } finally {
            decompressor.release();
            content.release();
        }
    }

    @Test
    public void shouldLeaveTheCompressedContentUntouched() {
        byte[] plain = "{\"name\":\"value\"}".getBytes(StandardCharsets.UTF_8);
        ByteBuf content = Unpooled.wrappedBuffer(gzip(plain));
        int readerIndex = content.readerIndex();
        int writerIndex = content.writerIndex();
        Http3RequestDecompressor decompressor = decompressor("gzip");
        try {
            assertThat(decompressor.decompress(content), is(true));
            assertThat(decompressor.finish(), is(true));

            assertThat(content.readerIndex(), is(readerIndex));
            assertThat(content.writerIndex(), is(writerIndex));
            assertThat(content.refCnt(), is(1));
            CompositeByteBuf body = decompressor.body();
            assertThat(body.toString(StandardCharsets.UTF_8), is("{\"name\":\"value\"}"));
            assertThat(decompressor.decompressedSize(), is((long) plain.length));
        } finally {
            CompositeByteBuf body = decompressor.body();
            decompressor.release();
            assertThat(body.refCnt(), is(0));
            content.release();
        }
    }

    @Test
    public void shouldThrowForACorruptBodyAndStillRelease() {
        ByteBuf content = Unpooled.wrappedBuffer("this is not gzip".getBytes(StandardCharsets.UTF_8));
        Http3RequestDecompressor decompressor = decompressor("gzip");
        CompositeByteBuf body = decompressor.body();
        try {
            assertThrows(DecoderException.class, () -> decompressor.decompress(content));
        } finally {
            decompressor.release();
            content.release();
        }
        assertThat(body.refCnt(), is(0));
    }

    @Test
    public void shouldAcceptOnlyAnEmptyBodyAtALimitOfZero() {
        assertThat(decompresses(new byte[0], 0), is(true));
        assertThat(decompresses(new byte[1], 0), is(false));
        assertThat(decompresses(new byte[1], -1), is(false));
    }

    @Test
    public void shouldNotDecompressWithoutAnEncodingHttp1Decodes() {
        assertThat(Http3RequestDecompressor.forHeaders(Collections.emptyList(), ByteBufAllocator.DEFAULT, MAX_BODY, 1024), nullValue());
        assertThat(decompressor("identity"), nullValue());
        assertThat(decompressor("compress"), nullValue());
        assertThat(decompressor("gzip, deflate"), nullValue());
        Http3RequestDecompressor gzip = decompressor("x-gzip");
        assertThat(gzip, notNullValue());
        gzip.release();
    }

    private static boolean decompresses(byte[] plain, int maxBody) {
        List<Map.Entry<String, String>> headers = Collections.singletonList(new AbstractMap.SimpleImmutableEntry<>("content-encoding", "gzip"));
        Http3RequestDecompressor decompressor = Http3RequestDecompressor.forHeaders(headers, ByteBufAllocator.DEFAULT, maxBody, 1024);
        ByteBuf content = Unpooled.wrappedBuffer(gzip(plain));
        try {
            return decompressor.decompress(content) && decompressor.finish();
        } finally {
            decompressor.release();
            content.release();
        }
    }

    private static Http3RequestDecompressor decompressor(String contentEncoding) {
        List<Map.Entry<String, String>> headers = Collections.singletonList(new AbstractMap.SimpleImmutableEntry<>("content-encoding", contentEncoding));
        return Http3RequestDecompressor.forHeaders(headers, ByteBufAllocator.DEFAULT, MAX_BODY, 1024);
    }

    private static byte[] gzip(byte[] plain) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(plain);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }
}
