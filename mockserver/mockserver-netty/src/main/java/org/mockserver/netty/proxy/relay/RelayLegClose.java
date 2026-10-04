package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;

/**
 * Closes one leg of a CONNECT/SOCKS relay at its socket, not through its pipeline, once the relay has no further use
 * for it. Asked to close, an HTTP/2 connection handler sends a {@code GOAWAY} and keeps the connection open while it has
 * active streams, for up to Netty's graceful-shutdown timeout (30 s), and a TLS handler waits for its
 * {@code close_notify} to be taken. Closing the socket fails what is queued and fires {@code channelInactive}.
 */
final class RelayLegClose {

    private RelayLegClose() {
    }

    static void now(Channel channel) {
        if (channel.eventLoop().inEventLoop()) {
            closeSocket(channel);
        } else {
            channel.eventLoop().execute(() -> closeSocket(channel));
        }
    }

    /**
     * Closes the socket once the leg's outbound buffer has been flushed. HTTP/2 DATA waiting for flow-control window
     * is not in that buffer and is failed by the close, so a caller waits for such a write to complete first.
     */
    static void afterFlush(Channel channel) {
        if (channel.isActive()) {
            channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(future -> now(channel));
        } else {
            now(channel);
        }
    }

    private static void closeSocket(Channel channel) {
        channel.unsafe().close(channel.unsafe().voidPromise());
    }
}
