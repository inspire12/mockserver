package org.mockserver.netty.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.mockserver.logging.MockServerLogger;

/**
 * Cuts a binary connection into the messages of the Redis serialization protocol (RESP2 and RESP3), as a client sends
 * them: one complete top-level value with everything nested in it, or, when the first byte names no RESP type, one
 * inline command up to and including its line feed. Bulk strings, bulk errors and verbatim strings are skipped by
 * their declared length; arrays, sets, pushes, maps and attributes count their values; streamed strings and
 * aggregates ({@code ?}) run to their end marker.
 * <p>
 * The value is parsed as it arrives, so what has been parsed is not parsed again when more is read. Nesting deeper
 * than {@link #MAX_DEPTH}, a length or count that is not a number, a value with no type byte inside another, or a
 * line or bulk string not ended by CRLF closes the connection.
 */
public class RedisMessageFramer extends BinaryMessageFramer {

    static final int MAX_DEPTH = 64;
    private static final long STREAMED = -1;
    private static final long NULL_LENGTH = -2;
    private static final int MAX_NUMBER_DIGITS = 18;
    // the shortest RESP value, a type byte then CRLF
    private static final int MIN_VALUE_BYTES = 3;

    // the message so far, from the reader index: bytes parsed, and where a line feed was last looked for in vain
    private int parsed;
    private int lineSearchedTo;
    private boolean started;
    private boolean complete;
    private boolean inStreamedString;
    // the aggregates open around the next value: how many values each still expects, or STREAMED
    private final long[] remaining = new long[MAX_DEPTH];
    private final boolean[] attribute = new boolean[MAX_DEPTH];
    private int depth;

    public RedisMessageFramer(int maxMessageBytes, MockServerLogger mockServerLogger) {
        super(maxMessageBytes, mockServerLogger, "Redis");
    }

    @Override
    protected long messageBytes(ChannelHandlerContext ctx, ByteBuf in) {
        int start = in.readerIndex();
        while (!complete) {
            int lineEnd = lineFeed(in, start);
            if (lineEnd < 0) {
                return NOT_YET_KNOWN;
            }
            byte type = in.getByte(start + parsed);
            if (!started && !inStreamedString && !isValueType(type)) {
                // an inline command: its line is the message
                return lineEnd + 1L;
            }
            started = true;
            if (lineEnd < parsed + 2 || in.getByte(start + lineEnd - 1) != '\r') {
                refuse(ctx, in, "a line not ended by CRLF");
                return NOT_YET_KNOWN;
            }
            int lineStart = parsed + 1;
            int lineLength = lineEnd - 1 - lineStart;
            int next = lineEnd + 1;
            if (inStreamedString) {
                if (type != ';') {
                    refuse(ctx, in, "type byte " + (type & 0xFF) + " in a streamed string, not a ';' chunk");
                    return NOT_YET_KNOWN;
                }
                long chunk = number(ctx, in, start + lineStart, lineLength, false);
                if (chunk < 0) {
                    return NOT_YET_KNOWN;
                }
                if (chunk == 0) {
                    inStreamedString = false;
                    parsed = next;
                    valueComplete();
                } else if (!skipBulk(ctx, in, start, next, chunk)) {
                    return NOT_YET_KNOWN;
                }
                continue;
            }
            switch (type) {
                case '$':
                case '!':
                case '=':
                    if (type == '$' && isStreamedLength(in, start + lineStart, lineLength)) {
                        inStreamedString = true;
                        parsed = next;
                        continue;
                    }
                    long length = number(ctx, in, start + lineStart, lineLength, type == '$');
                    if (length == NULL_LENGTH) {
                        parsed = next;
                        valueComplete();
                    } else if (length < 0 || !skipBulk(ctx, in, start, next, length)) {
                        return NOT_YET_KNOWN;
                    } else {
                        valueComplete();
                    }
                    break;
                case '*':
                case '~':
                case '>':
                case '%':
                case '|':
                    if (!openAggregate(ctx, in, start, type, lineStart, lineLength, next)) {
                        return NOT_YET_KNOWN;
                    }
                    break;
                case '.':
                    if (depth == 0 || remaining[depth - 1] != STREAMED) {
                        refuse(ctx, in, "an end marker '.' outside a streamed aggregate");
                        return NOT_YET_KNOWN;
                    }
                    depth--;
                    parsed = next;
                    valueComplete();
                    break;
                default:
                    if (!isValueType(type)) {
                        refuse(ctx, in, "type byte " + (type & 0xFF) + ", which names no RESP type, inside an aggregate");
                        return NOT_YET_KNOWN;
                    }
                    // a simple string, error, integer, null, boolean, double or big number: its line is the value
                    parsed = next;
                    valueComplete();
            }
        }
        return parsed;
    }

