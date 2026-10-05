package org.mockserver.netty.unification;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Exception;
import org.apache.commons.lang3.StringUtils;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import static org.mockserver.exception.ExceptionHandling.DIRECT_MEMORY_LIMIT_REACHED;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.directMemoryLimitReached;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;

/**
 * The last handler of a direct HTTP/2 connection's pipeline. It logs each exception that reaches the end of that
 * pipeline once, at the level its cause calls for, where Netty would log every one at {@code WARN} with a stack trace
 * through its own logger.
 * <p>
 * It must stay last: {@code Http2MultiplexHandler}, before it, hands a stream's errors to that stream. It does not
 * close the connection for an HTTP/2 connection error, which Netty's codec fires here before it sends the
 * {@code GOAWAY} and closes: closing here would lose the {@code GOAWAY}.
 */
public class Http2ConnectionExceptionHandler extends ChannelInboundHandlerAdapter {

    static final int MAX_FAULT_MESSAGE_LENGTH = 256;

    private final MockServerLogger mockServerLogger;

    public Http2ConnectionExceptionHandler(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Http2Exception connectionError = Http2CodecUtil.getEmbeddedHttp2Exception(cause);
        if (directMemoryLimitReached(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat(DIRECT_MEMORY_LIMIT_REACHED + ctx.channel() + " - " + cause.getMessage())
            );
            ctx.close();
        } else if (connectionError != null) {
            // a request refused for its header size was logged where it was refused
            if (!Http2RequestHeaderLimit.isRefusal(connectionError) && mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("closing HTTP/2 connection from:{}for connection error:{}")
                        .setArguments(ctx.channel().remoteAddress(), connectionError.error())
                        .setThrowable(cause)
                );
            }
        } else if (isSslOrDecoderFault(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                // no throwable: Netty's message for bytes that are not a TLS record is a hex dump of all of them
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("closing HTTP/2 connection " + ctx.channel() + sniDescription(ctx.channel()) + " for SSL or decoder fault " + cause.getClass().getName() + ":{}")
                        .setArguments(StringUtils.abbreviate(cause.getMessage(), MAX_FAULT_MESSAGE_LENGTH))
                );
            }
            // Netty's JDK TLS handler leaves the connection open after such bytes and reports every read that follows
            ctx.close();
        } else if (connectionClosedException(cause)) {
            // despite its name, true for everything except a connection its peer closed or reset
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught on HTTP/2 connection " + ctx.channel())
                    .setThrowable(cause)
            );
        } else if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setMessageFormat("HTTP/2 connection from:{}closed by its client:{}")
                    .setArguments(ctx.channel().remoteAddress(), cause.getMessage())
            );
        }
    }
}
