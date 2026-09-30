package org.mockserver.netty.connection;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.slf4j.event.Level;

import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts the inbound connections a MockServer holds open and, when {@code maxInboundConnections} is
 * set, refuses any connection accepted beyond it. Sits on the listening socket's pipeline, ahead of
 * the acceptor, so a refused connection is reset before it is registered with a worker event loop or
 * given a pipeline - it costs the server nothing but the accept.
 * <p>
 * The limit is read per accept, so a change through {@code PUT /mockserver/configuration} applies to
 * the next connection. One limiter is shared by all of a server's bound ports.
 */
@ChannelHandler.Sharable
public final class InboundConnectionLimiter extends ChannelInboundHandlerAdapter {

    private static final long REJECTION_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final AtomicInteger openConnections = new AtomicInteger();
    private final AtomicLong nextRejectionLogNanos = new AtomicLong(System.nanoTime());
    private final AtomicLong unloggedRejections = new AtomicLong();

    public InboundConnectionLimiter(Configuration configuration, MockServerLogger mockServerLogger) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof Channel) {
            Channel connection = (Channel) msg;
            int maxConnections = configuration.maxInboundConnections();
            int open = openConnections.incrementAndGet();
            if (maxConnections > 0 && open > maxConnections) {
                openConnections.decrementAndGet();
                reject(connection, maxConnections);
                return;
            }
            Metrics.inboundConnectionOpened();
            connection.closeFuture().addListener(future -> {
                openConnections.decrementAndGet();
                Metrics.inboundConnectionClosed();
            });
        }
        ctx.fireChannelRead(msg);
    }

    public int getOpenConnections() {
        return openConnections.get();
    }

    private void reject(Channel connection, int maxConnections) {
        SocketAddress remoteAddress = connection.remoteAddress();
        try {
            // a reset rather than a FIN: the client fails at once, and the server keeps no TIME_WAIT entry
            connection.config().setOption(ChannelOption.SO_LINGER, 0);
        } catch (RuntimeException ignore) {
            // best effort
        }
        connection.unsafe().closeForcibly();
        Metrics.incrementInboundConnectionsRejected();
        logRejection(remoteAddress, maxConnections);
    }

    private void logRejection(SocketAddress remoteAddress, int maxConnections) {
        long now = System.nanoTime();
        long nextLog = nextRejectionLogNanos.get();
        if (now - nextLog >= 0 && nextRejectionLogNanos.compareAndSet(nextLog, now + REJECTION_LOG_INTERVAL_NANOS)) {
            long suppressed = unloggedRejections.getAndSet(0);
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("refused inbound connection from:{}because maxInboundConnections:{}connections are already open; {}further connections were refused since the last message, which is repeated at most every 10 seconds")
                        .setArguments(remoteAddress, maxConnections, suppressed)
                );
            }
        } else {
            unloggedRejections.incrementAndGet();
        }
    }
}
