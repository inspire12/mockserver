package org.mockserver.httpclient;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameCodec;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Message;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import static org.mockserver.exception.ExceptionHandling.DIRECT_MEMORY_LIMIT_REACHED;
import static org.mockserver.exception.ExceptionHandling.boundedFaultDescription;
import static org.mockserver.exception.ExceptionHandling.boundedFaultDescriptionWithRootCause;
import static org.mockserver.exception.ExceptionHandling.boundedFaultMessage;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.directMemoryLimitReached;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sslCause;
import static org.mockserver.exception.ExceptionHandling.tlsFailure;
import static org.mockserver.httpclient.NettyHttpClient.REMOTE_SOCKET;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

/**
 * The last handler of the pipeline of an HTTP/2 connection to an upstream. It logs each exception that reaches the
 * end of that pipeline once, at the level its cause calls for, where Netty would log every one at {@code WARN} with a
 * stack trace through its own logger. A forward in flight is failed with a connection error or a TLS fault here,
 * which the stream's handlers do not see, and the entry is then {@code DEBUG}: the forward is logged with its cause.
 * <p>
 * It does not close the connection for an HTTP/2 connection error, which Netty's codec fires here before it sends
 * the {@code GOAWAY} and closes: closing here would lose the {@code GOAWAY}. For an exception it does not recognise
 * it fails the forward in flight, takes the connection out of the pool and closes it with
 * {@code GOAWAY(INTERNAL_ERROR)}, as {@code Http2ConnectionExceptionHandler} does for a connection to MockServer.
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
            Level level = failWaitingRequest(ctx, "HTTP/2 connection to " + upstream(ctx) + " failed: " + connectionError.error() + ": " + boundedFaultMessage(connectionError), connectionError) ? Level.DEBUG : Level.WARN;
            if (mockServerLogger.isEnabledForInstance(level)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(level)
                        .setMessageFormat("closing HTTP/2 connection to:{}for connection error:{}:{}")
                        .setArguments(ctx.channel().attr(REMOTE_SOCKET).get(), connectionError.error(), boundedFaultMessage(connectionError))
                );
            }
        } else if (isSslOrDecoderFault(cause) || sslCause(cause) != null) {
            SSLException tlsFault = tlsFailure(cause);
            boolean withRequest = tlsFault != null && (failWaitingRequest(ctx, "TLS with " + upstream(ctx) + " failed: " + boundedFaultDescriptionWithRootCause(tlsFault), tlsFault) || passedToTheStream(ctx, cause));
            Level level = withRequest ? Level.DEBUG : Level.WARN;
            if (mockServerLogger.isEnabledForInstance(level)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(level)
                        .setMessageFormat("closing HTTP/2 connection to:{}for SSL or decoder fault " + cause.getClass().getName() + ":{}")
                        .setArguments(ctx.channel().attr(REMOTE_SOCKET).get(), boundedFaultMessage(cause))
                );
            }
            // Netty's JDK TLS handler leaves the connection open after such bytes and reports every read that follows
            ctx.close();
        } else if (connectionClosedException(cause)) {
            // despite its name, true for everything except a connection its peer closed or reset;
            // ERROR whether or not a forward waits: that forward is reported as a failed connection, or sent again
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("closing HTTP/2 connection to:{}for unexpected exception")
                    .setArguments(ctx.channel().attr(REMOTE_SOCKET).get())
                    .setThrowable(cause)
            );
            failWaitingRequest(ctx, "HTTP/2 connection to " + upstream(ctx) + " failed: unexpected exception: " + boundedFaultDescription(cause), cause);
            HttpForwardConnectionPool.retire(ctx.channel());
            // later: the codec fires an exception it caught decoding here before it sends its own GOAWAY for it
            ctx.channel().eventLoop().execute(() -> closeWithGoAway(ctx));
        } else if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setMessageFormat("HTTP/2 connection to:{}closed by the upstream:{}")
                    .setArguments(ctx.channel().attr(REMOTE_SOCKET).get(), cause.getMessage())
            );
        }
    }

    /**
     * Through the codec, which writes the {@code GOAWAY} after the frames already written and, once it is written,
     * closes without waiting for the streams in flight, as it does after any {@code GOAWAY} with an error code. None
     * is written once the codec has sent one: its own, for a connection error, names a different last stream.
     */
    private static void closeWithGoAway(ChannelHandlerContext ctx) {
        if (ctx.channel().isActive()) {
            Http2FrameCodec codec = ctx.pipeline().get(Http2FrameCodec.class);
            if (codec == null || !codec.connection().goAwaySent()) {
                ctx.write(new DefaultHttp2GoAwayFrame(Http2Error.INTERNAL_ERROR));
            }
            ctx.close();
        }
    }

    /**
     * Fails the request in flight with the cause, wrapped as the {@link SocketConnectionException} it failed with
     * before, so a request on a reused connection is still sent again on a new one; the stream's own handlers would
     * report only the teardown that follows.
     */
    private static boolean failWaitingRequest(ChannelHandlerContext ctx, String message, Throwable cause) {
        return HttpClientConnectionErrorHandler.failWaitingRequest(ctx.channel(), new SocketConnectionException(message, cause));
    }

    /**
     * Netty's multiplex handler passes a fault an {@link SSLException} caused to the active streams before this
     * handler sees it, so the stream's handler has already failed the request with it.
     */
    private static boolean passedToTheStream(ChannelHandlerContext ctx, Throwable cause) {
        CompletableFuture<? extends Message> responseFuture = ctx.channel().attr(RESPONSE_FUTURE).get();
        return cause.getCause() instanceof SSLException && responseFuture != null && responseFuture.isCompletedExceptionally();
    }

    private static String upstream(ChannelHandlerContext ctx) {
        InetSocketAddress upstream = ctx.channel().attr(REMOTE_SOCKET).get();
        return upstream != null ? upstream.getHostString() + ":" + upstream.getPort() : "upstream";
    }
}
