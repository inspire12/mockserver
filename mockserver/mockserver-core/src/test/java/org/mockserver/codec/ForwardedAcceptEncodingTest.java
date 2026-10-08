package org.mockserver.codec;

import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.Zstd;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.StringJoiner;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.codec.ForwardedAcceptEncoding.forwarded;

public class ForwardedAcceptEncodingTest {

    @Test
    public void shouldSendNoneWhenTheClientSentNone() {
        assertThat(forwarded(null), is(nullValue()));
        assertThat(forwarded(Collections.emptyList()), is(nullValue()));
    }

    @Test
    public void shouldKeepDecodedCodingsInOrderWithTheirQValues() {
        assertThat(forwarded(Collections.singletonList("deflate;q=0.9, gzip;q=1.0, identity;q=0.1")), is("deflate;q=0.9, gzip;q=1.0, identity;q=0.1"));
        assertThat(forwarded(Collections.singletonList("gzip")), is("gzip"));
        assertThat(forwarded(Collections.singletonList("GZIP ; q=0.5")), is("GZIP ; q=0.5"));
        assertThat(forwarded(Collections.singletonList("x-gzip, x-deflate, snappy")), is("x-gzip, x-deflate, snappy"));
    }

    @Test
    public void shouldJoinSeveralFieldsInOrder() {
        assertThat(forwarded(Arrays.asList("deflate", "gzip;q=0.5")), is("deflate, gzip;q=0.5"));
    }

    @Test
    public void shouldDropCodingsNotDecoded() {
        assertThat(forwarded(Collections.singletonList("compress, gzip;q=0.8, x-unknown;q=0.9, deflate")), is("gzip;q=0.8, deflate"));
    }

    @Test
    public void shouldKeepBrotliAndZstdOnlyWhenTheirDecodersLoad() {
        StringJoiner expected = new StringJoiner(", ");
        if (Brotli.isAvailable()) {
            expected.add("br");
        }
        if (Zstd.isAvailable()) {
            expected.add("zstd;q=0.9");
        }
        expected.add("gzip;q=0.5");
        assertThat(forwarded(Collections.singletonList("br, zstd;q=0.9, gzip;q=0.5")), is(expected.toString()));
    }

    @Test
    public void shouldSendIdentityWhenNothingDecodedRemains() {
        assertThat(forwarded(Collections.singletonList("compress, x-unknown;q=0.5")), is("identity"));
        assertThat(forwarded(Collections.singletonList("")), is("identity"));
    }

    @Test
    public void shouldSendIdentityWhenNoRemainingCodingIsAcceptable() {
        assertThat(forwarded(Collections.singletonList("compress, gzip;q=0")), is("identity"));
        assertThat(forwarded(Collections.singletonList("gzip;q=0.000, identity;q=0")), is("identity"));
    }

    @Test
    public void shouldKeepAnExclusionBesideAnAcceptableCoding() {
        assertThat(forwarded(Collections.singletonList("gzip;q=0, deflate")), is("gzip;q=0, deflate"));
    }

    @Test
    public void shouldLeaveAnUnreadableQValueToTheUpstream() {
        assertThat(forwarded(Collections.singletonList("gzip;q=high")), is("gzip;q=high"));
    }

    @Test
    public void shouldReplaceAWildcardWithTheDecodedCodingsItCovers() {
        StringJoiner expected = new StringJoiner(", ");
        expected.add("gzip;q=1.0");
        expected.add("deflate;q=0.1");
        if (Brotli.isAvailable()) {
            expected.add("br;q=0.1");
        }
        if (Zstd.isAvailable()) {
            expected.add("zstd;q=0.1");
        }
        expected.add("identity;q=0.1");
        assertThat(forwarded(Collections.singletonList("gzip;q=1.0, *;q=0.1")), is(expected.toString()));
    }

    @Test
    public void shouldNotWidenAWildcardToACodingExcludedUnderItsAlias() {
        StringJoiner expected = new StringJoiner(", ");
        expected.add("x-gzip;q=0");
        expected.add("x-deflate;q=0");
        if (Brotli.isAvailable()) {
            expected.add("br");
        }
        if (Zstd.isAvailable()) {
            expected.add("zstd");
        }
        expected.add("identity");
        assertThat(forwarded(Collections.singletonList("x-gzip;q=0, x-deflate;q=0, *")), is(expected.toString()));
    }

    @Test
    public void shouldSendIdentityForAWildcardThatExcludesEverything() {
        assertThat(forwarded(Collections.singletonList("compress, *;q=0")), is("identity"));
    }
}
