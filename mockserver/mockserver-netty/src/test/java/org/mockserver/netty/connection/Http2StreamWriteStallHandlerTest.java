package org.mockserver.netty.connection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2SettingsFrame;
import io.netty.handler.codec.http2.DefaultHttp2WindowUpdateFrame;
import io.netty.handler.codec.http2.Http2ChannelDuplexHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2FrameTypes;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.metrics.Metrics;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Drives {@link Http2StreamWriteStallHandler} through a real HTTP/2 client and server codec joined in memory, so the
 * connection's socket writability can be set directly: a stream whose own window is open waits only for the socket,
 * which is the connection watcher's to time, while a stream whose window is closed waits for its client.
 */
public class Http2StreamWriteStallHandlerTest {

    private static final long TIMEOUT_MILLIS = 200;
    private static final int RESPONSE_BYTES = 16 * 1024;
    private static final String LARGE_RESPONSE_PATH = "/large";
    private static final int LARGE_RESPONSE_BYTES = 4 * Http2CodecUtil.DEFAULT_WINDOW_SIZE;
    private static final String STAGED_RESPONSE_PATH = "/staged";
    private static final int STAGED_FIRST_BYTES = 40_000;
    private static final int SOCKET_WRITABILITY = 1;

    private EmbeddedChannel server;
    private EmbeddedChannel client;
    private Socket socket;
    private Http2FrameCodec serverCodec;
    private Http2FrameCodec clientCodec;
    private Client clientHandler;
    private Responder responder;
    private boolean dropConnectionWindowUpdates;
    private int droppedConnectionWindowUpdates;

    @Before
    public void enableMetrics() {
        Metrics.resetAdditionalMetricsForTesting();
        new Metrics(configuration().metricsEnabled(true));
    }

    @After
    public void close() {
        closeChannels();
        Metrics.resetAdditionalMetricsForTesting();
    }

    private void closeChannels() {
        if (client != null) {
            client.finishAndReleaseAll();
        }
        if (server != null) {
            // the stand-in socket still holds what a test left unwritable, and would hold the GOAWAY closing writes
            socket.discardHeld();
            server.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldNotResetAStreamWithAnOpenWindowWaitingOnlyForTheSocket() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        socketWritable(false);

        ClientStream stream = clientHandler.request("/");
        waitThrough(4 * TIMEOUT_MILLIS);

        assertThat("the stream was not reset", stream.resetErrorCode, is(nullValue()));
        assertThat("the stream is still open", serverCodec.connection().numActiveStreams(), is(1));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(0L));

        socketWritable(true);
        exchange();
        assertThat("the response was sent once the socket was writable", stream.dataBytes, is(RESPONSE_BYTES));
        assertThat(stream.endStream, is(true));
    }

    @Test
    public void shouldResetAStreamWithAnOpenWindowBehindAnUnwritableSocketWhenNoConnectionWatcherIsPresent() throws Exception {
        // a connection accepted while the timeout was 0 has no connection watcher to cut a socket stall
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE, false);
        socketWritable(false);

