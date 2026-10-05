package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.socket.DuplexChannel;
import io.netty.util.concurrent.ScheduledFuture;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Closes one leg of a CONNECT/SOCKS relay at its socket, not through its pipeline, once the relay has no further use
 * for it. Asked to close, an HTTP/2 connection handler sends a {@code GOAWAY} and keeps the connection open while it has
 * active streams, for up to Netty's graceful-shutdown timeout (30 s), and a TLS handler waits for its
 * {@code close_notify} to be taken. Closing the socket fails what is queued and fires {@code channelInactive}.
 */
final class RelayLegClose {

    /**
     * How long {@link #afterFlush} waits for the other end to close once it has been sent the end of the leg's output.
     */
    static final long PEER_CLOSE_WAIT_MILLIS = 5_000;

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
     * Ends the leg once its outbound buffer has been flushed (HTTP/2 DATA waiting for flow-control window is not in it
     * and fails, so a caller waits for such a write first). A socket's output is shut down and the socket closed when
     * the other end closes, as it does on reading the end of the stream, or after {@link #PEER_CLOSE_WAIT_MILLIS}.
     * Closed at once it would reset whatever the other end writes next, and MockServer's end of a loopback writes
     * while it reads (its HTTP/2 settings): a write that fails closes that end with what was flushed here unread.
     */
    static void afterFlush(Channel channel) {
        if (channel.isActive()) {
            channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(future -> endOutput(channel));
        } else {
            now(channel);
        }
    }

    private static void endOutput(Channel channel) {
        if (!(channel instanceof DuplexChannel) || !channel.isActive()) {
            closeSocket(channel);
            return;
        }
        // at the socket, as the close is: through the pipeline a TLS handler would first write its close_notify
        ((DuplexChannel) channel).shutdownOutput();
        ScheduledFuture<?> otherEndDidNotClose = channel.eventLoop().schedule(() -> closeSocket(channel), PEER_CLOSE_WAIT_MILLIS, MILLISECONDS);
        channel.closeFuture().addListener(closed -> otherEndDidNotClose.cancel(false));
    }

    private static void closeSocket(Channel channel) {
        channel.unsafe().close(channel.unsafe().voidPromise());
    }
}
