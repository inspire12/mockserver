package org.mockserver.netty.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.util.List;

/**
 * Cuts a binary connection into the messages of a protocol, as a client sends them, so that each is matched,
 * forwarded and logged as one message however it was read. One per connection, in front of
 * {@link BinaryRequestProxyingHandler}, in place of {@link BinaryMessageGatherer}.
 * <p>
 * At most {@code maxMessageBytes} are held: a connection that declares a longer message, or sends bytes the protocol
 * does not allow, is closed and the rest of what it sends is dropped. The part of a message held when the connection
 * closes is released with the cumulation.
 */
public abstract class BinaryMessageFramer extends ByteToMessageDecoder {

    /** What {@link #messageBytes} answers while the bytes held cannot yet say how long the message is. */
    protected static final long NOT_YET_KNOWN = -1;

    private final int maxMessageBytes;
    private final MockServerLogger mockServerLogger;
    private final String framing;
    private boolean refused;

    protected BinaryMessageFramer(int maxMessageBytes, MockServerLogger mockServerLogger, String framing) {
        this.maxMessageBytes = Math.max(1, maxMessageBytes);
        this.mockServerLogger = mockServerLogger;
        this.framing = framing;
    }

    /**
     * The framer binaryMessageFraming names, or null for RAW.
     */
    public static BinaryMessageFramer forConfiguration(Configuration configuration, MockServerLogger mockServerLogger) {
        int maxMessageBytes = configuration.maxRequestBodySize();
        switch (configuration.binaryMessageFraming()) {
            case POSTGRESQL:
                return new PostgresqlMessageFramer(maxMessageBytes, mockServerLogger);
            case MYSQL:
                return new MysqlMessageFramer(maxMessageBytes, mockServerLogger);
            case REDIS:
                return new RedisMessageFramer(maxMessageBytes, mockServerLogger);
            case LENGTH_PREFIX:
                return new LengthPrefixMessageFramer(
                    maxMessageBytes,
                    mockServerLogger,
                    configuration.binaryMessageLengthPrefixOffset(),
                    configuration.binaryMessageLengthPrefixBytes(),
                    configuration.binaryMessageLengthPrefixByteOrder(),
                    configuration.binaryMessageLengthIncludesPrefix()
                );
            default:
                return null;
        }
    }

    protected int maxMessageBytes() {
        return maxMessageBytes;
    }

    /**
     * True when no part of a message is held: what is read next starts a message. Anything else is the middle of
     * one, whatever its bytes look like.
     */
    public boolean atMessageBoundary() {
        return !refused && actualReadableBytes() == 0;
    }

    /**
     * Whether a TLS handshake may begin with the next byte read: only at a message boundary, and only where the
     * protocol lets one begin.
     */
    public boolean tlsMayStartHere() {
        return atMessageBoundary() && protocolAllowsTlsHere();
    }

    protected boolean protocolAllowsTlsHere() {
        return true;
    }

    /**
     * How many of the {@code available} bytes from {@code in}'s reader index, read in the clear, end a message after
     * which a client may start a TLS handshake without waiting for a reply, when more bytes follow; otherwise 0.
     */
    public int bytesBeforeTlsMayStart(ByteBuf in, int available) {
        return 0;
    }

    /**
     * How many bytes the message that starts at the reader index has, {@link #NOT_YET_KNOWN} while the bytes held
     * cannot say, or anything after {@link #refuse}. Must not move the reader index.
     */
    protected abstract long messageBytes(ChannelHandlerContext ctx, ByteBuf in);

    /**
     * Called with each whole message before it is passed on.
     */
    protected void messageTaken(ByteBuf message) {
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (refused) {
            in.skipBytes(in.readableBytes());
            return;
        }
        // a refusal skips what is held and closes the connection, which can release it: nothing more is read
        while (in.isReadable()) {
            long messageBytes = messageBytes(ctx, in);
            if (refused) {
                return;
            }
            if (messageBytes == NOT_YET_KNOWN) {
                if (in.readableBytes() > maxMessageBytes) {
                    refuse(ctx, in, "more than " + maxMessageBytes + " bytes (maxRequestBodySize) without the end of a message");
                }
                return;
            }
            if (messageBytes > maxMessageBytes) {
                refuse(ctx, in, "a message of " + messageBytes + " bytes, over the limit of " + maxMessageBytes + " bytes (maxRequestBodySize)");
                return;
            }
            if (in.readableBytes() < messageBytes) {
                return;
            }
            ByteBuf message = in.readRetainedSlice((int) messageBytes);
            messageTaken(message);
            out.add(message);
        }
    }

    protected final void refuse(ChannelHandlerContext ctx, ByteBuf in, String declared) {
        refused = true;
        in.skipBytes(in.readableBytes());
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("closing binary connection from:{}because it declared{}which{}message framing (binaryMessageFraming) does not accept")
                    .setArguments(ctx.channel().remoteAddress(), declared, framing)
            );
        }
        ctx.close();
    }
}