        clientHandler.request("/");
        waitThrough(4 * TIMEOUT_MILLIS);

        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetAStreamWhoseClientGrantsItNoWindow() throws Exception {
        connect(0);

        ClientStream stream = clientHandler.request("/");
        waitThrough(4 * TIMEOUT_MILLIS);

        assertThat("the stream was reset", stream.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(serverCodec.connection().numActiveStreams(), is(0));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetAStreamWhoseClientGrantsItNoWindowWhileTheSocketIsNotWritableOnce() throws Exception {
        connect(0);
        socketWritable(false);

        ClientStream stream = clientHandler.request("/");
        waitThrough(4 * TIMEOUT_MILLIS);

        // the stream stays active until its RST_STREAM gets past the socket, and is not reset again meanwhile
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
        assertThat(serverCodec.connection().numActiveStreams(), is(1));
        serverCodec.connection().forEachActiveStream(serverStream -> {
            assertThat("the stream was reset", serverStream.isResetSent(), is(true));
            return true;
        });

        socketWritable(true);
        exchange();
        assertThat("the reset reached the client once the socket was writable", stream.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(serverCodec.connection().numActiveStreams(), is(0));
    }

    @Test
    public void shouldLetAStreamCarryOnOnceTheStalledStreamHoldingTheConnectionWindowIsReset() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);

        // the client consumes nothing, so the first stream's window's worth also takes the whole connection window
        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream sibling = clientHandler.request("/");
        exchange();
        assertThat(holder.dataBytes, is(Http2CodecUtil.DEFAULT_WINDOW_SIZE));
        assertThat(sibling.dataBytes, is(0));

        waitThrough(4 * TIMEOUT_MILLIS);

        assertThat("the stalled stream was reset", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("its sibling was not reset with it", sibling.resetErrorCode, is(nullValue()));
        assertThat("its sibling got the connection window its client released", sibling.dataBytes, is(RESPONSE_BYTES));
        assertThat(sibling.endStream, is(true));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetAStreamAfterItsFreshPeriodWhenItsClientReleasesNoConnectionWindow() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        dropConnectionWindowUpdates = true;

        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream sibling = clientHandler.request("/");
        exchange();
        waitUntilStallsCounted(1);

        assertThat("the stalled stream was reset", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the connection window it held was released, and discarded", droppedConnectionWindowUpdates, is(greaterThan(0)));

        waitThrough(4 * TIMEOUT_MILLIS);

        assertThat("its sibling, given no window in its fresh period, was reset", sibling.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(sibling.dataBytes, is(0));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        long apartMillis = TimeUnit.NANOSECONDS.toMillis(sibling.resetNanos - holder.resetNanos);
        assertThat("its sibling was reset after a fresh period, not with the stalled stream (" + apartMillis + " ms apart)", apartMillis >= TIMEOUT_MILLIS / 2, is(true));
    }

    @Test
    public void shouldLetAStreamCarryOnOnceAStalledStreamWhoseOwnWindowIsStillOpenIsReset() throws Exception {
        // at default priority the two streams share the connection window; the client takes the holder's data only up
        // to a cut-off, and at some cut-offs the holder's own window is still open when the connection window closes
        boolean holderWindowOpenSeen = false;
        for (int cutOff : new int[]{1, 10_000, 20_000, 33_000, 50_000, 70_000, 100_000, 130_000, 160_000}) {
            closeChannels();
            long countedBefore = Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM);
            connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
            ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH, cutOff);
            ClientStream sibling = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
            exchange();
            assertThat("the connection window closed at cut-off " + cutOff, serverWindow(0), is(0));
            holderWindowOpenSeen |= serverWindow(holder.frameStream.id()) > 0;

            waitThrough(4 * TIMEOUT_MILLIS);

            assertThat("the holder was reset at cut-off " + cutOff, holder.resetErrorCode, is(Http2Error.CANCEL.code()));
            assertThat("its sibling was not reset at cut-off " + cutOff, sibling.resetErrorCode, is(nullValue()));
            assertThat("its sibling completed at cut-off " + cutOff, sibling.dataBytes, is(LARGE_RESPONSE_BYTES));
            assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(countedBefore + 1));
        }
        assertThat("some cut-off left the holder's own window open", holderWindowOpenSeen, is(true));
    }

    @Test
    public void shouldResetOnlyTheStalledStreamWhenItsSiblingHoldsOneByteLessOfTheConnectionWindow() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);

        // the holder opens once its sibling's data is arriving, and takes what its sibling's client has not yet returned
        ClientStream sibling = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream[] opened = new ClientStream[1];
        sibling.onFirstData = () -> opened[0] = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        ClientStream holder = opened[0];
        assertThat(serverWindow(0), is(0));
        // Netty's codec returns window once more than half of it is consumed, so the most it leaves unreturned is 32,767
        assertThat("the holder's unconsumed data", held(holder), is(32_768));
        assertThat("what the sibling's client consumed and has not returned", held(sibling), is(32_767));

