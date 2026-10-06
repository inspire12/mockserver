package org.mockserver.dashboard.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Body;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogEntryBody;
import org.mockserver.model.RequestDefinition;

import java.io.IOException;
import java.io.Writer;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Shortens the bodies the dashboard sends in its live updates. A row carries its exchange's bodies about eight
 * times over, so uncapped bodies make an update hundreds of megabytes; the dashboard loads a full body on demand
 * (GET /mockserver/logEntryBody). Sizes are in characters of the body as the dashboard shows it.
 */
public final class DashboardBodyCap {

    public static final int MAX_BODY_CHARACTERS = 64 * 1024;
    // a message's headers, path and the like, which are not capped
    public static final int MESSAGE_OVERHEAD_CHARACTERS = 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private DashboardBodyCap() {
    }

    /**
     * What capping one log entry's messages found: each cut copy's full body length, and roughly how many
     * characters the entry adds to an update.
     */
    public static final class Sizes {
        private final Map<Object, Long> originalLengths = new IdentityHashMap<>();
        private final Map<Object, Long> shownLengths = new IdentityHashMap<>();
        private long displayedCharacters;

        void displayed(long characters) {
            displayedCharacters += characters;
        }

        void shown(Object message, long shownCharacters) {
            shownLengths.put(message, shownCharacters);
            displayedCharacters += shownCharacters;
        }

        void truncated(Object capped, long originalLength, long shownCharacters) {
            originalLengths.put(capped, originalLength);
            shown(capped, shownCharacters);
        }

        /**
         * How many characters of a message's body this sends, as measured when it was capped, or {@code null} when
         * the message was not capped here.
         */
        public Long shownLength(Object message) {
            return message == null ? null : shownLengths.get(message);
        }

        /**
         * The full body length of a message this cut short, or {@code null} when it was sent whole.
         */
        public Long originalLength(Object message) {
            return message == null ? null : originalLengths.get(message);
        }

        public long displayedCharacters() {
            return displayedCharacters;
        }

        private long longestOriginalLength() {
            return originalLengths.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        }
    }

    /**
     * A body's text, cut to at most {@code limit} characters, and its full length.
     */
    static final class BodyText {
        final String text;
        final long length;

        private BodyText(String text, long length) {
            this.text = text;
            this.length = length;
        }

        boolean truncated() {
            return length > text.length();
        }
    }

    static BodyText bodyText(Body<?> body, int limit) {
        Object value = body instanceof LogEntryBody ? body.getValue() : null;
        if (value instanceof JsonNode) {
            PrefixWriter writer = new PrefixWriter(limit);
            try {
                JSON.writeValue(writer, value);
            } catch (IOException e) {
                return of(body.toStringWithoutCaching(), limit);
            }
            String shown = writer.length > limit ? prefix(writer.prefix, writer.prefix.length()) : writer.prefix.toString();
            return new BodyText(shown, writer.length);
        }
        return of(value instanceof String ? (String) value : body.toStringWithoutCaching(), limit);
    }

    private static BodyText of(String text, int limit) {
        if (text == null) {
            return new BodyText("", 0);
        }
        return new BodyText(text.length() > limit ? prefix(text, limit) : text, text.length());
    }

    // The first characters of text, never ending on the first half of a surrogate pair.
    private static String prefix(CharSequence text, int limit) {
        int end = limit > 0 && Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.subSequence(0, end).toString();
    }

    /**
     * What the dashboard needs to say a body was cut and to load it whole: the log entry it belongs to,
     * which of the entry's messages it is, and the full and shown lengths.
     */
    public static Map<String, Object> truncationMarker(String logEntryId, Object message, long originalLength, boolean loadable) {
        Map<String, Object> marker = new java.util.LinkedHashMap<>();
        marker.put("logEntryId", logEntryId);
        marker.put("part", message instanceof HttpResponse ? "response" : message instanceof Expectation ? "expectation" : "request");
        marker.put("originalLength", originalLength);
        marker.put("shownLength", (long) MAX_BODY_CHARACTERS);
        marker.put("loadable", loadable && !(message instanceof Expectation));
        return marker;
    }

    static String capText(String text) {
        if (text == null || text.length() <= MAX_BODY_CHARACTERS) {
            return text;
        }
        String shown = prefix(text, MAX_BODY_CHARACTERS);
        return shown + "... (" + (text.length() - shown.length()) + " more characters not shown)";
    }

    /**
     * The request with its body cut to the cap, or the same request when it fits. A cut copy is recorded in
     * {@code sizes} with the body's full length.
     */
    static RequestDefinition cap(RequestDefinition requestDefinition, Sizes sizes) {
        if (!(requestDefinition instanceof HttpRequest) || ((HttpRequest) requestDefinition).getBody() == null) {
            return requestDefinition;
        }
        HttpRequest request = (HttpRequest) requestDefinition;
        BodyText text = bodyText(request.getBody(), MAX_BODY_CHARACTERS);
        if (!text.truncated()) {
            sizes.shown(request, text.length);
            return request;
        }
        HttpRequest capped = request.shallowClone().withBody(new LogEntryBody(text.text));
        sizes.truncated(capped, text.length, text.text.length());
        return capped;
    }

    static HttpResponse cap(HttpResponse response, Sizes sizes) {
        if (response == null || response.getBody() == null) {
            return response;
        }
        BodyText text = bodyText(response.getBody(), MAX_BODY_CHARACTERS);
        if (!text.truncated()) {
            sizes.shown(response, text.length);
            return response;
        }
        HttpResponse capped = response.shallowClone().withBody(new LogEntryBody(text.text));
        sizes.truncated(capped, text.length, text.text.length());
        return capped;
    }

    /**
     * A log message argument with any body or long text in it cut to the cap.
     */
    static Object capArgument(Object argument, Sizes sizes) {
        if (argument instanceof RequestDefinition) {
            sizes.displayed(MESSAGE_OVERHEAD_CHARACTERS);
            return cap((RequestDefinition) argument, sizes);
        } else if (argument instanceof HttpResponse) {
            sizes.displayed(MESSAGE_OVERHEAD_CHARACTERS);
            return cap((HttpResponse) argument, sizes);
        } else if (argument instanceof Expectation) {
            Expectation expectation = (Expectation) argument;
            sizes.displayed(MESSAGE_OVERHEAD_CHARACTERS);
            Sizes inner = new Sizes();
            RequestDefinition request = cap(expectation.getHttpRequest(), inner);
            HttpResponse response = cap(expectation.getHttpResponse(), inner);
            sizes.displayed(inner.displayedCharacters());
            if (request == expectation.getHttpRequest() && response == expectation.getHttpResponse()) {
                return expectation;
            }
            Expectation capped = expectation.cloneWith(request, response);
            sizes.truncated(capped, inner.longestOriginalLength(), 0);
            return capped;
        } else if (argument instanceof String) {
            String text = (String) argument;
            sizes.displayed(Math.min(text.length(), MAX_BODY_CHARACTERS));
            return capText(text);
        } else {
            sizes.displayed(256);
            return argument;
        }
    }

    // Keeps the first characters written and counts the rest without holding them.
    private static final class PrefixWriter extends Writer {
        private final int limit;
        private final StringBuilder prefix = new StringBuilder();
        private long length;

        private PrefixWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(char[] buffer, int offset, int count) {
            int room = (int) Math.max(0, Math.min(count, limit - length));
            if (room > 0) {
                prefix.append(buffer, offset, room);
            }
            length += count;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
