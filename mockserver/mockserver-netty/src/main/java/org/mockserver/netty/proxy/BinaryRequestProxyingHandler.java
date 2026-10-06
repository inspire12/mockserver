package org.mockserver.netty.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.ssl.SniCompletionEvent;
import io.netty.util.AttributeKey;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.log.model.SensitiveLogValue;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.model.BinaryMessage;
import org.mockserver.model.BinaryProxyListener;
import org.mockserver.model.BinaryRequestDefinition;
import org.mockserver.netty.proxy.relay.BinaryRelay;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.ChannelReadPause;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.mockserver.exception.ExceptionHandling.closeOnFlush;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;
import static org.mockserver.formatting.StringFormatter.formatBytes;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.mock.action.http.HttpActionHandler.getRemoteAddress;
import static org.mockserver.model.BinaryMessage.bytes;
import static org.mockserver.netty.unification.PortUnificationHandler.isSslEnabledUpstream;

/**
 * @author jamesdbloom
 */
@ChannelHandler.Sharable
public class BinaryRequestProxyingHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final AttributeKey<ForwardQueue> FORWARD_QUEUE = AttributeKey.valueOf("BINARY_FORWARD_QUEUE");
    public static final AttributeKey<CompletableFuture<Void>> PREVIOUS_LISTENER_CALL = AttributeKey.valueOf("PREVIOUS_BINARY_PROXY_LISTENER_CALL");
    private static final AttributeKey<Boolean> EXPECTATIONS_NOT_MATCHED_WARNED = AttributeKey.valueOf("BINARY_EXPECTATIONS_NOT_MATCHED_WARNED");
    /**
     * The client connection is not read while more than either of these wait to be forwarded, and is read again
     * once no more than half of each do. The queue only has to absorb what a client sends while one message is
     * connected and written; a client that stays ahead of that is slowed down rather than held in memory.
     */
    static final int MAX_WAITING_MESSAGES = 64;
    static final int MAX_WAITING_BYTES = 256 * 1024;

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final Scheduler scheduler;
    private final NettyHttpClient httpClient;
    private final BinaryProxyListener binaryExchangeCallback;
    private final HttpState httpState;

    public BinaryRequestProxyingHandler(final Configuration configuration, final MockServerLogger mockServerLogger, final Scheduler scheduler, final NettyHttpClient httpClient, final HttpState httpState) {
        super(true);
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.scheduler = scheduler;
        this.httpClient = httpClient;
        this.binaryExchangeCallback = configuration.binaryProxyListener();
        this.httpState = httpState;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf byteBuf) {
        BinaryMessage binaryRequest = bytes(ByteBufUtil.getBytes(byteBuf));
        String logCorrelationId = UUIDService.getNonSecureUUID();
        mockServerLogger.logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setCorrelationId(logCorrelationId)
                .setMessageFormat("received binary request:{}")
                .setArguments(SensitiveLogValue.of(ByteBufUtil.hexDump(binaryRequest.getBytes())))
        );
        final InetSocketAddress remoteAddress = getRemoteAddress(ctx);
        if (remoteAddress != null) {
            if (!answeredByExpectation(ctx, binaryRequest, logCorrelationId, remoteAddress)) {
                sendMessage(ctx, binaryRequest, logCorrelationId, remoteAddress);
            }
        } else if (httpState != null) {
            Expectation matchedExpectation = firstMatchingBinaryExpectation(binaryRequest, logCorrelationId);
            if (matchedExpectation != null && matchedExpectation.getBinaryResponse() != null) {
                replyFromExpectation(ctx, matchedExpectation, binaryRequest, logCorrelationId);
            } else {
                if (matchedExpectation != null) {
                    httpState.postProcess(matchedExpectation);
                }
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(logCorrelationId)
                            .setMessageFormat("no matching binary expectation for binary request:{}")
                            .setArguments(SensitiveLogValue.of(ByteBufUtil.hexDump(binaryRequest.getBytes())))
                    );
                }
                writeUnknownFormatMessage(ctx, binaryRequest, logCorrelationId);
            }
        } else {
            writeUnknownFormatMessage(ctx, binaryRequest, logCorrelationId);
        }
    }

    private Expectation firstMatchingBinaryExpectation(BinaryMessage binaryRequest, String logCorrelationId) {
        BinaryRequestDefinition binaryRequestDefinition = BinaryRequestDefinition.binaryRequest(binaryRequest.getBytes());
        binaryRequestDefinition.withLogCorrelationId(logCorrelationId);
        return httpState.firstMatchingExpectation(binaryRequestDefinition);
    }

    /**
     * Post-processes a matched expectation, which removes one that its Times have used up, and writes its binary
     * response if that has data.
     *
     * @return whether anything was written
     */
    private boolean replyFromExpectation(ChannelHandlerContext ctx, Expectation matchedExpectation, BinaryMessage binaryRequest, String logCorrelationId) {
        // before the write, so a client that has its reply finds the expectation's state already updated
        httpState.postProcess(matchedExpectation);
        byte[] reply = matchedExpectation.getBinaryResponse().getBinaryData();
        boolean written = reply != null && reply.length > 0;
        if (!written) {
            // a message that has no reply. Null and empty mean the same: an empty array is not serialised, so one
            // set through the Java client arrives as null, while raw JSON can still deliver it empty
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(FORWARDED_REQUEST)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(logCorrelationId)
                        .setMessageFormat("returning nothing, as the binary mock response is empty, for binary request:{}")
                        .setArguments(SensitiveLogValue.of(formatBytes(binaryRequest.getBytes())))
                );
            }
        } else {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(FORWARDED_REQUEST)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(logCorrelationId)
                        .setMessageFormat("returning binary mock response:{}for binary request:{}")
                        .setArguments(SensitiveLogValue.of(formatBytes(reply)), SensitiveLogValue.of(formatBytes(binaryRequest.getBytes())))
                );
            }
            ctx.writeAndFlush(Unpooled.copiedBuffer(reply));
        }
        return written;
    }

    /**
     * With forwardBinaryRequestsMatchExpectations, a message on a connection relayed on one upstream connection
     * whose bytes match a binary expectation is answered here and not forwarded. Any other connection is forwarded
     * as without the setting, and says so once.
     *
     * @return whether the message was answered, so is not to be forwarded
     */
    private boolean answeredByExpectation(ChannelHandlerContext ctx, BinaryMessage binaryRequest, String logCorrelationId, InetSocketAddress remoteAddress) {
        if (httpState == null || !configuration.forwardBinaryRequestsMatchExpectations()) {
            return false;
        }
        if (!configuration.forwardBinaryRequestsUseSingleConnection()
            || !BinaryRelay.relaysOnOneConnection(ctx, remoteAddress, configuration, mockServerLogger, scheduler, httpClient, binaryExchangeCallback)) {
            warnOnceThatExpectationsAreNotMatched(ctx, remoteAddress, logCorrelationId);
            return false;
        }
        if (!httpState.hasBinaryExpectations()) {
            return false;
        }
        Expectation matchedExpectation = firstMatchingBinaryExpectation(binaryRequest, logCorrelationId);
        if (matchedExpectation == null) {
            return false;
        }
        if (matchedExpectation.getBinaryResponse() == null) {
            httpState.postProcess(matchedExpectation);
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(logCorrelationId)
                        .setMessageFormat("forwarding binary request:{}to:{}because the expectation it matched has no binary response:{}")
                        .setArguments(SensitiveLogValue.of(formatBytes(binaryRequest.getBytes())), remoteAddress, matchedExpectation.getId())
                );
            }
            return false;
        }
        if (replyFromExpectation(ctx, matchedExpectation, binaryRequest, logCorrelationId)) {
            BinaryRelay.answeredLocally(ctx.channel(), logCorrelationId);
        }
        return true;
    }

    private void warnOnceThatExpectationsAreNotMatched(ChannelHandlerContext ctx, InetSocketAddress remoteAddress, String logCorrelationId) {
        if (ctx.channel().attr(EXPECTATIONS_NOT_MATCHED_WARNED).setIfAbsent(Boolean.TRUE) == null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setCorrelationId(logCorrelationId)
                    .setMessageFormat("binary expectations are not matched on binary connection from:{}to:{}because its messages are each forwarded on an upstream connection of their own; forwardBinaryRequestsMatchExpectations applies only to a connection relayed on one (forwardBinaryRequestsUseSingleConnection)")
                    .setArguments(ctx.channel().remoteAddress(), remoteAddress)
            );
        }
    }

    private void writeUnknownFormatMessage(ChannelHandlerContext ctx, BinaryMessage binaryRequest, String logCorrelationId) {
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(logCorrelationId)
                    .setMessageFormat(
                        "unknown message format, only HTTP requests are supported for mocking or HTTP & binary requests for proxying, but request is not being proxied and request is not valid HTTP, found request in binary: {} in utf8 text: {}"
                    )
                    .setArguments(SensitiveLogValue.of(ByteBufUtil.hexDump(binaryRequest.getBytes())), SensitiveLogValue.of(new String(binaryRequest.getBytes(), StandardCharsets.UTF_8)))
            );
        }
        ctx.writeAndFlush(Unpooled.copiedBuffer(
            "unknown message format, only HTTP requests are supported for mocking or HTTP & binary requests for proxying, but request is not being proxied and request is not valid HTTP".getBytes(StandardCharsets.UTF_8)
        ));
        ctx.close();
    }

    private void sendMessage(ChannelHandlerContext ctx, BinaryMessage binaryRequest, String logCorrelationId, InetSocketAddress remoteAddress) {
        if (configuration.forwardBinaryRequestsUseSingleConnection()
            && BinaryRelay.forward(ctx, binaryRequest, logCorrelationId, remoteAddress, configuration, mockServerLogger, scheduler, httpClient, binaryExchangeCallback)) {
            return;
        }
        if (configuration.forwardBinaryRequestsWithoutWaitingForResponse()) {
            processNotWaitingForResponse(ctx, binaryRequest, logCorrelationId, remoteAddress, sendInArrivalOrder(ctx, binaryRequest, remoteAddress));
        } else {
            CompletableFuture<BinaryMessage> binaryResponseFuture = httpClient
                .sendRequest(
                    binaryRequest,
                    isSslEnabledUpstream(ctx.channel()),
                    remoteAddress,
                    configuration.socketConnectionTimeoutInMillis()
                );
            processWaitingForResponse(ctx, binaryRequest, logCorrelationId, remoteAddress, binaryResponseFuture);
        }
    }

    /**
     * Without waiting for responses a client can send its next message at once, and each message is forwarded on
     * a connection of its own, so the next is only started once the previous has been written: otherwise the
     * upstream could accept the two in either order. Once a forward fails the messages behind it are not
     * attempted, as the connection is then closed. A client that has sent its messages and closed still has
     * every one of them forwarded.
     */
    private CompletableFuture<BinaryMessage> sendInArrivalOrder(ChannelHandlerContext ctx, BinaryMessage binaryRequest, InetSocketAddress remoteAddress) {
        ForwardQueue queue = ctx.channel().attr(FORWARD_QUEUE).get();
        if (queue == null) {
            queue = new ForwardQueue();
            ctx.channel().attr(FORWARD_QUEUE).set(queue);
        }
        QueuedForward forward = new QueuedForward(binaryRequest, remoteAddress, isSslEnabledUpstream(ctx.channel()));
        queue.waiting.add(forward);
        queue.waitingBytes += binaryRequest.getBytes().length;
        startWaitingForwards(ctx, queue);
        if (!queue.readsPaused && (queue.waiting.size() > MAX_WAITING_MESSAGES || queue.waitingBytes > MAX_WAITING_BYTES)) {
            queue.readsPaused = true;
            ChannelReadPause.pause(ctx.channel());
        }
        return forward.response;
    }

    private static void resumeReadsOnceDrained(ChannelHandlerContext ctx, ForwardQueue queue) {
        if (queue.readsPaused && queue.waiting.size() <= MAX_WAITING_MESSAGES / 2 && queue.waitingBytes <= MAX_WAITING_BYTES / 2) {
            queue.readsPaused = false;
            ChannelReadPause.resume(ctx.channel());
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        // nothing read from now on reaches this handler, so it has no reason left to hold reads back
        ForwardQueue queue = ctx.channel().attr(FORWARD_QUEUE).get();
        if (queue != null && queue.readsPaused) {
            queue.readsPaused = false;
            ChannelReadPause.resume(ctx.channel());
        }
    }

    private void startWaitingForwards(ChannelHandlerContext ctx, ForwardQueue queue) {
        if (queue.starting) {
            // called back from inside sendRequest: the loop below carries on, so a long queue does not recurse
            return;
        }
        queue.starting = true;
        try {
            int notForwarded = 0;
            InetSocketAddress remoteAddress = null;
            QueuedForward forward;
            while (!queue.inFlight && (forward = queue.waiting.poll()) != null) {
                queue.waitingBytes -= forward.request.getBytes().length;
                if (queue.failed) {
                    forward.response.completeExceptionally(new NotForwardedException());
                    remoteAddress = forward.remoteAddress;
                    notForwarded++;
                } else {
                    start(ctx, queue, forward);
                }
            }
            if (notForwarded > 0 && mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("not forwarding{}binary message(s) to{}because an earlier message on the same connection could not be forwarded")
                        .setArguments(notForwarded, remoteAddress)
                );
            }
        } finally {
            queue.starting = false;
            resumeReadsOnceDrained(ctx, queue);
        }
    }

    private void start(ChannelHandlerContext ctx, ForwardQueue queue, QueuedForward forward) {
        queue.inFlight = true;
        try {
            httpClient
                .sendRequest(forward.request, forward.secure, forward.remoteAddress, configuration.socketConnectionTimeoutInMillis(), sendFailure -> onEventLoop(ctx, () -> {
                    queue.inFlight = false;
                    if (sendFailure != null) {
                        queue.failed = true;
                        // a request that was not written gets no response; without this it would complete empty
                        forward.response.completeExceptionally(sendFailure);
                    }
                    startWaitingForwards(ctx, queue);
                }))
                .whenComplete((binaryResponse, throwable) -> {
                    if (throwable != null) {
                        forward.response.completeExceptionally(throwable);
                        onEventLoop(ctx, () -> {
                            queue.failed = true;
                            startWaitingForwards(ctx, queue);
                        });
                    } else {
                        forward.response.complete(binaryResponse);
                    }
                });
        } catch (RuntimeException cannotStart) {
            forward.response.completeExceptionally(cannotStart);
            queue.inFlight = false;
            queue.failed = true;
        }
    }

    private static void onEventLoop(ChannelHandlerContext ctx, Runnable task) {
        if (ctx.executor().inEventLoop()) {
            task.run();
        } else {
            try {
                ctx.executor().execute(task);
            } catch (RejectedExecutionException serverStopping) {
                // the event loop has shut down, so nothing more can be forwarded for this connection
            }
        }
    }

    /**
     * One client connection's messages waiting for the one being forwarded. Used only on that connection's event
     * loop. Bounded by not reading the connection while it is full, so it can exceed its limits only by the
     * message that takes it past them and what was read with that message.
     */
    private static final class ForwardQueue {
        private final Deque<QueuedForward> waiting = new ArrayDeque<>();
        private long waitingBytes;
        private boolean readsPaused;
        private boolean inFlight;
        private boolean failed;
        private boolean starting;
    }

    private static final class QueuedForward {
        private final BinaryMessage request;
        private final InetSocketAddress remoteAddress;
        private final boolean secure;
        private final CompletableFuture<BinaryMessage> response = new CompletableFuture<>();

        private QueuedForward(BinaryMessage request, InetSocketAddress remoteAddress, boolean secure) {
            this.request = request;
            this.remoteAddress = remoteAddress;
            this.secure = secure;
        }
    }

    /**
     * Fails the response of a message that was not forwarded because an earlier one on its connection failed.
     */
    static final class NotForwardedException extends RuntimeException {
        NotForwardedException() {
            super("not forwarded because an earlier message on the same connection could not be forwarded", null, false, false);
        }
    }

    /**
     * The listener is the user's code and may wait on the response, so it runs off the event loop, which would
     * otherwise forward nothing more until it returned. One connection's messages are still reported one at a
     * time, in arrival order, and a listener that throws still closes the connection.
     */
    private void notifyListener(ChannelHandlerContext ctx, BinaryMessage binaryRequest, CompletableFuture<BinaryMessage> binaryResponseFuture, InetSocketAddress remoteAddress) {
        SocketAddress clientAddress = ctx.channel().remoteAddress();
        CompletableFuture<Void> called = new CompletableFuture<>();
        CompletableFuture<Void> previousCalled = ctx.channel().attr(PREVIOUS_LISTENER_CALL).getAndSet(called);
        Runnable call = () -> scheduler.scheduleLocalCallback(() -> {
            try {
                binaryExchangeCallback.onProxy(binaryRequest, binaryResponseFuture, remoteAddress, clientAddress);
            } catch (Throwable throwable) {
                ctx.executor().execute(() -> exceptionCaught(ctx, throwable));
            } finally {
                called.complete(null);
            }
        }, false);
        if (previousCalled == null) {
            call.run();
        } else {
            previousCalled.thenRun(call);
        }
    }

    private void processNotWaitingForResponse(ChannelHandlerContext ctx, BinaryMessage binaryRequest, String logCorrelationId, InetSocketAddress remoteAddress, CompletableFuture<BinaryMessage> binaryResponseFuture) {
        if (binaryExchangeCallback != null) {
            notifyListener(ctx, binaryRequest, binaryResponseFuture, remoteAddress);
        }
        scheduler.submit(binaryResponseFuture, () -> {
            try {
                BinaryMessage binaryResponse = binaryResponseFuture.get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
                if (binaryResponse != null) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(FORWARDED_REQUEST)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(logCorrelationId)
                            .setMessageFormat("returning binary response:{}from:{}for forwarded binary request:{}")
                            .setArguments(SensitiveLogValue.of(formatBytes(binaryResponse.getBytes())), remoteAddress, SensitiveLogValue.of(formatBytes(binaryRequest.getBytes())))
                    );
                    ctx.writeAndFlush(Unpooled.copiedBuffer(binaryResponse.getBytes()));
                }
            } catch (Throwable throwable) {
                // messages not attempted behind a failed forward are logged once, together, when they are failed
                if (!(throwable.getCause() instanceof NotForwardedException) && mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(logCorrelationId)
                            .setMessageFormat("exception{}sending hex{}to{}closing connection")
                            .setArguments(throwable.getMessage(), SensitiveLogValue.of(ByteBufUtil.hexDump(binaryRequest.getBytes())), remoteAddress)
                            .setThrowable(throwable)
                    );
                }
                ctx.close();
            }
        }, false);
    }

    private void processWaitingForResponse(ChannelHandlerContext ctx, BinaryMessage binaryRequest, String logCorrelationId, InetSocketAddress remoteAddress, CompletableFuture<BinaryMessage> binaryResponseFuture) {
        scheduler.submit(binaryResponseFuture, () -> {
            try {
                BinaryMessage binaryResponse = binaryResponseFuture.get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(FORWARDED_REQUEST)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(logCorrelationId)
                        .setMessageFormat("returning binary response:{}from:{}for forwarded binary request:{}")
                        .setArguments(SensitiveLogValue.of(formatBytes(binaryResponse.getBytes())), remoteAddress, SensitiveLogValue.of(formatBytes(binaryRequest.getBytes())))
                );
                if (binaryExchangeCallback != null) {
                    binaryExchangeCallback.onProxy(binaryRequest, binaryResponseFuture, remoteAddress, ctx.channel().remoteAddress());
                }
                ctx.writeAndFlush(Unpooled.copiedBuffer(binaryResponse.getBytes()));
            } catch (Throwable throwable) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(logCorrelationId)
                            .setMessageFormat("exception{}sending hex{}to{}closing connection")
                            .setArguments(throwable.getMessage(), SensitiveLogValue.of(ByteBufUtil.hexDump(binaryRequest.getBytes())), remoteAddress)
                            .setThrowable(throwable)
                    );
                }
                ctx.close();
            }
        }, false);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
        // a mid-pipeline handler that swallows channelReadComplete starves Netty's HTTP/2
        // flow-control flush (Http2ConnectionHandler.channelReadComplete -> writePendingBytes),
        // stalling any h2 response larger than the peer's initial window - so propagate the event
        ctx.fireChannelReadComplete();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        BinaryRelay.clientInactive(ctx.channel());
        ctx.fireChannelInactive();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        BinaryRelay.clientWritabilityChanged(ctx.channel());
        ctx.fireChannelWritabilityChanged();
    }

    /**
     * A binary client that turns TLS on part way through reaches here once its server certificate is chosen, before
     * anything it sends over TLS is decrypted: the relay then starts TLS with the upstream on the same connection.
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof SniCompletionEvent && ((SniCompletionEvent) event).isSuccess()) {
            BinaryRelay.clientStartedTls(ctx.channel());
        }
        super.userEventTriggered(ctx, event);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (connectionClosedException(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught by " + this.getClass() + " handler -> closing pipeline " + ctx.channel())
                    .setThrowable(cause)
            );
        } else if (isSslOrDecoderFault(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("SSL or decoder fault caught by " + this.getClass() + " handler -> closing pipeline " + ctx.channel() + sniDescription(ctx.channel()))
                        .setThrowable(cause)
                );
            }
        }
        closeOnFlush(ctx.channel());
    }
}
