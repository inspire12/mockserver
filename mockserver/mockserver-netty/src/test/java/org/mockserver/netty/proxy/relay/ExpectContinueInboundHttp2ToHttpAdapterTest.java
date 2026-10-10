package org.mockserver.netty.proxy.relay;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandlerBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * The relay's client-facing HTTP/2 adapter against a real HTTP/2 client, frames shuttled between two embedded channels:
 * a request sent with {@code Expect} is handed on once, with its body, after the relay has met the expectation itself.
 */
public class ExpectContinueInboundHttp2ToHttpAdapterTest {

    private static final int MAX_CONTENT_LENGTH = 1024;
    private static final int STREAM = 3;

    private EmbeddedChannel client;
    private EmbeddedChannel relay;
    private Http2ConnectionHandler clientHandler;
    private final List<FullHttpRequest> handedOn = new ArrayList<>();
    private final List<String> framesAtClient = new ArrayList<>();
    private final RecordingAllocator relayAllocator = new RecordingAllocator();

    @Before
    public void connect() {
        clientHandler = new Http2ConnectionHandlerBuilder()
            .server(false)
            .frameListener(new Http2FrameAdapter() {
                @Override
                public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding, boolean endOfStream) {
                    framesAtClient.add(streamId + " HEADERS " + headers.status() + (endOfStream ? " END_STREAM" : ""));
                }

                @Override
                public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endOfStream) {
                    onHeadersRead(ctx, streamId, headers, padding, endOfStream);
                }

                @Override
                public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
                    framesAtClient.add(streamId + " RST_STREAM " + Http2Error.valueOf(errorCode));
                }
            })
            .build();
        client = new EmbeddedChannel(clientHandler);
        Http2Connection relayConnection = new DefaultHttp2Connection(true);
        relay = new EmbeddedChannel(
            new HttpToHttp2ConnectionHandlerBuilder()
                .frameListener(ExpectContinueInboundHttp2ToHttpAdapter.forConnection(relayConnection, MAX_CONTENT_LENGTH))
                .connection(relayConnection)
                .build(),
            new SimpleChannelInboundHandler<FullHttpRequest>(false) {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                    handedOn.add(request);
                }
            }
        );
        relay.config().setAllocator(relayAllocator);
        pump();
    }

    @After
    public void close() {
        handedOn.forEach(FullHttpRequest::release);
        client.finishAndReleaseAll();
        relay.finishAndReleaseAll();
    }

    @Test
    public void shouldAnswerContinueThenHandOnTheRequestOnceWithItsBody() {
        sendHeaders(STREAM, "/upload", false, "expect", "100-continue");

        assertThat(framesAtClient, contains(STREAM + " HEADERS 100"));
        assertThat(handedOn, empty());

        sendData(STREAM, "the body", true);

        assertThat(handedOn.size(), is(1));
        FullHttpRequest request = handedOn.get(0);
        assertThat(request.uri(), is("/upload"));
        assertThat(request.content().toString(StandardCharsets.UTF_8), is("the body"));
        assertThat("the relay has met the expectation", request.headers().get(HttpHeaderNames.EXPECT), nullValue());
        assertThat(framesAtClient, contains(STREAM + " HEADERS 100"));
    }

    @Test
    public void shouldMatchTheContinueExpectationIgnoringCase() {
        sendHeaders(STREAM, "/upload", false, "expect", "100-Continue", "content-length", String.valueOf(MAX_CONTENT_LENGTH));

        assertThat(framesAtClient, contains(STREAM + " HEADERS 100"));
        sendData(STREAM, "x".repeat(MAX_CONTENT_LENGTH), true);
        assertThat(handedOn.size(), is(1));
        assertThat(handedOn.get(0).content().readableBytes(), is(MAX_CONTENT_LENGTH));
    }

    @Test
    public void shouldRefuseAnExpectationItCannotMeet() {
        sendHeaders(STREAM, "/upload", false, "expect", "something-else");

        assertThat(framesAtClient, contains(STREAM + " HEADERS 417 END_STREAM", STREAM + " RST_STREAM NO_ERROR"));
        assertThat(handedOn, empty());
        assertNextStreamHandedOn();
    }

    @Test
    public void shouldRefuseAContinueExpectationForABodyOverTheLimit() {
        sendHeaders(STREAM, "/upload", false, "expect", "100-continue", "content-length", String.valueOf(MAX_CONTENT_LENGTH + 1));

        assertThat(framesAtClient, contains(STREAM + " HEADERS 413 END_STREAM", STREAM + " RST_STREAM NO_ERROR"));
        assertThat(handedOn, empty());
        assertNextStreamHandedOn();
    }

    @Test
    public void shouldKeepTheConnectionWhenARefusedRequestsBodyIsAlreadyOnItsWay() {
        // a client that does not wait to be told to continue: its DATA arrives after the relay has refused the stream
        ChannelHandlerContext ctx = client.pipeline().firstContext();
        clientHandler.encoder().writeHeaders(ctx, STREAM, requestHeaders("/upload", "expect", "something-else"), 0, false, ctx.newPromise());
        clientHandler.encoder().writeData(ctx, STREAM, Unpooled.copiedBuffer("part of the body", StandardCharsets.UTF_8), 0, false, ctx.newPromise());
        client.flush();
        pump();

        assertThat("the stream's DATA did not close the connection", relay.isActive(), is(true));
        assertThat(framesAtClient, contains(STREAM + " HEADERS 417 END_STREAM", STREAM + " RST_STREAM NO_ERROR"));
        assertNextStreamHandedOn();
    }

    @Test
    public void shouldAnswerTheFirstOfSeveralExpectations() {
        sendHeaders(STREAM, "/upload", false, "expect", "100-continue", "expect", "something-else");
        sendHeaders(STREAM + 2, "/upload", false, "expect", "something-else", "expect", "100-continue");

        assertThat(framesAtClient, contains(STREAM + " HEADERS 100", (STREAM + 2) + " HEADERS 417 END_STREAM", (STREAM + 2) + " RST_STREAM NO_ERROR"));
    }

    @Test
    public void shouldHandOnARequestWhoseHeadersEndTheStreamAsItIs() {
        sendHeaders(STREAM, "/no-body", true, "expect", "100-continue");

        assertThat("a complete request needs no 100", framesAtClient, empty());
        assertThat(handedOn.size(), is(1));
        assertThat(handedOn.get(0).headers().get(HttpHeaderNames.EXPECT), is("100-continue"));
    }

    @Test
    public void shouldHandOnARequestWithoutExpectOnlyOnceItIsComplete() {
        sendHeaders(STREAM, "/upload", false);
        assertThat(handedOn, empty());

        sendData(STREAM, "the body", true);

        assertThat(handedOn.size(), is(1));
        assertThat(handedOn.get(0).content().toString(StandardCharsets.UTF_8), is("the body"));
        assertThat(framesAtClient, empty());
    }

    @Test
    public void shouldReleaseARequestStillUploadingWhenItsStreamIsReset() {
        sendHeaders(STREAM, "/upload", false, "expect", "100-continue");
        sendData(STREAM, "part of the body", false);
        assertThat(relayAllocator.unreleased(), is(1L));

        // the relay resets the stream itself: the body passes the limit
        sendData(STREAM, "x".repeat(MAX_CONTENT_LENGTH), false);

        assertThat(framesAtClient, contains(STREAM + " HEADERS 100", STREAM + " RST_STREAM ENHANCE_YOUR_CALM"));
        assertThat(handedOn, empty());
        assertThat(relayAllocator.unreleased(), is(0L));
    }

    private void assertNextStreamHandedOn() {
        sendHeaders(STREAM + 2, "/next", true);
        assertThat(handedOn.size(), is(1));
        assertThat(handedOn.get(0).uri(), is("/next"));
    }

    private void sendHeaders(int streamId, String path, boolean endOfStream, String... extraHeaders) {
        ChannelHandlerContext ctx = client.pipeline().firstContext();
        clientHandler.encoder().writeHeaders(ctx, streamId, requestHeaders(path, extraHeaders), 0, endOfStream, ctx.newPromise());
        client.flush();
        pump();
    }

    private static Http2Headers requestHeaders(String path, String... extraHeaders) {
        Http2Headers headers = new DefaultHttp2Headers().method("POST").scheme("https").authority("localhost").path(path);
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
            headers.add(extraHeaders[i], extraHeaders[i + 1]);
        }
        return headers;
    }

    private void sendData(int streamId, String data, boolean endOfStream) {
        ChannelHandlerContext ctx = client.pipeline().firstContext();
        clientHandler.encoder().writeData(ctx, streamId, Unpooled.copiedBuffer(data, StandardCharsets.UTF_8), 0, endOfStream, ctx.newPromise());
        client.flush();
        pump();
    }

    /**
     * Records the relay's buffers. The request being built is the only one the relay holds between reads: every frame it
     * writes is read off and released by the client.
     */
    private static final class RecordingAllocator extends AbstractByteBufAllocator {
        private final List<ByteBuf> allocated = new ArrayList<>();

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            return record(UnpooledByteBufAllocator.DEFAULT.heapBuffer(initialCapacity, maxCapacity));
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            return record(UnpooledByteBufAllocator.DEFAULT.directBuffer(initialCapacity, maxCapacity));
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }

        private ByteBuf record(ByteBuf buffer) {
            allocated.add(buffer);
            return buffer;
        }

        long unreleased() {
            return allocated.stream().filter(buffer -> buffer.refCnt() > 0).count();
        }
    }

    private void pump() {
        boolean moved;
        do {
            moved = false;
            ByteBuf bytes;
            while ((bytes = client.readOutbound()) != null) {
                relay.writeInbound(bytes);
                moved = true;
            }
            while ((bytes = relay.readOutbound()) != null) {
                client.writeInbound(bytes);
                moved = true;
            }
        } while (moved);
    }
}
