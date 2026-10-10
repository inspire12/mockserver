package org.mockserver.httpclient;

/**
 * A forward failed because an upstream's response headers or trailers were larger than {@code maxHeaderSize}. The
 * message is the reason the client is given with its {@code 502}.
 */
public class HeaderLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HeaderLimitExceededException(String message) {
        // an expected refusal, logged where it is raised: its stack trace would say nothing
        super(message, null, false, false);
    }

    /**
     * @return the header limit failure {@code failure} is or was caused by, or {@code null}
     */
    public static HeaderLimitExceededException in(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof HeaderLimitExceededException) {
                return (HeaderLimitExceededException) failure;
            }
        }
        return null;
    }
}
