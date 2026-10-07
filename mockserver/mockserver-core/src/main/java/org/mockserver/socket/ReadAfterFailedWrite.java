package org.mockserver.socket;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.ChannelOutputShutdownEvent;
import io.netty.channel.socket.DuplexChannel;
import io.netty.util.AttributeKey;

/**
 * Keeps reading an accepted connection whose output has ended because a write to it failed, which is how a write to a
 * client that has reset the connection ends. A request the client sent before resetting has reached the socket, and
 * would be lost if the channel closed with it unread, as Netty closes a channel whose write fails (its
 * {@code autoClose}). Instead the channel's output is shut down and its reads go on, so that the request is decoded,
 * recorded and matched (its response is attempted, and fails); the channel closes when its input ends, which on a reset
 * connection follows the bytes still unread, or after {@link LingeringClose#LINGER_MILLIS} at the latest. A channel
 * whose reads are paused is closed at once, as nothing would be read.
 */
public final class ReadAfterFailedWrite {

    private static final AttributeKey<Boolean> OUTPUT_ENDED_ON_PURPOSE = AttributeKey.valueOf("mockserver.outputEndedOnPurpose");

    private ReadAfterFailedWrite() {
    }

    /**
     * Call on an accepted connection, before it is read.
     */
    public static void install(Channel channel) {
        if (channel instanceof DuplexChannel) {
            channel.config().setAutoClose(false);
            channel.pipeline().addFirst(OutputEnded.INSTANCE);
        }
    }

    /**
     * Shuts the socket's output down, as its owner has finished writing, without this class taking it for a failed
     * write: whoever calls this decides when the socket closes.
     */
    public static ChannelFuture endOutput(DuplexChannel channel) {
        channel.attr(OUTPUT_ENDED_ON_PURPOSE).set(Boolean.TRUE);
        return channel.shutdownOutput();
    }

    /**
     * @return whether the channel is open with its output ended, so that it is read to the end of its input and a close
     * asked for now would drop what is still to be read. True from the moment the output ends, before the writes it
     * fails are told
     */
    public static boolean isReadingOn(Channel channel) {
        // an HTTP/2 stream's channel never has an outbound buffer
        return channel instanceof DuplexChannel && channel.isActive() && channel.unsafe().outboundBuffer() == null;
    }

    /**
     * Leaves a close asked for while {@link #isReadingOn} to the end of the channel's input, or its linger limit, and
     * completes the promise then.
     */
    public static void closeWhenInputEnds(Channel channel, ChannelPromise promise) {
        if (!promise.isVoid()) {
            channel.closeFuture().addListener(closed -> promise.trySuccess());
        }
    }

    @ChannelHandler.Sharable
    static final class OutputEnded extends ChannelInboundHandlerAdapter {

        static final OutputEnded INSTANCE = new OutputEnded();

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            Channel channel = ctx.channel();
            if (evt instanceof ChannelOutputShutdownEvent && channel.isActive() && !channel.hasAttr(OUTPUT_ENDED_ON_PURPOSE)) {
                if (channel.config().isAutoRead()) {
                    LingeringClose.closeSocketUnlessClosedWithinLinger(channel);
                } else {
                    LingeringClose.closeSocket(channel);
                }
            }
            ctx.fireUserEventTriggered(evt);
        }
    }
}
