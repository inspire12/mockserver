package org.mockserver.netty.unification;

import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ServerTlsSettings;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import javax.net.ssl.SSLHandshakeException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockserver.exception.ExceptionHandling.boundedFaultMessage;

/**
 * Logs a client's failed TLS handshake, on TCP and on HTTP/3: at {@code WARN} for the first failure from a client
 * address on a transport, with a hint about its likely cause, and at {@code DEBUG} for each later one, so a client
 * that retries, or a scan, does not fill the log. It remembers at most {@link #MAX_CLIENT_ADDRESSES} addresses,
 * forgetting the one whose handshake failed least recently. No entry carries a stack trace or a peer's bytes.
 */
public class ClientTlsHandshakeFailureLog {

    public static final String TCP = "TCP";
    public static final String HTTP3 = "HTTP/3";
    static final int MAX_CLIENT_ADDRESSES = 1024;
    static final String TRUST_HINT = "the client does not trust MockServer's Certificate Authority";
    // a cause chain can be cyclic
    private static final int MAX_CAUSE_DEPTH = 8;
    private static final String LATER_AT_DEBUG = "later failed handshakes from this client address are logged at DEBUG";

    private final Map<Object, Boolean> clientAddresses = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Object, Boolean> eldest) {
            return size() > MAX_CLIENT_ADDRESSES;
        }
    };

    public void log(MockServerLogger mockServerLogger, Configuration configuration, String transport, SocketAddress clientAddress, Throwable cause) {
        log(mockServerLogger, configuration, transport, clientAddress, reason(cause), allMessages(cause));
    }

    /**
     * For a client that ended the handshake with a TLS alert of its own, which a QUIC server sees only as the
     * client's close of the connection.
     */
    public void logClientAlert(MockServerLogger mockServerLogger, Configuration configuration, String transport, SocketAddress clientAddress, int alert) {
        String reason = "the client closed the connection with TLS alert " + alert + " (" + alertName(alert) + ")";
        log(mockServerLogger, configuration, transport, clientAddress, reason, reason);
    }

    private void log(MockServerLogger mockServerLogger, Configuration configuration, String transport, SocketAddress clientAddress, String reason, String causeText) {
        boolean first = firstFailureFrom(transport, clientAddress);
        Level level = first ? Level.WARN : Level.DEBUG;
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(level)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(level)
                    // only constants in the format: what varies is an argument
                    .setMessageFormat("TLS handshake failed on " + (HTTP3.equals(transport) ? HTTP3 : TCP) + " connection from:{}reason:{}probable cause:{}"
                        + "to make a client trust MockServer's Certificate Authority see https://mock-server.com/mock_server/HTTPS_TLS.html, MockServer's certificates are configured as:{}"
                        + (first ? LATER_AT_DEBUG : ""))
                    .setArguments(clientAddress, reason, probableCause(causeText), configuredCertificates(configuration))
            );
        }
    }

    /**
     * Whether {@code cause} is or wraps a failed handshake of any TLS engine: OpenSSL's is a subclass of
     * {@link SSLHandshakeException}.
     */
    public static boolean isFailedHandshake(Throwable cause) {
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof SSLHandshakeException) {
                return true;
            }
        }
        return false;
    }

    boolean firstFailureFrom(String transport, SocketAddress clientAddress) {
        Object key = Arrays.asList(transport, host(clientAddress));
        synchronized (clientAddresses) {
            return clientAddresses.put(key, Boolean.TRUE) == null;
        }
    }

    int size() {
        synchronized (clientAddresses) {
            return clientAddresses.size();
        }
    }

    // the host alone: a client's every connection comes from another port
    private static Object host(SocketAddress clientAddress) {
        if (clientAddress instanceof InetSocketAddress) {
            InetAddress address = ((InetSocketAddress) clientAddress).getAddress();
            return address != null ? address : ((InetSocketAddress) clientAddress).getHostString();
        }
        return clientAddress != null ? clientAddress : "unknown";
    }

    private static String reason(Throwable cause) {
        String message = boundedFaultMessage(cause);
        return message != null ? message : cause.getClass().getName();
    }

    private static String probableCause(String causeText) {
        String messages = causeText.toLowerCase();
        if (messages.contains("certificate_unknown") || messages.contains("unknown_ca")) {
            return TRUST_HINT;
        } else if (messages.contains("bad_certificate") || messages.contains("unsupported_certificate")
            || messages.contains("certificate_revoked") || messages.contains("certificate_expired")) {
            return "the client rejected MockServer's certificate: it may not trust MockServer's Certificate Authority, or the certificate has expired or does not name the host the client asked for";
        } else if (messages.contains("no_application_protocol")) {
            return "the client offered no application protocol (ALPN) that MockServer serves on this port";
        } else if (messages.contains("handshake_failure")) {
            return "the client and MockServer share no TLS protocol version or cipher suite";
        } else if (messages.contains("no_certificate") || messages.contains("certificate_required")) {
            return "the client sent no certificate, which mutual TLS requires";
        } else {
            return "unknown";
        }
    }

    private static String allMessages(Throwable cause) {
        StringBuilder messages = new StringBuilder();
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            messages.append(cause.getMessage()).append(' ');
        }
        return messages.toString();
    }

    // the alerts of RFC 8446 section 6 a client sends when it rejects a server or its offer
    static String alertName(int alert) {
        switch (alert) {
            case 40:
                return "handshake_failure";
            case 42:
                return "bad_certificate";
            case 43:
                return "unsupported_certificate";
            case 44:
                return "certificate_revoked";
            case 45:
                return "certificate_expired";
            case 46:
                return "certificate_unknown";
            case 48:
                return "unknown_ca";
            case 112:
                return "unrecognized_name";
            case 116:
                return "certificate_required";
            case 120:
                return "no_application_protocol";
            default:
                return "another alert";
        }
    }

    private static String configuredCertificates(Configuration configuration) {
        if (configuration == null) {
            return "default";
        }
        ServerTlsSettings tlsSettings = ServerTlsSettings.of(configuration);
        if (tlsSettings == null) {
            return "default";
        }
        return "x509CertificatePath=\"" + tlsSettings.x509CertificatePath()
            + "\" certificateAuthorityCertificate=\"" + tlsSettings.certificateAuthorityCertificate() + "\"";
    }
}
