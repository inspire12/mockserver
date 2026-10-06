package org.mockserver.httpclient;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import org.mockserver.model.Message;

import java.util.concurrent.CompletableFuture;

import static org.mockserver.httpclient.NettyHttpClient.ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

@ChannelHandler.Sharable
public class HttpClientConnectionErrorHandler extends ChannelDuplexHandler {

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        CompletableFuture<? extends Message> responseFuture = ctx.channel().attr(RESPONSE_FUTURE).get();
        if (responseFuture != null && !responseFuture.isDone()) {
            if (ctx.channel().attr(ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE).get()) {
                responseFuture.completeExceptionally(new SocketConnectionException("Channel handler removed before valid response has been received"));
            } else {
                responseFuture.complete(null);
            }
        }
        super.handlerRemoved(ctx);
    }

    /**
     * Fails the request waiting on {@code channel} with {@code failure}, for a handler after this one that sees a cause
     * this handler does not, before the connection closes and its teardown is reported in its place.
     *
     * @return whether this call failed it: false when no request is waiting or it has an outcome already
     */
    static boolean failWaitingRequest(Channel channel, Throwable failure) {
        CompletableFuture<? extends Message> responseFuture = channel.attr(RESPONSE_FUTURE).get();
        return responseFuture != null && responseFuture.completeExceptionally(failure);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        CompletableFuture<? extends Message> responseFuture = ctx.channel().attr(RESPONSE_FUTURE).get();
        if (responseFuture != null && !responseFuture.isDone()) {
            responseFuture.completeExceptionally(cause);
        }
        super.exceptionCaught(ctx, cause);
    }
}
