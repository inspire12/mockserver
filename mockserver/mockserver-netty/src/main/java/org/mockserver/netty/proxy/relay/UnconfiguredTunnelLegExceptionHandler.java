package org.mockserver.netty.proxy.relay;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import static org.mockserver.exception.ExceptionHandling.DIRECT_MEMORY_LIMIT_REACHED;
import static org.mockserver.exception.ExceptionHandling.boundedFault;
import static org.mockserver.exception.ExceptionHandling.boundedFaultMessage;
import static org.mockserver.exception.ExceptionHandling.causeDescription;
import static org.mockserver.exception.ExceptionHandling.clientGoneException;
import static org.mockserver.exception.ExceptionHandling.directMemoryLimitReached;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;

/**
 * Added last to each leg of a CONNECT or SOCKS tunnel when the tunnel is set up, and removed when the relay's handlers
 * are installed, once the client's first bytes or its TLS handshake have shown the tunnel's protocol. Nothing else
 * takes the leg's exceptions meanwhile, which Netty would log at {@code WARN} with a stack trace through its own logger.
 * It logs each once and closes the leg, whose close closes the other.
 */
final class UnconfiguredTunnelLegExceptionHandler extends ChannelInboundHandlerAdapter {

    private final MockServerLogger mockServerLogger;
    private final String leg;

    /**
     * @param leg which leg it is on, for the log: {@code "client"} or {@code "loopback"}
     */
    UnconfiguredTunnelLegExceptionHandler(MockServerLogger mockServerLogger, String leg) {
        this.mockServerLogger = mockServerLogger;
        this.leg = leg;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (directMemoryLimitReached(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat(DIRECT_MEMORY_LIMIT_REACHED + ctx.channel() + " - " + cause.getMessage())
            );
        } else if (isSslOrDecoderFault(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                // no throwable: its message is not bounded, as the one logged here is
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("closing tunnel's{}connection " + ctx.channel() + sniDescription(ctx.channel()) + " before its relay was set up, for SSL or decoder fault " + cause.getClass().getName() + ":{}")
                        .setArguments(leg, boundedFaultMessage(cause))
                );
            }
        } else if (clientGoneException(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("tunnel's{}connection from:{}closed by its peer before its relay was set up:{}")
                        .setArguments(leg, ctx.channel().remoteAddress(), causeDescription(cause))
                );
            }
        } else {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught on tunnel's{}connection " + ctx.channel() + " before its relay was set up")
                    .setArguments(leg)
                    .setThrowable(boundedFault(cause))
            );
        }
        ctx.close();
    }
}
