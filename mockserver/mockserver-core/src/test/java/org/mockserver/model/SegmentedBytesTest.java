package org.mockserver.model;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;

public class SegmentedBytesTest {

    private static final String MIXED = "ascii é ñ 中文 😀🚀 \" \\ \n";

    private static List<String> texts() {
        StringBuilder longMixed = new StringBuilder();
        while (longMixed.length() < 100_000) {
            longMixed.append(MIXED);
        }
        // a high surrogate as the last character of each 8 KiB block, to split pairs at every boundary
        StringBuilder pairsOnBoundaries = new StringBuilder();
        while (pairsOnBoundaries.length() < 50_000) {
            while ((pairsOnBoundaries.length() + 1) % 8192 != 0) {
                pairsOnBoundaries.append('x');
            }
            pairsOnBoundaries.append("😀");
        }
        return Arrays.asList(
            "",
            "a",
            "plain ascii",
            MIXED,
            "unpaired high at end \uD83D",
            "\uDE00 unpaired low at start",
            "high then not low \uD83Dx and low alone \uDE00 and pair 😀",
            "\uD83D😀",
            longMixed.toString(),
            longMixed + "\uD83D",
            pairsOnBoundaries.toString()
        );
    }

    private static byte[] written(String text, Charset charset, int chunk, int mode) throws IOException {
        SegmentedBytes bytes = new SegmentedBytes();
        try (Writer writer = bytes.writer(charset)) {
            for (int offset = 0; offset < text.length(); offset += chunk) {
                int length = Math.min(chunk, text.length() - offset);
                switch (mode) {
                    case 0:
                        writer.write(text, offset, length);
                        break;
                    case 1:
                        writer.write(text.toCharArray(), offset, length);
                        break;
                    default:
                        for (int i = offset; i < offset + length; i++) {
                            writer.write(text.charAt(i));
                        }
                }
            }
        }
        byte[] array = bytes.toByteArray();
        assertThat(array.length, is(bytes.size()));
        return array;
    }

    @Test
    public void shouldEncodeTextAsStringGetBytesDoesHoweverItIsWritten() throws IOException {
        for (Charset charset : new Charset[]{StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1, StandardCharsets.US_ASCII, StandardCharsets.UTF_16}) {
            for (String text : texts()) {
                byte[] expected = text.getBytes(charset);
                for (int chunk : new int[]{1, 2, 3, 7, 8191, 8192, 8193, Integer.MAX_VALUE}) {
                    for (int mode = 0; mode < 3; mode++) {
                        if (mode == 2 && chunk < 8191 && text.length() > 1000) {
                            continue;
                        }
                        String description = charset + " text of " + text.length() + " characters in chunks of " + chunk + " by mode " + mode;
                        assertThat(description, Arrays.equals(written(text, charset, chunk, mode), expected), is(true));
                    }
                }
            }
        }
    }

    @Test
    public void shouldPairASurrogateSplitAcrossTwoWrites() throws IOException {
        SegmentedBytes bytes = new SegmentedBytes();
        try (Writer writer = bytes.writer(StandardCharsets.UTF_8)) {
            writer.write("a\uD83D");
            writer.flush();
            writer.write("\uDE00b");
        }
        assertThat(new String(bytes.toByteArray(), StandardCharsets.UTF_8), is("a😀b"));
    }

    @Test
    public void shouldHoldTheBytesInSegmentsThatDoubleUpToTheLargest() {
        SegmentedBytes bytes = new SegmentedBytes();
        byte[] content = new byte[5 * SegmentedBytes.LARGEST_SEGMENT + 17];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31);
        }
        bytes.write(content, 0, 10);
        bytes.write(content[10]);
        bytes.write(content, 11, content.length - 11);

        ByteBuffer[] buffers = bytes.asByteBuffers();
        List<Integer> capacities = new ArrayList<>();
        int total = 0;
        for (ByteBuffer buffer : buffers) {
            capacities.add(buffer.capacity());
            total += buffer.remaining();
        }
        assertThat(capacities.subList(0, 12), is(Arrays.asList(1024, 1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288, 1048576)));
        assertThat(capacities.get(capacities.size() - 1), is(SegmentedBytes.LARGEST_SEGMENT));
        assertThat(total, is(content.length));
        assertThat(bytes.size(), is(content.length));
        assertThat(Arrays.equals(bytes.toByteArray(), content), is(true));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            bytes.writeTo(out);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertThat(Arrays.equals(out.toByteArray(), content), is(true));
    }

    @Test
    public void shouldHoldNothingWhenNothingIsWritten() {
        SegmentedBytes bytes = new SegmentedBytes();
        assertThat(bytes.size(), is(0));
        assertThat(bytes.asByteBuffers().length, is(0));
        assertThat(bytes.toByteArray().length, is(0));
    }

    @Test
    public void shouldRunOutOfMemoryPastTheLargestSize() {
        SegmentedBytes bytes = new SegmentedBytes(10);
        bytes.write(new byte[10], 0, 10);
        OutOfMemoryError error = assertThrows(OutOfMemoryError.class, () -> bytes.write(1));
        assertThat(error.getMessage(), containsString("more than 10 bytes"));
        assertThat(bytes.size(), is(10));

        SegmentedBytes oneWrite = new SegmentedBytes(10);
        assertThrows(OutOfMemoryError.class, () -> oneWrite.write(new byte[11], 0, 11));
    }

    @Test
    public void shouldNotLetASegmentPassTheLargestSize() {
        SegmentedBytes bytes = new SegmentedBytes(1500);
        bytes.write(new byte[1500], 0, 1500);
        ByteBuffer[] buffers = bytes.asByteBuffers();
        assertThat(buffers.length, is(2));
        assertThat(buffers[1].capacity(), is(476));
    }

    @Test
    public void shouldRejectAWriteAfterClose() throws IOException {
        SegmentedBytes bytes = new SegmentedBytes();
        Writer writer = bytes.writer(StandardCharsets.UTF_8);
        writer.write("text");
        writer.close();
        writer.close();
        assertThat(new String(bytes.toByteArray(), StandardCharsets.UTF_8), is("text"));
        assertThrows(IllegalStateException.class, () -> writer.write("more"));
        assertThrows(IllegalStateException.class, () -> writer.write('m'));
        assertThrows(IllegalStateException.class, () -> writer.write(new char[]{'m'}, 0, 1));
    }

    @Test
    public void shouldRejectAnOutOfRangeWrite() {
        SegmentedBytes bytes = new SegmentedBytes();
        assertThrows(IndexOutOfBoundsException.class, () -> bytes.write(new byte[4], 2, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> bytes.write(new byte[4], -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> bytes.write(new byte[4], 0, -1));
        assertThat(bytes.size(), is(0));
    }
}
