package org.mockserver.netty.http3;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.concurrent.GenericFutureListener;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.StreamingBody;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Unit tests for {@link Http3ResponseWriter}, covering both static and
 * streaming response paths.
 * <p>
 * These tests do NOT require the native QUIC transport -- they exercise
 * the writer's frame serialisation using mocked Netty channel contexts.
 */
public class Http3ResponseWriterTest {

    private static final Configuration CONFIGURATION = configuration();
    private static final MockServerLogger LOGGER = new MockServerLogger(Http3ResponseWriterTest.class);

    @Test
    public void shouldWriteStaticResponseWithBody() {
        // given
        ChannelHandlerContext ctx = mockCtxWithActiveChannel();
        Http3ResponseWriter writer = new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx);

        HttpRequest req = request().withPath("/test");
        HttpResponse resp = response()
            .withStatusCode(200)
            .withHeader("content-type", "text/plain")
            .withBody("hello");

        // when
        writer.sendResponse(req, resp);

        // then: should write headers frame + data frame
        ArgumentCaptor<Object> writeCaptor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).write(writeCaptor.capture()); // headers
        verify(ctx).writeAndFlush(any(DefaultHttp3DataFrame.class)); // data + shutdown_output listener

        Object headersObj = writeCaptor.getValue();
        assertThat(headersObj, instanceOf(DefaultHttp3HeadersFrame.class));
        DefaultHttp3HeadersFrame headers = (DefaultHttp3HeadersFrame) headersObj;
        assertThat(headers.headers().status().toString(), is("200"));
    }

    @Test
    public void shouldWriteStaticResponseWithoutBody() {
        // given
        ChannelHandlerContext ctx = mockCtxWithActiveChannel();
        Http3ResponseWriter writer = new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx);

        HttpRequest req = request().withPath("/empty");
        HttpResponse resp = response().withStatusCode(204);

        // when
        writer.sendResponse(req, resp);

        // then: should write headers frame, flush, and shutdown output
        verify(ctx).write(any(DefaultHttp3HeadersFrame.class));
        verify(ctx).flush();
        verify(ctx, never()).writeAndFlush(any(DefaultHttp3DataFrame.class));
    }

    @Test
    public void shouldWriteStreamingResponse() throws Exception {
        // given -- a ctx whose writeAndFlush immediately fires its listener as a success,
        // so the backpressure path (requestMore on write-completion) is exercised
        List<ByteBuf> writtenBufs = new ArrayList<>();
        ChannelHandlerContext ctx = mockCtxWithListenerFiringChannel(writtenBufs);
        Http3ResponseWriter writer = new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx);

        // track requestMore() invocations via a counting callback
        StreamingBody streamingBody = new StreamingBody(8192);
        int[] requestMoreCount = {0};
        streamingBody.setRequestMoreCallback(() -> requestMoreCount[0]++);

        HttpRequest req = request().withPath("/stream");
        HttpResponse resp = response()
            .withStatusCode(200)
            .withHeader("content-type", "text/event-stream")
            .withStreamingBody(streamingBody);

        // when: send response (subscribes to streaming body)
        writer.sendResponse(req, resp);

        // then: headers should be written immediately
        verify(ctx).writeAndFlush(any(DefaultHttp3HeadersFrame.class));

        // when: push chunks through the streaming body
        ByteBuf chunk1 = Unpooled.wrappedBuffer("data: chunk1\n\n".getBytes(StandardCharsets.UTF_8));
        ByteBuf chunk2 = Unpooled.wrappedBuffer("data: chunk2\n\n".getBytes(StandardCharsets.UTF_8));

        streamingBody.addChunk(chunk1);
        streamingBody.addChunk(chunk2);

        // then: each chunk should produce an HTTP/3 data frame write
        // writeAndFlush is called once for headers + once per chunk = 3
        verify(ctx, times(3)).writeAndFlush(any());

        // then: backpressure -- requestMore() should have been called 3 times:
        // 1 initial call from subscribe() to trigger the first upstream read +
        // 1 per chunk from the write-completion listener (2 chunks)
        assertThat("requestMore should be called once initially + once per chunk",
            requestMoreCount[0], is(3));

        // then: the copied DataFrame ByteBufs should have been released after the write
        // completes (Netty pipeline releases after writeAndFlush; our copiedBuffer ByteBufs
        // are captured in writtenBufs -- verify they were created from the chunk content)
        // Filter to only data frames (skip the headers frame)
        List<ByteBuf> dataFrameBufs = new ArrayList<>();
        for (ByteBuf buf : writtenBufs) {
            if (buf.readableBytes() > 0) {
                dataFrameBufs.add(buf);
            }
        }
        assertThat("should have written 2 data frame bufs", dataFrameBufs.size(), is(2));

        // when: complete the stream
        streamingBody.complete();

        // then: completion should have written an empty DATA frame (sentinel) = 4 total
        verify(ctx, times(4)).writeAndFlush(any());

        // cleanup
        chunk1.release();
        chunk2.release();
    }

    @Test
    public void shouldWriteALargeStaticBodyAsDataFramesOfAtMostTheMaximumSize() {
        // given -- a QUIC write completes only once all of it is taken, so smaller frames show a slow reader's progress
        ChannelHandlerContext ctx = mockCtxWithActiveChannel();
        List<ByteBuf> writtenBufs = new ArrayList<>();
        List<ByteBuf> writtenWithoutFlush = new ArrayList<>();
        when(ctx.write(any())).thenAnswer(invocation -> copyDataFrame(invocation.getArgument(0), writtenWithoutFlush));
        when(ctx.writeAndFlush(any())).thenAnswer(invocation -> copyDataFrame(invocation.getArgument(0), writtenBufs));
        byte[] body = new byte[3 * Http3ResponseWriter.MAX_DATA_FRAME_BYTES + 100];
        new java.util.Random(72).nextBytes(body);

        // when
        new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx).sendResponse(request().withPath("/large"), response().withBody(body));

        // then -- three full frames, then the remainder flushed last
        assertThat(writtenWithoutFlush.size(), is(3));
        assertThat(writtenBufs.size(), is(1));
        List<ByteBuf> frames = new ArrayList<>(writtenWithoutFlush);
        frames.addAll(writtenBufs);
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        for (ByteBuf frame : frames) {
            assertThat(frame.readableBytes(), lessThanOrEqualTo(Http3ResponseWriter.MAX_DATA_FRAME_BYTES));
            byte[] bytes = new byte[frame.readableBytes()];
            frame.readBytes(bytes);
            joined.write(bytes, 0, bytes.length);
            frame.release();
        }
        assertThat("the frames carry the body intact, in order", java.util.Arrays.equals(joined.toByteArray(), body), is(true));
    }

    @Test
    public void shouldCloseTheUpstreamWhenTheStreamClosesBeforeTheStreamedResponseCompletes() throws Exception {
        // given
        ChannelHandlerContext ctx = mockCtxWithListenerFiringChannel(new ArrayList<>());
        List<GenericFutureListener<ChannelFuture>> closeListeners = stubCloseFuture(ctx.channel());
        StreamingBody streamingBody = new StreamingBody(8192);
        boolean[] upstreamClosed = {false};
        streamingBody.setUpstreamCloser(() -> upstreamClosed[0] = true);
        new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx).sendResponse(request().withPath("/stream"), response().withStreamingBody(streamingBody));

        // when -- the stream closes (reset by a write stall, or the client went away) mid-response
        assertThat(closeListeners.size(), is(1));
        closeListeners.get(0).operationComplete(mock(ChannelFuture.class));

        // then
        assertThat("nothing will take the rest of the stream, so the upstream is closed", upstreamClosed[0], is(true));
    }

    @Test
    public void shouldStopWatchingTheStreamForCloseOnceTheStreamedResponseCompletes() {
        // given
        ChannelHandlerContext ctx = mockCtxWithListenerFiringChannel(new ArrayList<>());
        List<GenericFutureListener<ChannelFuture>> closeListeners = stubCloseFuture(ctx.channel());
        StreamingBody streamingBody = new StreamingBody(8192);
        new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx).sendResponse(request().withPath("/stream"), response().withStreamingBody(streamingBody));
        assertThat(closeListeners.size(), is(1));

        // when
        streamingBody.complete();

        // then
        assertThat(closeListeners.size(), is(0));
    }

    @Test
    public void shouldHandleStreamingBodyError() {
        // given
        ChannelHandlerContext ctx = mockCtxWithListenerFiringChannel(new ArrayList<>());
        Http3ResponseWriter writer = new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx);

        StreamingBody streamingBody = new StreamingBody(8192);

        HttpRequest req = request().withPath("/stream-error");
        HttpResponse resp = response()
            .withStatusCode(200)
            .withStreamingBody(streamingBody);

        // when
        writer.sendResponse(req, resp);

        // then: headers should be written
        verify(ctx).writeAndFlush(any(DefaultHttp3HeadersFrame.class));

        // when: signal error
        streamingBody.error(new RuntimeException("upstream closed"));

        // then: the error path should write the headers frame + the sentinel empty DATA frame
        // (1 headers + 1 empty DATA = 2 writeAndFlush calls)
        verify(ctx, times(2)).writeAndFlush(any());

        // verify the second write is a DefaultHttp3DataFrame (the empty sentinel)
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx, times(2)).writeAndFlush(captor.capture());
        List<Object> allWrites = captor.getAllValues();
        assertThat("first write should be headers frame",
            allWrites.get(0), instanceOf(DefaultHttp3HeadersFrame.class));
        assertThat("second write should be empty DATA frame (error sentinel)",
            allWrites.get(1), instanceOf(DefaultHttp3DataFrame.class));
    }

    @Test
    public void shouldFreeTheUnwrittenBoundAsEachDataFrameIsWritten() {
        // given -- every DATA frame write completes at once
        List<ByteBuf> writtenBufs = new ArrayList<>();
        Http3ResponseWriter writer = new Http3ResponseWriter(CONFIGURATION, LOGGER, mockCtxWithListenerFiringChannel(writtenBufs));
        StreamingBody streamingBody = new StreamingBody(0, false, 4096);
        writer.sendResponse(request().withPath("/stream"), response().withStreamingBody(streamingBody));

        // when -- a stream 25 times the bound
        for (int i = 0; i < 100; i++) {
            ByteBuf chunk = Unpooled.buffer(1024).writeZero(1024);
            assertThat("chunk " + i + " is taken", streamingBody.addChunk(chunk), is(true));
            chunk.release();
        }

        // then
        assertThat(streamingBody.getError(), is(nullValue()));
        assertThat(writtenBufs.size(), is(100));
        writtenBufs.forEach(ByteBuf::release);
    }

    @Test
    public void shouldFreeTheUnwrittenBoundForChunksAfterTheClientHasGone() {
        // given -- a stream whose client has gone, so no DATA frame is written
        List<ByteBuf> writtenBufs = new ArrayList<>();
        ChannelHandlerContext ctx = mockCtxWithListenerFiringChannel(writtenBufs);
        Http3ResponseWriter writer = new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx);
        StreamingBody streamingBody = new StreamingBody(0, false, 4096);
        boolean[] upstreamClosed = {false};
        streamingBody.setUpstreamCloser(() -> upstreamClosed[0] = true);
        writer.sendResponse(request().withPath("/stream"), response().withStreamingBody(streamingBody));
        when(ctx.channel().isActive()).thenReturn(false);

        // when -- ten times the bound arrives
        for (int i = 0; i < 40; i++) {
            ByteBuf chunk = Unpooled.buffer(1024).writeZero(1024);
            assertThat("chunk " + i + " is taken", streamingBody.addChunk(chunk), is(true));
            chunk.release();
        }

        // then -- each discarded chunk was reported, so the bound never fails the stream
        assertThat(streamingBody.getError(), is(nullValue()));
        assertThat(writtenBufs.size(), is(0));
        assertThat("nothing will take the rest of the stream, so the upstream is closed", upstreamClosed[0], is(true));
    }

    @Test
    public void shouldResetTheStreamWhenUnwrittenBytesPassTheBound() {
        // given -- a QUIC stream whose DATA frame writes never complete
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        QuicStreamChannel channel = mock(QuicStreamChannel.class);
        when(channel.isActive()).thenReturn(true);
        stubCloseFuture(channel);
        when(ctx.channel()).thenReturn(channel);
        List<Object> written = new ArrayList<>();
        when(ctx.writeAndFlush(any())).thenAnswer(invocation -> {
            written.add(invocation.getArgument(0));
            ChannelFuture future = mock(ChannelFuture.class);
            when(future.addListener(any())).thenReturn(future);
            return future;
        });
        Http3ResponseWriter writer = new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx);
        StreamingBody streamingBody = new StreamingBody(0, false, 4096);
        writer.sendResponse(request().withPath("/stream"), response().withStreamingBody(streamingBody));

        // when
        for (int i = 0; i < 5; i++) {
            ByteBuf chunk = Unpooled.buffer(1024).writeZero(1024);
            streamingBody.addChunk(chunk);
            chunk.release();
        }

        // then -- reset, not ended: no empty DATA frame after the four within the bound
        assertThat(streamingBody.getError(), instanceOf(StreamingBody.UnwrittenBytesLimitExceededException.class));
        verify(channel).shutdownOutput(Http3ErrorCode.H3_INTERNAL_ERROR.code());
        verify(channel).close();
        assertThat("headers and the four DATA frames within the bound", written.size(), is(5));
        for (Object frame : written) {
            if (frame instanceof DefaultHttp3DataFrame) {
                assertThat(((DefaultHttp3DataFrame) frame).content().readableBytes(), is(1024));
                ((DefaultHttp3DataFrame) frame).release();
            }
        }
    }

    /**
     * The streaming path must ask the bridge to drop content-length. A stale content-length -
     * typically copied from a relayed upstream response - describes a different body than the one
     * actually streamed, which a conforming HTTP/3 client treats as malformed. Asserting it here
     * (rather than only on the bridge) pins the call site, which is the half that can silently
     * regress by passing the wrong flag.
     */
    @Test
    public void shouldNotAnnounceContentLengthOnStreamingResponse() {
        // given
        ChannelHandlerContext ctx = mockCtxWithActiveChannel();
        Http3ResponseWriter writer = new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx);

        StreamingBody streamingBody = new StreamingBody(8192);

        HttpRequest req = request().withPath("/streaming_content_length");
        HttpResponse resp = response()
            .withStatusCode(200)
            .withHeader("content-length", "1234")
            .withHeader("x-custom", "kept")
            .withStreamingBody(streamingBody);

        // when
        writer.sendResponse(req, resp);

        // then
        ArgumentCaptor<DefaultHttp3HeadersFrame> headersCaptor = ArgumentCaptor.forClass(DefaultHttp3HeadersFrame.class);
        verify(ctx).writeAndFlush(headersCaptor.capture());
        DefaultHttp3HeadersFrame headers = headersCaptor.getValue();
        assertThat(headers.headers().get("content-length"), is(nullValue()));
        assertThat(headers.headers().get("x-custom").toString(), is("kept"));

        streamingBody.complete();
    }

    @Test
    public void shouldNotWriteOnCompleteWhenChannelInactive() {
        // given -- channel becomes inactive before onComplete
        ChannelHandlerContext ctx = mockCtxWithActiveChannel();
        Channel channel = ctx.channel();
        Http3ResponseWriter writer = new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx);

        StreamingBody streamingBody = new StreamingBody(8192);

        HttpRequest req = request().withPath("/disconnect");
        HttpResponse resp = response()
            .withStatusCode(200)
            .withStreamingBody(streamingBody);

        // when: send response (subscribes)
        writer.sendResponse(req, resp);
        verify(ctx).writeAndFlush(any(DefaultHttp3HeadersFrame.class));

        // simulate client disconnect
        when(channel.isActive()).thenReturn(false);

        // when: complete the stream
        streamingBody.complete();

        // then: no additional writeAndFlush should happen (only the headers)
        verify(ctx, times(1)).writeAndFlush(any());
    }

    @Test
    public void shouldNotWriteOnErrorWhenChannelInactive() {
        // given -- channel becomes inactive before onError
        ChannelHandlerContext ctx = mockCtxWithActiveChannel();
        Channel channel = ctx.channel();
        Http3ResponseWriter writer = new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx);

        StreamingBody streamingBody = new StreamingBody(8192);

        HttpRequest req = request().withPath("/disconnect-error");
        HttpResponse resp = response()
            .withStatusCode(200)
            .withStreamingBody(streamingBody);

        // when: send response (subscribes)
        writer.sendResponse(req, resp);
        verify(ctx).writeAndFlush(any(DefaultHttp3HeadersFrame.class));

        // simulate client disconnect
        when(channel.isActive()).thenReturn(false);

        // when: signal error
        streamingBody.error(new RuntimeException("upstream closed"));

        // then: no additional writeAndFlush should happen (only the headers)
        verify(ctx, times(1)).writeAndFlush(any());
    }

    @Test
    public void shouldHandleNullResponse() {
        // given
        ChannelHandlerContext ctx = mockCtxWithActiveChannel();
        Http3ResponseWriter writer = new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx);

        HttpRequest req = request().withPath("/null");

        // when: sendResponse with null
        writer.sendResponse(req, null);

        // then: should write a 404 response
        ArgumentCaptor<Object> writeCaptor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).write(writeCaptor.capture());

        Object headersObj = writeCaptor.getValue();
        assertThat(headersObj, instanceOf(DefaultHttp3HeadersFrame.class));
        DefaultHttp3HeadersFrame headers = (DefaultHttp3HeadersFrame) headersObj;
        assertThat(headers.headers().status().toString(), is("404"));
    }

    /**
     * Create a mock ChannelHandlerContext with an active channel.
     * The channel is NOT a QuicStreamChannel (just a regular channel mock)
     * which exercises the instanceof guard in shutdownQuicStreamOutput.
     */
    private ChannelHandlerContext mockCtxWithActiveChannel() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        stubCloseFuture(channel);
        when(ctx.channel()).thenReturn(channel);

        // writeAndFlush returns a succeeded future
        ChannelFuture future = mock(ChannelFuture.class);
        when(future.addListener(any())).thenReturn(future);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(ctx.write(any())).thenReturn(future);

        return ctx;
    }

    /**
     * Create a mock ChannelHandlerContext whose writeAndFlush immediately fires
     * the added GenericFutureListener as a success. This exercises the backpressure
     * path (requestMore() is called from the write-completion listener) and
     * verifies that the ByteBuf content is written correctly.
     *
     * @param writtenBufs collects the ByteBuf content of each DefaultHttp3DataFrame written
     */
    @SuppressWarnings("unchecked")
    private ChannelHandlerContext mockCtxWithListenerFiringChannel(List<ByteBuf> writtenBufs) {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        stubCloseFuture(channel);
        when(ctx.channel()).thenReturn(channel);

        // writeAndFlush returns a future that immediately fires its listener
        when(ctx.writeAndFlush(any())).thenAnswer(invocation -> {
            Object msg = invocation.getArgument(0);
            if (msg instanceof DefaultHttp3DataFrame) {
                ByteBuf content = ((DefaultHttp3DataFrame) msg).content();
                if (content.readableBytes() > 0) {
                    // retain a copy of the content for assertions
                    writtenBufs.add(Unpooled.copiedBuffer(content));
                }
            }
            ChannelFuture future = mock(ChannelFuture.class);
            when(future.isSuccess()).thenReturn(true);
            // immediately invoke any listener added to this future
            when(future.addListener(any())).thenAnswer(listenerInvocation -> {
                GenericFutureListener<ChannelFuture> listener = listenerInvocation.getArgument(0);
                listener.operationComplete(future);
                return future;
            });
            return future;
        });

        // write (non-flush) returns a basic succeeded future
        ChannelFuture writeFuture = mock(ChannelFuture.class);
        when(writeFuture.addListener(any())).thenReturn(writeFuture);
        when(ctx.write(any())).thenReturn(writeFuture);

        return ctx;
    }

    private static ChannelFuture copyDataFrame(Object msg, List<ByteBuf> copies) {
        if (msg instanceof DefaultHttp3DataFrame) {
            copies.add(Unpooled.copiedBuffer(((DefaultHttp3DataFrame) msg).content()));
            ((DefaultHttp3DataFrame) msg).release();
        }
        ChannelFuture future = mock(ChannelFuture.class);
        when(future.addListener(any())).thenReturn(future);
        return future;
    }

    /**
     * Stub the channel's close future, collecting the listeners added to it and dropping the ones removed.
     */
    @SuppressWarnings("unchecked")
    private static List<GenericFutureListener<ChannelFuture>> stubCloseFuture(Channel channel) {
        List<GenericFutureListener<ChannelFuture>> listeners = new ArrayList<>();
        ChannelFuture closeFuture = mock(ChannelFuture.class);
        when(closeFuture.addListener(any())).thenAnswer(invocation -> {
            listeners.add(invocation.getArgument(0));
            return closeFuture;
        });
        when(closeFuture.removeListener(any())).thenAnswer(invocation -> {
            listeners.remove(invocation.<GenericFutureListener<ChannelFuture>>getArgument(0));
            return closeFuture;
        });
        when(channel.closeFuture()).thenReturn(closeFuture);
        return listeners;
    }
}
