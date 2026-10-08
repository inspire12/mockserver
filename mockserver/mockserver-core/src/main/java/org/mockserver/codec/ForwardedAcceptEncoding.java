package org.mockserver.codec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

import static io.netty.handler.codec.http.HttpHeaderValues.BR;
import static io.netty.handler.codec.http.HttpHeaderValues.DEFLATE;
import static io.netty.handler.codec.http.HttpHeaderValues.GZIP;
import static io.netty.handler.codec.http.HttpHeaderValues.IDENTITY;
import static io.netty.handler.codec.http.HttpHeaderValues.X_DEFLATE;
import static io.netty.handler.codec.http.HttpHeaderValues.X_GZIP;
import static io.netty.handler.codec.http.HttpHeaderValues.ZSTD;

/**
 * The {@code Accept-Encoding} a forwarded request carries upstream: the client's own, limited to the content codings
 * {@link BoundedZstdHttpContentDecompressor#decodes} accepts, so any coding the upstream picks from it is one
 * MockServer decodes before it records, matches or relays the response.
 */
public final class ForwardedAcceptEncoding {

    private static final String WILDCARD = "*";
    // the registered codings a "*" can stand for; each is kept only if decoded and not named elsewhere
    private static final String[] WILDCARD_CODINGS = {GZIP.toString(), DEFLATE.toString(), BR.toString(), ZSTD.toString(), IDENTITY.toString()};

    private ForwardedAcceptEncoding() {
    }

    /**
     * @param values the request's {@code Accept-Encoding} field values in order, each possibly a list; null or empty
     *               when the request has no {@code Accept-Encoding}
     * @return null when there are no values, so none is sent; otherwise the elements in their order with their
     * parameters, less those in a coding not decoded, with {@code *} replaced by the codings it covers that are decoded
     * and not named elsewhere, or {@code identity} when no element left has a q-value above zero
     */
    public static String forwarded(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> elements = new ArrayList<>();
        Set<String> named = new HashSet<>();
        for (String value : values) {
            for (String element : (value != null ? value : "").split(",")) {
                String trimmed = element.trim();
                if (!trimmed.isEmpty()) {
                    elements.add(trimmed);
                    named.add(canonical(coding(trimmed)));
                }
            }
        }
        StringJoiner forwarded = new StringJoiner(", ");
        boolean anyAcceptable = false;
        for (String element : elements) {
            String coding = coding(element);
            int semicolon = element.indexOf(';');
            String parameters = semicolon >= 0 ? element.substring(semicolon) : "";
            if (WILDCARD.equals(coding)) {
                for (String covered : WILDCARD_CODINGS) {
                    if (!named.contains(covered) && kept(covered)) {
                        forwarded.add(covered + parameters);
                        anyAcceptable |= acceptable(parameters);
                    }
                }
            } else if (kept(coding)) {
                forwarded.add(element);
                anyAcceptable |= acceptable(parameters);
            }
        }
        return anyAcceptable ? forwarded.toString() : IDENTITY.toString();
    }

    private static String coding(String element) {
        int semicolon = element.indexOf(';');
        return (semicolon >= 0 ? element.substring(0, semicolon) : element).trim().toLowerCase(Locale.ROOT);
    }

    // RFC 9110 section 8.4.1 has x-gzip and x-deflate mean gzip and deflate, so naming one covers the other for "*"
    private static String canonical(String coding) {
        if (X_GZIP.contentEqualsIgnoreCase(coding)) {
            return GZIP.toString();
        }
        return X_DEFLATE.contentEqualsIgnoreCase(coding) ? DEFLATE.toString() : coding;
    }

    private static boolean kept(String coding) {
        return IDENTITY.contentEqualsIgnoreCase(coding) || BoundedZstdHttpContentDecompressor.decodes(coding);
    }

    /**
     * Whether an element with these parameters ({@code ;q=0.5}, or none) is acceptable: its q-value is above zero. An
     * unreadable q-value is left for the upstream to judge, so it counts as acceptable.
     */
    private static boolean acceptable(String parameters) {
        for (String parameter : parameters.split(";")) {
            String trimmed = parameter.trim();
            if (trimmed.length() > 2 && trimmed.substring(0, 2).equalsIgnoreCase("q=")) {
                try {
                    return Double.parseDouble(trimmed.substring(2).trim()) > 0;
                } catch (NumberFormatException unreadable) {
                    return true;
                }
            }
        }
        return true;
    }
}
