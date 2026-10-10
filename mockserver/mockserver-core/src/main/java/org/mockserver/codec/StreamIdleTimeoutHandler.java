package org.mockserver.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.StreamingBody;
import org.slf4j.event.Level;

import java.util.function.BooleanSupplier;

/**
 * Aborts a streamed response when an {@link IdleStateEvent} fires while MockServer is reading the upstream. While more
 * than the watermark waits for the client, reads are withheld on purpose, so an idle event then is backpressure, not
 * an idle upstream, and is ignored; reading resumes, and the idle bound applies again, once the client drains it.
 * <p>
 * The abort fires {@link StreamingBody.IdleTimeoutException} down the pipeline, so the streaming body fails with it and
 * the client's response ends incomplete, and then closes the channel.
 */
public class StreamIdleTimeoutHandler extends ChannelInboundHandlerAdapter {

    private final MockServerLogger mockServerLogger;
    private final long idleTimeoutSeconds;
    private final BooleanSupplier awaitingClient;

    /**
     * @param idleTimeoutSeconds the timeout of the {@code IdleStateHandler} before this handler
     * @param awaitingClient     whether upstream reads are being withheld until the client takes what waits for it
     */
    public StreamIdleTimeoutHandler(MockServerLogger mockServerLogger, long idleTimeoutSeconds, BooleanSupplier awaitingClient) {
        this.mockServerLogger = mockServerLogger;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.awaitingClient = awaitingClient;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            if (awaitingClient.getAsBoolean()) {
                return;
            }
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("streaming response idle timeout - closing channel " + ctx.channel())
                );
            }
            ctx.fireExceptionCaught(new StreamingBody.IdleTimeoutException(idleTimeoutSeconds));
            ctx.close();
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
}
