package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.log.model.SensitiveLogValue;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.BinaryMessage;
import org.mockserver.model.BinaryProxyListener;
import org.mockserver.netty.proxy.BinaryRequestProxyingHandler;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.ChannelReadPause;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.mockserver.exception.ExceptionHandling.closeOnFlush;
import static org.mockserver.formatting.StringFormatter.formatBytes;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.BinaryMessage.bytes;
import static org.mockserver.netty.unification.PortUnificationHandler.isSslEnabledUpstream;

/**
 * One binary client connection's single upstream connection, and the bytes relayed between the two as they arrive.
 * Kept in a channel attribute of the client connection. The upstream connection is registered on the client
 * connection's event loop, so everything here runs on that one thread; only the listener runs elsewhere.
 * <p>
 * Neither connection is read while the other cannot take what is read from it, so what is held is one read each
 * way, each connection's write buffer and the messages not yet reported to the listener.
 * <p>
 * A connection this cannot carry is left to the caller, which forwards each of its messages on an upstream
 * connection of its own: see {@link #forwardedPerMessageBecause()}.
 */
public final class BinaryRelay {

    private static final AttributeKey<BinaryRelay> RELAY = AttributeKey.valueOf("mockserver.binaryRelay");
    private static final String SETTING = "forwardBinaryRequestsUseSingleConnection";
    /**
     * The client connection is not read while more messages than this wait to be reported to the listener, and is
     * read again once no more than half as many do.
     */
    static final int MAX_PENDING_LISTENER_CALLS = 64;

    /** Why the client connection is not being read; each is one hold on {@link ChannelReadPause}. */
    private enum ClientHold {
        UPSTREAM_CONNECTING, UPSTREAM_NOT_WRITABLE, LISTENER_BEHIND, CLIENT_NOT_WRITABLE
    }

    private final Channel client;
    private final InetSocketAddress target;
    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final Scheduler scheduler;
    private final NettyHttpClient httpClient;
    private final BinaryProxyListener listener;
    private final EnumSet<ClientHold> clientHolds = EnumSet.noneOf(ClientHold.class);
    private final Deque<Exchange> waitingForConnect = new ArrayDeque<>(1);
    private Channel upstream;
    private boolean connected;
    private boolean clientClosed;
    private boolean finished;
    private boolean perMessage;
    private boolean upstreamHeldForClient;
    private Exchange latest;
    private String latestCorrelationId;
    private int pendingListenerCalls;
    private ScheduledFuture<?> upstreamStallCheck;
    private long bytesWaitingAtLastStallCheck;

    private BinaryRelay(Channel client, InetSocketAddress target, Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, NettyHttpClient httpClient, BinaryProxyListener listener) {
        this.client = client;
        this.target = target;
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.scheduler = scheduler;
        this.httpClient = httpClient;
        this.listener = listener;
    }

    /**
     * Relays a message read from the client to its upstream connection, opening that connection for the first one.
     * Must be called on the client connection's event loop.
     *
     * @return false if the message was not taken: the caller is to forward it on an upstream connection of its own
     */
    public static boolean forward(ChannelHandlerContext ctx, BinaryMessage binaryRequest, String logCorrelationId, InetSocketAddress target, Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, NettyHttpClient httpClient, BinaryProxyListener listener) {
        return relayOf(ctx, target, configuration, mockServerLogger, scheduler, httpClient, listener).fromClient(binaryRequest, logCorrelationId);
    }

    /**
     * Whether this connection's messages are relayed on one upstream connection, so that MockServer may answer one
     * itself instead (forwardBinaryRequestsMatchExpectations). Opens no upstream connection. Must be called on the
     * client connection's event loop.
     */
    public static boolean relaysOnOneConnection(ChannelHandlerContext ctx, InetSocketAddress target, Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, NettyHttpClient httpClient, BinaryProxyListener listener) {
        BinaryRelay relay = relayOf(ctx, target, configuration, mockServerLogger, scheduler, httpClient, listener);
        return !relay.perMessage && relay.forwardedPerMessageBecause() == null;
    }

    /**
     * Called once MockServer has written its own reply to a message read from the client, which is not relayed and
     * is not reported to the listener. Nothing orders that reply against what the upstream still owes an earlier
     * message, so that case is logged; and the client is not read while it cannot take more.
     */
    public static void answeredLocally(Channel client, String logCorrelationId) {
        BinaryRelay relay = client.hasAttr(RELAY) ? client.attr(RELAY).get() : null;
        if (relay != null) {
            relay.repliedLocally(logCorrelationId);
        }
    }