    private boolean openAggregate(ChannelHandlerContext ctx, ByteBuf in, int start, byte type, int lineStart, int lineLength, int next) {
        boolean streamed = type != '|' && isStreamedLength(in, start + lineStart, lineLength);
        long count = streamed ? STREAMED : number(ctx, in, start + lineStart, lineLength, type == '*');
        if (count == NULL_LENGTH || count == 0) {
            parsed = next;
            if (type != '|') {
                valueComplete();
            }
            return true;
        }
        if (count < 0 && !streamed) {
            return false;
        }
        long values = streamed ? STREAMED : (type == '%' || type == '|') ? 2 * count : count;
        if (!streamed && next + values * MIN_VALUE_BYTES > maxMessageBytes()) {
            refuse(ctx, in, "an aggregate of " + values + " values, so a message of at least " + (next + values * MIN_VALUE_BYTES) + " bytes, over the limit of " + maxMessageBytes() + " bytes (maxRequestBodySize)");
            return false;
        }
        if (depth == MAX_DEPTH) {
            refuse(ctx, in, "values nested more than " + MAX_DEPTH + " deep");
            return false;
        }
        remaining[depth] = values;
        attribute[depth] = type == '|';
        depth++;
        parsed = next;
        return true;
    }

    /**
     * Takes a bulk body of {@code length} bytes and its CRLF, starting at {@code bodyStart}, once all of it has
     * arrived; refuses one that cannot fit or is not ended by CRLF.
     */
    private boolean skipBulk(ChannelHandlerContext ctx, ByteBuf in, int start, int bodyStart, long length) {
        long end = bodyStart + length + 2;
        if (end > maxMessageBytes()) {
            refuse(ctx, in, "a bulk string of " + length + " bytes, so a message of at least " + end + " bytes, over the limit of " + maxMessageBytes() + " bytes (maxRequestBodySize)");
            return false;
        }
        if (in.readableBytes() < end) {
            return false;
        }
        if (in.getByte(start + (int) end - 2) != '\r' || in.getByte(start + (int) end - 1) != '\n') {
            refuse(ctx, in, "a bulk string of " + length + " bytes not followed by CRLF");
            return false;
        }
        parsed = (int) end;
        return true;
    }

    private void valueComplete() {
        while (depth > 0) {
            int top = depth - 1;
            if (remaining[top] == STREAMED || --remaining[top] > 0) {
                return;
            }
            depth--;
            if (attribute[depth]) {
                // an attribute's map has ended: the value it describes follows, in the attribute's place
                return;
            }
        }
        complete = true;
    }

    /**
     * Where the line that starts the next value ends: the index, from the reader index, of its line feed, or -1
     * while it has not arrived.
     */
    private int lineFeed(ByteBuf in, int start) {
        int from = Math.max(parsed, lineSearchedTo);
        int found = in.indexOf(start + from, start + in.readableBytes(), (byte) '\n');
        if (found < 0) {
            lineSearchedTo = in.readableBytes();
            return -1;
        }
        return found - start;
    }

    private static boolean isStreamedLength(ByteBuf in, int index, int length) {
        return length == 1 && in.getByte(index) == '?';
    }

    /**
     * A non-negative decimal of at most 18 digits, or, where {@code nullAllowed}, -1 for a RESP2 null (answered as
     * {@link #NULL_LENGTH}). Anything else is refused and answered as -1.
     */
    private long number(ChannelHandlerContext ctx, ByteBuf in, int index, int length, boolean nullAllowed) {
        if (nullAllowed && length == 2 && in.getByte(index) == '-' && in.getByte(index + 1) == '1') {
            return NULL_LENGTH;
        }
        if (length < 1 || length > MAX_NUMBER_DIGITS) {
            refuse(ctx, in, "a length or count of " + length + " characters");
            return -1;
        }
        long value = 0;
        for (int i = 0; i < length; i++) {
            byte digit = in.getByte(index + i);
            if (digit < '0' || digit > '9') {
                refuse(ctx, in, "a length or count that is not a non-negative number");
                return -1;
            }
            value = value * 10 + (digit - '0');
        }
        return value;
    }

    private static boolean isValueType(byte type) {
        switch (type) {
            case '+':
            case '-':
            case ':':
            case '$':
            case '*':
            case '_':
            case '#':
            case ',':
            case '(':
            case '!':
            case '=':
            case '%':
            case '~':
            case '>':
            case '|':
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void messageTaken(ByteBuf message) {
        parsed = 0;
        lineSearchedTo = 0;
        started = false;
        complete = false;
        inStreamedString = false;
        depth = 0;
    }
}
