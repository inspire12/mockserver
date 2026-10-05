package org.mockserver.httpclient;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Exception;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import static org.mockserver.exception.ExceptionHandling.DIRECT_MEMORY_LIMIT_REACHED;
import static org.mockserver.exception.ExceptionHandling.boundedFaultMessage;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.directMemoryLimitReached;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.httpclient.NettyHttpClient.REMOTE_SOCKET;

/**
 * The last handler of the pipeline of an HTTP/2 connection to an upstream. It logs each exception that reaches the
 * end of that pipeline once, at the level its cause calls for, where Netty would log every one at {@code WARN} with a
 * stack trace through its own logger. A forward in flight is failed by the handlers before this one.
 * <p>
 * It does not close the connection for an HTTP/2 connection error, which Netty's codec fires here before it sends
 * the {@code GOAWAY} and closes: closing here would lose the {@code GOAWAY}.
 */
final class Http2ForwardConnectionExceptionHandler extends ChannelInboundHandlerAdapter {

    private final MockServerLogger mockServerLogger;

    Http2ForwardConnectionExceptionHandler(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Http2Exception connectionError = Http2CodecUtil.getEmbeddedHttp2Exception(cause);
        if (ForwardHeaderLimit.isAlreadyLogged(ctx.channel(), cause)) {
            // logged as the refusal that failed the forward
        } else if (directMemoryLimitReached(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat(DIRECT_MEMORY_LIMIT_REACHED + ctx.channel() + " - " + cause.getMessage())
            );
            ctx.close();
        } else if (connectionError != null) {
            // no throwable: the message says what the upstream sent, and the stack trace only where Netty read it
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("closing HTTP/2 connection to:{}for connection error:{}:{}")
                        .setArguments(ctx.channel().attr(REMOTE_SOCKET).get(), connectionError.error(), boundedFaultMessage(connectionError))
                );
            }
        } else if (isSslOrDecoderFault(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("closing HTTP/2 connection to:{}for SSL or decoder fault " + cause.getClass().getName() + ":{}")
                        .setArguments(ctx.channel().attr(REMOTE_SOCKET).get(), boundedFaultMessage(cause))
                );
            }
            // Netty's JDK TLS handler leaves the connection open after such bytes and reports every read that follows
            ctx.close();
        } else if (connectionClosedException(cause)) {
            // despite its name, true for everything except a connection its peer closed or reset
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught on HTTP/2 connection to upstream " + ctx.channel())
                    .setThrowable(cause)
            );
        } else if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setMessageFormat("HTTP/2 connection to:{}closed by the upstream:{}")
                    .setArguments(ctx.channel().attr(REMOTE_SOCKET).get(), cause.getMessage())
            );
        }
    }
}