    private static BinaryRelay relayOf(ChannelHandlerContext ctx, InetSocketAddress target, Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, NettyHttpClient httpClient, BinaryProxyListener listener) {
        Channel client = ctx.channel();
        BinaryRelay relay = client.attr(RELAY).get();
        if (relay == null) {
            relay = new BinaryRelay(client, target, configuration, mockServerLogger, scheduler, httpClient, listener);
            client.attr(RELAY).set(relay);
        }
        return relay;
    }

    /**
     * Ends the upstream connection once the client's has closed, after what the client sent has been written to it.
     * Called from the client connection's {@code channelInactive}, not its close future, which completes before the
     * handlers in front of this one hand over the bytes they still hold (a possible handshake start, a gathered
     * message). A connection without a relay is left alone.
     */
    public static void clientInactive(Channel client) {
        BinaryRelay relay = client.hasAttr(RELAY) ? client.attr(RELAY).get() : null;
        if (relay != null) {
            relay.clientClosed();
        }
    }

    /**
     * Reads the upstream again once the client can take more. A connection without a relay is left alone.
     */
    public static void clientWritabilityChanged(Channel client) {
        BinaryRelay relay = client.hasAttr(RELAY) ? client.attr(RELAY).get() : null;
        if (relay != null && client.isWritable()) {
            relay.release(ClientHold.CLIENT_NOT_WRITABLE);
            if (relay.upstreamHeldForClient) {
                relay.upstreamHeldForClient = false;
                ChannelReadPause.resume(relay.upstream);
            }
        }
    }

