package org.mockserver.responsewriter;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpServerCodec;

/**
 * User event announcing that the oldest outstanding HTTP/1.1 exchange on a connection has ended
 * without its response passing through {@link HttpServerCodec} as HTTP objects ending in a
 * {@code LastHttpContent}: the response was written as raw bytes, will never be written, or was a
 * final {@code 1xx} that handlers after the codec cannot tell from an interim one.
 * <p>
 * Handlers that pair decoded requests with encoded responses (the exchange tracker and transport timer
 * in mockserver-netty) end their oldest exchange on it. It is fired from the codec's own context, so it
 * travels inbound through exactly the handlers that sit after the codec; on a pipeline without the
 * codec (HTTP/2, HTTP/3) {@link #fire} does nothing.
 */
public final class HttpExchangeEndedEvent {

    public static final HttpExchangeEndedEvent INSTANCE = new HttpExchangeEndedEvent();

    private HttpExchangeEndedEvent() {
    }

    /**
     * Fire the event on the connection {@code ctx} belongs to. Call it after the stand-in write has been
     * issued, from the same thread, so it reaches the handlers after that write and before any later one.
     * A {@code null} context (the servlet deployments have no channel) is ignored.
     */
    public static void fire(ChannelHandlerContext ctx) {
        if (ctx == null) {
            return;
        }
        ChannelHandlerContext httpCodecContext = ctx.pipeline().context(HttpServerCodec.class);
        if (httpCodecContext != null) {
            httpCodecContext.fireUserEventTriggered(INSTANCE);
        }
    }

    @Override
    public String toString() {
        return "HttpExchangeEndedEvent";
    }
}
