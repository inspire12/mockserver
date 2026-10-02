package org.mockserver.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpObject;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.model.StreamingBody;

/**
 * Sits between the HTTP codec and the content decompressor of a streamed upstream response. The codec reports an
 * invalid message (for example a chunk size that is not hex) as a {@code LastHttpContent} whose decoder result has
 * failed; a decompressor replaces that with a successful last content, so the stream would end looking complete.
 * This fails the stream with {@link StreamingBody.StreamAbortedException} instead and drops the rest of the message.
 */
public class StreamedResponseDecoderResultGuard extends ChannelInboundHandlerAdapter {

    private boolean failed;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (failed) {
            ReferenceCountUtil.release(msg);
            return;
        }
        if (msg instanceof HttpObject && ((HttpObject) msg).decoderResult().isFailure()) {
            failed = true;
            Throwable cause = ((HttpObject) msg).decoderResult().cause();
            ReferenceCountUtil.release(msg);
            ctx.fireExceptionCaught(new StreamingBody.StreamAbortedException("upstream sent an invalid message", cause));
            return;
        }
        ctx.fireChannelRead(msg);
    }
}
