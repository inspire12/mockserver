package org.mockserver.netty.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.mockserver.logging.MockServerLogger;

/**
 * Cuts a binary connection into the messages of the MySQL client/server protocol, as a client sends them. A packet is
 * a 3-byte little-endian payload length, a 1-byte sequence id, then the payload. A payload of 0xFFFFFF bytes or more
 * is sent as several packets, each full one followed by the next, ending with one shorter than 0xFFFFFF (possibly
 * empty): those packets, headers included, are one message, as they carry one command. Any other packet is a
 * message of its own.
 * <p>
 * A TLS handshake is looked for only straight after an SSLRequest (a 32-byte payload, sequence id 1, with the
 * CLIENT_SSL capability), as a MySQL packet at a message boundary can start with bytes that look like one.
 */
public class MysqlMessageFramer extends BinaryMessageFramer {

    static final int HEADER_BYTES = 4;
    static final int MAX_PACKET_PAYLOAD = 0xFFFFFF;
    static final int SSL_REQUEST_PAYLOAD = 32;
    static final int CLIENT_SSL = 0x00000800;
    private static final int SSL_REQUEST_BYTES = HEADER_BYTES + SSL_REQUEST_PAYLOAD;

    private boolean sslRequested;

    public MysqlMessageFramer(int maxMessageBytes, MockServerLogger mockServerLogger) {
        super(maxMessageBytes, mockServerLogger, "MySQL");
    }

    @Override
    protected long messageBytes(ChannelHandlerContext ctx, ByteBuf in) {
        int start = in.readerIndex();
        long offset = 0;
        int previousSequence = -1;
        while (in.readableBytes() >= offset + HEADER_BYTES) {
            int payload = in.getUnsignedMediumLE(start + (int) offset);
            int sequence = in.getUnsignedByte(start + (int) offset + 3);
            if (previousSequence >= 0 && sequence != ((previousSequence + 1) & 0xFF)) {
                refuse(ctx, in, "a continuation packet with sequence id " + sequence + " after sequence id " + previousSequence);
                return NOT_YET_KNOWN;
            }
            long packetEnd = offset + HEADER_BYTES + payload;
            if (payload < MAX_PACKET_PAYLOAD) {
                return packetEnd;
            }
            if (packetEnd + HEADER_BYTES > maxMessageBytes()) {
                refuse(ctx, in, "a message of more than " + packetEnd + " bytes, over the limit of " + maxMessageBytes() + " bytes (maxRequestBodySize)");
                return NOT_YET_KNOWN;
            }
            offset = packetEnd;
            previousSequence = sequence;
        }
        return NOT_YET_KNOWN;
    }

    @Override
    protected void messageTaken(ByteBuf message) {
        sslRequested = message.readableBytes() == SSL_REQUEST_BYTES && isSslRequest(message, message.readerIndex());
    }

    /**
     * A client sends its ClientHello straight after an SSLRequest, without waiting for a reply, so the two can be read
     * together.
     */
    @Override
    public int bytesBeforeTlsMayStart(ByteBuf in, int available) {
        return atMessageBoundary() && available > SSL_REQUEST_BYTES && isSslRequest(in, in.readerIndex()) ? SSL_REQUEST_BYTES : 0;
    }

    private static boolean isSslRequest(ByteBuf bytes, int start) {
        return bytes.getUnsignedMediumLE(start) == SSL_REQUEST_PAYLOAD
            && bytes.getUnsignedByte(start + 3) == 1
            && (bytes.getIntLE(start + HEADER_BYTES) & CLIENT_SSL) != 0;
    }

    @Override
    protected boolean protocolAllowsTlsHere() {
        return sslRequested;
    }
}
