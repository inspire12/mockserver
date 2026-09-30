package org.mockserver.netty.connection;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.slf4j.event.Level;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Closes an inbound connection that has read and written nothing for {@code inboundConnectionIdleTimeoutMillis}
 * and has nothing in progress (see {@link InboundConnectionActivity#isBusy}). A busy connection is left
 * alone and checked again after another full period of silence.
 * <p>
 * Installed first in the pipeline when the connection is accepted. TLS and SOCKS handlers added later
 * by protocol detection sit in front of it, so it observes decrypted application traffic rather than raw
 * socket bytes; TLS set-up itself is covered by the busy check. The idle event is consumed here rather
 * than fired down the pipeline, so no other handler that reacts to {@link IdleStateEvent} can act on it.
 */
public final class InboundConnectionIdleHandler extends IdleStateHandler {

    private final MockServerLogger mockServerLogger;

    public InboundConnectionIdleHandler(long idleTimeoutMillis, MockServerLogger mockServerLogger) {
        super(0, 0, idleTimeoutMillis, MILLISECONDS);
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().attr(InboundConnectionActivity.ACTIVITY).setIfAbsent(new InboundConnectionActivity());
        super.handlerAdded(ctx);
    }

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) {
        InboundConnectionActivity activity = InboundConnectionActivity.of(ctx.channel());
        if (activity != null && activity.isBusy(ctx.channel())) {
            return;
        }
        if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setMessageFormat("closing idle inbound connection from:{}after:{}ms with nothing in progress")
                    .setArguments(ctx.channel().remoteAddress(), getAllIdleTimeInMillis())
            );
        }
        Metrics.incrementInboundConnectionsIdleClosed();
        // channel().close() rather than ctx.close(): the close must pass through the whole pipeline so an
        // HTTP/2 codec sends GOAWAY and a TLS handler sends close_notify.
        ctx.channel().close();
    }
}
