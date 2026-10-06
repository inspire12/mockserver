package org.mockserver.httpclient;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import org.junit.Test;
import org.mockserver.codec.StreamedResponseDecoderResultGuard;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Message;
import org.mockserver.model.StreamingBody;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.httpclient.NettyHttpClient.ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE;
import static org.mockserver.httpclient.NettyHttpClient.REMOTE_SOCKET;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

/**
 * How the forward client turns what Netty's codecs report for headers over a limit into a failed forward, and what
 * its HTTP/2 connection allocates for a response header block that is small on the wire and far over
 * {@code maxHeaderSize} once decoded: one 4 KiB field, sent once and then referred to 12,000 times from HPACK's
 * dynamic table at a byte a time.
 * <p>
 * The codecs run in an {@link EmbeddedChannel} on buffers without leak tracking: the test JVM's tracking records a
 * stack trace for every byte read, which would be all that the allocation test measured.
 */
public class ForwardHeaderLimitTest {

    private static final int LIMIT = 32 * 1024;
    private static final int TEN_MIB = 10 * 1024 * 1024;
    private static final int REFERENCES = 12_000;
    private static final String FIELD_NAME = "x-filler";
    private static final String FIELD_VALUE = "a".repeat(4000);
    private static final long DECODED_BYTES = (long) REFERENCES * (FIELD_NAME.length() + FIELD_VALUE.length() + 32);
    private static final String NETTYS_HEADER_BLOCK_MESSAGE = "Header size exceeded max allowed size (%d)";
    private static final AttributeKey<Http2ForwardStreamChildInitializer> STREAM_INITIALIZER = AttributeKey.valueOf("TEST_STREAM_INITIALIZER");

