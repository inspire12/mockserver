package org.mockserver.netty.proxy.relay;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpServerCodec;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;
import org.mockserver.responsewriter.RawResponseBytesEvent;

/**
 * On MockServer's side of an HTTP/1.1 relay loopback, after its codec: tells the tunnel's relay what the bytes on the
 * loopback cannot. An exchange ended with nothing a client could read as its response (no response, or a final
 * {@code 1xx}) is passed to the client leg, which the idle timeout watches and which would otherwise count that
 * exchange for the rest of its life. A response about to be written as raw bytes is announced to
 * {@link LoopbackRawResponseSplitter}, which relays it and ends the client leg's exchange when it has, so
 * {@link HttpExchangeEndedEvent#RAW_RESPONSE_WRITTEN} is not passed on.
 */
@ChannelHandler.Sharable
public final class LoopbackExchangeEndedHandler extends ChannelInboundHandlerAdapter {

    public static final LoopbackExchangeEndedHandler INSTANCE = new LoopbackExchangeEndedHandler();

    private LoopbackExchangeEndedHandler() {
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        ChannelHandlerContext codec = ctx.pipeline().context(HttpServerCodec.class);
        if (codec != null && ctx.pipeline().get(LoopbackWrittenBytes.class) == null) {
            ctx.pipeline().addBefore(codec.name(), null, new LoopbackWrittenBytes());
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt == HttpExchangeEndedEvent.INSTANCE) {
            Channel proxyClient = RelayLoopbackAddresses.proxyClientOf(ctx.channel());
            if (proxyClient != null && proxyClient.isActive()) {
                // the client leg's exchange count is confined to its own event loop
                proxyClient.eventLoop().execute(() -> HttpExchangeEndedEvent.fire(proxyClient.pipeline().firstContext()));
            }
        } else if (evt instanceof RawResponseBytesEvent) {
            Channel proxyClient = RelayLoopbackAddresses.proxyClientOf(ctx.channel());
            LoopbackRawResponseSplitter splitter = proxyClient != null ? LoopbackRawResponseSplitter.of(proxyClient) : null;
            LoopbackWrittenBytes written = ctx.pipeline().get(LoopbackWrittenBytes.class);
            if (splitter != null && written != null) {
                // the bytes are written next, in this event loop task, so the count is where they start
                splitter.announce(written.count(), ((RawResponseBytesEvent) evt).length());
            }
        }
        ctx.fireUserEventTriggered(evt);
    }
}
