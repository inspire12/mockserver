package org.mockserver.serialization.code;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.function.Consumer;

/**
 * Helpers for code generators that write the code for many expectations to a {@link Writer}, a piece at
 * a time, and to a String through the same path.
 */
final class GeneratedCode {

    private GeneratedCode() {
    }

    /**
     * Writes the code held in {@code output} to {@code writer} and empties {@code output}.
     */
    static void flush(StringBuilder output, Writer writer) {
        try {
            writer.append(output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        output.setLength(0);
    }

    static String toString(Consumer<Writer> generator) {
        StringWriter writer = new StringWriter();
        generator.accept(writer);
        return writer.toString();
    }

    /**
     * A writer that takes what is written to it one character at a time, so a generator can escape or
     * scan an expectation's JSON as it is written rather than once it is a String. Closing it does nothing.
     */
    abstract static class CharWriter extends Writer {

        @Override
        public abstract void write(int c) throws IOException;

        @Override
        public void write(char[] chars, int offset, int length) throws IOException {
            for (int i = offset; i < offset + length; i++) {
                write(chars[i]);
            }
        }

        @Override
        public void write(String text, int offset, int length) throws IOException {
            for (int i = offset; i < offset + length; i++) {
                write(text.charAt(i));
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
