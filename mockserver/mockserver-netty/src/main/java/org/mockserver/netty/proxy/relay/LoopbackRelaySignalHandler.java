package org.mockserver.netty.proxy.relay;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.mockserver.netty.unification.HttpServerCodecResponsePairing;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;
import org.mockserver.responsewriter.RawResponseBytesEvent;

/**
 * On MockServer's side of an HTTP/1.1 relay loopback, after its codec: tells the tunnel's relay what the bytes on the
 * loopback cannot. Each response about to be written as raw bytes, and each exchange ended with nothing a client could
 * read as its response (no response, or a final {@code 1xx}), is announced to {@link LoopbackRawResponseSplitter} at
 * its place among the bytes written, the latter as a response of no bytes. The relay then tells its own codec and the
 * client leg's at that place, so neither pairs a later response with that exchange's request, and ends the client
 * leg's exchange, which the idle timeout watches. {@link HttpExchangeEndedEvent#RAW_RESPONSE_WRITTEN} is not passed on.
 */
@ChannelHandler.Sharable
public final class LoopbackRelaySignalHandler extends ChannelInboundHandlerAdapter {

    public static final LoopbackRelaySignalHandler INSTANCE = new LoopbackRelaySignalHandler();

    private LoopbackRelaySignalHandler() {
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        ChannelHandlerContext beneathCodec = HttpServerCodecResponsePairing.beneathCodec(ctx.pipeline());
        if (beneathCodec != null && ctx.pipeline().get(LoopbackWrittenBytes.class) == null) {
            ctx.pipeline().addBefore(beneathCodec.name(), null, new LoopbackWrittenBytes());
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt == HttpExchangeEndedEvent.INSTANCE) {
            Channel proxyClient = RelayLoopbackAddresses.proxyClientOf(ctx.channel());
            if (!announce(ctx, proxyClient, 0) && proxyClient != null && proxyClient.isActive()) {
                // the client leg's exchange count is confined to its own event loop
                proxyClient.eventLoop().execute(() -> HttpExchangeEndedEvent.fire(proxyClient.pipeline().firstContext()));
            }
        } else if (evt instanceof RawResponseBytesEvent) {
            // the bytes are written next, in this event loop task, so the count is where they start
            announce(ctx, RelayLoopbackAddresses.proxyClientOf(ctx.channel()), ((RawResponseBytesEvent) evt).length());
        }
        ctx.fireUserEventTriggered(evt);
    }

    private static boolean announce(ChannelHandlerContext ctx, Channel proxyClient, int length) {
        LoopbackRawResponseSplitter splitter = proxyClient != null ? LoopbackRawResponseSplitter.of(proxyClient) : null;
        LoopbackWrittenBytes written = ctx.pipeline().get(LoopbackWrittenBytes.class);
        if (splitter == null || written == null) {
            return false;
        }
        splitter.announce(written.count(), length);
        return true;
    }
}
