package org.mockserver.httpclient;

/**
 * A forward failed because the upstream's HTTP/1.1 response could not be decoded, such as a status line that is not
 * HTTP, a header that is not valid or a chunk size that is not a number. Its cause is the decoder's. The upstream
 * answered, so the request is not sent again, and its connection is closed rather than pooled.
 */
public class UndecodableResponseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UndecodableResponseException(String message, Throwable cause) {
        // logged where it is raised, and its cause says where decoding stopped: its own stack trace would say nothing
        super(message, cause, false, false);
    }

    /**
     * @return the decoding failure {@code failure} is or was caused by, or {@code null}
     */
    public static UndecodableResponseException in(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof UndecodableResponseException) {
                return (UndecodableResponseException) failure;
            }
        }
        return null;
    }
}
