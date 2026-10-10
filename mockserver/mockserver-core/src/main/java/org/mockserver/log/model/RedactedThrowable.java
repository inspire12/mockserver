package org.mockserver.log.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.mockserver.fixture.SensitiveValueMatcher;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * A copy of a logged throwable, shown in its place when its message (or a cause's) may not be logged as it is: it
 * quotes a credential while {@code redactSecretsInLog} is enabled, or it is a fault's message that is too long or
 * dumps the bytes a peer sent. The messages are rewritten, the stack traces are the original ones, and the message
 * leads with the original class name, so every renderer (logback, {@code printStackTrace}, JSON) still says what
 * was thrown.
 */
// getters only: this is not a JDK class, so field detection would try to open Throwable's private fields
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE, getterVisibility = JsonAutoDetect.Visibility.PUBLIC_ONLY, isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public final class RedactedThrowable extends RuntimeException {

    private final String originalClassName;
    private final String rewrittenMessage;

    private RedactedThrowable(String originalClassName, String rewrittenMessage, Throwable cause) {
        super(rewrittenMessage == null ? originalClassName : originalClassName + ": " + rewrittenMessage, cause, true, true);
        this.originalClassName = originalClassName;
        this.rewrittenMessage = rewrittenMessage;
    }

    static RedactedThrowable of(Throwable original, SensitiveValueMatcher sensitiveValues) {
        return of(original, sensitiveValues::scrub);
    }

    /**
     * @param rewrite what each message of the throwable, its causes and its suppressed throwables becomes
     */
    public static RedactedThrowable of(Throwable original, UnaryOperator<String> rewrite) {
        return copy(original, rewrite, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Each throwable is copied once: a throwable met again (a cyclic cause or suppressed graph) is left out, so the
     * copy is finite and serializable, where the original would recurse.
     */
    private static RedactedThrowable copy(Throwable original, UnaryOperator<String> rewrite, Set<Throwable> visited) {
        if (original == null || !visited.add(original)) {
            return null;
        }
        // a copy of a copy names what was first thrown, and has its message rewritten without that name
        RedactedThrowable copied = original instanceof RedactedThrowable ? (RedactedThrowable) original : null;
        String className = copied != null ? copied.originalClassName : original.getClass().getName();
        String message = rewrite.apply(copied != null ? copied.rewrittenMessage : original.getMessage());
        RedactedThrowable copy = new RedactedThrowable(className, message, copy(original.getCause(), rewrite, visited));
        copy.setStackTrace(original.getStackTrace());
        for (Throwable suppressed : original.getSuppressed()) {
            RedactedThrowable suppressedCopy = copy(suppressed, rewrite, visited);
            if (suppressedCopy != null) {
                copy.addSuppressed(suppressedCopy);
            }
        }
        return copy;
    }

    /**
     * The original's stack trace is set on the copy; capturing the copy's own would only be discarded.
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }

    @JsonIgnore
    public String getOriginalClassName() {
        return originalClassName;
    }

    /**
     * @return the rewritten message of what was thrown, without the class name {@link #getMessage()} leads with
     */
    @JsonIgnore
    public String getRewrittenMessage() {
        return rewrittenMessage;
    }

    @Override
    public String toString() {
        return getMessage();
    }
}
