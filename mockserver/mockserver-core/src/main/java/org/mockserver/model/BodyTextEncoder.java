package org.mockserver.model;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;

/**
 * Encodes a body's text to bytes, whatever its length. {@link String#getBytes(Charset)} sizes a
 * working array as the text's length times the charset's largest bytes-per-character, in an
 * {@code int}; for a long text that overflows (on Java 17, UTF-8 asks for an array of negative
 * size) although the encoded text would fit. Such a text is measured and encoded into an array of
 * exactly that size; any other text is encoded by the JDK as before. The bytes are the same.
 */
public final class BodyTextEncoder {

    // the largest array every JVM allocates
    static final int LARGEST_ARRAY = Integer.MAX_VALUE - 8;
    // more than any JDK charset writes for one character, so a text under LARGEST_ARRAY / this needs no check
    private static final int MOST_BYTES_PER_CHARACTER = 16;
    private static final int CHUNK_SIZE = 8192;

    private BodyTextEncoder() {
    }

    public static byte[] encode(String text, Charset charset) {
        return encode(text, charset, LARGEST_ARRAY / MOST_BYTES_PER_CHARACTER, LARGEST_ARRAY);
    }

    /**
     * @param leftToTheJdk a text of up to this many characters goes to the JDK without a check
     * @throws OutOfMemoryError if the encoded text is larger than {@code largestArray}, as the JDK
     *                          throws for an array it cannot allocate
     */
    static byte[] encode(String text, Charset charset, int leftToTheJdk, int largestArray) {
        if (text.length() <= leftToTheJdk) {
            return text.getBytes(charset);
        }
        CharsetEncoder encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
        if (text.length() * Math.ceil(encoder.maxBytesPerChar()) <= largestArray) {
            return text.getBytes(charset);
        }
        long size = encode(text, encoder, ByteBuffer.allocate(CHUNK_SIZE * 2), true);
        if (size > largestArray) {
            throw new OutOfMemoryError("text of " + text.length() + " characters encodes to " + size + " bytes in " + charset + ", more than the " + largestArray + " bytes one array can hold");
        }
        byte[] bytes = new byte[(int) size];
        encode(text, encoder, ByteBuffer.wrap(bytes), false);
        return bytes;
    }

    /**
     * @param countOnly when true {@code out} is emptied each time it fills, so only the count is kept
     * @return the number of bytes the text encodes to
     */
    private static long encode(String text, CharsetEncoder encoder, ByteBuffer out, boolean countOnly) {
        encoder.reset();
        CharBuffer in = CharBuffer.allocate(CHUNK_SIZE);
        in.limit(0);
        long counted = 0;
        int next = 0;
        boolean endOfInput;
        do {
            // compact() keeps a high surrogate the encoder left at the end of the previous chunk
            in.compact();
            int count = Math.min(in.remaining(), text.length() - next);
            text.getChars(next, next + count, in.array(), in.position());
            in.position(in.position() + count);
            in.flip();
            next += count;
            endOfInput = next == text.length();
            while (encoder.encode(in, out, endOfInput) == CoderResult.OVERFLOW) {
                counted += empty(out, countOnly);
            }
        } while (!endOfInput);
        while (encoder.flush(out) == CoderResult.OVERFLOW) {
            counted += empty(out, countOnly);
        }
        return counted + out.position();
    }

    private static int empty(ByteBuffer out, boolean countOnly) {
        if (!countOnly) {
            throw new IllegalStateException("text encoded to more bytes than it was measured to need");
        }
        int filled = out.position();
        out.clear();
        return filled;
    }
}
