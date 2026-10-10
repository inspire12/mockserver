package org.mockserver.netty.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.mockserver.logging.MockServerLogger;

import java.nio.ByteOrder;

/**
 * Cuts a binary connection into messages that each carry their own length: {@code offset} bytes, then an unsigned
 * length field of {@code prefixBytes} (1, 2, 4 or 8) in the byte order given, then the body. The length counts the
 * whole message when {@code lengthIncludesPrefix}, or only the body after the field otherwise. The bytes before the
 * field are part of the message.
 */
public class LengthPrefixMessageFramer extends BinaryMessageFramer {

    private final int offset;
    private final int prefixBytes;
    private final boolean littleEndian;
    private final boolean lengthIncludesPrefix;
    private final long headerBytes;

    public LengthPrefixMessageFramer(int maxMessageBytes, MockServerLogger mockServerLogger, int offset, int prefixBytes, ByteOrder byteOrder, boolean lengthIncludesPrefix) {
        super(maxMessageBytes, mockServerLogger, "length-prefix");
        if (prefixBytes != 1 && prefixBytes != 2 && prefixBytes != 4 && prefixBytes != 8) {
            throw new IllegalArgumentException("a length prefix has 1, 2, 4 or 8 bytes, not " + prefixBytes);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("a length prefix offset is zero or more, not " + offset);
        }
        this.offset = offset;
        this.prefixBytes = prefixBytes;
        this.littleEndian = ByteOrder.LITTLE_ENDIAN.equals(byteOrder);
        this.lengthIncludesPrefix = lengthIncludesPrefix;
        this.headerBytes = (long) offset + prefixBytes;
    }

    @Override
    protected long messageBytes(ChannelHandlerContext ctx, ByteBuf in) {
        if (in.readableBytes() < headerBytes) {
            return NOT_YET_KNOWN;
        }
        long length = length(in, in.readerIndex() + offset);
        if (length < 0) {
            refuse(ctx, in, "a length of " + Long.toUnsignedString(length) + " bytes, over the limit of " + maxMessageBytes() + " bytes (maxRequestBodySize)");
            return NOT_YET_KNOWN;
        }
        if (lengthIncludesPrefix) {
            if (length < headerBytes) {
                refuse(ctx, in, "a length of " + length + ", less than the " + headerBytes + " bytes before the body that it counts");
                return NOT_YET_KNOWN;
            }
            return length;
        }
        return length > Long.MAX_VALUE - headerBytes ? Long.MAX_VALUE : headerBytes + length;
    }

    private long length(ByteBuf in, int index) {
        switch (prefixBytes) {
            case 1:
                return in.getUnsignedByte(index);
            case 2:
                return littleEndian ? in.getUnsignedShortLE(index) : in.getUnsignedShort(index);
            case 4:
                return littleEndian ? in.getUnsignedIntLE(index) : in.getUnsignedInt(index);
            default:
                return littleEndian ? in.getLongLE(index) : in.getLong(index);
        }
    }
}
