package org.mockserver.model;

import org.junit.Test;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThrows;

public class StringBodyFromSegmentedBytesTest {

    private static SegmentedBytes encoded(String text) throws IOException {
        SegmentedBytes bytes = new SegmentedBytes();
        try (Writer writer = bytes.writer(StandardCharsets.UTF_8)) {
            writer.write(text);
        }
        return bytes;
    }

    @Test
    public void shouldEqualTheBodyBuiltFromTheSameText() throws IOException {
        for (String text : new String[]{"[]", "{ \"é\" : \"😀 中文\" }", "  ", "\n", ""}) {
            StringBody fromText = new StringBody(text, MediaType.JSON_UTF_8);
            StringBody fromBytes = StringBody.fromSegmentedBytes(encoded(text), MediaType.JSON_UTF_8);

            assertThat(text, fromBytes, is(fromText));
            assertThat(text, fromText, is(fromBytes));
            assertThat(text, fromBytes.hashCode(), is(fromText.hashCode()));
            assertThat(text, fromBytes.getValue(), is(fromText.getValue()));
            assertThat(text, fromBytes.getValueWithoutCaching(), is(fromText.getValue()));
            assertThat(text, fromBytes.toString(), is(fromText.toString()));
            assertThat(text, Arrays.equals(fromBytes.getRawBytes(), fromText.getRawBytes()), is(true));
            assertThat(text, fromBytes.getContentType(), is(fromText.getContentType()));
            assertThat(text, fromBytes.getType(), is(Body.Type.STRING));
            assertThat(text, fromBytes.isSubString(), is(false));
            assertThat(text, HttpResponse.response().withBody(fromBytes), is(HttpResponse.response().withBody(text, MediaType.JSON_UTF_8)));
        }
    }

    @Test
    public void shouldDifferFromTheBodyOfOtherText() throws IOException {
        assertThat(StringBody.fromSegmentedBytes(encoded("[1]"), MediaType.JSON_UTF_8), not(new StringBody("[2]", MediaType.JSON_UTF_8)));
        assertThat(StringBody.fromSegmentedBytes(encoded("[1]"), MediaType.JSON_UTF_8), not(new StringBody("[1]", MediaType.PLAIN_TEXT_UTF_8)));
    }

    @Test
    public void shouldHoldTheSegmentsItWasBuiltFrom() throws IOException {
        SegmentedBytes bytes = encoded("text");
        StringBody body = StringBody.fromSegmentedBytes(bytes, MediaType.PLAIN_TEXT_UTF_8);
        assertThat(body.getSegmentedBytes(), sameInstance(bytes));
        assertThat(new StringBody("text").getSegmentedBytes(), nullValue());
    }

    @Test
    public void shouldNeedTheCharsetTheBytesAreEncodedIn() throws IOException {
        SegmentedBytes bytes = encoded("text");
        assertThrows(IllegalArgumentException.class, () -> StringBody.fromSegmentedBytes(bytes, MediaType.create("text", "plain")));
        assertThrows(IllegalArgumentException.class, () -> StringBody.fromSegmentedBytes(bytes, null));
        assertThrows(IllegalArgumentException.class, () -> StringBody.fromSegmentedBytes(null, MediaType.PLAIN_TEXT_UTF_8));
    }

    @Test
    public void shouldDecodeInTheContentTypesCharset() throws IOException {
        SegmentedBytes bytes = new SegmentedBytes();
        try (Writer writer = bytes.writer(StandardCharsets.UTF_16)) {
            writer.write("é😀");
        }
        StringBody body = StringBody.fromSegmentedBytes(bytes, MediaType.PLAIN_TEXT_UTF_8.withCharset(StandardCharsets.UTF_16));
        assertThat(body.getValue(), is("é😀"));
        assertThat(body, is(new StringBody("é😀", MediaType.PLAIN_TEXT_UTF_8.withCharset(StandardCharsets.UTF_16))));
    }
}
