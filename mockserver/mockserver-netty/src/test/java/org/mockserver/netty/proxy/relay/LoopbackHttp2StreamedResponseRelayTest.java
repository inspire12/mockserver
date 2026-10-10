package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2LocalFlowController;
import io.netty.handler.codec.http2.Http2RemoteFlowController;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandlerBuilder;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.SCHEME;
import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_ID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The relay's HTTP/2 loopback pipeline as {@code RelayConnectHandler} builds it, between a real proxy client
 * connection and a real HTTP/2 peer standing in for MockServer, read as the frames each leg writes. A response of
 * undeclared length crosses the relay frame by frame while it is still being written; its bytes are returned to the
 * peer's window only as the client takes them; and a stream that ends early, on either leg, ends on the other alone.
 */
public class LoopbackHttp2StreamedResponseRelayTest {

    private static final int WINDOW = Http2CodecUtil.DEFAULT_WINDOW_SIZE;
    private static final int MAX_AGGREGATED_BYTES = 1024 * 1024;

    private EmbeddedChannel proxyClient;
    private Http2Connection proxyClientConnection;
    private Http2Connection loopbackConnection;
    private EmbeddedChannel loopback;
    private EmbeddedChannel server;
    private Http2ConnectionHandler serverHandler;
    private ChannelHandlerContext serverCtx;
    private LoopbackHttp2StreamIdRemapper remapper;
    private final Frames writtenToProxyClient = new Frames();
    private final Frames writtenToServer = new Frames();
    private final Frames writtenByServer = new Frames();
    private final List<StreamedHttp2ResponsePart> parts = new ArrayList<>();
    private final List<Throwable> loopbackErrors = new ArrayList<>();

