package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapterBuilder;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.SCHEME;
import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_ID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * How promptly each HTTP/2 leg of the CONNECT/SOCKS relay closes when the tunnel ends, with both legs' pipelines as
 * {@code RelayConnectHandler} builds them and a real HTTP/2 peer standing in for MockServer. Closed through its
 * pipeline, an {@code HttpToHttp2ConnectionHandler} with a stream open sends a {@code GOAWAY} and stays connected for
 * its graceful-shutdown timeout (30 s). No time passes unless a test advances it, so a leg still open at the end of one
 * is a leg waiting for that timeout.
 */
public class RelayHttp2LegCloseTest {

    private final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private final WriteRefuser proxyClientSocket = new WriteRefuser();
    private final List<Integer> requestsAtServer = new ArrayList<>();
    private final List<Integer> requestBodyBytesAtServer = new ArrayList<>();
    private final List<FullHttpResponse> heldAnswers = new ArrayList<>();
    private final List<FullHttpResponse> responsesHandedToTheRelay = new ArrayList<>();
    private ProxyClientChannel proxyClient;
    private Http2Connection proxyClientConnection;
    private Http2Connection loopbackConnection;
    private EmbeddedChannel loopback;
    private EmbeddedChannel server;

    @Before
    public void connect() {
        MockServerLogger mockServerLogger = new MockServerLogger(RelayHttp2LegCloseTest.class) {
            @Override
            public void logEvent(LogEntry logEntry) {
                logged.add(logEntry);
            }
        };
        proxyClient = new ProxyClientChannel();
        loopbackConnection = new DefaultHttp2Connection(false);
        LoopbackHttp2StreamIdRemapper remapper = new LoopbackHttp2StreamIdRemapper(mockServerLogger, loopbackConnection, proxyClient);
        LoopbackHttp2StreamErrorHandler errorHandler = new LoopbackHttp2StreamErrorHandler(mockServerLogger, loopbackConnection, remapper, proxyClient);
        proxyClientConnection = new DefaultHttp2Connection(true);
        loopback = new EmbeddedChannel(
            new HttpToHttp2ConnectionHandlerBuilder()
                .frameListener(errorHandler.frameListener(new InboundHttp2ToHttpAdapterBuilder(loopbackConnection).maxContentLength(1024 * 1024).build()))
                .connection(loopbackConnection)
                .build(),
            errorHandler,
            remapper,
            new LoopbackHttp2ConnectionCloseHandler(mockServerLogger, loopbackConnection, proxyClient, remapper),
            new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    if (msg instanceof FullHttpResponse) {
                        responsesHandedToTheRelay.add((FullHttpResponse) msg);
                    }
                    ctx.fireChannelRead(msg);
                }
            },
            new DownstreamProxyRelayHandler(mockServerLogger, proxyClient)
        );
        proxyClient.pipeline().addLast(
            proxyClientSocket,
            new HttpToHttp2ConnectionHandlerBuilder()
                .frameListener(errorHandler.proxyClientFrameListener(proxyClientConnection, new Http2FrameAdapter()))
                .connection(proxyClientConnection)
                .build(),
            new UpstreamProxyRelayHandler(mockServerLogger, proxyClient, loopback, "localhost", 443, 1024 * 1024)
        );
        Http2Connection serverConnection = new DefaultHttp2Connection(true);
        server = new EmbeddedChannel(
            new HttpToHttp2ConnectionHandlerBuilder()
                .frameListener(new InboundHttp2ToHttpAdapterBuilder(serverConnection).maxContentLength(1024 * 1024).build())
                .connection(serverConnection)
                .build(),
            new SimpleChannelInboundHandler<FullHttpRequest>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                    int streamId = request.headers().getInt(STREAM_ID.text());
                    requestsAtServer.add(streamId);
                    requestBodyBytesAtServer.add(request.content().readableBytes());
                    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer("answer for " + request.uri(), StandardCharsets.UTF_8));
                    response.headers().setInt(STREAM_ID.text(), streamId);
                    heldAnswers.add(response);
                }
            }
        );
        pump();
    }

    @After
    public void close() {
        heldAnswers.forEach(ReferenceCountUtil::release);
        // at its socket: closed through its pipeline it would wait for a stream whose request never arrived whole
        server.unsafe().close(server.voidPromise());
        loopback.finishAndReleaseAll();
        server.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    @Test
    public void shouldCloseTheLoopbackAtOnceWhenTheRelayEndsWithAnotherLoopbackStreamOpen() throws Exception {
        relay(3, "/answered");
        relay(5, "/unanswered");
        assertThat(requestsAtServer, contains(1, 3));
        assertThat(loopbackConnection.numActiveStreams(), is(2));

        // as a TLS handler whose close_notify waits behind bytes its client has not taken: open, refusing every write
        proxyClientSocket.refusing = true;
        proxyClientSocket.holdingClose = true;
        answer(1);

        assertThat("the client's leg is still open", proxyClient.isOpen(), is(true));
        assertThat("the relay stopped reading the loopback", loopback.config().isAutoRead(), is(false));
        assertThat("the loopback is closed without waiting for the stream that can no longer be answered", loopback.isOpen(), is(false));
        assertThat(loopbackConnection.numActiveStreams(), is(0));
        assertThat("the failed write is logged once", logged(Level.ERROR), contains("exception while returning writing:{}"));
        assertRelayedResponseReleased();
    }

    @Test
    public void shouldCloseBothLegsAtOnceWhenAWriteToTheClientFailsWithOtherStreamsOpen() throws Exception {
        relay(3, "/answered");
        relay(5, "/unanswered");
        assertThat(proxyClientConnection.numActiveStreams(), is(2));
        assertThat(loopbackConnection.numActiveStreams(), is(2));

        // every write fails, but a close is carried out
        proxyClientSocket.refusing = true;
        answer(1);

        // closed through its pipeline, not at its socket: the failed write is a connection error to its HTTP/2 handler
        assertThat("the client's leg did not wait for its other stream", proxyClient.isOpen(), is(false));
        assertThat(proxyClientConnection.numActiveStreams(), is(0));
        assertThat("the loopback did not wait for its other stream", loopback.isOpen(), is(false));
        assertThat(loopbackConnection.numActiveStreams(), is(0));
        assertRelayedResponseReleased();
    }

    @Test
    public void shouldCloseTheLoopbackAtOnceWithoutLoggingWhenTheClientGoesAwayWithResponseDataQueued() throws Exception {
        relay(3, "/answered");
        relay(5, "/unanswered");
        // the client takes nothing more, so the response's body waits in the client leg's flow controller
        proxyClientConnection.remote().flowController().initialWindowSize(0);
        answer(1);
        assertThat("the response was relayed and is queued", proxyClientConnection.stream(3).state().localSideOpen(), is(true));
        assertThat(loopback.isOpen(), is(true));

        proxyClient.unsafe().close(proxyClient.voidPromise());
        pump();

        assertThat("the loopback is closed without waiting for the stream that can no longer be answered", loopback.isOpen(), is(false));
        assertThat(loopbackConnection.numActiveStreams(), is(0));
        assertThat("nothing logged above DEBUG for a client that went away", loggedAboveDebug(), is(empty()));
        assertRelayedResponseReleased();
    }

    @Test
    public void shouldCloseTheLoopbackAtOnceWithoutLoggingWhenTheClientGoesAwayWithARequestInFlight() throws Exception {
        relay(3, "/in-flight");
        assertThat(requestsAtServer, contains(1));
        assertThat(loopbackConnection.numActiveStreams(), is(1));

        proxyClient.unsafe().close(proxyClient.voidPromise());
        pump();

        assertThat("the loopback is closed without waiting for a response no one is left to take", loopback.isOpen(), is(false));
        assertThat(loopbackConnection.numActiveStreams(), is(0));
        assertThat("nothing logged above DEBUG for a client that went away", loggedAboveDebug(), is(empty()));
    }

    @Test
    public void shouldFlushARequestAlreadyRelayedBeforeClosingTheLoopbackOfAClientThatHasGone() throws Exception {
        proxyClientConnection.remote().createStream(3, true);
        loopback.write(request(3, "/sent-then-gone"));
        assertThat("the request is written but not yet flushed", loopback.outboundMessages(), is(empty()));

        proxyClient.unsafe().close(proxyClient.voidPromise());
        pump();

        assertThat("the request reached MockServer", requestsAtServer, contains(1));
        assertThat(loopback.isOpen(), is(false));
    }

    @Test
    public void shouldDeliverARequestWaitingForFlowControlWindowBeforeClosingTheLoopbackOfAClientThatHasGone() throws Exception {
        FullHttpRequest upload = upload(3, "/upload", 200_000);
        assertThat("part of the request waits for MockServer to extend its window", loopbackConnection.remote().flowController().hasFlowControlled(loopbackConnection.stream(1)), is(true));

        proxyClient.unsafe().close(proxyClient.voidPromise());
        pump();

        assertThat("the request reached MockServer", requestsAtServer, contains(1));
        assertThat("with its whole body", requestBodyBytesAtServer, contains(200_000));
        assertThat("the loopback is then closed without waiting for a response no one is left to take", loopback.isOpen(), is(false));
        assertThat(loopbackConnection.numActiveStreams(), is(0));
        assertThat("nothing logged above DEBUG for a client that went away", loggedAboveDebug(), is(empty()));
        assertThat(upload.refCnt(), is(0));
    }

    @Test
    public void shouldDeliverEveryRequestStillBeingWrittenBeforeClosingTheLoopbackOfAClientThatHasGone() throws Exception {
        upload(3, "/shorter", 100_000);
        upload(5, "/longer", 300_000);

        proxyClient.unsafe().close(proxyClient.voidPromise());
        pump();

        // the shorter request is written first, with most of the longer one still waiting for window
        assertThat("both requests reached MockServer whole", requestBodyBytesAtServer, contains(100_000, 300_000));
        assertThat(loopback.isOpen(), is(false));
        assertThat(loopbackConnection.numActiveStreams(), is(0));
        assertThat("nothing logged above DEBUG for a client that went away", loggedAboveDebug(), is(empty()));
    }

    @Test
    public void shouldCloseTheLoopbackOfAClientThatHasGoneWhenItsLastRequestBeingWrittenFails() throws Exception {
        upload(3, "/unanswered", 10);
        pump();
        upload(5, "/refused", 200_000);
        assertThat(loopbackConnection.numActiveStreams(), is(2));

        proxyClient.unsafe().close(proxyClient.voidPromise());
        proxyClient.runPendingTasks();
        loopback.runPendingTasks();
        assertThat("closing gracefully while a request is being written", loopback.isOpen(), is(true));
        // MockServer resets the stream of the request still being written
        ByteBuf bytes;
        while ((bytes = loopback.readOutbound()) != null) {
            server.writeInbound(bytes);
        }
        ChannelHandlerContext serverCtx = server.pipeline().context(Http2ConnectionHandler.class);
        ((Http2ConnectionHandler) serverCtx.handler()).resetStream(serverCtx, 3, Http2Error.CANCEL.code(), serverCtx.newPromise());
        server.flush();
        pump();

        assertThat("only the first request reached MockServer", requestsAtServer, contains(1));
        assertThat("the loopback does not wait for the stream that can no longer be answered", loopback.isOpen(), is(false));
        assertThat(loopbackConnection.numActiveStreams(), is(0));
    }

    @Test
    public void shouldDeliverARequestStillBeingWrittenWhenAResponseOnAnotherStreamFailsAsItsClientLeaves() throws Exception {
        relay(3, "/answered");
        // the client takes nothing more, so the response's body waits in the client leg's flow controller
        proxyClientConnection.remote().flowController().initialWindowSize(0);
        answer(1);
        FullHttpRequest upload = upload(5, "/upload", 200_000);
        assertThat("part of the request waits for MockServer to extend its window", loopbackConnection.remote().flowController().hasFlowControlled(loopbackConnection.stream(3)), is(true));

        // the client leg sees its client leave, and then fails the response it had queued
        proxyClient.unsafe().close(proxyClient.voidPromise());
        pump();

        assertThat("the request reached MockServer whole", requestBodyBytesAtServer, contains("upload for /answered".length(), 200_000));
        assertThat("the loopback is then closed without waiting for a response no one is left to take", loopback.isOpen(), is(false));
        assertThat(loopbackConnection.numActiveStreams(), is(0));
        assertThat("nothing logged above DEBUG for a client that went away", loggedAboveDebug(), is(empty()));
        assertThat(upload.refCnt(), is(0));
        assertRelayedResponseReleased();
    }

    @Test
    public void shouldDeliverARequestStillBeingWrittenWhenAResponseArrivesBeforeTheClientLegSeesItsClientLeave() throws Exception {
        relay(3, "/answered");
        relay(5, "/answered-later");
        FullHttpRequest upload = upload(7, "/upload", 200_000);

        closeProxyClientSocket();
        answerWithoutExtendingTheWindow(1);
        assertThat("left open for the request still being written", loopback.isOpen(), is(true));
        assertThat("and still read, for MockServer's window updates", loopback.config().isAutoRead(), is(true));
        int writesRefused = proxyClientSocket.refused;
        answerWithoutExtendingTheWindow(3);
        assertThat(responsesHandedToTheRelay, hasSize(2));
        assertThat("both undelivered responses were released", refCnts(responsesHandedToTheRelay), everyItem(is(0)));
        assertThat("the second was dropped, not written to the client", proxyClientSocket.refused, is(writesRefused));

        fireProxyClientInactive();
        assertThat("still open for the request still being written", loopback.isOpen(), is(true));
        assertThat("and closing gracefully, so for no longer than the graceful-shutdown timeout", loopbackConnection.goAwaySent(), is(true));
        pump();

        assertThat("the request reached MockServer whole", requestBodyBytesAtServer, contains("upload for /answered".length(), "upload for /answered-later".length(), 200_000));
        assertThat(loopback.isOpen(), is(false));
        assertThat(loopbackConnection.numActiveStreams(), is(0));
        assertThat("nothing logged above DEBUG for a client that went away", loggedAboveDebug(), is(empty()));
        assertThat(upload.refCnt(), is(0));
        assertThat("nothing is left queued for the client", proxyClient.outboundMessages(), is(empty()));
    }

    @Test
    public void shouldLeaveTheLoopbackToTheClientLegWhenAResponseFailsBeforeItSeesItsClientLeaveWithNoRequestBeingWritten() throws Exception {
        relay(3, "/answered");
        relay(5, "/unanswered");

        closeProxyClientSocket();
        answer(1);

        // a request already written may not yet have been read by MockServer, which closing the loopback could lose
        assertThat("left open for the client's leg to end", loopback.isOpen(), is(true));
        assertThat("and still read, so that MockServer's close is seen", loopback.config().isAutoRead(), is(true));
        assertThat("the undelivered response was released", refCnts(responsesHandedToTheRelay), contains(0));

        fireProxyClientInactive();
        pump();

        assertThat(loopback.isOpen(), is(false));
        assertThat(loopbackConnection.numActiveStreams(), is(0));
        assertThat("nothing logged above DEBUG for a client that went away", loggedAboveDebug(), is(empty()));
    }

    @Test
    public void shouldCloseTheLoopbackWhenTheClientLegSeesItsClientLeaveOnlyAfterTheRequestIsWritten() throws Exception {
        relay(3, "/answered");
        upload(5, "/upload", 200_000);

        closeProxyClientSocket();
        answer(1);
        assertThat("the request reached MockServer whole", requestBodyBytesAtServer, contains("upload for /answered".length(), 200_000));
        assertThat("no one has yet closed the loopback", loopback.isOpen(), is(true));

        fireProxyClientInactive();
        pump();

        assertThat(loopback.isOpen(), is(false));
        assertThat(loopbackConnection.numActiveStreams(), is(0));
        assertThat("nothing logged above DEBUG for a client that went away", loggedAboveDebug(), is(empty()));
    }

    @Test
    public void shouldCloseTheLoopbackOfAClientThatHasGoneWhenTheRequestOutlivingAFailedResponseFails() throws Exception {
        relay(3, "/answered");
        proxyClientConnection.remote().flowController().initialWindowSize(0);
        answer(1);
        FullHttpRequest upload = upload(5, "/refused", 200_000);

        proxyClient.unsafe().close(proxyClient.voidPromise());
        proxyClient.runPendingTasks();
        loopback.runPendingTasks();
        assertThat("left open for the request still being written", loopback.isOpen(), is(true));
        assertThat("and closing gracefully, so for no longer than the graceful-shutdown timeout", loopbackConnection.goAwaySent(), is(true));
        // MockServer resets the stream of the request still being written
        ByteBuf bytes;
        while ((bytes = loopback.readOutbound()) != null) {
            server.writeInbound(bytes);
        }
        ChannelHandlerContext serverCtx = server.pipeline().context(Http2ConnectionHandler.class);
        ((Http2ConnectionHandler) serverCtx.handler()).resetStream(serverCtx, 3, Http2Error.CANCEL.code(), serverCtx.newPromise());
        server.flush();
        pump();

        assertThat("only the first request reached MockServer", requestsAtServer, contains(1));
        assertThat("the loopback does not wait for the stream that can no longer be answered", loopback.isOpen(), is(false));
        assertThat(loopbackConnection.numActiveStreams(), is(0));
        assertThat(upload.refCnt(), is(0));
        assertRelayedResponseReleased();
    }

    @Test
    public void shouldReleaseARequestStillBeingWrittenWhenTheLoopbackOfAClientThatHasGoneClosesFirst() throws Exception {
        relay(3, "/answered");
        proxyClientConnection.remote().flowController().initialWindowSize(0);
        answer(1);
        FullHttpRequest upload = upload(5, "/upload", 200_000);

        proxyClient.unsafe().close(proxyClient.voidPromise());
        proxyClient.runPendingTasks();
        loopback.runPendingTasks();
        assertThat("left open for the request still being written", loopback.isOpen(), is(true));
        // MockServer closes its end
        loopback.unsafe().close(loopback.voidPromise());
        pump();

        assertThat(loopback.isOpen(), is(false));
        assertThat(loopbackConnection.numActiveStreams(), is(0));
        assertThat(upload.refCnt(), is(0));
        assertRelayedResponseReleased();
    }

    @Test
    public void shouldCloseTheLoopbackOfAClientThatHasGoneWhenItsGracefulCloseTimesOutWithTheWindowNeverExtended() throws Exception {
        relay(3, "/answered");
        proxyClientConnection.remote().flowController().initialWindowSize(0);
        answer(1);
        FullHttpRequest upload = upload(5, "/upload", 200_000);

        proxyClient.unsafe().close(proxyClient.voidPromise());
        proxyClient.runPendingTasks();
        loopback.runPendingTasks();
        assertThat("left open for the request still being written", loopback.isOpen(), is(true));
        assertThat("and closing gracefully", loopbackConnection.goAwaySent(), is(true));
        // EmbeddedChannel.close() cancelled the timeout that graceful close scheduled, so it is asked for again, through
        // the pipeline. Nothing the loopback writes is taken to MockServer, so no window update ever comes back.
        loopback.pipeline().close();
        loopback.advanceTimeBy(29, TimeUnit.SECONDS);
        loopback.runScheduledPendingTasks();
        assertThat("still waiting for the request to be written", loopback.isOpen(), is(true));

        loopback.advanceTimeBy(2, TimeUnit.SECONDS);
        loopback.runScheduledPendingTasks();
        loopback.runPendingTasks();

        assertThat("closed by the graceful shutdown's timeout", loopback.isOpen(), is(false));
        assertThat(loopbackConnection.numActiveStreams(), is(0));
        assertThat("the request that was never written is reported", logged(Level.ERROR), contains("exception while returning response for request:{}"));
        // the part of the request written before the window ran out, which no one took
        loopback.releaseOutbound();
        assertThat(upload.refCnt(), is(0));
        assertThat("the undelivered response was released", refCnts(responsesHandedToTheRelay), contains(0));
    }

    @Test
    public void shouldCloseTheLoopbackAtOnceWithARequestStillBeingWrittenWhenTheClientLegIsOpenButRefusingWrites() throws Exception {
        relay(3, "/answered");
        FullHttpRequest upload = upload(5, "/upload", 200_000);

        // as a TLS handler whose close_notify waits behind bytes its client has not taken: open, refusing every write
        proxyClientSocket.refusing = true;
        proxyClientSocket.holdingClose = true;
        answer(1);

        assertThat("the client's leg is still open", proxyClient.isOpen(), is(true));
        assertThat("the loopback is closed at once", loopback.isOpen(), is(false));
        assertThat("the relay stopped reading the loopback", loopback.config().isAutoRead(), is(false));
        assertThat("the request did not reach MockServer", requestsAtServer, contains(1));
        assertThat("both failures are logged", logged(Level.ERROR), contains("exception while returning writing:{}", "exception while returning response for request:{}"));
        assertThat(upload.refCnt(), is(0));
        assertRelayedResponseReleased();
    }

    @Test
    public void shouldDeliverARequestStillBeingWrittenWhenTheFailedWriteHasClosedTheClientLeg() throws Exception {
        relay(3, "/answered");
        FullHttpRequest upload = upload(5, "/upload", 200_000);

        // every write fails, but a close is carried out: the client leg's HTTP/2 handler closes it on the failed write
        proxyClientSocket.refusing = true;
        answer(1);

        assertThat(proxyClient.isOpen(), is(false));
        assertThat("the request reached MockServer whole", requestBodyBytesAtServer, contains("upload for /answered".length(), 200_000));
        assertThat(loopback.isOpen(), is(false));
        assertThat(loopbackConnection.numActiveStreams(), is(0));
        assertThat(upload.refCnt(), is(0));
        assertRelayedResponseReleased();
    }

    /**
     * The response the relay was handed and could not deliver, and nothing left queued on either leg.
     */
    private void assertRelayedResponseReleased() {
        assertThat(responsesHandedToTheRelay, hasSize(1));
        assertThat("the undelivered response was released", responsesHandedToTheRelay.get(0).refCnt(), is(0));
        assertThat("nothing is left queued for the client", proxyClient.outboundMessages(), is(empty()));
        assertThat("nothing is left queued for MockServer", loopback.outboundMessages(), is(empty()));
    }

    private List<String> logged(Level level) {
        List<String> messages = new ArrayList<>();
        for (LogEntry entry : logged) {
            if (entry.getLogLevel() == level) {
                messages.add(entry.getMessageFormat());
            }
        }
        return messages;
    }

    private List<String> loggedAboveDebug() {
        List<String> messages = new ArrayList<>();
        for (LogEntry entry : logged) {
            if (entry.getLogLevel().toInt() > Level.DEBUG.toInt()) {
                messages.add(entry.getLogLevel() + " " + entry.getMessageFormat());
            }
        }
        return messages;
    }

    private void relay(int clientStreamId, String uri) throws Http2Exception {
        // half closed, as a client's stream is once its whole request has arrived
        proxyClientConnection.remote().createStream(clientStreamId, true);
        loopback.writeAndFlush(request(clientStreamId, uri));
        pump();
    }

    /**
     * Hands the relay a request as the client's leg does, so that the relay's own write of it to the loopback runs. No
     * bytes are moved, so a body above MockServer's 65,535 byte window is left partly waiting for window.
     */
    private FullHttpRequest upload(int clientStreamId, String uri, int bodyBytes) throws Http2Exception {
        proxyClientConnection.remote().createStream(clientStreamId, true);
        FullHttpRequest request = request(clientStreamId, uri, Unpooled.buffer(bodyBytes).writeZero(bodyBytes));
        proxyClient.pipeline().fireChannelRead(request);
        proxyClient.runPendingTasks();
        loopback.runPendingTasks();
        return request;
    }

    private static FullHttpRequest request(int clientStreamId, String uri) {
        return request(clientStreamId, uri, Unpooled.copiedBuffer("upload for " + uri, StandardCharsets.UTF_8));
    }

    private static FullHttpRequest request(int clientStreamId, String uri, ByteBuf body) {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, body);
        request.headers().setInt(STREAM_ID.text(), clientStreamId);
        request.headers().set("host", "localhost");
        request.headers().set(SCHEME.text(), "https");
        return request;
    }

    /**
     * Has the peer answer the request it holds for this loopback stream.
     */
    private void answer(int loopbackStreamId) {
        for (FullHttpResponse response : new ArrayList<>(heldAnswers)) {
            if (response.headers().getInt(STREAM_ID.text()) == loopbackStreamId) {
                heldAnswers.remove(response);
                server.writeAndFlush(response);
            }
        }
        pump();
    }

    /**
     * Has the peer answer, and takes the answer to the loopback, without taking the peer anything the loopback wrote:
     * MockServer has not yet read the request still being written, so has sent no window update for it.
     */
    private void answerWithoutExtendingTheWindow(int loopbackStreamId) {
        for (FullHttpResponse response : new ArrayList<>(heldAnswers)) {
            if (response.headers().getInt(STREAM_ID.text()) == loopbackStreamId) {
                heldAnswers.remove(response);
                server.writeAndFlush(response);
            }
        }
        ByteBuf bytes;
        while ((bytes = server.readOutbound()) != null) {
            loopback.writeInbound(bytes);
        }
        proxyClient.runPendingTasks();
        loopback.runPendingTasks();
    }

    /**
     * The client's socket closes: the leg is inactive and fails every write, and its {@code channelInactive} has not run.
     */
    private void closeProxyClientSocket() {
        proxyClient.socketClosed = true;
        proxyClientSocket.refusal = new ClosedChannelException();
        proxyClientSocket.refusing = true;
        proxyClientSocket.holdingClose = true;
    }

    private void fireProxyClientInactive() {
        proxyClient.socketClosed = false;
        proxyClientSocket.holdingClose = false;
        proxyClient.unsafe().close(proxyClient.voidPromise());
    }

    private static List<Integer> refCnts(List<FullHttpResponse> responses) {
        List<Integer> refCnts = new ArrayList<>();
        for (FullHttpResponse response : responses) {
            refCnts.add(response.refCnt());
        }
        return refCnts;
    }

    private void pump() {
        boolean moved;
        do {
            moved = false;
            ByteBuf bytes;
            while ((bytes = loopback.readOutbound()) != null) {
                server.writeInbound(bytes);
                moved = true;
            }
            while ((bytes = server.readOutbound()) != null) {
                if (loopback.isOpen()) {
                    loopback.writeInbound(bytes);
                } else {
                    bytes.release();
                }
                moved = true;
            }
            while ((bytes = proxyClient.readOutbound()) != null) {
                bytes.release();
                moved = true;
            }
            proxyClient.runPendingTasks();
            loopback.runPendingTasks();
        } while (moved);
    }

    /**
     * The client leg's socket: once refusing, fails every write while the connection stays open, and once holding its
     * close does not carry one out (as a TLS handler waiting to flush its {@code close_notify}).
     */
    private static final class WriteRefuser extends ChannelOutboundHandlerAdapter {
        private Throwable refusal = new SSLException("SSLEngine closed already");
        private boolean refusing;
        private boolean holdingClose;
        private int refused;

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
            if (!holdingClose) {
                ctx.close(promise);
            }
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (refusing) {
                refused++;
                ReferenceCountUtil.release(msg);
                promise.setFailure(refusal);
            } else {
                ctx.write(msg, promise);
            }
        }
    }

    /**
     * A client leg whose socket can be closed ahead of its {@code channelInactive}, as a real one's is: the close fails
     * what is queued, and the event loop may read the loopback, before the task that fires {@code channelInactive} runs.
     */
    private static final class ProxyClientChannel extends EmbeddedChannel {
        private boolean socketClosed;

        @Override
        public boolean isActive() {
            return !socketClosed && super.isActive();
        }
    }
}
