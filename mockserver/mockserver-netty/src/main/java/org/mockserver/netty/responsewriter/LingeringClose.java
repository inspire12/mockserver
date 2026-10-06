package org.mockserver.netty.responsewriter;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.DuplexChannel;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Ends a connection whose client may still be sending a request body the server will never read. Closing such a socket
 * with unread bytes makes the kernel send a reset instead of a FIN, and a client's stack may then discard the response
 * it has not read yet. So the output is ended (after a TLS {@code close_notify}), inbound bytes are discarded before any
 * decoder, and the socket is closed when the client closes or after {@link #LINGER_MILLIS}, whichever comes first.
 */
final class LingeringClose {

    static final long LINGER_MILLIS = 5_000;

    private LingeringClose() {
    }

    static void close(Channel channel) {
        if (channel.eventLoop().inEventLoop()) {
            start(channel);
        } else {
            channel.eventLoop().execute(() -> start(channel));
        }
    }

    private static void start(Channel channel) {
        if (!channel.isActive() || !(channel instanceof DuplexChannel)) {
            channel.close();
            return;
        }
        if (channel.pipeline().get(DiscardInbound.class) != null) {
            return;
        }
        channel.pipeline().addFirst(DiscardInbound.INSTANCE);
        channel.config().setAutoRead(true);
        ScheduledFuture<?> limit = channel.eventLoop().schedule(() -> closeSocket(channel), LINGER_MILLIS, MILLISECONDS);
        channel.closeFuture().addListener(closed -> limit.cancel(false));

        SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
        ChannelFuture outputWritten = sslHandler != null ? sslHandler.closeOutbound() : channel.writeAndFlush(Unpooled.EMPTY_BUFFER);
        outputWritten.addListener(written -> {
            if (written.isSuccess() && channel.isActive()) {
                ((DuplexChannel) channel).shutdownOutput().addListener(shutdown -> {
                    if (!shutdown.isSuccess()) {
                        closeSocket(channel);
                    }
                });
            } else {
                closeSocket(channel);
            }
        });
    }

    // at the socket: through the pipeline a TLS handler would try to write its close_notify to an ended output
    private static void closeSocket(Channel channel) {
        channel.unsafe().close(channel.unsafe().voidPromise());
    }

    @ChannelHandler.Sharable
    static final class DiscardInbound extends ChannelInboundHandlerAdapter {

        static final DiscardInbound INSTANCE = new DiscardInbound();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ReferenceCountUtil.release(msg);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            // nothing reaches the handlers behind, so neither does the end of a read
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof ChannelInputShutdownEvent) {
                closeSocket(ctx.channel());
            } else {
                ctx.fireUserEventTriggered(evt);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // typically the client resetting a connection it no longer needs
            closeSocket(ctx.channel());
        }
    }
}