    @Before
    public void connect() throws Exception {
        MockServerLogger mockServerLogger = new MockServerLogger();
        proxyClient = new EmbeddedChannel();
        loopbackConnection = new DefaultHttp2Connection(false);
        remapper = new LoopbackHttp2StreamIdRemapper(mockServerLogger, loopbackConnection, proxyClient);
        LoopbackHttp2StreamErrorHandler errorHandler = new LoopbackHttp2StreamErrorHandler(mockServerLogger, loopbackConnection, remapper, proxyClient);
        proxyClientConnection = new DefaultHttp2Connection(true);
        proxyClient.pipeline().addLast(new HttpToHttp2ConnectionHandlerBuilder()
            .frameListener(errorHandler.proxyClientFrameListener(proxyClientConnection, new Http2FrameAdapter()))
            .connection(proxyClientConnection)
            .build());
        proxyClient.pipeline().addLast(new StreamedHttp2ResponseWriter());
        LoopbackHttp2ResponseStreamer streamer = new LoopbackHttp2ResponseStreamer(loopbackConnection);
        HttpToHttp2ConnectionHandler loopbackHandler = new HttpToHttp2ConnectionHandlerBuilder()
            .frameListener(errorHandler.frameListener(
                streamer.relaying(LoopbackAggregatingListener.of(loopbackConnection, MAX_AGGREGATED_BYTES))))
            .connection(loopbackConnection)
            .build();
        loopback = new EmbeddedChannel(
            loopbackHandler,
            errorHandler,
            remapper,
            new LoopbackHttp2ConnectionCloseHandler(mockServerLogger, loopbackConnection, proxyClient, remapper),
            new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    if (msg instanceof StreamedHttp2ResponsePart) {
                        parts.add((StreamedHttp2ResponsePart) msg);
                    }
                    ctx.fireChannelRead(msg);
                }
            },
            new DownstreamProxyRelayHandler(mockServerLogger, proxyClient),
            new ChannelInboundHandlerAdapter() {
                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    loopbackErrors.add(cause);
                }
            }
        );
        streamer.widenConnectionWindow(loopback.pipeline().context(loopbackHandler));
        serverHandler = new Http2ConnectionHandlerBuilder()
            .connection(new DefaultHttp2Connection(true))
            .frameListener(new Http2FrameAdapter())
            .build();
        server = new EmbeddedChannel(serverHandler);
        serverCtx = server.pipeline().context(serverHandler);
        pump();
    }

    @After
    public void close() {
        loopback.finishAndReleaseAll();
        server.finishAndReleaseAll();
        // lost, not closed gracefully: a graceful close waits for a stream the client has not finished taking
        proxyClient.unsafe().close(proxyClient.voidPromise());
        proxyClient.finishAndReleaseAll();
        // the relay leaves a gone client's loopback for the client's leg to end, and there is no such leg here: what the
        // failed writes made the loopback write (its window updates) is released here
        loopback.unsafe().close(loopback.voidPromise());
        loopback.finishAndReleaseAll();
        writtenToProxyClient.close();
        writtenToServer.close();
        writtenByServer.close();
        assertThat("every part handed on was written or released", unreleasedParts(), is(empty()));
    }

    @Test
    public void shouldHandOnTheHeadersAndEachDataFrameAsItArrives() throws Exception {
        relay(3, "/events");

        serverWritesHeaders(1, new DefaultHttp2Headers().status("200").set("content-type", "text/event-stream"), false);
        assertThat(writtenToProxyClient.frames, contains("3 HEADERS 200"));
        assertThat(writtenToProxyClient.headers.get(3).get("content-type").toString(), is("text/event-stream"));
        assertThat("a stream's length is not invented", writtenToProxyClient.headers.get(3).get("content-length"), is(nullValue()));

        serverWritesData(1, "data: one\n\n", false);
        assertThat(writtenToProxyClient.frames, contains("3 HEADERS 200", "3 DATA 11"));

        serverWritesData(1, "data: two\n\n", false);
        serverWritesData(1, "", true);
        assertThat(writtenToProxyClient.frames, contains("3 HEADERS 200", "3 DATA 11", "3 DATA 11", "3 DATA 0 END"));
        assertThat(writtenToProxyClient.bodies.get(3), is("data: one\n\ndata: two\n\n"));
        assertExchangeEndedCleanly(3);
    }

    @Test
    public void shouldHoldAResponseOfDeclaredLengthUntilItIsWhole() throws Exception {
        relay(3, "/declared");

        serverWritesHeaders(1, new DefaultHttp2Headers().status("200").setInt("content-length", 10), false);
        serverWritesData(1, "01234", false);
        assertThat("nothing until it is whole", writtenToProxyClient.frames, is(empty()));

        serverWritesData(1, "56789", true);
        assertThat(writtenToProxyClient.frames, contains("3 HEADERS 200", "3 DATA 10 END"));
        assertThat(writtenToProxyClient.bodies.get(3), is("0123456789"));
        assertThat(parts, is(empty()));
        assertExchangeEndedCleanly(3);
    }

    @Test
    public void shouldRelayAResponseItsHeadersEndAsItsHeaderBlock() throws Exception {
        relay(3, "/empty");

        serverWritesHeaders(1, new DefaultHttp2Headers().status("204"), true);

        assertThat(writtenToProxyClient.frames, contains("3 HEADERS 204 END"));
        assertThat("no length is added", writtenToProxyClient.headers.get(3).get("content-length"), is(nullValue()));
        assertThat(parts.size(), is(1));
        assertExchangeEndedCleanly(3);
    }

    /**
     * A response to {@code HEAD} declares the length of the body a {@code GET} would have had, and has none. Held whole,
     * it was given the length of the body it came with: 0.
     */
    @Test
    public void shouldKeepTheDeclaredLengthOfAResponseItsHeadersEnd() throws Exception {
        relay(3, "/head");

        serverWritesHeaders(1, new DefaultHttp2Headers().status("200").setInt("content-length", 6).set("content-type", "text/plain"), true);

        assertThat(writtenToProxyClient.frames, contains("3 HEADERS 200 END"));
        assertThat(writtenToProxyClient.headers.get(3).get("content-length").toString(), is("6"));
        assertThat(writtenToProxyClient.headers.get(3).get("content-type").toString(), is("text/plain"));
        assertThat(writtenToProxyClient.dataBytes(3), is(0));
        assertExchangeEndedCleanly(3);
    }

    /**
     * Trailers end a response held whole as they end it on a direct connection: with the response, not before it.
     */
    @Test
    public void shouldHoldTheTrailersOfAResponseOfDeclaredLengthWithIt() throws Exception {
        relay(3, "/declared-with-trailers");

        serverWritesHeaders(1, new DefaultHttp2Headers().status("200").setInt("content-length", 4), false);
        serverWritesData(1, "body", false);
        serverWritesHeaders(1, new DefaultHttp2Headers().set("x-checksum", "abc"), true);

        assertThat(writtenToProxyClient.frames, contains("3 HEADERS 200", "3 DATA 4", "3 TRAILERS END"));
        assertThat(writtenToProxyClient.trailers.get(3).get("x-checksum").toString(), is("abc"));
        assertThat(parts, is(empty()));
        assertExchangeEndedCleanly(3);
    }

    /**
     * Netty empties the decompressor of a removed stream into the listener after it, which then has no stream to add
     * the last of it to; that must not reach Netty's own log as an error.
     */
    @Test
    public void shouldLogNothingWhenACompressedResponseIsCutShort() throws Exception {
        byte[] compressed = gzip("z".repeat(5000).getBytes(StandardCharsets.UTF_8));
        try (NettyErrorCapture nettyErrors = NettyErrorCapture.of(DefaultHttp2Connection.class)) {
            relay(3, "/compressed/reset");
            serverWritesHeaders(1, new DefaultHttp2Headers().status("200").set("content-encoding", "gzip"), false);
            serverWritesData(1, Unpooled.wrappedBuffer(compressed, 0, compressed.length / 2), false);
            serverHandler.resetStream(serverCtx, 1, Http2Error.CANCEL.code(), serverCtx.newPromise());
            server.flush();
            pump();

            relay(5, "/compressed/corrupt");
            serverWritesHeaders(3, new DefaultHttp2Headers().status("200").set("content-encoding", "gzip"), false);
            // a gzip header, then a deflate block of the reserved type 3
            serverWritesData(3, Unpooled.wrappedBuffer(new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff}), true);

            assertThat(writtenToProxyClient.resets, contains("3:" + Http2Error.CANCEL.code(), "5:" + Http2Error.INTERNAL_ERROR.code()));
            assertThat(nettyErrors.logged(), is(empty()));
        }
        assertThat(loopbackErrors, is(empty()));
        assertTunnelOpen();
    }

    /**
     * A response with a content coding is decoded by the relay, as before, and what it decodes to is bounded only as
     * a whole response is. So it is held whole whether or not it declares a length.
     */
    @Test
    public void shouldHoldACompressedResponseUntilItIsWholeWhetherOrNotItDeclaresALength() throws Exception {
        byte[] compressed = gzip("z".repeat(5000).getBytes(StandardCharsets.UTF_8));
        relay(3, "/compressed/declared");
        relay(5, "/compressed/undeclared");

        serverWritesHeaders(1, new DefaultHttp2Headers().status("200").set("content-encoding", "gzip").setInt("content-length", compressed.length), false);
        serverWritesHeaders(3, new DefaultHttp2Headers().status("200").set("content-encoding", "gzip"), false);
        serverWritesData(1, Unpooled.wrappedBuffer(compressed, 0, 10), false);
        serverWritesData(3, Unpooled.wrappedBuffer(compressed, 0, 10), false);
        assertThat(writtenToProxyClient.frames, is(empty()));

        serverWritesData(1, Unpooled.wrappedBuffer(compressed, 10, compressed.length - 10), true);
        serverWritesData(3, Unpooled.wrappedBuffer(compressed, 10, compressed.length - 10), true);

        for (int clientStreamId : new int[]{3, 5}) {
            assertThat(writtenToProxyClient.bodies.get(clientStreamId), is("z".repeat(5000)));
            assertThat("decoded, with the decoded length", writtenToProxyClient.headers.get(clientStreamId).get("content-length").toString(), is("5000"));
            assertThat(writtenToProxyClient.headers.get(clientStreamId).get("content-encoding"), is(nullValue()));
        }
        assertThat(parts, is(empty()));
    }

    @Test
    public void shouldRelayTrailersAndEndTheExchange() throws Exception {
        relay(3, "/grpc");

        serverWritesHeaders(1, new DefaultHttp2Headers().status("200").set("content-type", "application/grpc"), false);
        serverWritesData(1, "message", false);
        serverWritesHeaders(1, new DefaultHttp2Headers().set("grpc-status", "0"), true);

        assertThat(writtenToProxyClient.frames, contains("3 HEADERS 200", "3 DATA 7", "3 TRAILERS END"));
        assertThat(writtenToProxyClient.trailers.get(3).get("grpc-status").toString(), is("0"));
        assertExchangeEndedCleanly(3);
    }

    /**
     * Netty's decoder reports every header block with a priority, set or not. A listener reached another way is told
     * of one without, and hands it on the same.
     */
    @Test
    public void shouldHandOnAHeaderBlockReportedWithoutAPriority() throws Exception {
        relay(3, "/grpc");
        Http2ConnectionHandler loopbackHandler = loopback.pipeline().get(Http2ConnectionHandler.class);
        ChannelHandlerContext loopbackCtx = loopback.pipeline().context(loopbackHandler);

        loopbackHandler.decoder().frameListener().onHeadersRead(loopbackCtx, 1, new DefaultHttp2Headers().status("200"), 0, false);
        loopbackHandler.decoder().frameListener().onHeadersRead(loopbackCtx, 1, new DefaultHttp2Headers().set("grpc-status", "0"), 0, true);
        pump();

        assertThat(writtenToProxyClient.frames, contains("3 HEADERS 200", "3 TRAILERS END"));
        assertThat("the response ended the client's stream", proxyClientConnection.stream(3), nullValue());
    }

    /**
     * MockServer resets the stream of a 1xx it mocks. The 1xx is handed on as the interim response it was written
     * as, which must not end the client's stream (RFC 9113 section 8.1), and the reset with its code.
     */
    @Test
    public void shouldHandOnAnInformationalResponseWithoutEndingTheClientsStreamAndThenItsReset() throws Exception {
        relay(3, "/hints");

        serverWritesHeaders(1, new DefaultHttp2Headers().status("103").set("link", "</style.css>"), false);

        assertThat(writtenToProxyClient.frames, contains("3 HEADERS 103"));
        assertThat(writtenToProxyClient.headers.get(3).get("link").toString(), is("</style.css>"));
        assertThat("the client's stream is still open", proxyClientConnection.stream(3), notNullValue());
        assertThat(writtenToProxyClient.resets, is(empty()));

        serverHandler.resetStream(serverCtx, 1, Http2Error.NO_ERROR.code(), serverCtx.newPromise());
        server.flush();
        pump();

        assertThat(writtenToProxyClient.frames, contains("3 HEADERS 103"));
        assertThat(writtenToProxyClient.resets, contains("3:" + Http2Error.NO_ERROR.code()));
        assertThat(writtenToServer.resets, is(empty()));
        assertThat(remapper.mappedStreams(), is(0));
        assertTunnelOpen();
    }

    @Test
    public void shouldHandOnTheFinalResponseThatFollowsAnInterimResponse() throws Exception {
        relay(3, "/hints-then-page");
        relay(5, "/hints-then-stream");

        serverWritesHeaders(1, new DefaultHttp2Headers().status("103"), false);
        serverWritesHeaders(3, new DefaultHttp2Headers().status("103"), false);
        serverWritesHeaders(1, new DefaultHttp2Headers().status("200").setInt("content-length", 4), false);
        serverWritesData(1, "page", true);
        serverWritesHeaders(3, new DefaultHttp2Headers().status("200"), false);
        serverWritesData(3, "stream", true);

        assertThat(writtenToProxyClient.frames, contains("3 HEADERS 103", "5 HEADERS 103", "3 HEADERS 200", "3 DATA 4 END", "5 HEADERS 200", "5 DATA 6 END"));
        assertThat(writtenToProxyClient.resets, is(empty()));
        assertExchangeEndedCleanly(3);
        assertExchangeEndedCleanly(5);
    }

    @Test
    public void shouldInterleaveThePartsOfTwoResponsesEachOnItsOwnStream() throws Exception {
        // relayed in the other order, so the loopback's ids are not the client's
        proxyClientConnection.remote().createStream(3, true);
        proxyClientConnection.remote().createStream(5, true);
        writeRequest(5, "/second");
        writeRequest(3, "/first");
        assertThat(remapper.loopbackStreamId(5), is(1));
        assertThat(remapper.loopbackStreamId(3), is(3));

        serverWritesHeaders(1, new DefaultHttp2Headers().status("200"), false);
        serverWritesHeaders(3, new DefaultHttp2Headers().status("200"), false);
        serverWritesData(1, "second-a ", false);
        serverWritesData(3, "first-a ", false);
        serverWritesData(1, "second-b", true);
        serverWritesData(3, "first-b", false);
        serverWritesData(3, "", true);

        assertThat(writtenToProxyClient.frames, contains(
            "5 HEADERS 200", "3 HEADERS 200", "5 DATA 9", "3 DATA 8", "5 DATA 8 END", "3 DATA 7", "3 DATA 0 END"));
        assertThat(writtenToProxyClient.bodies.get(5), is("second-a second-b"));
        assertThat(writtenToProxyClient.bodies.get(3), is("first-a first-b"));
        assertExchangeEndedCleanly(3);
        assertExchangeEndedCleanly(5);
    }

    /**
     * The client takes nothing, so nothing is written to it and nothing is returned to MockServer, which may send one
     * window, the one the relay holds, and then waits. What the client takes later is what MockServer is then allowed.
     */
    @Test
    public void shouldLetThePeerSendOnlyWhatTheClientHasTaken() throws Exception {
        clientGrantsNoWindowUnasked();
        relay(3, "/large");
        serverWritesHeaders(1, new DefaultHttp2Headers().status("200"), false);

        serverWritesData(1, Unpooled.wrappedBuffer(new byte[6 * WINDOW]), true);

        assertThat(writtenToProxyClient.dataBytes(3), is(0));
        assertThat("one window, held by the relay", writtenByServer.dataBytes(1), is(WINDOW));
        assertThat("none of it returned", writtenToServer.windowIncrements(1), is(0));

        clientTakes(3, WINDOW);

        assertThat(writtenToProxyClient.dataBytes(3), is(WINDOW));
        assertThat("more was allowed once the client took some", writtenByServer.dataBytes(1), greaterThan(WINDOW));
        assertThat("never more than a window held", writtenByServer.dataBytes(1) - writtenToProxyClient.dataBytes(3), lessThanOrEqualTo(WINDOW));
        assertThat("no more returned than was written", writtenToServer.windowIncrements(1), lessThanOrEqualTo(WINDOW));

        while (writtenToProxyClient.dataBytes(3) < 6 * WINDOW) {
            clientTakes(3, WINDOW);
            assertThat(writtenByServer.dataBytes(1) - writtenToProxyClient.dataBytes(3), lessThanOrEqualTo(WINDOW));
        }
        assertThat(writtenToProxyClient.frames.get(writtenToProxyClient.frames.size() - 1).endsWith("END"), is(true));
        assertExchangeEndedCleanly(3);
    }

    /**
     * The bytes held for the stalled stream are a whole window, which is all a connection is allowed by default.
     */
    @Test
    public void shouldReturnEveryByteWrittenToTheClientToTheLoopbacksWindows() throws Exception {
        relay(3, "/events");
        serverWritesHeaders(1, new DefaultHttp2Headers().status("200"), false);

        // frames of different sizes, all within the client's window, and the stream left open
        int sent = 0;
        for (int frame = 1; frame <= 40; frame++) {
            serverWritesData(1, Unpooled.wrappedBuffer(new byte[frame * 37]), false);
            sent += frame * 37;
        }

        assertThat(writtenToProxyClient.dataBytes(3), is(sent));
        Http2LocalFlowController loopbackFlowController = loopbackConnection.local().flowController();
        assertThat("the stream is still open", loopbackConnection.stream(1).state(), is(Http2Stream.State.HALF_CLOSED_LOCAL));
        assertThat("nothing of the stream is held", loopbackFlowController.unconsumedBytes(loopbackConnection.stream(1)), is(0));
        assertThat("nor of the connection", loopbackFlowController.unconsumedBytes(loopbackConnection.connectionStream()), is(0));
    }

    @Test
    public void shouldNotLetAStreamTheClientIsNotTakingStopAnother() throws Exception {
        clientGrantsNoWindowUnasked();
        relay(3, "/stalled");
        serverWritesHeaders(1, new DefaultHttp2Headers().status("200"), false);
        serverWritesData(1, Unpooled.wrappedBuffer(new byte[6 * WINDOW]), true);
        assertThat("the relay holds a whole window for the stalled stream", writtenByServer.dataBytes(1), is(WINDOW));

        relay(5, "/other");
        clientTakes(5, 2 * WINDOW);
        serverWritesHeaders(3, new DefaultHttp2Headers().status("200"), false);
        serverWritesData(3, Unpooled.wrappedBuffer(new byte[2 * WINDOW]), true);

        assertThat("the other stream is relayed in full", writtenToProxyClient.dataBytes(5), is(2 * WINDOW));
        assertExchangeEndedCleanly(5);
        assertThat("the stalled stream is still open", proxyClientConnection.stream(3) != null, is(true));
        assertThat(writtenToProxyClient.dataBytes(3), is(0));
    }

    @Test
    public void shouldCancelTheLoopbackStreamWhenTheClientResetsAStreamedResponse() throws Exception {
        relay(3, "/abandoned");
        relay(5, "/other");
        serverWritesHeaders(1, new DefaultHttp2Headers().status("200"), false);
        serverWritesData(1, "data: one\n\n", false);

        clientResets(3, Http2Error.CANCEL);

        assertThat("MockServer is told to stop", writtenToServer.resets, contains("1:" + Http2Error.CANCEL.code()));
        assertThat("a reset is not answered with a reset", writtenToProxyClient.resets, is(empty()));
        serverWritesHeaders(3, new DefaultHttp2Headers().status("200"), false);
        serverWritesData(3, "unaffected", true);
        assertThat(writtenToProxyClient.bodies.get(5), is("unaffected"));
        assertTunnelOpen();
    }

    /**
     * The response's data is still waiting for the client's window when the client resets the stream, so the writes
     * of those parts fail. That is the one stream's failure, and the relay carries on for the others.
     */
    @Test
    public void shouldCarryOnWhenAStreamIsResetWithPartsStillWaitingForItsWindow() throws Exception {
        clientGrantsConnectionWindow(8 * WINDOW);
        relay(3, "/abandoned");
        relay(5, "/other");
        serverWritesHeaders(1, new DefaultHttp2Headers().status("200"), false);
        serverWritesData(1, Unpooled.wrappedBuffer(new byte[2 * WINDOW]), false);
        assertThat("a window of it waits in the client leg's flow controller", writtenToProxyClient.dataBytes(3), is(WINDOW));

        clientResets(3, Http2Error.CANCEL);

        assertThat(writtenToServer.resets, contains("1:" + Http2Error.CANCEL.code()));
        assertTunnelOpen();
        serverWritesHeaders(3, new DefaultHttp2Headers().status("200"), false);
        serverWritesData(3, "unaffected", true);
        assertThat(writtenToProxyClient.bodies.get(5), is("unaffected"));
        assertThat(writtenToProxyClient.goAways, is(0));
    }

    @Test
    public void shouldRelayThePeersResetOfAStreamedResponse() throws Exception {
        relay(3, "/cut");
        serverWritesHeaders(1, new DefaultHttp2Headers().status("200"), false);
        serverWritesData(1, "data: one\n\n", false);

        serverHandler.resetStream(serverCtx, 1, Http2Error.INTERNAL_ERROR.code(), serverCtx.newPromise());
        server.flush();
        pump();

        assertThat(writtenToProxyClient.frames, contains("3 HEADERS 200", "3 DATA 11"));
        assertThat(writtenToProxyClient.resets, contains("3:" + Http2Error.INTERNAL_ERROR.code()));
        assertThat(writtenToServer.resets, is(empty()));
        assertThat(remapper.mappedStreams(), is(0));
        assertTunnelOpen();
    }

    @Test
    public void shouldResetAStreamedResponseCutShortByTheLoopbackClosing() throws Exception {
        relay(3, "/cut");
        serverWritesHeaders(1, new DefaultHttp2Headers().status("200"), false);
        serverWritesData(1, "data: one\n\n", false);

        loopback.unsafe().close(loopback.voidPromise());
        pump();

        assertThat(writtenToProxyClient.frames, contains("3 HEADERS 200", "3 DATA 11"));
        assertThat(writtenToProxyClient.resets, contains("3:" + Http2Error.INTERNAL_ERROR.code()));
    }

    @Test
    public void shouldLeaveAStreamedResponseAlreadyHandedOnWholeToFinishWhenTheLoopbackCloses() throws Exception {
        clientGrantsConnectionWindow(8 * WINDOW);
        relay(3, "/whole");
        serverWritesHeaders(1, new DefaultHttp2Headers().status("200"), false);
        serverWritesData(1, Unpooled.wrappedBuffer(new byte[WINDOW + 100]), true);
        assertThat(writtenToProxyClient.dataBytes(3), is(WINDOW));
        assertThat("all of it sent by the peer", writtenByServer.dataBytes(1), is(WINDOW + 100));

        loopback.unsafe().close(loopback.voidPromise());
        pump();

        assertThat("not cut short: the rest waits only for the client's window", writtenToProxyClient.resets, is(empty()));
        clientTakes(3, WINDOW);
        assertThat(writtenToProxyClient.dataBytes(3), is(WINDOW + 100));
        assertThat(writtenToProxyClient.frames.get(writtenToProxyClient.frames.size() - 1).endsWith("END"), is(true));
    }

    private void assertExchangeEndedCleanly(int clientStreamId) {
        assertThat("the response ended the client's stream", proxyClientConnection.stream(clientStreamId), nullValue());
        assertThat(writtenToProxyClient.resets, is(empty()));
        assertThat(writtenToServer.resets, is(empty()));
        assertThat(loopbackErrors, is(empty()));
        assertTunnelOpen();
    }

    private void assertTunnelOpen() {
        assertThat(proxyClient.isActive(), is(true));
        assertThat(loopback.isActive(), is(true));
        assertThat(writtenToProxyClient.goAways, is(0));
    }

    private List<Integer> unreleasedParts() {
        List<Integer> referenceCounts = new ArrayList<>();
        for (StreamedHttp2ResponsePart part : parts) {
            // a header part has no buffer of its own, and the empty buffer is never released
            if (part.headers() == null && part.refCnt() > 0 && part.content() != Unpooled.EMPTY_BUFFER) {
                referenceCounts.add(part.refCnt());
            }
        }
        return referenceCounts;
    }

    private void relay(int clientStreamId, String uri) throws Http2Exception {
        // half closed, as a client's stream is once its whole request has arrived
        proxyClientConnection.remote().createStream(clientStreamId, true);
        writeRequest(clientStreamId, uri);
    }

    private void writeRequest(int clientStreamId, String uri) {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
        request.headers().setInt(STREAM_ID.text(), clientStreamId);
        request.headers().set("host", "localhost");
        request.headers().set(SCHEME.text(), "https");
        loopback.writeAndFlush(request);
        pump();
    }

    private void serverWritesHeaders(int loopbackStreamId, Http2Headers headers, boolean endOfStream) {
        serverHandler.encoder().writeHeaders(serverCtx, loopbackStreamId, headers, 0, endOfStream, serverCtx.newPromise());
        server.flush();
        pump();
    }

    private void serverWritesData(int loopbackStreamId, String data, boolean endOfStream) {
        serverWritesData(loopbackStreamId, Unpooled.copiedBuffer(data, StandardCharsets.UTF_8), endOfStream);
    }

    private void serverWritesData(int loopbackStreamId, ByteBuf data, boolean endOfStream) {
        serverHandler.encoder().writeData(serverCtx, loopbackStreamId, data, 0, endOfStream, serverCtx.newPromise());
        server.flush();
        pump();
    }

    // as the client-facing decoder handles a RST_STREAM frame: the frame listener, then the stream's close
    private void clientResets(int clientStreamId, Http2Error error) throws Http2Exception {
        Http2ConnectionHandler clientHandler = proxyClient.pipeline().get(Http2ConnectionHandler.class);
        clientHandler.decoder().frameListener().onRstStreamRead(proxyClient.pipeline().context(clientHandler), clientStreamId, error.code());
        proxyClientConnection.stream(clientStreamId).close();
        pump();
    }

    // as the client-facing decoder handles a WINDOW_UPDATE frame for the stream
    private void clientTakes(int clientStreamId, int bytes) throws Http2Exception {
        Http2Stream stream = proxyClientConnection.stream(clientStreamId);
        if (stream != null) {
            Http2RemoteFlowController flowController = proxyClientConnection.remote().flowController();
            flowController.incrementWindowSize(stream, bytes);
            flowController.writePendingBytes();
            proxyClient.flush();
        }
        pump();
    }

    // as a client whose SETTINGS give each stream no window until it sends a WINDOW_UPDATE for it
    private void clientGrantsNoWindowUnasked() throws Http2Exception {
        proxyClientConnection.remote().flowController().initialWindowSize(0);
        clientGrantsConnectionWindow(8 * WINDOW);
    }

    private void clientGrantsConnectionWindow(int bytes) throws Http2Exception {
        proxyClientConnection.remote().flowController().incrementWindowSize(proxyClientConnection.connectionStream(), bytes);
    }

    private static byte[] gzip(byte[] plain) {
        EmbeddedChannel encoder = new EmbeddedChannel(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
        try {
            encoder.writeOutbound(Unpooled.wrappedBuffer(plain));
            encoder.finish();
            ByteBuf compressed = Unpooled.buffer();
            ByteBuf piece;
            while ((piece = encoder.readOutbound()) != null) {
                compressed.writeBytes(piece);
                piece.release();
            }
            byte[] bytes = new byte[compressed.readableBytes()];
            compressed.readBytes(bytes);
            compressed.release();
            return bytes;
        } finally {
            encoder.finishAndReleaseAll();
        }
    }

    private void pump() {
        boolean moved;
        do {
            moved = false;
            ByteBuf bytes;
            while ((bytes = loopback.readOutbound()) != null) {
                writtenToServer.read(bytes);
                server.writeInbound(bytes);
                moved = true;
            }
            // one read, as frames written together arrive together
            ByteBuf fromServer = Unpooled.buffer();
            while ((bytes = server.readOutbound()) != null) {
                writtenByServer.read(bytes);
                fromServer.writeBytes(bytes);
                bytes.release();
                moved = true;
            }
            if (fromServer.isReadable() && loopback.isActive()) {
                loopback.writeInbound(fromServer);
            } else {
                fromServer.release();
            }
            while ((bytes = proxyClient.readOutbound()) != null) {
                writtenToProxyClient.read(bytes);
                bytes.release();
                moved = true;
            }
            proxyClient.runPendingTasks();
            loopback.runPendingTasks();
        } while (moved);
    }

    /**
     * What one Netty class logs through Netty's own logger at {@code WARN} or above on this thread, which is the event
     * loop of every channel here. Checked to see such an entry when made, so it cannot pass by looking elsewhere.
     */
    private static final class NettyErrorCapture extends Handler implements AutoCloseable {
        private final Logger logger;
        private final Thread thread = Thread.currentThread();
        private final List<String> logged = new CopyOnWriteArrayList<>();

        private NettyErrorCapture(Class<?> nettyClass) {
            logger = Logger.getLogger(nettyClass.getName());
            logger.addHandler(this);
        }

        static NettyErrorCapture of(Class<?> nettyClass) {
            NettyErrorCapture capture = new NettyErrorCapture(nettyClass);
            InternalLoggerFactory.getInstance(nettyClass).error("capture check");
            assertThat("the capture sees what Netty logs", capture.logged(), contains("capture check"));
            capture.logged.clear();
            return capture;
        }

        List<String> logged() {
            return logged;
        }

        @Override
        public void publish(LogRecord record) {
            if (Thread.currentThread() == thread && record.getLevel().intValue() >= Level.WARNING.intValue()) {
                logged.add(record.getThrown() != null ? record.getMessage() + " " + record.getThrown() : record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
        }
    }

    /**
     * The frames one leg of the relay has written, in order.
     */
    private static final class Frames extends Http2FrameAdapter {
        private final DefaultHttp2FrameReader reader = new DefaultHttp2FrameReader();
        // the reader allocates a header block's buffer from its context
        private final EmbeddedChannel readerChannel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        private final ByteBuf unread = Unpooled.buffer();
        private final List<String> frames = new ArrayList<>();
        private final List<String> resets = new ArrayList<>();
        private final Map<Integer, String> bodies = new LinkedHashMap<>();
        private final Map<Integer, Integer> dataBytes = new LinkedHashMap<>();
        private final Map<Integer, Integer> windowIncrements = new LinkedHashMap<>();
        private final Map<Integer, Http2Headers> headers = new LinkedHashMap<>();
        private final Map<Integer, Http2Headers> trailers = new LinkedHashMap<>();
        private int goAways;

        void read(ByteBuf written) {
            ByteBuf preface = Http2CodecUtil.connectionPrefaceBuf();
            if (!written.equals(preface)) {
                unread.writeBytes(written, written.readerIndex(), written.readableBytes());
                try {
                    reader.readFrame(readerChannel.pipeline().firstContext(), unread, this);
                } catch (Http2Exception unreadable) {
                    throw new AssertionError(unreadable);
                }
            }
        }

        int dataBytes(int streamId) {
            return dataBytes.getOrDefault(streamId, 0);
        }

        int windowIncrements(int streamId) {
            return windowIncrements.getOrDefault(streamId, 0);
        }

        @Override
        public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers frameHeaders, int padding, boolean endOfStream) {
            if (frameHeaders.status() != null) {
                headers.put(streamId, frameHeaders);
                frames.add(streamId + " HEADERS " + frameHeaders.status() + (endOfStream ? " END" : ""));
            } else if (frameHeaders.method() == null) {
                trailers.put(streamId, frameHeaders);
                frames.add(streamId + " TRAILERS" + (endOfStream ? " END" : ""));
            }
        }

        @Override
        public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers frameHeaders, int streamDependency, short weight, boolean exclusive, int padding, boolean endOfStream) {
            onHeadersRead(ctx, streamId, frameHeaders, padding, endOfStream);
        }

        @Override
        public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
            resets.add(streamId + ":" + errorCode);
        }

        @Override
        public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData) {
            goAways++;
        }

        @Override
        public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) {
            windowIncrements.merge(streamId, windowSizeIncrement, Integer::sum);
        }

        @Override
        public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) {
            frames.add(streamId + " DATA " + data.readableBytes() + (endOfStream ? " END" : ""));
            dataBytes.merge(streamId, data.readableBytes(), Integer::sum);
            if (data.readableBytes() <= 8192) {
                bodies.merge(streamId, data.toString(StandardCharsets.UTF_8), String::concat);
            }
            return data.readableBytes() + padding;
        }

        void close() {
            reader.close();
            unread.release();
            readerChannel.finishAndReleaseAll();
        }
    }
}
