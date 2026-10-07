package org.mockserver.exception;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.socket.ChannelOutputShutdownException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslClosedEngineException;
import io.netty.util.internal.OutOfDirectMemoryError;
import io.netty.util.internal.PlatformDependent;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.mockserver.httpclient.SocketCommunicationException;
import org.mockserver.httpclient.SocketConnectionException;
import org.mockserver.log.model.LogEntry;
import org.mockserver.log.model.RedactedThrowable;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.SniHandler;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.security.SignatureException;
import java.security.cert.CertPathValidatorException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static org.slf4j.event.Level.WARN;

public class ExceptionHandling {

    static MockServerLogger mockServerLogger = new MockServerLogger();

    private static final Pattern IGNORABLE_CLASS_IN_STACK = Pattern.compile("^.*(?:Socket|Datagram|Sctp|Udt)Channel.*$");
    private static final Pattern IGNORABLE_ERROR_MESSAGE = Pattern.compile("^.*(?:connection.*(?:reset|closed|abort|broken)|broken.*pipe).*$", Pattern.CASE_INSENSITIVE);


    public static <T> T handleThrowable(CompletableFuture<T> future, long timeout, TimeUnit unit) {
        try {
            return future.get(timeout, unit);
        } catch (Throwable throwable) {
            if (MockServerLogger.isEnabled(WARN) && mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(WARN)
                        .setMessageFormat(throwable.getMessage())
                        .setThrowable(throwable)
                );
            }
            throw new RuntimeException(throwable);
        }
    }

    public static <T> T handleThrowable(Callable<T> callable) {
        try {
            return callable.call();
        } catch (Throwable throwable) {
            if (MockServerLogger.isEnabled(WARN) && mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(WARN)
                        .setMessageFormat(throwable.getMessage())
                        .setThrowable(throwable)
                );
            }
            throw new RuntimeException(throwable);
        }
    }

    public static void swallowThrowable(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable throwable) {
            if (MockServerLogger.isEnabled(WARN) && mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(WARN)
                        .setMessageFormat(throwable.getMessage())
                        .setThrowable(throwable)
                );
            }
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }

    @FunctionalInterface
    public interface ThrowingConsumer<T> extends Consumer<T> {

        @Override
        default void accept(final T elem) {
            try {
                acceptThrows(elem);
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }

        void acceptThrows(T elem) throws Exception;
    }

    /**
     * Returns a log suffix identifying the SNI hostname of the failed connection, formatted as
     * {@code " (SNI: <host>)"}, using the first non-null SNI hostname found across the given channels,
     * or {@code ""} when none of the channels carried an SNI hostname. Several channels can be passed
     * (e.g. relay handlers hold both an upstream and a downstream channel) so the SNI is found
     * whichever channel recorded it. Null-safe — a {@code null} array, or {@code null} elements within
     * it, are skipped.
     *
     * @param channels the channels to search for a recorded SNI hostname
     * @return {@code " (SNI: <host>)"} for the first hostname found, otherwise {@code ""}
     */
    public static String sniDescription(Channel... channels) {
        if (channels != null) {
            for (Channel channel : channels) {
                String sniHostname = SniHandler.getSniHostname(channel);
                if (sniHostname != null) {
                    return " (SNI: " + sniHostname + ")";
                }
            }
        }
        return "";
    }

    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    public static void closeOnFlush(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    public static final String DIRECT_MEMORY_LIMIT_REACHED = "direct memory limit (io.netty.maxDirectMemory) reached"
        + " - raise it with -XX:MaxDirectMemorySize or -Dio.netty.maxDirectMemory - closing connection ";

    /**
     * True when Netty refused a buffer because its direct-memory limit ({@code io.netty.maxDirectMemory}) was
     * reached. The connection that happened to allocate next fails, which is not necessarily the one holding
     * the memory.
     */
    public static boolean directMemoryLimitReached(Throwable throwable) {
        return ExceptionUtils.indexOfType(throwable, OutOfDirectMemoryError.class) >= 0;
    }

    /**
     * returns true is the exception was caused by the connection being closed
     */
    public static boolean connectionClosedException(Throwable throwable) {
        String message = String.valueOf(throwable.getMessage()).toLowerCase();

        // is ssl exception
        if (throwable.getCause() instanceof SSLException || throwable instanceof DecoderException || throwable instanceof NotSslRecordException) {
            return false;
        }

        // first try to match connection reset / broke peer based on the regex.
        // This is the fastest way but may fail on different jdk impls or OS's
        if (IGNORABLE_ERROR_MESSAGE.matcher(message).matches()) {
            return false;
        }

        // Inspect the StackTraceElements to see if it was a connection reset / broken pipe or not
        StackTraceElement[] elements = throwable.getStackTrace();
        for (StackTraceElement element : elements) {
            String classname = element.getClassName();
            String methodname = element.getMethodName();

            // skip all classes that belong to the io.netty package
            if (classname.startsWith("io.netty.")) {
                continue;
            }

            // check if the method name is read if not skip it
            if (!"read".equals(methodname)) {
                continue;
            }

            // This will also match against SocketInputStream which is used by openjdk 7 and maybe
            // also others
            if (IGNORABLE_CLASS_IN_STACK.matcher(classname).matches()) {
                return false;
            }

            try {
                // No match by now. Try to load the class via classloader and inspect it.
                // This is mainly done as other JDK implementations may differ in name of
                // the impl.
                Class<?> clazz = PlatformDependent.getClassLoader(ExceptionHandling.class).loadClass(classname);

                if (SocketChannel.class.isAssignableFrom(clazz)
                    || DatagramChannel.class.isAssignableFrom(clazz)) {
                    return false;
                }

                // also match against SctpChannel via String matching as it may not present.
                if (PlatformDependent.javaVersion() >= 7
                    && "com.sun.nio.sctp.SctpChannel".equals(clazz.getSuperclass().getName())) {
                    return false;
                }
            } catch (ClassNotFoundException e) {
                // This should not happen just ignore
            }
        }
        return true;
    }

    /**
     * returns true if the exception is a genuine SSL or decoder fault, i.e. the SSL/decoder subset
     * that {@link #connectionClosedException(Throwable)} returns false for at lines 123-124 (an
     * {@link SSLException} cause, a {@link DecoderException}, or a {@link NotSslRecordException}).
     * Note that {@code connectionClosedException} also returns false for benign connection resets
     * via its message-regex and stack-trace inspection; those are NOT matched here. Callers can use
     * this to surface real SSL/decoder faults at WARN rather than dropping them silently alongside
     * benign connection closes.
     */
    public static boolean isSslOrDecoderFault(Throwable throwable) {
        // mirrors the SSL/decoder predicate at connectionClosedException line 123. The
        // NotSslRecordException check is NOT redundant: it extends SSLException (not
        // DecoderException), and the first check inspects getCause() rather than the throwable
        // itself, so a directly-thrown NotSslRecordException (null/non-SSL cause) is only caught here.
        return throwable.getCause() instanceof SSLException
            || throwable instanceof DecoderException
            || throwable instanceof NotSslRecordException;
    }

    /**
     * returns true if a write failed because its connection had already closed, because the connection's output had
     * ended (as a failed write ends it, see {@link org.mockserver.socket.ReadAfterFailedWrite}), or because its client
     * had closed its TLS session (the socket may stay open a moment longer)
     */
    public static boolean socketClosedException(Throwable throwable) {
        return throwable instanceof ClosedChannelException || throwable instanceof ChannelOutputShutdownException || throwable instanceof ClosedSelectorException || throwable instanceof SslClosedEngineException;
    }

    /**
     * returns true if a write to a client failed because the client has gone: its connection or TLS session closed,
     * or its connection reset or broken, as {@link #connectionClosedException(Throwable)} classifies it
     */
    public static boolean clientGoneException(Throwable throwable) {
        return socketClosedException(throwable) || !(isSslOrDecoderFault(throwable) || connectionClosedException(throwable));
    }

    /**
     * the exception's class and, when it has one, its message, such as {@code IOException: Broken pipe}: a closed
     * channel's exception has no message
     */
    public static String causeDescription(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    /**
     * The most of a fault's message that a log entry carries.
     */
    public static final int MAX_FAULT_MESSAGE_LENGTH = 256;

    /**
     * What Netty's message for bytes that are not a TLS record says before its hex dump of every byte read.
     */
    private static final String NOT_A_TLS_RECORD = "not an SSL/TLS record: ";

    /**
     * A fault's message as a log entry may carry it: at most {@link #MAX_FAULT_MESSAGE_LENGTH} characters, and with
     * the count of the bytes in place of Netty's hex dump of them. The dump is whatever the peer sent, as long as
     * the read was, and no redaction would recognise a credential in it. A message this method returned is returned
     * as it is.
     */
    public static String boundedFaultMessage(Throwable fault) {
        return boundedFaultMessage(fault.getMessage());
    }

    /**
     * As {@link #boundedFaultMessage(Throwable)}, with {@code scrub} applied to the message before it is cut: a
     * credential cut in two is no longer recognised by the redaction of a later render, so it has to be masked first.
     */
    public static String boundedFaultMessage(Throwable fault, UnaryOperator<String> scrub) {
        return scrubbedAndBounded(fault.getMessage(), scrub);
    }

    /**
     * A fault's simple class name and its {@link #boundedFaultMessage(Throwable) bounded message}, such as
     * {@code NotSslRecordException: not an SSL/TLS record: 400 bytes}.
     */
    public static String boundedFaultDescription(Throwable fault) {
        return boundedFaultDescription(fault, UnaryOperator.identity());
    }

    /**
     * As {@link #boundedFaultDescription(Throwable)}, with {@code scrub} applied to the message before it is cut.
     */
    public static String boundedFaultDescription(Throwable fault, UnaryOperator<String> scrub) {
        String message = boundedFaultMessage(fault, scrub);
        return fault.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    /**
     * As {@link #boundedFaultDescription(Throwable)}, followed by the root cause's when the fault's message does not
     * already say it: OpenSSL reports a failed handshake as {@code General OpenSslEngine problem}, with the reason,
     * such as the trust manager's, as its cause.
     */
    public static String boundedFaultDescriptionWithRootCause(Throwable fault) {
        return boundedFaultDescriptionWithRootCause(fault, UnaryOperator.identity());
    }

    /**
     * As {@link #boundedFaultDescriptionWithRootCause(Throwable)}, with {@code scrub} applied to each message before
     * it is cut.
     */
    public static String boundedFaultDescriptionWithRootCause(Throwable fault, UnaryOperator<String> scrub) {
        String description = boundedFaultDescription(fault, scrub);
        Throwable root = ExceptionUtils.getRootCause(fault);
        if (root != null && root != fault && root.getMessage() != null && (fault.getMessage() == null || !fault.getMessage().contains(root.getMessage()))) {
            description += "; caused by " + boundedFaultDescription(root, scrub);
        }
        return description;
    }

    /**
     * @return the {@link SSLException}, of any subtype, that {@code throwable} is or was caused by, or null
     */
    public static SSLException sslCause(Throwable throwable) {
        return ExceptionUtils.throwableOfType(throwable, SSLException.class);
    }

    /**
     * As {@link #sslCause(Throwable)}, except null when that {@link SSLException} was itself caused by an I/O failure
     * that is not TLS's: Netty reports a write that failed under the handshake, such as a proxy refusing the tunnel
     * or a closed connection, as {@code failure when writing TLS control frames}, and that is a connection failure.
     */
    public static SSLException tlsFailure(Throwable throwable) {
        SSLException ssl = sslCause(throwable);
        if (ssl != null) {
            for (Throwable cause : ExceptionUtils.getThrowableList(ssl.getCause())) {
                if (cause instanceof IOException && !(cause instanceof SSLException)) {
                    return null;
                }
            }
        }
        return ssl;
    }

    private static final String UPSTREAM_CLOSED_DURING_TLS_HANDSHAKE = "upstream closed the connection during the TLS handshake";

    /**
     * What a failed TLS handshake with an upstream failed with, as it is reported: {@code cause}, except that a
     * connection that closed while the handshake was in progress, which Netty reports as a
     * {@link ClosedChannelException} with a suppressed {@link SSLHandshakeException}, is an
     * {@link SSLHandshakeException} saying the upstream closed it.
     */
    public static Throwable upstreamHandshakeFailure(Throwable cause) {
        if (cause instanceof ClosedChannelException && Arrays.stream(cause.getSuppressed()).anyMatch(SSLHandshakeException.class::isInstance)) {
            SSLHandshakeException closed = new SSLHandshakeException(UPSTREAM_CLOSED_DURING_TLS_HANDSHAKE);
            // a stack trace would point here, not at the close
            closed.setStackTrace(new StackTraceElement[0]);
            return closed;
        }
        return cause;
    }

    private static String scrubbedAndBounded(String message, UnaryOperator<String> scrub) {
        return message == null ? null : boundedFaultMessage(scrub.apply(message));
    }

    private static String boundedFaultMessage(String message) {
        if (message == null) {
            return null;
        }
        int dump = message.indexOf(NOT_A_TLS_RECORD);
        if (dump >= 0 && isHexDump(message, dump + NOT_A_TLS_RECORD.length())) {
            dump += NOT_A_TLS_RECORD.length();
            message = message.substring(0, dump) + (message.length() - dump) / 2 + " bytes";
        }
        return StringUtils.abbreviate(message, MAX_FAULT_MESSAGE_LENGTH);
    }

    /**
     * Whether the rest of the message is what {@code ByteBufUtil.hexDump} writes: two lower-case hex digits a byte.
     */
    private static boolean isHexDump(String message, int from) {
        for (int i = from; i < message.length(); i++) {
            if (Character.digit(message.charAt(i), 16) < 0 || Character.isUpperCase(message.charAt(i))) {
                return false;
            }
        }
        return message.length() > from && (message.length() - from) % 2 == 0;
    }

    /**
     * The throwable to attach to a fault's log entry: {@code fault} itself, unless a message of it, of a cause or of
     * a suppressed throwable is not what {@link #boundedFaultMessage(Throwable)} gives, in which case a copy with
     * those messages, each after the name of its class, and the same stack traces. Such a copy is returned as it is.
     */
    public static Throwable boundedFault(Throwable fault) {
        return boundedFault(fault, UnaryOperator.identity());
    }

    /**
     * As {@link #boundedFault(Throwable)}, with {@code scrub} applied to each message before it is cut, and a copy
     * also when {@code scrub} changes a message that needed no cut.
     */
    public static Throwable boundedFault(Throwable fault, UnaryOperator<String> scrub) {
        UnaryOperator<String> rewrite = message -> scrubbedAndBounded(message, scrub);
        return hasUnboundedMessage(fault, rewrite, Collections.newSetFromMap(new IdentityHashMap<>()))
            ? RedactedThrowable.of(fault, rewrite)
            : fault;
    }

    private static boolean hasUnboundedMessage(Throwable throwable, UnaryOperator<String> rewrite, Set<Throwable> visited) {
        // visited by identity: a cause or suppressed graph can be cyclic
        if (throwable == null || !visited.add(throwable)) {
            return false;
        }
        // a copy's message leads with a class name, which is no part of what was bounded
        String message = throwable instanceof RedactedThrowable ? ((RedactedThrowable) throwable).getRewrittenMessage() : throwable.getMessage();
        if (message != null && !message.equals(rewrite.apply(message))) {
            return true;
        }
        for (Throwable suppressed : throwable.getSuppressed()) {
            if (hasUnboundedMessage(suppressed, rewrite, visited)) {
                return true;
            }
        }
        return hasUnboundedMessage(throwable.getCause(), rewrite, visited);
    }

    private static final List<Class<? extends Exception>> SSL_HANDSHAKE_FAILURE_CLASSES = Arrays.asList(SSLException.class, SSLHandshakeException.class, CertPathValidatorException.class, SignatureException.class);
    public static boolean sslHandshakeException(Throwable throwable) {
        for (Class<? extends Throwable> cause : getCauses(throwable)) {
            if (SSL_HANDSHAKE_FAILURE_CLASSES.contains(cause)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a forward failed for its upstream connection: it could not be made, failed, was closed, or no response
     * came in time. Such a failure's message says what happened, so its log entry needs no stack trace.
     */
    public static boolean upstreamConnectionFailure(Throwable failure) {
        return ExceptionUtils.indexOfType(failure, IOException.class) >= 0
            || ExceptionUtils.indexOfType(failure, SocketConnectionException.class) >= 0
            || ExceptionUtils.indexOfType(failure, SocketCommunicationException.class) >= 0
            || ExceptionUtils.indexOfType(failure, TimeoutException.class) >= 0;
    }

    private static final List<Class<? extends Exception>> CONNECTION_EXCEPTION_CLASSES = Arrays.asList(SocketConnectionException.class, ConnectException.class);
    public static boolean connectionException(Throwable throwable) {
        for (Class<? extends Throwable> cause : getCauses(throwable)) {
            if (CONNECTION_EXCEPTION_CLASSES.contains(cause)) {
                return true;
            }
        }
        return false;
    }

    private static List<Class<? extends Throwable>> getCauses(final Throwable throwable) {
        if (throwable.getCause() != null) {
            if (throwable.getClass().equals(throwable.getCause().getClass())) {
                return new ArrayList<>(Collections.singletonList(throwable.getClass()));
            } else {
                final List<Class<? extends Throwable>> causes = getCauses(throwable.getCause());
                causes.add(throwable.getClass());
                return causes;
            }
        } else {
            return new ArrayList<>(Collections.singletonList(throwable.getClass()));
        }
    }

}