        waitUntilStallsCounted(1);

        assertThat("the stalled stream was reset", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("its sibling was not reset with it", sibling.resetErrorCode, is(nullValue()));
        assertThat(sibling.dataBytes, is(LARGE_RESPONSE_BYTES));
        assertThat(sibling.endStream, is(true));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldLetEverySiblingCarryOnOnceTheStalledStreamAmongThemIsReset() throws Exception {
        boolean holderWindowOpenSeen = false;
        for (int cutOff : new int[]{32_768, 65_535, 75_090, 80_096}) {
            closeChannels();
            long countedBefore = Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM);
            connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
            ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH, cutOff);
            ClientStream[] siblings = new ClientStream[3];
            for (int i = 0; i < siblings.length; i++) {
                siblings[i] = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
            }
            exchange();
            assertThat("the connection window closed at cut-off " + cutOff, serverWindow(0), is(0));
            holderWindowOpenSeen |= serverWindow(holder.frameStream.id()) > 0;

            waitUntilStallsCounted(countedBefore + 1);

            assertThat("the holder was reset at cut-off " + cutOff, holder.resetErrorCode, is(Http2Error.CANCEL.code()));
            for (ClientStream sibling : siblings) {
                assertThat("no sibling was reset at cut-off " + cutOff, sibling.resetErrorCode, is(nullValue()));
                assertThat("every sibling completed at cut-off " + cutOff, sibling.dataBytes, is(LARGE_RESPONSE_BYTES));
            }
            assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(countedBefore + 1));
        }
        assertThat("some cut-off left the holder's own window open", holderWindowOpenSeen, is(true));
    }

    @Test
    public void shouldResetEveryStreamHoldingTheMostOfTheConnectionWindowWhenTheyHoldTheSame() throws Exception {
        // a stream window above the connection window lets a client leave as much of a stream it consumes unreturned as
        // a stalled stream holds; the two cannot be told apart, and resetting both is what surely releases the window
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        clientHandler.initialStreamWindow(80_000);
        exchange();

        ClientStream first = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream second = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream[] opened = new ClientStream[1];
        first.onFirstData = () -> opened[0] = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        ClientStream holder = opened[0];
        assertThat(serverWindow(0), is(0));
        assertThat("a consumed sibling holds as much as the holder", held(second), is(held(holder)));
        assertThat("the other sibling holds less", held(first) < held(holder), is(true));

        waitUntilStallsCounted(1);

        assertThat("both were reset in the same check", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        assertThat(holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(second.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the sibling holding less was not reset", first.resetErrorCode, is(nullValue()));
        assertThat(first.dataBytes, is(LARGE_RESPONSE_BYTES));
        assertThat(first.endStream, is(true));
    }

    @Test
    public void shouldResetTheStalledStreamOnePeriodAfterAConsumedSiblingThatHoldsMoreOfTheConnectionWindow() throws Exception {
        // with a stream window above the connection window the server cannot tell which of the two its client is reading
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        clientHandler.initialStreamWindow(100_000);
        exchange();

        ClientStream sibling = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream[] opened = new ClientStream[1];
        sibling.onFirstData = () -> opened[0] = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        ClientStream holder = opened[0];
        assertThat(serverWindow(0), is(0));
        assertThat("the consumed sibling holds more than the holder", held(sibling), is(greaterThan(held(holder))));

        waitUntilStallsCounted(1);
        assertThat("the sibling was reset first", sibling.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));

        waitUntilStallsCounted(2);
        assertThat("the stalled stream, which released nothing, was reset too", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        long apartMillis = TimeUnit.NANOSECONDS.toMillis(holder.resetNanos - sibling.resetNanos);
        assertThat("one period later (" + apartMillis + " ms apart)", apartMillis >= TIMEOUT_MILLIS / 2, is(true));
    }

    @Test
    public void shouldResetEveryStreamWhenNothingMovesInTheFreshPeriodAfterAStalledStreamIsReset() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH, 33_000);
        ClientStream first = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream second = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        exchange();
        // each sibling holds some connection window its client consumed but has not yet returned, a different amount each
        int firstWindow = serverWindow(first.frameStream.id());
        int secondWindow = serverWindow(second.frameStream.id());
        assertThat(firstWindow < Http2CodecUtil.DEFAULT_WINDOW_SIZE && secondWindow < Http2CodecUtil.DEFAULT_WINDOW_SIZE && firstWindow != secondWindow, is(true));
        dropConnectionWindowUpdates = true;

        waitUntilStallsCounted(1);
        assertThat("the stalled stream was reset", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));

        // nothing moved in the fresh period, so the sibling holding more is not reset alone to give the other another
        waitUntilStallsCounted(2);
        assertThat("both siblings were reset in the same check", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(3L));
        assertThat(first.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(second.resetErrorCode, is(Http2Error.CANCEL.code()));
    }

    @Test
    public void shouldLetAStreamCarryOnWhenAStalledStreamHoldsTheConnectionWindowAgainOnceTheConnectionHasBeenIdle() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream sibling = clientHandler.request("/");
        exchange();
        waitUntilStallsCounted(1);
        assertThat(holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(sibling.endStream, is(true));
        // a check finds no stream active
        waitThrough(TIMEOUT_MILLIS);

        ClientStream secondHolder = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream secondSibling = clientHandler.request("/");
        exchange();
        assertThat(secondSibling.dataBytes, is(0));
        waitUntilStallsCounted(2);

        assertThat("only the second stalled stream was reset", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        assertThat(secondHolder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("its sibling was not reset with it", secondSibling.resetErrorCode, is(nullValue()));
        assertThat(secondSibling.dataBytes, is(RESPONSE_BYTES));
        assertThat(secondSibling.endStream, is(true));
    }

    @Test
    public void shouldLetAStreamCarryOnWhenANewStalledStreamTakesTheConnectionWindowAsSoonAsItIsReleased() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream sibling = clientHandler.request("/");
        ClientStream[] opened = new ClientStream[2];
        holder.onReset = () -> {
            opened[0] = clientHandler.request(LARGE_RESPONSE_PATH);
            opened[1] = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        };
        exchange();
        waitUntilStallsCounted(1);
        // the first sibling completed and the connection window closed again before the next check, with new streams only
        assertThat(sibling.endStream, is(true));
        assertThat(serverWindow(0), is(0));
        assertThat(opened[1].endStream, is(false));

        waitUntilStallsCounted(2);

        assertThat("only the second stalled stream was reset", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        assertThat(opened[0].resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("its sibling was not reset with it", opened[1].resetErrorCode, is(nullValue()));
        assertThat(opened[1].dataBytes, is(LARGE_RESPONSE_BYTES));
        assertThat(opened[1].endStream, is(true));
    }

    @Test
    public void shouldLetTheSameStreamCarryOnEachTimeAStalledStreamHoldingTheConnectionWindowIsReset() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream sibling = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream[] opened = new ClientStream[1];
        holder.onReset = () -> opened[0] = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        waitUntilStallsCounted(1);
        // the sibling took some of the released window, then the second stalled stream closed the connection window again
        assertThat(sibling.dataBytes, is(greaterThan(0)));
        assertThat(sibling.endStream, is(false));
        assertThat(serverWindow(0), is(0));

        waitUntilStallsCounted(2);

        assertThat("only the second stalled stream was reset", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        assertThat(opened[0].resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the sibling was spared a second time", sibling.resetErrorCode, is(nullValue()));
        assertThat(sibling.dataBytes, is(LARGE_RESPONSE_BYTES));
        assertThat(sibling.endStream, is(true));
    }

    @Test
    public void shouldResetEveryStreamBehindAnUnwritableSocketInTheSameCheckWhenNoConnectionWatcherIsPresent() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE, false);
        ClientStream first = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        move(client, server);
        socketWritable(false);
        ClientStream second = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        // the streams wait for the socket, not for connection window one of them holds
        assertThat(serverWindow(0), is(greaterThan(0)));
        assertThat(serverWindow(first.frameStream.id()), is(greaterThan(0)));
        assertThat(held(first), is(greaterThan(0)));
        assertThat(held(second), is(0));

        waitUntilStallsCounted(1);

        assertThat("both were reset in the same check", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
    }

    @Test
    public void shouldResetAnOpenWindowedStreamWithAClosedWindowStreamWhenTheConnectionWindowIsOpen() throws Exception {
        // behind an unwritable socket nothing times, the open-windowed stream waits for the socket, not for window
        connect(1_000, false);
        ClientStream closed = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        socketWritable(false);
        ClientStream open = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        assertThat(serverWindow(closed.frameStream.id()), is(0));
        assertThat(serverWindow(open.frameStream.id()), is(greaterThan(0)));
        assertThat(serverWindow(0), is(greaterThan(0)));

        waitUntilStallsCounted(1);

        assertThat("both were reset in the same check", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
    }

    @Test
    public void shouldLetAStreamCarryOnAgainOnceAnotherStreamHasMovedSinceItsFreshPeriod() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        // two stalled streams share the connection window, and the sibling is sent nothing
        ClientStream holder = clientHandler.request(STAGED_RESPONSE_PATH);
        ClientStream secondHolder = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        responder.writeRest.run();
        ClientStream sibling = clientHandler.request("/");
        exchange();
        assertThat(serverWindow(0), is(0));
        assertThat(held(holder), is(greaterThan(held(secondHolder))));
        assertThat(held(secondHolder), is(greaterThan(0)));
        dropConnectionWindowUpdates = true;

        waitUntilStallsCounted(1);
        assertThat(holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
        // the sibling is sent nothing in its fresh period, but the other stream moves
        clientHandler.windowUpdate(secondHolder, 1);
        exchange();
        assertThat(sibling.dataBytes, is(0));

        waitUntilStallsCounted(2);

        assertThat("only the second stalled stream was reset", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        assertThat(secondHolder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the sibling was given a fresh period again", sibling.resetErrorCode, is(nullValue()));
    }

    @Test
    public void shouldGiveAFreshPeriodToAStreamThatHasNotYetTimedOutWhenTheStalledStreamIsReset() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        // a check sees the holder before its sibling opens, so the sibling times out one check later
        runChecks(1);
        ClientStream sibling = clientHandler.request("/");
        exchange();

        // the reset stays undelivered through the next check, so nothing is released meanwhile
        while (Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM) < 1) {
            runChecks(1);
        }
        long resetNanos = System.nanoTime();
        runChecks(1);
        Assume.assumeTrue("the checks ran on time", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - resetNanos) < TIMEOUT_MILLIS);
        assertThat("the sibling was not reset at its own timeout", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));

        exchange();
        assertThat(holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(sibling.resetErrorCode, is(nullValue()));
        assertThat(sibling.dataBytes, is(RESPONSE_BYTES));
        assertThat(sibling.endStream, is(true));
    }

    @Test
    public void shouldNotResetTheStreamHoldingAClosedConnectionWindowWhileTheSocketIsNotWritable() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream sibling = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream[] opened = new ClientStream[1];
        sibling.onFirstData = () -> opened[0] = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        assertThat(serverWindow(0), is(0));
        assertThat("the holder's own window is open", serverWindow(opened[0].frameStream.id()), is(greaterThan(0)));
        // nothing moves until the check at which the streams would time out, by when the socket is not writable
        runChecks(2);
        socketWritable(false);

        runChecks(4);

        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(0L));
        assertThat(serverCodec.connection().numActiveStreams(), is(2));
    }

    @Test
    public void shouldPickTheStreamHoldingTheMostAmongThoseThatHaveTimedOut() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        // the larger holder has nothing waiting until the rest of its response is written, a check after the others
        ClientStream larger = clientHandler.request(STAGED_RESPONSE_PATH);
        ClientStream smaller = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream sibling = clientHandler.request("/");
        exchange();
        assertThat(serverWindow(0), is(0));
        assertThat(held(larger), is(greaterThan(held(smaller))));
        runChecks(1);
        responder.writeRest.run();

        waitUntilStallsCounted(1);

        assertThat("the sibling was not reset with the first stream to time out", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
        assertThat(sibling.resetErrorCode, is(nullValue()));
        assertThat(larger.resetErrorCode == null ? smaller.resetErrorCode : larger.resetErrorCode, is(Http2Error.CANCEL.code()));
    }

    @Test
    public void shouldNotGiveAStreamAFreshPeriodForTheResetOfAStreamThatWasSentNothing() throws Exception {
        // a stream never sent data holds no connection window, so a client cannot keep a stalled stream going by
        // opening, every timeout period, a stream it grants no window
        connect(0);

        ClientStream granted = clientHandler.request(LARGE_RESPONSE_PATH);
        clientHandler.grantWindow(granted, 2 * Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream ungranted = clientHandler.request("/");
        exchange();
        assertThat("the granted stream took the whole connection window", granted.dataBytes, is(Http2CodecUtil.DEFAULT_WINDOW_SIZE));

        waitUntilStallsCounted(1);

        assertThat("both were reset in the same check", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        exchange();
        assertThat(ungranted.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(granted.resetErrorCode, is(Http2Error.CANCEL.code()));
    }

    private void connect(int clientInitialWindowSize) {
        connect(clientInitialWindowSize, true);
    }

    private void connect(int clientInitialWindowSize, boolean connectionWatcher) {
        serverCodec = Http2FrameCodecBuilder.forServer().build();
        socket = new Socket();
        responder = new Responder();
        server = new EmbeddedChannel(socket, serverCodec, new Http2StreamWriteStallHandler(TIMEOUT_MILLIS, null), responder);
        if (connectionWatcher) {
            // its timer never arms here, since the embedded channel's outbound buffer is always emptied by a flush
            server.pipeline().addFirst(new WriteStallTimeoutHandler(60_000, null));
        }
        clientCodec = Http2FrameCodecBuilder.forClient().initialSettings(Http2Settings.defaultSettings().initialWindowSize(clientInitialWindowSize)).build();
        clientHandler = new Client(clientCodec);
        client = new EmbeddedChannel(clientCodec, clientHandler);
        client.flush();
        exchange();
    }

    private void socketWritable(boolean writable) {
        socket.hold(!writable);
        server.unsafe().outboundBuffer().setUserDefinedWritability(SOCKET_WRITABILITY, writable);
        server.runPendingTasks();
        assertThat(server.isWritable(), is(writable));
    }

    private void waitThrough(long millis) throws InterruptedException {
        long started = System.nanoTime();
        while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < millis) {
            TimeUnit.MILLISECONDS.sleep(WriteStallTimeoutHandler.checkIntervalMillis(TIMEOUT_MILLIS));
            server.runScheduledPendingTasks();
            exchange();
        }
    }

    // runs that many of the watcher's checks, delivering nothing between the client and the server
    private void runChecks(int checks) throws InterruptedException {
        long untilNextCheck = server.runScheduledPendingTasks();
        for (int ran = 0; ran < checks; ) {
            TimeUnit.MILLISECONDS.sleep(5);
            long untilNext = server.runScheduledPendingTasks();
            // a check that has run schedules the next one a whole interval away
            if (untilNext > untilNextCheck) {
                ran++;
            }
            untilNextCheck = untilNext;
        }
    }

    private int serverWindow(int streamId) {
        return serverCodec.connection().remote().flowController().windowSize(serverCodec.connection().stream(streamId));
    }

    // the stream's data its client has not returned window for
    private int held(ClientStream stream) {
        return serverCodec.connection().remote().flowController().initialWindowSize() - serverWindow(stream.frameStream.id());
    }

    private void waitUntilStallsCounted(long count) throws InterruptedException {
        long started = System.nanoTime();
        while (Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM) < count) {
            assertThat("a stall was counted in time", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 20 * TIMEOUT_MILLIS, is(true));
            TimeUnit.MILLISECONDS.sleep(WriteStallTimeoutHandler.checkIntervalMillis(TIMEOUT_MILLIS) / 4);
            server.runScheduledPendingTasks();
        }
        exchange();
    }

    private void exchange() {
        boolean moved = true;
        while (moved) {
            moved = move(client, server) | move(server, client);
        }
    }

    private boolean move(EmbeddedChannel from, EmbeddedChannel to) {
        boolean moved = false;
        for (Object msg; (msg = from.readOutbound()) != null; ) {
            if (from == client && dropConnectionWindowUpdates && isConnectionWindowUpdate(msg)) {
                droppedConnectionWindowUpdates++;
                ReferenceCountUtil.release(msg);
                continue;
            }
            to.writeInbound(msg);
            moved = true;
        }
        to.runPendingTasks();
        return moved;
    }

    // Netty's frame writer writes each WINDOW_UPDATE frame in a buffer of its own
    private static boolean isConnectionWindowUpdate(Object msg) {
        if (!(msg instanceof ByteBuf) || ((ByteBuf) msg).readableBytes() != Http2CodecUtil.FRAME_HEADER_LENGTH + 4) {
            return false;
        }
        ByteBuf frame = (ByteBuf) msg;
        return frame.getByte(frame.readerIndex() + 3) == Http2FrameTypes.WINDOW_UPDATE && frame.getInt(frame.readerIndex() + 5) == Http2CodecUtil.CONNECTION_STREAM_ID;
    }

    /**
     * Stands for the connection's socket: while it is not writable it takes nothing, so writes stay incomplete, as they
     * do behind a client that is not reading.
     */
    private static final class Socket extends ChannelOutboundHandlerAdapter {
        private final Queue<Object> heldMessages = new ArrayDeque<>();
        private final Queue<ChannelPromise> heldPromises = new ArrayDeque<>();
        private ChannelHandlerContext ctx;
        private boolean holding;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (holding) {
                heldMessages.add(msg);
                heldPromises.add(promise);
            } else {
                ctx.write(msg, promise);
            }
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            if (!holding) {
                ctx.flush();
            }
        }

        void discardHeld() {
            holding = false;
            while (!heldMessages.isEmpty()) {
                ReferenceCountUtil.release(heldMessages.poll());
                heldPromises.poll().tryFailure(new ClosedChannelException());
            }
        }

        void hold(boolean hold) {
            holding = hold;
            if (!hold) {
                while (!heldMessages.isEmpty()) {
                    ctx.write(heldMessages.poll(), heldPromises.poll());
                }
                ctx.flush();
            }
        }
    }

    /**
     * Answers every request with a body the size of a few frames, or of a few windows for {@link #LARGE_RESPONSE_PATH}.
     * For {@link #STAGED_RESPONSE_PATH} it writes the first part, and the rest only when {@code writeRest} is run.
     */
    private static final class Responder extends ChannelInboundHandlerAdapter {
        private Runnable writeRest;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof Http2HeadersFrame) {
                    Http2HeadersFrame request = (Http2HeadersFrame) msg;
                    if (STAGED_RESPONSE_PATH.contentEquals(request.headers().path())) {
                        ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200")).stream(request.stream()));
                        ctx.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(new byte[STAGED_FIRST_BYTES]), false).stream(request.stream()));
                        writeRest = () -> ctx.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(new byte[RESPONSE_BYTES]), true).stream(request.stream()));
                        return;
                    }
                    int bytes = LARGE_RESPONSE_PATH.contentEquals(request.headers().path()) ? LARGE_RESPONSE_BYTES : RESPONSE_BYTES;
                    ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200")).stream(request.stream()));
                    ctx.write(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(new byte[bytes]), true).stream(request.stream()));
                    ctx.flush();
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    private static final class ClientStream {
        private Http2FrameStream frameStream;
        private int consumeLimit;
        private int consumed;
        private Runnable onFirstData;
        private Runnable onReset;
        private long resetNanos;
        private Long resetErrorCode;
        private int dataBytes;
        private boolean endStream;
    }

    /**
     * Consumes, and so returns window for, only up to each stream's limit (by default nothing), besides what Netty's codec
     * returns for a stream when it closes.
     */
    private static final class Client extends Http2ChannelDuplexHandler {
        private final Http2FrameCodec codec;
        private final Map<Http2FrameStream, ClientStream> streams = new HashMap<>();
        private ChannelHandlerContext ctx;

        private Client(Http2FrameCodec codec) {
            this.codec = codec;
        }

        @Override
        protected void handlerAdded0(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        ClientStream request(String path) {
            return request(path, 0);
        }

        ClientStream request(String path, int consumeLimit) {
            ClientStream stream = new ClientStream();
            stream.consumeLimit = consumeLimit;
            stream.frameStream = newStream();
            streams.put(stream.frameStream, stream);
            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().method("GET").scheme("http").authority("localhost").path(path), true).stream(stream.frameStream));
            return stream;
        }

        // SETTINGS_INITIAL_WINDOW_SIZE, which leaves the connection window as it is
        void initialStreamWindow(int bytes) {
            ctx.writeAndFlush(new DefaultHttp2SettingsFrame(new Http2Settings().initialWindowSize(bytes)));
        }

        // written past the codec's flow controller, which would hold so small an update back
        void windowUpdate(ClientStream stream, int bytes) {
            ChannelHandlerContext codecCtx = ctx.pipeline().context(codec);
            codec.encoder().frameWriter().writeWindowUpdate(codecCtx, stream.frameStream.id(), bytes, codecCtx.newPromise());
            codecCtx.flush();
        }

        void grantWindow(ClientStream stream, int bytes) throws Http2Exception {
            codec.connection().local().flowController().incrementWindowSize(codec.connection().stream(stream.frameStream.id()), bytes);
            ctx.flush();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                ClientStream stream = msg instanceof Http2StreamFrame ? streams.get(((Http2StreamFrame) msg).stream()) : null;
                if (stream == null) {
                    return;
                }
                if (msg instanceof Http2DataFrame) {
                    Http2DataFrame data = (Http2DataFrame) msg;
                    stream.dataBytes += data.content().readableBytes();
                    stream.endStream |= data.isEndStream();
                    if (stream.onFirstData != null) {
                        Runnable opened = stream.onFirstData;
                        stream.onFirstData = null;
                        opened.run();
                    }
                    int consume = Math.min(data.initialFlowControlledBytes(), stream.consumeLimit - stream.consumed);
                    if (consume > 0 && !data.isEndStream()) {
                        stream.consumed += consume;
                        ctx.writeAndFlush(new DefaultHttp2WindowUpdateFrame(consume).stream(stream.frameStream));
                    }
                } else if (msg instanceof Http2ResetFrame) {
                    stream.resetNanos = System.nanoTime();
                    stream.resetErrorCode = ((Http2ResetFrame) msg).errorCode();
                    if (stream.onReset != null) {
                        stream.onReset.run();
                    }
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
    }
}
