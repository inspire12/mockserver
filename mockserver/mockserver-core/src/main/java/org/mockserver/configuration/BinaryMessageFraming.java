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
    POSTGRESQL,
    /**
     * The MySQL client/server protocol: a message is one packet (a 3-byte little-endian payload length, a sequence id,
     * then the payload), or, for a payload of 16 MiB or more, all the packets that carry it.
     */
    MYSQL,
    /**
     * The Redis serialization protocol (RESP2 and RESP3): a message is one complete top-level value, nested values
     * included, or one inline command line.
     */
    REDIS,
    /**
     * A generic length prefix: a message is the length field and the bytes it counts, as the
     * binaryMessageLengthPrefix* properties describe it.
     */
    LENGTH_PREFIX
}
