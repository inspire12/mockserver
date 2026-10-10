package org.mockserver.serialization.java;

import com.google.common.base.Strings;
import org.apache.commons.text.StringEscapeUtils;
import org.mockserver.model.Delay;
import org.mockserver.model.Header;
import org.mockserver.model.Headers;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.util.Base64;
import java.util.List;
import java.util.function.Function;

import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.serialization.java.ExpectationToJavaSerializer.INDENT_SIZE;

/**
 * Writes the Java for a model object built by a static factory and a chain of {@code with...} calls, one call per
 * line. A call is written only for a value that is set, so the code builds an object equal to the one it came from.
 * Enum constants are written fully qualified, so the code needs no import for them.
 */
final class FluentJavaBuilder {

    private final StringBuilder output = new StringBuilder();
    private final int numberOfSpacesToIndent;

    FluentJavaBuilder(int numberOfSpacesToIndent, String factory) {
        this.numberOfSpacesToIndent = numberOfSpacesToIndent;
        appendNewLineAndIndent(numberOfSpacesToIndent).append(factory);
    }

    /**
     * @param value a String, Integer, Long, Double, Boolean or enum constant; nothing is written when it is null
     */
    FluentJavaBuilder with(String method, Object value) {
        if (value != null) {
            appendNewLineAndIndent(numberOfSpacesToIndent + 1).append(".").append(method).append("(").append(literal(value)).append(")");
        }
        return this;
    }

    FluentJavaBuilder withDelay(String method, Delay delay) {
        if (delay != null) {
            appendNewLineAndIndent(numberOfSpacesToIndent + 1).append(".").append(method).append("(").append(new DelayToJavaSerializer().serialize(0, delay)).append(")");
        }
        return this;
    }

    FluentJavaBuilder withBytes(String method, byte[] bytes) {
        if (bytes != null) {
            appendNewLineAndIndent(numberOfSpacesToIndent + 1).append(".").append(method).append("(java.util.Base64.getDecoder().decode(\"").append(Base64.getEncoder().encodeToString(bytes)).append("\"))");
        }
        return this;
    }

    FluentJavaBuilder withHeaders(Headers headers) {
        if (headers != null) {
            List<Header> entries = headers.getEntries();
            appendNewLineAndIndent(numberOfSpacesToIndent + 1).append(".withHeaders(new Headers(");
            output.append(new HeaderToJavaSerializer().serializeAsJava(numberOfSpacesToIndent + 2, entries));
            appendNewLineAndIndent(numberOfSpacesToIndent + 1).append("))");
        }
        return this;
    }

    /**
     * @param values written as the arguments of one call, so {@code method} takes varargs; nothing is written when null
     */
    FluentJavaBuilder withLiterals(String method, List<?> values) {
        if (values != null) {
            appendNewLineAndIndent(numberOfSpacesToIndent + 1).append(".").append(method).append("(");
            for (int i = 0; i < values.size(); i++) {
                output.append(i > 0 ? ", " : "").append(literal(values.get(i)));
            }
            output.append(")");
        }
        return this;
    }

    /**
     * @param values each written by {@code serializer} at the indent it is given, as the arguments of one call, so
     *               {@code method} takes varargs; nothing is written when null
     */
    <T extends ObjectWithReflectiveEqualsHashCodeToString> FluentJavaBuilder withEach(String method, List<T> values, ToJavaSerializer<T> serializer) {
        if (values != null) {
            appendNewLineAndIndent(numberOfSpacesToIndent + 1).append(".").append(method).append("(");
            for (int i = 0; i < values.size(); i++) {
                output.append(i > 0 ? "," : "").append(serializer.serialize(numberOfSpacesToIndent + 2, values.get(i)));
            }
            appendNewLineAndIndent(numberOfSpacesToIndent + 1).append(")");
        }
        return this;
    }

    /**
     * @param serializer writes the value at the indent it is given; nothing is written when the value is null
     */
    <T extends ObjectWithReflectiveEqualsHashCodeToString> FluentJavaBuilder withObject(String method, T value, ToJavaSerializer<T> serializer) {
        if (value != null) {
            appendNewLineAndIndent(numberOfSpacesToIndent + 1).append(".").append(method).append("(");
            output.append(serializer.serialize(numberOfSpacesToIndent + 2, value));
            appendNewLineAndIndent(numberOfSpacesToIndent + 1).append(")");
        }
        return this;
    }

    <T> FluentJavaBuilder withCode(String method, T value, Function<T, String> code) {
        if (value != null) {
            appendNewLineAndIndent(numberOfSpacesToIndent + 1).append(".").append(method).append("(").append(code.apply(value)).append(")");
        }
        return this;
    }

    FluentJavaBuilder comment(boolean condition, String comment) {
        if (condition) {
            appendNewLineAndIndent(numberOfSpacesToIndent + 1).append("/*").append(comment).append("*/");
        }
        return this;
    }

    String build() {
        return output.toString();
    }

    static String literal(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof String) {
            return "\"" + StringEscapeUtils.escapeJava((String) value) + "\"";
        } else if (value instanceof Long) {
            return value + "L";
        } else if (value instanceof Double) {
            double number = (Double) value;
            if (Double.isNaN(number)) {
                return "Double.NaN";
            } else if (Double.isInfinite(number)) {
                return number > 0 ? "Double.POSITIVE_INFINITY" : "Double.NEGATIVE_INFINITY";
            }
            return Double.toString(number);
        } else if (value instanceof Enum) {
            Enum<?> constant = (Enum<?>) value;
            return constant.getDeclaringClass().getCanonicalName() + "." + constant.name();
        } else if (value instanceof Integer || value instanceof Boolean) {
            return value.toString();
        }
        throw new IllegalArgumentException("no Java literal for " + value.getClass().getName());
    }

    private StringBuilder appendNewLineAndIndent(int indent) {
        return output.append(NEW_LINE).append(Strings.padStart("", indent * INDENT_SIZE, ' '));
    }
}
