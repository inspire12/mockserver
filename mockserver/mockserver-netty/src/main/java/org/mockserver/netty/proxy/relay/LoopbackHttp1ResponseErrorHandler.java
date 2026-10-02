package org.mockserver.netty.proxy.relay;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_GATEWAY;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;

/**
 * Answers the proxy client with a {@code 502} when the CONNECT/SOCKS relay's HTTP/1.1 loopback fails to decode a
 * response (a corrupt compressed body, or one larger than {@code maxRequestBodySize}) before its head has been relayed.
 * After the head the client is left with an incomplete response: {@link DownstreamProxyRelayHandler} closes both
 * connections and no terminating chunk is written. Either way the exception carries on to it, which logs and closes,
 * and anything decoded after it is dropped.
 * Sits between the loopback's aggregator and {@link DownstreamProxyRelayHandler}; one per loopback.
 */
public class LoopbackHttp1ResponseErrorHandler extends ChannelInboundHandlerAdapter {

    private final Channel proxyClientChannel;
    private boolean responseHeadRelayed;
    private boolean failed;

    public LoopbackHttp1ResponseErrorHandler(Channel proxyClientChannel) {
        this.proxyClientChannel = proxyClientChannel;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (failed) {
            // what is still decoded from the rest of the read is no longer a valid response
            ReferenceCountUtil.release(msg);
            return;
        }
        if (msg instanceof HttpResponse && ((HttpResponse) msg).status().codeClass() != HttpStatusClass.INFORMATIONAL) {
            responseHeadRelayed = true;
        }
        if (msg instanceof LastHttpContent) {
            responseHeadRelayed = false;
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!failed && !responseHeadRelayed && isSslOrDecoderFault(cause) && proxyClientChannel.isActive()) {
            FullHttpResponse badGateway = new DefaultFullHttpResponse(HTTP_1_1, BAD_GATEWAY);
            badGateway.headers()
                .set(HttpHeaderNames.CONTENT_LENGTH, 0)
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            proxyClientChannel.writeAndFlush(badGateway);
        }
        failed = true;
        ctx.fireExceptionCaught(cause);
    }
}
