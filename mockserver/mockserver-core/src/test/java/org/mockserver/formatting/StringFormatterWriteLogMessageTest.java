package org.mockserver.formatting;

import com.fasterxml.jackson.annotation.JsonValue;
import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.ObjectWithJsonToString;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.formatting.StringFormatter.formatLogMessage;
import static org.mockserver.formatting.StringFormatter.writeLogMessage;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * writeLogMessage writes what formatLogMessage returns, which indents each line of an argument with a
 * multiline {@code ^} pattern and gives an ObjectWithJsonToString argument as its JSON, without the quotes of
 * a JSON string.
 */
public class StringFormatterWriteLogMessageTest {

    private static final String TERMINATORS = "a\nb\rc\r\nd\u0085e f g\n\r\nh\r\r\n\n";

    @Test
    public void shouldWriteWhatFormatLogMessageReturns() throws IOException {
        Object[][] cases = {
            {"received request:{}", request("/path").withHeader(header("x-line", "one two\u0085three")).withBody("body\nwith\r\nlines")},
            {"returning response:{}for request:{}for action:{}", response("text"), request("/a"), new Expectation(request("/b")).withId("id").thenRespond(response("c"))},
            {"{}", "only an argument"},
            {"leading {} and trailing", "middle"},
            {"no argument at all", "unused"},
            {"more placeholders{}than{}arguments{}", "one"},
            {"two{}{}", "", null},
            {"terminators:{}", TERMINATORS},
            {"terminators at the start:{}", "\n\nstart"},
            {"a carriage return then a line feed:{}", "x\r"},
            {"numbers{}and arrays{}", 42, Arrays.asList("a\nb", "c")},
            {"a JSON string:{}", new JsonString("quoted \" and\nline")},
            {"an empty JSON string:{}", new JsonString("")},
            {"line terminators in a JSON string:{}", new JsonString(" \u0085x")},
            {"", "empty message"},
        };
        for (Object[] testCase : cases) {
            String message = (String) testCase[0];
            Object[] arguments = Arrays.copyOfRange(testCase, 1, testCase.length);
            StringWriter writer = new StringWriter();

            // when
            writeLogMessage(writer, message, arguments);

            // then
            assertThat(message, writer.toString(), is(formatLogMessage(message, arguments)));
        }
    }

    @Test
    public void shouldFailWhereFormatLogMessageShowsAnArgumentByItsFields() {
        // given
        Unserialisable argument = new Unserialisable();
        assertThat(formatLogMessage("value:{}", argument), containsString("Unserialisable"));

        // when
        IOException exception = assertThrows(IOException.class, () -> writeLogMessage(new StringWriter(), "value:{}", argument));

        // then
        assertThat(exception.getMessage(), is("could not write log message argument as JSON"));
    }

    public static class JsonString extends ObjectWithJsonToString {
        private final String value;

        JsonString(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    public static class Unserialisable extends ObjectWithJsonToString {
        public String getValue() {
            throw new IllegalStateException("no value");
        }
    }
}
