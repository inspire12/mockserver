package org.mockserver.netty.http3;

import org.apache.commons.lang3.exception.ExceptionUtils;

import java.net.BindException;

/**
 * Thrown at start-up when {@code http3Port} is set and the HTTP/3 server could not be started on it, so that
 * MockServer does not run without a protocol it was configured to serve. The message is one line naming the
 * port and the root cause. It is not an {@link IllegalArgumentException}, whatever its cause: the CLI treats
 * that as a usage error and exits 0.
 */
public class Http3StartupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public Http3StartupException(int http3Port, Throwable cause) {
        super(message(http3Port, cause), cause);
    }

    /**
     * @return true if the UDP port could not be bound, which the message alone explains
     */
    public boolean isPortUnavailable() {
        return portUnavailable(getCause());
    }

    static String message(int http3Port, Throwable cause) {
        String start = "HTTP/3 is enabled (http3Port=" + http3Port + ") but ";
        String end = " or remove http3Port to run without HTTP/3 (underlying error: " + Http3NativeUnavailableException.underlyingError(cause) + ")";
        if (portUnavailable(cause)) {
            return start + "UDP port " + http3Port + " could not be bound, so MockServer cannot start: free the port if another application holds it, choose a different http3Port," + end;
        }
        return start + "its server could not start on UDP port " + http3Port + ", so MockServer cannot start: fix the underlying error" + end;
    }

    private static boolean portUnavailable(Throwable cause) {
        return ExceptionUtils.indexOfType(cause, BindException.class) >= 0;
    }
}
