package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import static org.mockserver.exception.ExceptionHandling.boundedFault;
import static org.mockserver.exception.ExceptionHandling.boundedFaultMessage;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;

/**
 * The upstream connection's end of a {@link BinaryRelay}: hands the relay what the upstream sends and tells it when
 * the upstream can take more. One per upstream connection.
 */
final class BinaryRelayUpstreamHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private final BinaryRelay relay;
    private final MockServerLogger mockServerLogger;

    BinaryRelayUpstreamHandler(BinaryRelay relay, MockServerLogger mockServerLogger) {
        super(true);
        this.relay = relay;
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf byteBuf) {
        relay.fromUpstream(ByteBufUtil.getBytes(byteBuf));
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        relay.upstreamReadComplete();
        ctx.fireChannelReadComplete();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        relay.upstreamWritabilityChanged();
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (isSslOrDecoderFault(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("SSL or decoder fault on the upstream connection of a binary connection -> closing both " + ctx.channel())
                        .setThrowable(boundedFault(cause))
                );
            }
        } else if (connectionClosedException(cause)) {
            // despite its name, true for everything except a connection its peer closed or reset
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught on the upstream connection of a binary connection -> closing both " + ctx.channel())
                    .setThrowable(boundedFault(cause))
            );
        } else if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("upstream:{}of a binary connection reset it:{}closing both")
                    .setArguments(ctx.channel().remoteAddress(), boundedFaultMessage(cause))
            );
        }
        // closing the upstream connection closes the client's
        RelayLegClose.now(ctx.channel());
    }
}
