package org.mockserver.mappers;

import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.util.AsciiString;

/**
 * Netty's {@code x-http2-} extension headers ({@link HttpConversionUtil.ExtensionHeaderNames}). Netty's conversion of
 * an HTTP/2 message to an HTTP/1 one adds them and drops any the peer sent, so on a message read over HTTP/2 they
 * are Netty's, not the peer's, and are left out of the message MockServer records, matches and forwards.
 */
final class Http2ExtensionHeaders {

    private static final String PREFIX = "x-http2-";
    private static final AsciiString[] NAMES = names();

    private Http2ExtensionHeaders() {
    }

    private static AsciiString[] names() {
        HttpConversionUtil.ExtensionHeaderNames[] values = HttpConversionUtil.ExtensionHeaderNames.values();
        AsciiString[] names = new AsciiString[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].text();
        }
        return names;
    }

    static boolean isExtensionHeader(CharSequence name) {
        if (name == null || name.length() <= PREFIX.length() || !AsciiString.regionMatches(name, true, 0, PREFIX, 0, PREFIX.length())) {
            return false;
        }
        for (AsciiString extensionHeader : NAMES) {
            if (extensionHeader.contentEqualsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
