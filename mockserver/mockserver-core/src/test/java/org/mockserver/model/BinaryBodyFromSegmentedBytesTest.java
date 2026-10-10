package org.mockserver.model;

import org.junit.Test;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThrows;

public class BinaryBodyFromSegmentedBytesTest {

    private static SegmentedBytes segmented(byte[] data) {
        SegmentedBytes bytes = new SegmentedBytes();
        bytes.write(data, 0, data.length);
        return bytes;
    }

    private static byte[] data(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i * 7);
        }
        return data;
    }

    @Test
    public void shouldEqualTheBodyBuiltFromTheSameBytes() {
        for (int length : new int[]{0, 1, 1024, 1025, 10_000}) {
            for (MediaType contentType : new MediaType[]{null, MediaType.APPLICATION_OCTET_STREAM}) {
                String label = length + " bytes as " + contentType;
                BinaryBody fromBytes = new BinaryBody(data(length), contentType);
                BinaryBody fromSegments = BinaryBody.fromSegmentedBytes(segmented(data(length)), contentType);

                assertThat(label, fromSegments, is(fromBytes));
                assertThat(label, fromBytes, is(fromSegments));
                assertThat(label, fromSegments.hashCode(), is(fromBytes.hashCode()));
                assertThat(label, Arrays.equals(fromSegments.getValue(), fromBytes.getValue()), is(true));
                assertThat(label, Arrays.equals(fromSegments.getRawBytes(), fromBytes.getRawBytes()), is(true));
                assertThat(label, fromSegments.toString(), is(fromBytes.toString()));
                assertThat(label, fromSegments.getContentType(), is(fromBytes.getContentType()));
                assertThat(label, fromSegments.getType(), is(Body.Type.BINARY));
                assertThat(label, HttpResponse.response().withBody(fromSegments), is(HttpResponse.response().withBody(fromBytes)));
            }
        }
    }

    @Test
    public void shouldDifferFromTheBodyOfOtherBytes() {
        assertThat(BinaryBody.fromSegmentedBytes(segmented(new byte[]{1}), null), not(new BinaryBody(new byte[]{2})));
        assertThat(BinaryBody.fromSegmentedBytes(segmented(new byte[]{1}), null), not(new BinaryBody(new byte[]{1}, MediaType.APPLICATION_OCTET_STREAM)));
    }

    @Test
    public void shouldHoldTheSegmentsItWasBuiltFrom() {
        SegmentedBytes bytes = segmented(data(10));
        assertThat(BinaryBody.fromSegmentedBytes(bytes, null).getSegmentedBytes(), sameInstance(bytes));
        assertThat(new BinaryBody(data(10)).getSegmentedBytes(), nullValue());
    }

    @Test
    public void shouldNeedSegmentedBytes() {
        assertThrows(IllegalArgumentException.class, () -> BinaryBody.fromSegmentedBytes(null, null));
    }
}
