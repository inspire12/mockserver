package org.mockserver.netty.proxy.relay;

/**
 * What a relay does with the upstream's reply to one message it forwards: relays it to the client (the default),
 * drops it, or drops it and writes other bytes to the client in its place once it has ended.
 */
public final class UpstreamReply {

    public static final UpstreamReply RELAY = new UpstreamReply(false, null, 0);
    private static final UpstreamReply DROP = new UpstreamReply(true, null, 0);

    private final boolean dropped;
    private final byte[] replacement;
    private final long delayMillis;

    private UpstreamReply(boolean dropped, byte[] replacement, long delayMillis) {
        this.dropped = dropped;
        this.replacement = replacement;
        this.delayMillis = delayMillis;
    }

    public static UpstreamReply drop() {
        return DROP;
    }

    /**
     * @param replacement written in place of the reply; null or empty writes nothing, so the reply is only dropped
     * @param delayMillis the replacement is not written before this long after its message was read
     */
    public static UpstreamReply replaceWith(byte[] replacement, long delayMillis) {
        return new UpstreamReply(true, replacement != null && replacement.length > 0 ? replacement : null, Math.max(0, delayMillis));
    }

    boolean relayed() {
        return !dropped;
    }

    byte[] replacement() {
        return replacement;
    }

    long delayMillis() {
        return delayMillis;
    }

    /**
     * One reply standing for several messages' (PostgreSQL's extended-query messages up to their Sync): dropped if
     * any is, and with the replacements of each in message order.
     */
    UpstreamReply and(UpstreamReply next) {
        if (!next.dropped) {
            return this;
        }
        if (!dropped) {
            return next;
        }
        byte[] joined = replacement;
        if (next.replacement != null) {
            if (joined == null) {
                joined = next.replacement;
            } else {
                joined = new byte[replacement.length + next.replacement.length];
                System.arraycopy(replacement, 0, joined, 0, replacement.length);
                System.arraycopy(next.replacement, 0, joined, replacement.length, next.replacement.length);
            }
        }
        return new UpstreamReply(true, joined, Math.max(delayMillis, next.delayMillis));
    }
}
