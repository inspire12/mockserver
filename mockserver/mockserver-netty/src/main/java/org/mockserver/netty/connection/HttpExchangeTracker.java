package org.mockserver.netty.connection;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.LastHttpContent;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;

/**
 * Counts HTTP/1.1 exchanges in progress on a connection for {@link InboundConnectionIdleHandler}: an
 * exchange starts when a request head is decoded and ends when the last part of its response has been
 * written to the socket, so a delayed, paused or streaming response keeps the connection busy.
 * <p>
 * Must sit after {@code HttpServerCodec}, ahead of every handler that answers, so it sees every decoded request and every
 * encoded response whichever handler writes it. The client's leg of an HTTP/1.1 CONNECT/SOCKS tunnel has one after the
 * tunnel's own codec, where an exchange spans the upload of its request and the relay of its response.
 * A {@code 101 Switching Protocols} response makes the
 * connection long-lived (it is a WebSocket from then on); other {@code 1xx} responses, such as
 * {@code 100 Continue}, precede the real response and do not end the exchange. An exchange that ends
 * without a {@code LastHttpContent} passing through (a raw-bytes {@code HttpError}, an abandoned
 * exchange, a final {@code 1xx}) is ended by {@link HttpExchangeEndedEvent}; without it the connection
 * would count as busy for the rest of its life and never be closed as idle.
 */
@ChannelHandler.Sharable
public final class HttpExchangeTracker extends ChannelDuplexHandler {

    public static final HttpExchangeTracker INSTANCE = new HttpExchangeTracker();

    private HttpExchangeTracker() {
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            InboundConnectionActivity activity = InboundConnectionActivity.of(ctx.channel());
            if (activity != null) {
                activity.httpExchangeStarted();
            }
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt == HttpExchangeEndedEvent.INSTANCE) {
            InboundConnectionActivity activity = InboundConnectionActivity.of(ctx.channel());
            if (activity != null) {
                activity.httpExchangeCompleted();
            }
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        InboundConnectionActivity activity = InboundConnectionActivity.of(ctx.channel());
        if (activity != null) {
            boolean informational = false;
            if (msg instanceof HttpResponse) {
                HttpResponseStatus status = ((HttpResponse) msg).status();
                informational = status.codeClass() == HttpStatusClass.INFORMATIONAL;
                if (status.code() == HttpResponseStatus.SWITCHING_PROTOCOLS.code()) {
                    activity.becomeLongLived(ctx.channel());
                }
                activity.informationalResponseHeadWritten(informational);
            }
            if (msg instanceof LastHttpContent) {
                if (activity.informationalResponseHeadWritten()) {
                    activity.informationalResponseHeadWritten(false);
                } else if (!informational) {
                    promise = promise.unvoid();
                    promise.addListener(activity.httpExchangeCompletedListener());
                }
            }
        }
        ctx.write(msg, promise);
    }
}
