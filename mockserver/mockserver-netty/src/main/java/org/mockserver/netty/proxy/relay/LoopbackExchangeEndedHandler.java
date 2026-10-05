package org.mockserver.netty.proxy.relay;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;

/**
 * On MockServer's side of an HTTP/1.1 relay loopback, after its codec: tells the tunnel's client leg when MockServer
 * ends an exchange with nothing its client could read as the response (no response, or a final {@code 1xx}). The
 * client leg is the one the idle timeout watches, and would otherwise count that exchange for the rest of its life.
 * {@link HttpExchangeEndedEvent#RAW_RESPONSE_WRITTEN} is not passed on: the relay reads those bytes as a response
 * and the client leg ends the exchange when it has relayed it.
 */
@ChannelHandler.Sharable
public final class LoopbackExchangeEndedHandler extends ChannelInboundHandlerAdapter {

    public static final LoopbackExchangeEndedHandler INSTANCE = new LoopbackExchangeEndedHandler();

    private LoopbackExchangeEndedHandler() {
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt == HttpExchangeEndedEvent.INSTANCE) {
            Channel proxyClient = RelayLoopbackAddresses.proxyClientOf(ctx.channel());
            if (proxyClient != null && proxyClient.isActive()) {
                // the client leg's exchange count is confined to its own event loop
                proxyClient.eventLoop().execute(() -> HttpExchangeEndedEvent.fire(proxyClient.pipeline().firstContext()));
            }
        }
        ctx.fireUserEventTriggered(evt);
    }
}
