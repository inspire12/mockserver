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
}