    private final List<String> logged = new ArrayList<>();
    private final MockServerLogger mockServerLogger = new MockServerLogger(ForwardHeaderLimitTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return true;
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry.getLogLevel() + " " + logEntry.getMessageFormat() + " " + logEntry.getArguments()[1]);
        }
    };

    @Test
    public void shouldAnnounceMaxHeaderSizeAsTheHeaderListLimit() {
        assertThat(HttpClientInitializer.forwardClientSettings(TEN_MIB, LIMIT).maxHeaderListSize(), is((long) LIMIT));
        assertThat(HttpClientInitializer.forwardClientSettings(TEN_MIB, 1).maxHeaderListSize(), is(1L));
    }

    @Test
    public void shouldBuildACodecThatReadsHeaderListsOfAnySizeWhenMaxHeaderSizeIsTheLargestInteger() {
        Http2Settings settings = HttpClientInitializer.forwardClientSettings(TEN_MIB, Integer.MAX_VALUE);
        Http2FrameCodec codec = Http2FrameCodecBuilder.forClient().initialSettings(settings).build();
        try {
            assertThat(settings.maxHeaderListSize(), is((long) Integer.MAX_VALUE));
            assertThat(codec.decoder().localSettings().maxHeaderListSize(), is((long) Integer.MAX_VALUE));
        } finally {
            new EmbeddedChannel(codec).finishAndReleaseAll();
        }
    }

    @Test
    public void shouldFailTheForwardForAnHttp2HeaderListOverTheLimitAndSayWhetherItWasHeadersOrTrailers() {
        Http2Exception overLimit = Http2Exception.headerListSizeError(3, Http2Error.PROTOCOL_ERROR, true, NETTYS_HEADER_BLOCK_MESSAGE, LIMIT);

        assertThat(streamFailure(overLimit).getMessage(), is("upstream response headers are larger than maxHeaderSize (" + LIMIT + " bytes)"));
        assertThat(streamFailure(overLimit, new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("103"))).getMessage(), is("upstream response headers are larger than maxHeaderSize (" + LIMIT + " bytes)"));
        assertThat(streamFailure(overLimit, new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"))).getMessage(), is("upstream response trailers are larger than maxHeaderSize (" + LIMIT + " bytes)"));
        assertThat(logged, contains(
            "WARN failing forward to:{}because:{} upstream response headers are larger than maxHeaderSize (" + LIMIT + " bytes)",
            "WARN failing forward to:{}because:{} upstream response headers are larger than maxHeaderSize (" + LIMIT + " bytes)",
            "WARN failing forward to:{}because:{} upstream response trailers are larger than maxHeaderSize (" + LIMIT + " bytes)"
        ));
    }

    @Test
    public void shouldPassOnEveryOtherExceptionOfAnHttp2Stream() {
        Http2Exception whileEncoding = Http2Exception.headerListSizeError(3, Http2Error.PROTOCOL_ERROR, false, NETTYS_HEADER_BLOCK_MESSAGE, LIMIT);
        Http2Exception anotherStreamError = Http2Exception.streamError(3, Http2Error.PROTOCOL_ERROR, NETTYS_HEADER_BLOCK_MESSAGE, LIMIT);
        IOException notHttp2 = new IOException("Header size exceeded max allowed size");

        assertThat(streamFailure(whileEncoding), sameInstance(whileEncoding));
        assertThat(streamFailure(anotherStreamError), sameInstance(anotherStreamError));
        assertThat(streamFailure(notHttp2), sameInstance(notHttp2));
        assertThat(logged, empty());
    }

    @Test
    public void shouldFailTheForwardForAnHttp2HeaderBlockMoreThanAQuarterOverTheLimitAndNotPassItOn() throws Exception {
        CompletableFuture<Message> forward = new CompletableFuture<>();
        EmbeddedChannel connection = new EmbeddedChannel(new ForwardHeaderLimit.Http2Connection(mockServerLogger, LIMIT));
        connection.attr(RESPONSE_FUTURE).set(forward);

        connection.pipeline().fireExceptionCaught(Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, NETTYS_HEADER_BLOCK_MESSAGE, LIMIT + LIMIT / 4));

        connection.checkException();
        assertThat(failureOf(forward), instanceOf(HeaderLimitExceededException.class));
        assertThat(failureOf(forward).getMessage(), is("an upstream response header block is more than a quarter larger than maxHeaderSize (" + LIMIT + " bytes)"));
        assertThat(logged, hasSize(1));
        assertThat(ForwardHeaderLimit.isAlreadyLogged(connection, new PrematureChannelClosureException("cut short")), is(true));
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldPassOnEveryOtherExceptionOfAnHttp2Connection() {
        List<Throwable> otherFailures = new ArrayList<>();
        otherFailures.add(Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, "another protocol error"));
        otherFailures.add(Http2Exception.connectionError(Http2Error.ENHANCE_YOUR_CALM, NETTYS_HEADER_BLOCK_MESSAGE, LIMIT));
        otherFailures.add(Http2Exception.streamError(3, Http2Error.PROTOCOL_ERROR, NETTYS_HEADER_BLOCK_MESSAGE, LIMIT));
        otherFailures.add(new IOException("Header size exceeded max allowed size"));
        for (Throwable otherFailure : otherFailures) {
            CompletableFuture<Message> forward = new CompletableFuture<>();
            List<Throwable> passedOn = new ArrayList<>();
            EmbeddedChannel connection = new EmbeddedChannel(new ForwardHeaderLimit.Http2Connection(mockServerLogger, LIMIT), recorder(passedOn));
            connection.attr(RESPONSE_FUTURE).set(forward);

            connection.pipeline().fireExceptionCaught(otherFailure);

            assertThat(passedOn, contains(sameInstance(otherFailure)));
            assertThat(forward.isDone(), is(false));
            assertThat(ForwardHeaderLimit.isAlreadyLogged(connection, new PrematureChannelClosureException("cut short")), is(false));
            connection.finishAndReleaseAll();
        }
        assertThat(logged, empty());
    }

    @Test
    public void shouldFailTheForwardForHttp1ResponseHeadersOverTheLimitAndCloseTheConnection() {
        List<Throwable> failures = new ArrayList<>();
        List<Object> read = new ArrayList<>();
        EmbeddedChannel connection = http1Connection(100, failures, read);

        connection.writeInbound(ascii("HTTP/1.1 200 OK\r\ncontent-length: 2\r\nset-cookie: session=" + "a".repeat(100 - 37 + 1) + "\r\n\r\nok"));

        assertThat(failures, hasSize(1));
        assertThat(failures.get(0), instanceOf(HeaderLimitExceededException.class));
        assertThat(failures.get(0).getMessage(), is("upstream response headers are larger than maxHeaderSize (100 bytes)"));
        assertThat("nothing of the response is handed on", read, empty());
        assertThat(connection.isOpen(), is(false));
        assertThat(logged, hasSize(1));
        assertThat(ForwardHeaderLimit.isAlreadyLogged(connection, new PrematureChannelClosureException("cut short")), is(true));
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldFailTheForwardForHttp1ResponseTrailersOverTheLimit() {
        List<Throwable> failures = new ArrayList<>();
        List<Object> read = new ArrayList<>();
        EmbeddedChannel connection = http1Connection(100, failures, read);

        // Netty counts the trailers on top of the 26 bytes of "transfer-encoding: chunked"
        connection.writeInbound(ascii("HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n\r\n2\r\nok\r\n0\r\nx-trailer: " + "a".repeat(100 - 26 - 11 + 1) + "\r\n\r\n"));

        assertThat(failures, hasSize(1));
        assertThat(failures.get(0).getMessage(), is("upstream response headers and trailers are together larger than maxHeaderSize (100 bytes)"));
        assertThat(connection.isOpen(), is(false));
        assertThat(logged, hasSize(1));
        read.forEach(ReferenceCountUtil::release);
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldHandOnAnHttp1ResponseWithinTheLimit() {
        List<Throwable> failures = new ArrayList<>();
        List<Object> read = new ArrayList<>();
        EmbeddedChannel connection = http1Connection(100, failures, read);

        connection.writeInbound(ascii("HTTP/1.1 200 OK\r\ncontent-length: 2\r\nset-cookie: session=" + "a".repeat(100 - 37) + "\r\n\r\nok"));

        assertThat(failures, empty());
        assertThat(((HttpResponse) read.get(0)).headers().get("set-cookie").length(), is("session=".length() + 100 - 37));
        assertThat(((HttpContent) read.get(1)).content().toString(StandardCharsets.US_ASCII), is("ok"));
        assertThat(connection.isOpen(), is(true));
        assertThat(logged, empty());
        read.forEach(ReferenceCountUtil::release);
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldFailTheForwardForAnHttp1ResponseThatCannotBeDecodedAndCloseTheConnection() {
        Map<String, String> undecodable = new LinkedHashMap<>();
        undecodable.put("HTTP/1.1 200 " + "a".repeat(HttpObjectDecoder.DEFAULT_MAX_INITIAL_LINE_LENGTH) + "\r\n\r\n", "TooLongHttpLineException: An HTTP line is larger than 4096 bytes.");
        undecodable.put("not http\r\n\r\n", "IllegalArgumentException: ");
        undecodable.put("HTTP/1.1 200 OK\r\nx-bad(header): value\r\ncontent-length: 2\r\n\r\nok", "IllegalArgumentException: ");
        for (Map.Entry<String, String> response : undecodable.entrySet()) {
            List<Throwable> failures = new ArrayList<>();
            List<Object> read = new ArrayList<>();
            EmbeddedChannel connection = http1Connection(LIMIT, failures, read);

            connection.writeInbound(ascii(response.getKey()));

            assertThat(failures, hasSize(1));
            assertThat(failures.get(0), instanceOf(UndecodableResponseException.class));
            assertThat(failures.get(0).getMessage(), startsWith("response from upstream could not be decoded: " + response.getValue()));
            assertThat("nothing of the response is handed on", read, empty());
            assertThat(connection.isOpen(), is(false));
            assertThat(ForwardHeaderLimit.isAlreadyLogged(connection, failures.get(0)), is(true));
            connection.finishAndReleaseAll();
        }
        assertThat(logged, hasSize(undecodable.size()));
        for (String entry : logged) {
            assertThat("no request was waiting", entry, startsWith("WARN response on connection to:{}could not be decoded:{} "));
        }
    }

    @Test
    public void shouldFailTheForwardForAnHttp1ChunkSizeThatIsNotANumberAfterHandingOnTheHead() {
        List<Throwable> failures = new ArrayList<>();
        List<Object> read = new ArrayList<>();
        EmbeddedChannel connection = http1Connection(LIMIT, failures, read);

        connection.writeInbound(ascii("HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n\r\nzz\r\nok\r\n0\r\n\r\n"));

        assertThat(failures, hasSize(1));
        assertThat(failures.get(0).getMessage(), startsWith("response from upstream could not be decoded: NumberFormatException: "));
        assertThat("only the head, which the aggregator holds until the response is complete", read, hasSize(1));
        assertThat(read.get(0), instanceOf(HttpResponse.class));
        assertThat(connection.isOpen(), is(false));
        assertThat("the aggregator's report of the head it held", ForwardHeaderLimit.isAlreadyLogged(connection, new PrematureChannelClosureException("Channel closed while still aggregating message")), is(true));
        read.forEach(ReferenceCountUtil::release);
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldLogAnUndecodableHttp1ResponseBelowAWarningWhenARequestIsWaitingForIt() {
        List<Throwable> failures = new ArrayList<>();
        EmbeddedChannel connection = http1Connection(LIMIT, failures, new ArrayList<>());
        CompletableFuture<Message> forward = new CompletableFuture<>();
        connection.attr(RESPONSE_FUTURE).set(forward);
        connection.attr(REMOTE_SOCKET).set(InetSocketAddress.createUnresolved("upstream.example", 8080));

        connection.writeInbound(ascii("not http\r\n\r\n"));

        assertThat(failures, hasSize(1));
        assertThat(failures.get(0).getMessage(), startsWith("response from upstream.example:8080 could not be decoded: IllegalArgumentException: "));
        assertThat(logged, hasSize(1));
        assertThat(logged.get(0), startsWith("DEBUG response on connection to:{}could not be decoded:{} IllegalArgumentException: "));
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldLeaveAnUndecodableHttp1ResponseThatIsStreamedToTheStreamsGuard() {
        List<Throwable> failures = new ArrayList<>();
        List<Object> read = new ArrayList<>();
        EmbeddedChannel connection = http1Connection(LIMIT, failures, read);
        connection.pipeline().addAfter(connection.pipeline().context(ForwardHeaderLimit.Http1Response.class).name(), "guard", new StreamedResponseDecoderResultGuard());

        connection.writeInbound(ascii("HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n\r\nzz\r\n"));

        assertThat(failures, hasSize(1));
        assertThat(failures.get(0), instanceOf(StreamingBody.StreamAbortedException.class));
        assertThat(logged, empty());
        read.forEach(ReferenceCountUtil::release);
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldFindAHeaderLimitFailureInWhatItCaused() {
        HeaderLimitExceededException refusal = new HeaderLimitExceededException("too large");

        assertThat(HeaderLimitExceededException.in(refusal), sameInstance(refusal));
        assertThat(HeaderLimitExceededException.in(new ExecutionException(new IllegalStateException("proxy", refusal))), sameInstance(refusal));
        assertThat(HeaderLimitExceededException.in(new ExecutionException(new IOException("reset"))), nullValue());
        assertThat(HeaderLimitExceededException.in(null), nullValue());
        assertThat(ForwardHeaderLimit.isAlreadyLogged(new EmbeddedChannel(), refusal), is(true));
        assertThat(ForwardHeaderLimit.isAlreadyLogged(new EmbeddedChannel(), new IOException("reset")), is(false));
    }

    @Test
    public void shouldFailAResponseThatDecodesFarOverTheLimitWithinTheAllocationBound() throws Exception {
        Configuration configuration = configuration();
        assertThat("far over the limit decoded", DECODED_BYTES, greaterThan(100L * configuration.maxHeaderSize()));
        // a first connection, so that loading the classes involved is not measured
        readResponseThatDecodesFarOverTheLimit(configuration);

        long allocated = readResponseThatDecodesFarOverTheLimit(configuration);

        // less than the limit itself: the fields kept up to the limit all share the one name and value
        assertThat("allocated " + allocated + " bytes for " + DECODED_BYTES + " decoded", allocated, lessThan((long) configuration.maxHeaderSize()));
    }

    /**
     * A forward-client connection as {@link HttpClientInitializer} builds it, answered by a plain HTTP/2 server
     * connection standing in for the upstream.
     *
     * @return the bytes the client allocated while it read and refused the response's header block
     */
    private long readResponseThatDecodesFarOverTheLimit(Configuration configuration) throws Exception {
        logged.clear();
        int maxHeaderSize = configuration.maxHeaderSize();
        CompletableFuture<Message> forward = new CompletableFuture<>();
        AtomicInteger requestStream = new AtomicInteger();
        AtomicLong resetErrorCode = new AtomicLong(-1);
        AtomicLong goAwayErrorCode = new AtomicLong(-1);
        Http2ConnectionHandler upstreamCodec = new Http2ConnectionHandlerBuilder()
            .server(true)
            .frameListener(new Http2FrameAdapter() {
                @Override
                public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endStream) {
                    requestStream.set(streamId);
                }

                @Override
                public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
                    resetErrorCode.set(errorCode);
                }

                @Override
                public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData) {
                    goAwayErrorCode.set(errorCode);
                }
            })
            .build();
        EmbeddedChannel upstream = new EmbeddedChannel(upstreamCodec);
        EmbeddedChannel client = forwardClientConnection(configuration, forward);
        try {
            upstream.writeInbound(withoutLeakTracking(written(client)));
            client.writeInbound(withoutLeakTracking(written(upstream)));
            Future<Http2StreamChannel> open = new Http2StreamChannelBootstrap(client).handler(client.attr(STREAM_INITIALIZER).get()).open();
            client.runPendingTasks();
            open.getNow().writeAndFlush(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/far_over"));
            client.runPendingTasks();
            upstream.writeInbound(withoutLeakTracking(written(client)));
            assertThat("the upstream read the request", requestStream.get(), greaterThan(0));

            Http2Headers headers = new DefaultHttp2Headers().status("200");
            for (int i = 0; i < REFERENCES; i++) {
                headers.add(FIELD_NAME, FIELD_VALUE);
            }
            ChannelHandlerContext upstreamCtx = upstream.pipeline().context(upstreamCodec);
            // left open, so that the upstream is told of the reset that follows
            upstreamCodec.encoder().writeHeaders(upstreamCtx, requestStream.get(), headers, 0, false, upstreamCtx.newPromise());
            upstream.flush();
            byte[] headerBlockFrames = written(upstream);
            assertThat("small on the wire: no connection error", headerBlockFrames.length, lessThan(maxHeaderSize / 8));

            com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long thread = Thread.currentThread().getId();
            long allocatedBefore = threads.getThreadAllocatedBytes(thread);
            client.writeInbound(withoutLeakTracking(headerBlockFrames));
            client.runPendingTasks();
            long allocated = threads.getThreadAllocatedBytes(thread) - allocatedBefore;

            assertThat(failureOf(forward), instanceOf(HeaderLimitExceededException.class));
            assertThat(failureOf(forward).getMessage(), is("upstream response headers are larger than maxHeaderSize (" + maxHeaderSize + " bytes)"));
            assertThat(logged, contains("WARN failing forward to:{}because:{} upstream response headers are larger than maxHeaderSize (" + maxHeaderSize + " bytes)"));
            client.checkException();
            upstream.writeInbound(withoutLeakTracking(written(client)));
            assertThat("the stream is reset", resetErrorCode.get(), is(Http2Error.CANCEL.code()));
            assertThat("the connection carried only this forward, and is closed without a connection error", goAwayErrorCode.get(), is(Http2Error.NO_ERROR.code()));
            return allocated;
        } finally {
            client.finishAndReleaseAll();
            upstream.finishAndReleaseAll();
        }
    }

    /**
     * The HTTP/2 connection {@link HttpClientInitializer} builds, with the forward in flight on it.
     */
    private EmbeddedChannel forwardClientConnection(Configuration configuration, CompletableFuture<Message> forward) {
        int maxHeaderSize = configuration.maxHeaderSize();
        EmbeddedChannel client = new EmbeddedChannel();
        client.config().setAllocator(new UnpooledByteBufAllocator(false, true));
        client.attr(RESPONSE_FUTURE).set(forward);
        client.attr(ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE).set(true);
        client.attr(REMOTE_SOCKET).set(InetSocketAddress.createUnresolved("upstream", 443));
        Http2ForwardStreamChildInitializer childInitializer = new Http2ForwardStreamChildInitializer(configuration, mockServerLogger, Collections.emptyMap(), new HttpClientHandler(mockServerLogger), new HttpClientConnectionErrorHandler(), maxHeaderSize);
        client.pipeline().addLast(Http2FrameCodecBuilder.forClient().initialSettings(HttpClientInitializer.forwardClientSettings(TEN_MIB, maxHeaderSize)).build());
        client.pipeline().addLast(new Http2MultiplexHandler(childInitializer));
        client.pipeline().addLast(new ForwardHeaderLimit.Http2Connection(mockServerLogger, maxHeaderSize));
        client.attr(STREAM_INITIALIZER).set(childInitializer);
        return client;
    }

    /**
     * @return what a stream's handlers after the limit handler are told failed, having first read {@code framesRead}
     */
    private Throwable streamFailure(Throwable cause, Object... framesRead) {
        List<Throwable> failures = new ArrayList<>();
        EmbeddedChannel stream = new EmbeddedChannel(new ForwardHeaderLimit.Http2Stream(mockServerLogger, LIMIT), recorder(failures));
        for (Object frame : framesRead) {
            stream.writeInbound(frame);
        }
        stream.pipeline().fireExceptionCaught(cause);
        stream.finishAndReleaseAll();
        assertThat(failures, hasSize(1));
        return failures.get(0);
    }

    private EmbeddedChannel http1Connection(int maxHeaderSize, List<Throwable> failures, List<Object> read) {
        EmbeddedChannel connection = new EmbeddedChannel(
            new HttpClientCodec(HttpObjectDecoder.DEFAULT_MAX_INITIAL_LINE_LENGTH, maxHeaderSize, HttpObjectDecoder.DEFAULT_MAX_CHUNK_SIZE),
            new ForwardHeaderLimit.Http1Response(mockServerLogger, maxHeaderSize),
            new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    read.add(msg);
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    failures.add(cause);
                }
            }
        );
        connection.writeOutbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        ReferenceCountUtil.release(connection.readOutbound());
        return connection;
    }

    private static ChannelInboundHandlerAdapter recorder(List<Throwable> failures) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ReferenceCountUtil.release(msg);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                failures.add(cause);
            }
        };
    }

    private static Throwable failureOf(CompletableFuture<Message> forward) throws InterruptedException {
        assertThat("the forward has failed", forward.isCompletedExceptionally(), is(true));
        try {
            forward.get();
            throw new AssertionError("the forward did not fail");
        } catch (ExecutionException failed) {
            return failed.getCause();
        }
    }

    private static ByteBuf ascii(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
    }

    private static byte[] written(EmbeddedChannel channel) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (ByteBuf buffer = channel.readOutbound(); buffer != null; buffer = channel.readOutbound()) {
            bytes.writeBytes(ByteBufUtil.getBytes(buffer));
            buffer.release();
        }
        return bytes.toByteArray();
    }

    private static ByteBuf withoutLeakTracking(byte[] bytes) {
        return Unpooled.wrappedBuffer(bytes);
    }
}
