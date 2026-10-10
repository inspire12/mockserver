package org.mockserver.serialization.model;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

/**
 * Server-to-client WebSocket notice that a paused breakpoint item was resolved by
 * MockServer itself rather than by the owning client, so the client must stop
 * offering it for resolution. A reply the client sends for it afterwards is ignored.
 *
 * <ul>
 *     <li>{@code correlationId} — the id of the paused request, response or stream frame</li>
 *     <li>{@code reason} — {@code "TIMEOUT"} (auto-continued after
 *         {@code breakpointTimeoutMillis}) or {@code "STREAM_ENDED"} (the stream ended
 *         before the frame could be delivered, so it was dropped)</li>
 *     <li>{@code message} — a human-readable description of what happened</li>
 * </ul>
 *
 * <p>Clients that do not recognise this message type may ignore it.
 */
public class BreakpointReleasedDTO extends ObjectWithReflectiveEqualsHashCodeToString {

    public static final String REASON_TIMEOUT = "TIMEOUT";
    public static final String REASON_STREAM_ENDED = "STREAM_ENDED";

    private String correlationId;
    private String reason;
    private String message;

    public String getCorrelationId() {
        return correlationId;
    }

    public BreakpointReleasedDTO setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }

    public String getReason() {
        return reason;
    }

    public BreakpointReleasedDTO setReason(String reason) {
        this.reason = reason;
        return this;
    }

    public String getMessage() {
        return message;
    }

    public BreakpointReleasedDTO setMessage(String message) {
        this.message = message;
        return this;
    }
}
