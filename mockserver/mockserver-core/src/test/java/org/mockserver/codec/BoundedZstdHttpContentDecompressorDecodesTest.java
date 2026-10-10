package org.mockserver.codec;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * {@link BoundedZstdHttpContentDecompressor#decodes} answers as the decompressor acts: a coding it decodes has its
 * {@code Content-Encoding} removed from the head, and one it does not keeps it.
 */
public class BoundedZstdHttpContentDecompressorDecodesTest {

    @Test
    public void shouldAnswerAsTheDecompressorActs() {
        for (String coding : Arrays.asList("gzip", "GZIP", "x-gzip", "deflate", "x-deflate", "snappy", "br", "zstd",
            "identity", "x-unknown", "gzip, br", "gzip,deflate")) {
            assertThat(coding, BoundedZstdHttpContentDecompressor.decodes(coding), is(decodedByTheDecompressor(coding)));
        }
    }

    @Test
    public void shouldDecodeTheCodingsNettyDecodes() {
        for (String coding : Arrays.asList("gzip", "X-Gzip", "deflate", "x-deflate", "snappy")) {
            assertThat(coding, BoundedZstdHttpContentDecompressor.decodes(coding), is(true));
        }
        assertThat(BoundedZstdHttpContentDecompressor.decodes("br"), is(Brotli.isAvailable()));
        assertThat(BoundedZstdHttpContentDecompressor.decodes("zstd"), is(Zstd.isAvailable()));
    }

    @Test
    public void shouldNotDecodeAnUnknownCodingOrAListOfCodings() {
        for (String coding : Arrays.asList("x-unknown", "identity", "gzip, br", "")) {
            assertThat(coding, BoundedZstdHttpContentDecompressor.decodes(coding), is(false));
        }
        assertThat(BoundedZstdHttpContentDecompressor.decodes(null), is(false));
    }

    private static boolean decodedByTheDecompressor(String coding) {
        EmbeddedChannel channel = new EmbeddedChannel(new BoundedZstdHttpContentDecompressor());
        try {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_ENCODING, coding);
            channel.writeInbound(head);
            HttpResponse relayed = channel.readInbound();
            return !relayed.headers().contains(HttpHeaderNames.CONTENT_ENCODING);
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
