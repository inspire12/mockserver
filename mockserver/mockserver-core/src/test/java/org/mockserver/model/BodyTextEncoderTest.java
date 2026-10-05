package org.mockserver.model;

import org.junit.Test;
import org.mockito.MockedStatic;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * A text long enough to overflow the JDK's own size estimate needs more than a gigabyte, so these
 * tests lower the two limits instead: no text is left to the JDK unchecked, and a text whose
 * estimate is over the largest array takes the same measured path a long text takes.
 */
public class BodyTextEncoderTest {

    private static final String HIGH_SURROGATE = "\uD83D";
    private static final String LOW_SURROGATE = "\uDE00";
    private static final String PAIR = HIGH_SURROGATE + LOW_SURROGATE;

    private static final List<Charset> CHARSETS = Arrays.asList(
        StandardCharsets.UTF_8,
        StandardCharsets.UTF_16,
        StandardCharsets.UTF_16LE,
        StandardCharsets.ISO_8859_1,
        StandardCharsets.US_ASCII,
        Charset.forName("windows-1252"),
        Charset.forName("Shift_JIS"),
        // stateful: writes escape sequences, the last of them when the encoder is flushed
        Charset.forName("ISO-2022-JP")
    );

    private static List<String> texts() {
        List<String> texts = new ArrayList<>(Arrays.asList(
            "a",
            "plain ascii text",
            "café ÿ latin-1",
            "\u0000\u0001\u007f control characters",
            "€ euro 中文 日本語",
            "pair " + PAIR + " in the middle",
            PAIR,
            HIGH_SURROGATE,
            LOW_SURROGATE,
            "unpaired high " + HIGH_SURROGATE + " then text",
            "unpaired low " + LOW_SURROGATE + " then text",
            "reversed " + LOW_SURROGATE + HIGH_SURROGATE + " pair",
            "ends with a high surrogate " + HIGH_SURROGATE
        ));
        // a lone surrogate at every position, the last included, of text from each range
        String everyRange = "a\u00e9\u20ac" + PAIR + "z";
        for (int position = 0; position <= everyRange.length(); position++) {
            texts.add(everyRange.substring(0, position) + HIGH_SURROGATE + everyRange.substring(position));
            texts.add(everyRange.substring(0, position) + LOW_SURROGATE + everyRange.substring(position));
        }
        // longer than one chunk of characters and one buffer of bytes, with each awkward
        // sequence placed across the 8192-character chunk boundary
        for (String acrossTheBoundary : Arrays.asList(PAIR, HIGH_SURROGATE + "x", "x" + LOW_SURROGATE, "€é", HIGH_SURROGATE + HIGH_SURROGATE)) {
            for (int charactersBeforeTheBoundary = 0; charactersBeforeTheBoundary <= 2; charactersBeforeTheBoundary++) {
                texts.add(
                    "a".repeat(8192 - charactersBeforeTheBoundary)
                        + acrossTheBoundary
                        + "b".repeat(8192)
                        + acrossTheBoundary
                        + "c".repeat(20000)
                        + PAIR
                );
            }
        }
        return texts;
    }

    @Test(timeout = 60000)
    public void shouldEncodeMeasuredTextToTheSameBytesAsTheJdk() {
        for (Charset charset : CHARSETS) {
            for (String text : texts()) {
                byte[] expected = text.getBytes(charset);

                // the largest array is exactly the encoded size, below the estimate for every text here
                // but those the JDK's estimate is already exact for
                byte[] encoded = BodyTextEncoder.encode(text, charset, 0, expected.length);

                assertThat(describe(text, charset), Arrays.equals(encoded, expected), is(true));
            }
        }
    }

    @Test(timeout = 60000)
    public void shouldRefuseTextThatEncodesToMoreThanTheLargestArray() {
        for (Charset charset : CHARSETS) {
            for (String text : texts()) {
                int encodedSize = text.getBytes(charset).length;

                OutOfMemoryError error = assertThrows(
                    describe(text, charset),
                    OutOfMemoryError.class,
                    () -> BodyTextEncoder.encode(text, charset, 0, encodedSize - 1)
                );

                assertThat(describe(text, charset), error.getMessage(), allOf(
                    containsString(text.length() + " characters"),
                    containsString(encodedSize + " bytes"),
                    containsString((encodedSize - 1) + " bytes one array can hold")
                ));
            }
        }
    }

    @Test(timeout = 60000)
    public void shouldEncodeTextWhoseEstimateIsOverTheLargestArrayButWhoseBytesAreNot() {
        // the shape of a large retrieve response: almost all ASCII, a few characters outside Latin-1
        String text = "{\"body\":\"" + "\\u0000".repeat(5000) + "…\"}";
        byte[] expected = text.getBytes(StandardCharsets.UTF_8);
        int largestArray = expected.length + 10;
        assertThat("the worst-case estimate must be over the largest array", text.length() * 3, greaterThan(largestArray));

        byte[] encoded = BodyTextEncoder.encode(text, StandardCharsets.UTF_8, 0, largestArray);

        assertThat(Arrays.equals(encoded, expected), is(true));
    }

    @Test(timeout = 60000)
    public void shouldLeaveTextUpToTheLimitToTheJdkUnchecked() {
        // a largest array of nothing refuses any text that is checked
        assertThat(Arrays.equals(BodyTextEncoder.encode("abcd", StandardCharsets.UTF_8, 4, 0), "abcd".getBytes(StandardCharsets.UTF_8)), is(true));
        assertThrows(OutOfMemoryError.class, () -> BodyTextEncoder.encode("abcde", StandardCharsets.UTF_8, 4, 0));
    }

    @Test(timeout = 60000)
    public void shouldCheckEveryTextTheJdkEstimateCouldOverflowFor() {
        try (MockedStatic<BodyTextEncoder> encoder = mockStatic(BodyTextEncoder.class, CALLS_REAL_METHODS)) {
            byte[] encoded = BodyTextEncoder.encode("caf\u00e9", StandardCharsets.UTF_8);

            assertThat(Arrays.equals(encoded, "caf\u00e9".getBytes(StandardCharsets.UTF_8)), is(true));
            // 16 bytes a character is more than any charset of the JDK writes
            int largestArray = Integer.MAX_VALUE - 8;
            encoder.verify(() -> BodyTextEncoder.encode("caf\u00e9", StandardCharsets.UTF_8, largestArray / 16, largestArray));
        }
    }

    private static String describe(String text, Charset charset) {
        StringBuilder start = new StringBuilder();
        text.chars().limit(40).forEach(character -> start.append(String.format("\\u%04x", character)));
        return charset + " text of " + text.length() + " characters starting " + start;
    }
}
