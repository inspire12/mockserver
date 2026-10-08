package org.mockserver.netty.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.util.List;

/**
 * Cuts a binary connection into the messages of the PostgreSQL frontend/backend protocol (version 3), as a client
 * sends them, so that each is matched, forwarded and logged as one message however it was read. Until the startup
 * message a message is an int32 length (itself included) then an int32 code; SSLRequest, GSSENCRequest and
 * CancelRequest are such messages too and leave the next one untyped. From the startup message on a message is a
 * type byte then an int32 length (itself included). One per connection, in front of
 * {@link BinaryRequestProxyingHandler}, in place of {@link BinaryMessageGatherer}.
 * <p>
 * At most {@code maxMessageBytes} are held: a connection that declares a longer message, or a length the protocol
 * does not allow, is closed and the rest of what it sends is dropped.
 */
public class PostgresqlMessageFramer extends ByteToMessageDecoder {

    static final int SSL_REQUEST_CODE = 80877103;
    static final int GSSENC_REQUEST_CODE = 80877104;
    static final int CANCEL_REQUEST_CODE = 80877102;
    private static final int UNTYPED_HEADER_BYTES = 8;
    private static final int TYPED_HEADER_BYTES = 5;

    private final int maxMessageBytes;
    private final MockServerLogger mockServerLogger;
    private boolean typed;
    private boolean refused;

    public PostgresqlMessageFramer(int maxMessageBytes, MockServerLogger mockServerLogger) {
        this.maxMessageBytes = Math.max(1, maxMessageBytes);
        this.mockServerLogger = mockServerLogger;
    }

    /**
     * True when no part of a message is held: what is read next starts a message. Anything else is the middle of
     * one, whatever its bytes look like.
     */
    public boolean atMessageBoundary() {
        return !refused && actualReadableBytes() == 0;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (!refused && in.isReadable()) {
            int start = in.readerIndex();
            long messageBytes;
            if (typed) {
                if (in.readableBytes() < TYPED_HEADER_BYTES) {
                    return;
                }
                int length = in.getInt(start + 1);
                if (length < 4) {
                    refuse(ctx, in, "a length of " + length + " after type byte " + in.getUnsignedByte(start));
                    return;
                }
                messageBytes = 1L + length;
            } else {
                if (in.readableBytes() < 4) {
                    return;
                }
                int length = in.getInt(start);
                if (length < UNTYPED_HEADER_BYTES) {
                    refuse(ctx, in, "a startup-phase length of " + length);
                    return;
                }
                messageBytes = length;
            }
            if (messageBytes > maxMessageBytes) {
                refuse(ctx, in, "a message of " + messageBytes + " bytes, over the limit of " + maxMessageBytes + " bytes (maxRequestBodySize)");
                return;
            }
            if (in.readableBytes() < messageBytes) {
                return;
            }
            if (!typed) {
                int code = in.getInt(start + 4);
                typed = code != SSL_REQUEST_CODE && code != GSSENC_REQUEST_CODE && code != CANCEL_REQUEST_CODE;
            }
            out.add(in.readRetainedSlice((int) messageBytes));
        }
        if (refused) {
            in.skipBytes(in.readableBytes());
        }
    }

    private void refuse(ChannelHandlerContext ctx, ByteBuf in, String declared) {
        refused = true;
        in.skipBytes(in.readableBytes());
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("closing binary connection from:{}because it declared{}which PostgreSQL message framing (binaryMessageFraming) does not accept")
                    .setArguments(ctx.channel().remoteAddress(), declared)
            );
        }
        ctx.close();
    }
}
