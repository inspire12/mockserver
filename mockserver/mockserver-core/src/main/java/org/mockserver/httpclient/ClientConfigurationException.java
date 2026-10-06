package org.mockserver.httpclient;

/**
 * A connection the client could not set up from its configuration, such as a forward-proxy private key or
 * certificate chain that is not valid PEM: the upstream was not reached, and trying again fails the same way. It is
 * a {@link SocketConnectionException}, which is what a request failed with before, so a caller that catches that
 * still does.
 */
public class ClientConfigurationException extends SocketConnectionException {

    private static final long serialVersionUID = 1L;

    ClientConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * @return the configuration error {@code failure} is, or was caused by, or null
     */
    public static ClientConfigurationException in(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof ClientConfigurationException) {
                return (ClientConfigurationException) failure;
            }
        }
        return null;
    }
}
