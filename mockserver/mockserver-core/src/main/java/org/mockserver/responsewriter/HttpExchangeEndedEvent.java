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
 * in mockserver-netty) end their oldest exchange on either instance. It is fired from the codec's own context, so it
 * travels inbound through exactly the handlers that sit after the codec; on a pipeline without the
 * codec (HTTP/2, HTTP/3) {@link #fire} does nothing.
 */
public final class HttpExchangeEndedEvent {

    /**
     * No response will be written, or a final {@code 1xx} was: an HTTP client reading the connection is still waiting.
     */
    public static final HttpExchangeEndedEvent INSTANCE = new HttpExchangeEndedEvent("HttpExchangeEndedEvent");

    /**
     * The response was written as raw bytes, which an HTTP client reading the connection may take as a whole response.
     * The CONNECT/SOCKS relay is such a client, and counts the response it relays, so it is not told of this one.
     */
    public static final HttpExchangeEndedEvent RAW_RESPONSE_WRITTEN = new HttpExchangeEndedEvent("HttpExchangeEndedEvent(raw response written)");

    private final String description;

    private HttpExchangeEndedEvent(String description) {
        this.description = description;
    }

    /**
     * Fire {@link #INSTANCE} on the connection {@code ctx} belongs to. Call it after the stand-in write has been
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
        return description;
    }
}