    /** A forwarded message that has had no upstream read since is {@link #latest}; each is warned about once. */
    private void repliedLocally(String logCorrelationId) {
        if (latest != null && !latest.overtakenWarned && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            latest.overtakenWarned = true;
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setCorrelationId(logCorrelationId)
                    .setMessageFormat("binary mock response written to binary connection from:{}while forwarded binary request:{}has had no response from:{}the two may reach the client out of order")
                    .setArguments(client.remoteAddress(), SensitiveLogValue.of(formatBytes(latest.request.getBytes())), target)
            );
        }
        if (!client.isWritable()) {
            hold(ClientHold.CLIENT_NOT_WRITABLE);
        }
    }

    private boolean fromClient(BinaryMessage binaryRequest, String logCorrelationId) {
        latestCorrelationId = logCorrelationId;
        if (!perMessage) {
            String reason = forwardedPerMessageBecause();
            if (reason != null) {
                forwardPerMessageFromNowOn(reason);
            }
        }
        if (perMessage) {
            return false;
        }
        if (finished || upstream == null && !connect()) {
            return true;
        }
        Exchange exchange = new Exchange(binaryRequest);
        if (latest != null) {
            latest.response.complete(null);
        }
        latest = exchange;
        reportToListener(exchange);
        if (connected) {
            writeToUpstream(exchange);
        } else {
            waitingForConnect.add(exchange);
        }
        return true;
    }

    /**
     * Why this connection's messages are each forwarded on an upstream connection of their own, as all are when
     * the setting is off, or null if it is relayed on one. The upstream connection is made directly and is not
     * encrypted, so it cannot go through an upstream proxy or carry what a client sent over TLS.
     */
    private String forwardedPerMessageBecause() {
        if (httpClient.forwardsThroughProxy()) {
            return "an upstream proxy is configured";
        }
        if (isSslEnabledUpstream(client)) {
            return "the client's connection uses TLS";
        }
        return null;
    }

    /**
     * A connection that turns TLS on part way through has an upstream connection by now, in the clear. It is ended
     * once what was sent in the clear has been delivered, and the client's connection is kept.
     */
    private void forwardPerMessageFromNowOn(String reason) {
        perMessage = true;
        if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setCorrelationId(latestCorrelationId)
                    .setMessageFormat("forwarding each message of binary connection from:{}to:{}on an upstream connection of its own, as when " + SETTING + " is false, because:{}")
                    .setArguments(client.remoteAddress(), target, reason)
            );
        }
        releaseEveryHold();
        if (connected && !finished) {
            RelayLegClose.afterFlush(upstream);
        }
    }

    private boolean connect() {
        ChannelFuture connect;
        try {
            connect = httpClient.connectBinaryRelay(client.eventLoop(), target, new BinaryRelayUpstreamHandler(this, mockServerLogger));
        } catch (RuntimeException notPermitted) {
            refuse(notPermitted.getMessage());
            return false;
        }
        if (connect == null) {
            refuse("the forward client opened no upstream connection");
            return false;
        }
        upstream = connect.channel();
        hold(ClientHold.UPSTREAM_CONNECTING);
        connect.addListener(future -> connectCompleted(future.cause()));
        upstream.closeFuture().addListener(future -> upstreamClosed());
        return !finished;
    }

    private void connectCompleted(Throwable failure) {
        if (failure != null) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(latestCorrelationId)
                        .setMessageFormat("unable to connect to:{}for binary connection from:{}closing connection")
                        .setArguments(target, client.remoteAddress())
                        .setThrowable(failure)
                );
            }
            for (Exchange notSent : waitingForConnect) {
                notSent.response.completeExceptionally(failure);
            }
            waitingForConnect.clear();
            latest = null;
            // the failed channel is closed by the bootstrap, which is what closes the client
            return;
        }
        connected = true;
        release(ClientHold.UPSTREAM_CONNECTING);
        for (Exchange exchange; (exchange = waitingForConnect.poll()) != null; ) {
            writeToUpstream(exchange);
        }
        if (clientClosed || perMessage) {
            RelayLegClose.afterFlush(upstream);
        }
    }

    private void writeToUpstream(Exchange exchange) {
        upstream.writeAndFlush(Unpooled.wrappedBuffer(exchange.request.getBytes())).addListener(written -> {
            if (written.isSuccess()) {
                exchange.written = true;
            } else {
                exchange.response.completeExceptionally(written.cause());
                RelayLegClose.now(upstream);
            }
        });
        if (upstreamStallCheck != null) {
            // what this write added is not the upstream failing to take what was already waiting
            bytesWaitingAtLastStallCheck = upstream.bytesBeforeWritable();
        }
        upstreamWritabilityChanged();
    }

    void fromUpstream(byte[] bytesRead) {
        if (perMessage) {
            // the client is no longer this connection's: its messages have their own upstream connections
            return;
        }
        BinaryMessage binaryResponse = bytes(bytesRead);
        Exchange answered = latest != null && latest.written ? latest : null;
        LogEntry logEntry = new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(Level.INFO)
            .setCorrelationId(latestCorrelationId);
        if (answered != null) {
            logEntry
                .setMessageFormat("returning binary response:{}from:{}for forwarded binary request:{}")
                .setArguments(SensitiveLogValue.of(formatBytes(bytesRead)), target, SensitiveLogValue.of(formatBytes(answered.request.getBytes())));
        } else {
            logEntry
                .setMessageFormat("returning binary response:{}from:{}")
                .setArguments(SensitiveLogValue.of(formatBytes(bytesRead)), target);
        }
        mockServerLogger.logEvent(logEntry);
        if (answered != null) {
            latest = null;
            answered.response.complete(binaryResponse);
        }
        if (client.isActive()) {
            client.write(Unpooled.wrappedBuffer(bytesRead));
            if (!client.isWritable() && !upstreamHeldForClient) {
                upstreamHeldForClient = true;
                ChannelReadPause.pause(upstream);
            }
        }
    }

    void upstreamReadComplete() {
        client.flush();
    }

    void upstreamWritabilityChanged() {
        if (finished || !upstream.isActive()) {
            return;
        }
        if (upstream.isWritable()) {
            release(ClientHold.UPSTREAM_NOT_WRITABLE);
            stopUpstreamStallCheck();
        } else {
            hold(ClientHold.UPSTREAM_NOT_WRITABLE);
            long timeoutMillis = configuration.responseWriteStallTimeoutMillis();
            if (upstreamStallCheck == null && timeoutMillis > 0) {
                bytesWaitingAtLastStallCheck = upstream.bytesBeforeWritable();
                upstreamStallCheck = client.eventLoop().schedule(() -> checkUpstreamStall(timeoutMillis), timeoutMillis, MILLISECONDS);
            }
        }
    }

    /**
     * Without this an upstream that stops taking bytes would hold its client, which is never timed out, for good.
     */
    private void checkUpstreamStall(long timeoutMillis) {
        upstreamStallCheck = null;
        if (finished || !upstream.isActive() || upstream.isWritable()) {
            return;
        }
        long bytesWaiting = upstream.bytesBeforeWritable();
        if (bytesWaiting < bytesWaitingAtLastStallCheck) {
            bytesWaitingAtLastStallCheck = bytesWaiting;
            upstreamStallCheck = client.eventLoop().schedule(() -> checkUpstreamStall(timeoutMillis), timeoutMillis, MILLISECONDS);
            return;
        }
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setCorrelationId(latestCorrelationId)
                    .setMessageFormat("closing binary connection from:{}because:{}took none of the bytes waiting for it for:{}ms (responseWriteStallTimeoutMillis)")
                    .setArguments(client.remoteAddress(), target, timeoutMillis)
            );
        }
        RelayLegClose.now(upstream);
    }

    private void stopUpstreamStallCheck() {
        if (upstreamStallCheck != null) {
            upstreamStallCheck.cancel(false);
            upstreamStallCheck = null;
        }
    }

    /**
     * The listener is the user's code and may wait on the response, so it runs off the event loop. One connection's
     * messages are reported one at a time, in arrival order, and those not yet reported are counted, so a listener
     * slower than the client slows the client down.
     */
    private void reportToListener(Exchange exchange) {
        if (listener == null) {
            return;
        }
        if (++pendingListenerCalls > MAX_PENDING_LISTENER_CALLS) {
            hold(ClientHold.LISTENER_BEHIND);
        }
        SocketAddress clientAddress = client.remoteAddress();
        CompletableFuture<Void> called = new CompletableFuture<>();
        // one chain with the per-message forwarder's, so a connection handed back to it keeps its calls in order
        CompletableFuture<Void> previousCalled = client.attr(BinaryRequestProxyingHandler.PREVIOUS_LISTENER_CALL).getAndSet(called);
        Runnable call = () -> scheduler.scheduleLocalCallback(() -> {
            Throwable thrown = null;
            try {
                listener.onProxy(exchange.request, exchange.response, target, clientAddress);
            } catch (Throwable throwable) {
                thrown = throwable;
            } finally {
                called.complete(null);
                listenerReturned(thrown);
            }
        }, false);
        if (previousCalled == null) {
            call.run();
        } else {
            previousCalled.thenRun(call);
        }
    }

    private void listenerReturned(Throwable thrown) {
        try {
            client.eventLoop().execute(() -> {
                if (--pendingListenerCalls <= MAX_PENDING_LISTENER_CALLS / 2) {
                    release(ClientHold.LISTENER_BEHIND);
                }
                if (thrown != null) {
                    // reported and closed as a listener that throws is without a relay
                    client.pipeline().fireExceptionCaught(thrown);
                    closeOnFlush(client);
                }
            });
        } catch (RejectedExecutionException serverStopping) {
            // the event loop has shut down, and the connection with it
        }
    }

    private void refuse(String reason) {
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setCorrelationId(latestCorrelationId)
                    .setMessageFormat("closing binary connection from:{}which is not forwarded to:{}on one upstream connection (" + SETTING + ") because:{}")
                    .setArguments(client.remoteAddress(), target, reason)
            );
        }
        finished = true;
        if (upstream != null) {
            RelayLegClose.now(upstream);
        }
        closeOnFlush(client);
    }

    private void upstreamClosed() {
        finished = true;
        stopUpstreamStallCheck();
        releaseEveryHold();
        // a connection that never connected fails its messages when its connect completes, before or after this
        if (connected && latest != null) {
            latest.response.complete(null);
            latest = null;
        }
        if (!perMessage) {
            // what the upstream sent before it closed is delivered first
            closeOnFlush(client);
        }
    }

    private void clientClosed() {
        if (clientClosed) {
            return;
        }
        clientClosed = true;
        // the upstream is read again so that its close is seen
        releaseEveryHold();
        if (connected && !finished && !perMessage) {
            // what the client sent before it closed is delivered first
            RelayLegClose.afterFlush(upstream);
        }
    }

    private void hold(ClientHold reason) {
        if (!clientClosed && !finished && !perMessage && clientHolds.add(reason)) {
            ChannelReadPause.pause(client);
        }
    }

    private void release(ClientHold reason) {
        if (clientHolds.remove(reason)) {
            ChannelReadPause.resume(client);
        }
    }

    private void releaseEveryHold() {
        for (ClientHold reason : ClientHold.values()) {
            release(reason);
        }
        if (upstreamHeldForClient) {
            upstreamHeldForClient = false;
            ChannelReadPause.resume(upstream);
        }
    }

    /** A message read from the client and the future its listener call is given. */
    private static final class Exchange {
        private final BinaryMessage request;
        private final CompletableFuture<BinaryMessage> response = new CompletableFuture<>();
        private boolean written;
        private boolean overtakenWarned;

        private Exchange(BinaryMessage request) {
            this.request = request;
        }
    }
}
