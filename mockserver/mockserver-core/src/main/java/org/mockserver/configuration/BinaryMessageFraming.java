package org.mockserver.configuration;

/**
 * How MockServer finds where one message ends on a binary (non-HTTP) connection, before matching, forwarding or
 * logging it.
 */
public enum BinaryMessageFraming {
    /**
     * No protocol's framing: everything one read loop delivers is one message, up to 256 KiB. A message that arrives
     * over time can be split, and messages sent without waiting for a reply can be joined.
     */
    RAW,
    /**
     * The PostgreSQL frontend/backend protocol (version 3): a message is exactly what its length field says, so a
     * message read over several reads is one message, and messages read together are separate messages.
     */
    POSTGRESQL
}
