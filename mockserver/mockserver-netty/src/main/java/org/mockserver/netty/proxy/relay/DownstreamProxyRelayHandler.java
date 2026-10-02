package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.log.model.LogEntry;
import org.mockserver.log.model.SensitiveLogValue;
import org.mockserver.mappers.NettyMessageForLog;
import org.mockserver.socket.ChannelReadPause;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockserver.exception.ExceptionHandling.closeOnFlush;
import static org.mockserver.exception.ExceptionHandling.DIRECT_MEMORY_LIMIT_REACHED;
import static org.mockserver.exception.ExceptionHandling.directMemoryLimitReached;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;

public class DownstreamProxyRelayHandler extends SimpleChannelInboundHandler<HttpObject> {

    private final MockServerLogger mockServerLogger;
    private final Channel upstreamChannel;
    private final long maxUnwrittenStreamedBytes;
    private final long pauseReadsAboveBytes;
    private final AtomicLong unwrittenStreamedBytes = new AtomicLong();
    private boolean streamAborted;
    // confined to the loopback's event loop, which the proxy client's channel shares
    private boolean readsPaused;

    /**
     * Above this many unwritten streamed bytes (or half the bound, if less) the loopback stops reading until they drain
     * to half as many, so a slow proxy client gets backpressure long before {@code maxUnwrittenStreamedBytes} aborts.
     */
    static final long PAUSE_READS_ABOVE_BYTES = 256 * 1024;

    public DownstreamProxyRelayHandler(MockServerLogger mockServerLogger, Channel upstreamChannel) {
        this(mockServerLogger, upstreamChannel, 0);
    }

    /**
     * @param maxUnwrittenStreamedBytes the most bytes of streamed (unaggregated) content relayed but not yet written to
     *                                  {@code upstreamChannel}, past which both channels are closed; zero or less for no
     *                                  bound. One read of a compressed response can decode to far more than any heap.
     */
    public DownstreamProxyRelayHandler(MockServerLogger mockServerLogger, Channel upstreamChannel, long maxUnwrittenStreamedBytes) {
        super(false);
        this.upstreamChannel = upstreamChannel;
        this.mockServerLogger = mockServerLogger;
        this.maxUnwrittenStreamedBytes = maxUnwrittenStreamedBytes;
        this.pauseReadsAboveBytes = maxUnwrittenStreamedBytes > 0 ? Math.min(PAUSE_READS_ABOVE_BYTES, maxUnwrittenStreamedBytes / 2) : 0;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
        ctx.write(Unpooled.EMPTY_BUFFER);
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) {
        if (streamAborted) {
            // the rest of the read that passed the bound is still decoded, and dropped here
            ReferenceCountUtil.release(msg);
            return;
        }
        // an aggregated message is already bounded by its aggregator
        final int streamedBytes = msg instanceof HttpContent && !(msg instanceof FullHttpMessage) ? ((HttpContent) msg).content().readableBytes() : 0;
        final long unwritten = streamedBytes > 0 && maxUnwrittenStreamedBytes > 0 ? unwrittenStreamedBytes.addAndGet(streamedBytes) : 0;
        if (unwritten > maxUnwrittenStreamedBytes) {
            streamAborted = true;
            ReferenceCountUtil.release(msg);
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("aborting streamed response relayed to {} because more than {} bytes of it were waiting to be written to the client")
                        .setArguments(upstreamChannel.remoteAddress(), maxUnwrittenStreamedBytes)
                );
            }
            // no terminating chunk is written, so the client sees an incomplete response
            upstreamChannel.close();
            ctx.close();
            return;
        }
        if (unwritten > pauseReadsAboveBytes && !readsPaused) {
            readsPaused = true;
            ChannelReadPause.pause(ctx.channel());
        }
        upstreamChannel.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
            if (unwritten > 0 && unwrittenStreamedBytes.addAndGet(-streamedBytes) <= pauseReadsAboveBytes / 2 && readsPaused) {
                readsPaused = false;
                ChannelReadPause.resume(ctx.channel());
            }
            if (future.isSuccess()) {
                // while paused, reads resume only when the backlog has drained (resume above)
                if (!readsPaused) {
                    ctx.read();
                }
            } else {
                if (isNotSocketClosedException(future.cause())) {
                    mockServerLogger.logEvent(writeFailure(msg, future.cause()));
                }
                future.channel().close();
            }
        });
    }

    /**
     * The relayed message's text lists every header, so it is logged with its headers attached for redactSecretsInLog
     * to mask; a message with no headers of its own is masked whole. The text is taken now: the message may already
     * be released, and the entry must not keep it.
     */
    static LogEntry writeFailure(HttpObject msg, Throwable cause) {
        LogEntry logEntry = new LogEntry()
            .setLogLevel(Level.ERROR)
            .setMessageFormat("exception while returning writing:{}")
            .setThrowable(cause);
        String text = String.valueOf(msg);
        org.mockserver.model.HttpResponse headersOfResponse = msg instanceof io.netty.handler.codec.http.HttpResponse ? NettyMessageForLog.response((io.netty.handler.codec.http.HttpResponse) msg) : null;
        org.mockserver.model.HttpRequest headersOfRequest = msg instanceof io.netty.handler.codec.http.HttpRequest ? NettyMessageForLog.request((io.netty.handler.codec.http.HttpRequest) msg) : null;
        if (headersOfResponse != null) {
            logEntry.setHttpResponse(headersOfResponse).setArguments(text);
        } else if (headersOfRequest != null) {
            logEntry.setHttpRequest(headersOfRequest).setArguments(text);
        } else {
            // no headers to redact against: masked whole
            logEntry.setArguments(SensitiveLogValue.of(text));
        }
        return logEntry;
    }

    private boolean isNotSocketClosedException(Throwable cause) {
        return !(cause instanceof ClosedChannelException || cause instanceof ClosedSelectorException);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        closeOnFlush(upstreamChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (directMemoryLimitReached(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat(DIRECT_MEMORY_LIMIT_REACHED + ctx.channel() + " - " + cause.getMessage())
            );
        } else if (connectionClosedException(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught by downstream relay handler -> closing pipeline " + ctx.channel())
                    .setThrowable(cause)
            );
        } else if (isSslOrDecoderFault(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("SSL or decoder fault caught by downstream relay handler -> closing pipeline " + ctx.channel() + sniDescription(ctx.channel(), upstreamChannel))
                        .setThrowable(cause)
                );
            }
        }
        closeOnFlush(ctx.channel());
    }

}
