package org.mockserver.netty.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.mockserver.logging.MockServerLogger;

/**
 * Cuts a binary connection into the messages of the PostgreSQL frontend/backend protocol (version 3), as a client
 * sends them. Until the startup message a message is an int32 length (itself included) then an int32 code;
 * SSLRequest, GSSENCRequest and CancelRequest are such messages too and leave the next one untyped. From the startup
 * message on a message is a type byte then an int32 length (itself included).
 */
public class PostgresqlMessageFramer extends BinaryMessageFramer {

    static final int SSL_REQUEST_CODE = 80877103;
    static final int GSSENC_REQUEST_CODE = 80877104;
    static final int CANCEL_REQUEST_CODE = 80877102;
    private static final int UNTYPED_HEADER_BYTES = 8;
    private static final int TYPED_HEADER_BYTES = 5;

    private boolean typed;

    public PostgresqlMessageFramer(int maxMessageBytes, MockServerLogger mockServerLogger) {
        super(maxMessageBytes, mockServerLogger, "PostgreSQL");
    }

    @Override
    protected long messageBytes(ChannelHandlerContext ctx, ByteBuf in) {
        int start = in.readerIndex();
        if (typed) {
            if (in.readableBytes() < TYPED_HEADER_BYTES) {
                return NOT_YET_KNOWN;
            }
            int length = in.getInt(start + 1);
            if (length < 4) {
                refuse(ctx, in, "a length of " + length + " after type byte " + in.getUnsignedByte(start));
                return NOT_YET_KNOWN;
            }
            return 1L + length;
        }
        if (in.readableBytes() < 4) {
            return NOT_YET_KNOWN;
        }
        int length = in.getInt(start);
        if (length < UNTYPED_HEADER_BYTES) {
            refuse(ctx, in, "a startup-phase length of " + length);
            return NOT_YET_KNOWN;
        }
        return length;
    }

    @Override
    protected void messageTaken(ByteBuf message) {
        if (!typed) {
            int code = message.getInt(message.readerIndex() + 4);
            typed = code != SSL_REQUEST_CODE && code != GSSENC_REQUEST_CODE && code != CANCEL_REQUEST_CODE;
        }
    }
}
