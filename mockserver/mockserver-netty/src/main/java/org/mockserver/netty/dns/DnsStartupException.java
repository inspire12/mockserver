package org.mockserver.netty.dns;

import org.apache.commons.lang3.exception.ExceptionUtils;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.substringBefore;

/**
 * Thrown at start-up when {@code dnsEnabled} is true and the DNS server could not be started on {@code dnsPort},
 * so that MockServer does not run without a listener it was configured to serve. The message is one line naming
 * the port and the root cause. It is not an {@link IllegalArgumentException}, whatever its cause: the CLI treats
 * that as a usage error and exits 0.
 */
public class DnsStartupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean portUnavailable;

    private DnsStartupException(int dnsPort, boolean portUnavailable, Throwable cause) {
        super(message(dnsPort, portUnavailable, cause), cause);
        this.portUnavailable = portUnavailable;
    }

    /**
     * The channel for an explicit {@code dnsPort} could not be created, registered or bound; a bind the
     * operating system refused is the usual case. That is told by where the start failed, not by the type
     * of {@code cause}: for a refused bind the NIO transport reports a {@link java.net.BindException}, the
     * epoll transport an {@link java.io.IOException} of its own.
     */
    public static DnsStartupException portCouldNotBeOpenedOrBound(int dnsPort, Throwable cause) {
        return new DnsStartupException(dnsPort, true, cause);
    }

    public static DnsStartupException serverCouldNotStart(int dnsPort, Throwable cause) {
        return new DnsStartupException(dnsPort, false, cause);
    }

    /**
     * @return true if an explicit UDP port could not be opened or bound, which the message alone explains
     */
    public boolean isPortUnavailable() {
        return portUnavailable;
    }

    private static String message(int dnsPort, boolean portUnavailable, Throwable cause) {
        String start = "DNS mocking is enabled (dnsEnabled=true, dnsPort=" + dnsPort + ") but ";
        String end = " set dnsEnabled=false to run without DNS mocking (underlying error: " + underlyingError(cause) + ")";
        if (portUnavailable) {
            return start + "UDP port " + dnsPort + " could not be opened or bound, so MockServer cannot start: free the port if another application holds it,"
                + " choose a different dnsPort (0 picks a free port, and a port below 1024 can need extra privileges), or" + end;
        }
        return start + "its server could not start on UDP port " + dnsPort + ", so MockServer cannot start: fix the underlying error or" + end;
    }

    private static String underlyingError(Throwable cause) {
        Throwable root = cause == null ? null : ExceptionUtils.getRootCause(cause);
        if (root == null) {
            return "unknown";
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (isBlank(message) ? "" : ": " + substringBefore(message.trim(), "\n"));
    }
}
