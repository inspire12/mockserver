package org.mockserver.netty.unification;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import org.junit.Test;
import org.mockserver.codec.CoalescingHttpObjectAggregator;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * One HTTP/2 connection carries up to 100 concurrent streams, so each stream's aggregator holds a tenth of an
 * HTTP/1.1 connection's components (1,024 at the default 10 MiB). Frames go through the chain every ordinary
 * stream gets ({@link Http2MultiplexChildInitializer#installReAggregatingChain}); the request is captured
 * straight after the aggregator.
 */
public class Http2StreamComponentLimitTest {

    private static final int SIXTEEN_KIB = 16 * 1024;
    private static final int SIXTY_FOUR_MIB = 64 * 1024 * 1024;

    @Test
    public void shouldInstallTheStreamComponentLimit() {
        EmbeddedChannel channel = streamChain(configuration());
        try {
            assertThat(channel.pipeline().get(HttpObjectAggregator.class).maxCumulationBufferComponents(), is(1024));
        } finally {
            channel.finishAndReleaseAll();
        }
        channel = streamChain(configuration().maxRequestBodySize(SIXTY_FOUR_MIB));
        try {
            assertThat(channel.pipeline().get(HttpObjectAggregator.class).maxCumulationBufferComponents(), is(6553));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldCoalesceTheBodyOfEveryStream() {
        EmbeddedChannel channel = streamChain(configuration());
        try {
            HttpObjectAggregator aggregator = channel.pipeline().get(HttpObjectAggregator.class);
            assertThat(aggregator, instanceOf(CoalescingHttpObjectAggregator.class));
            assertThat(((CoalescingHttpObjectAggregator) aggregator).isCoalescingSmallContent(), is(true));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    // the limit is a hang guard: the build's leak detector records a stack trace for each of these frames
    @Test(timeout = 600_000)
    public void shouldCopyABodyOfOneByteDataFramesAboutOnce() {
        // consolidating the whole body every 1,024 frames would copy about frames^2 / 2,048 bytes: 2 GiB here
        int frames = 2 * 1024 * 1024;
        TrackingAllocator allocator = new TrackingAllocator();
        EmbeddedChannel channel = streamChain(configuration(), allocator);
        CapturingHandler capture = captureAfterAggregator(channel);
        channel.writeInbound(new DefaultHttp2HeadersFrame(postHeaders(), false));
        byte[] body = new byte[frames];
        for (int i = 0; i < frames; i++) {
            body[i] = (byte) ((i * 31 + i / 251) % 256);
            channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(body, i, 1), i == frames - 1));
        }
        CompositeByteBuf content = capture.content();
        try {
            assertThat(ByteBufUtil.getBytes(content), is(body));
            assertThat(content.numComponents(), lessThanOrEqualTo(64 + frames / SIXTEEN_KIB + 16));
            assertThat(allocator.allocatedBytes, lessThanOrEqualTo((long) frames + SIXTEEN_KIB));
        } finally {
            content.release();
            channel.finishAndReleaseAll();
        }
        assertThat(content.refCnt(), is(0));
        allocator.assertAllReleased();
    }

    @Test(timeout = 60_000)
    public void shouldKeepABodyIntactOnThePooledAllocatorAcrossMerges() {
        // runs of 16 x 64-byte frames between 1 KiB frames merge first near 1 MB, so runs are copied into a pooled
        // block both before and after a merge releases the previous one
        int bodyBytes = 3 * 1024 * 1024;
        EmbeddedChannel channel = streamChain(configuration(), PooledByteBufAllocator.DEFAULT);
        CapturingHandler capture = captureAfterAggregator(channel);
        byte[] body = new byte[bodyBytes];
        for (int i = 0; i < bodyBytes; i++) {
            body[i] = (byte) ((i * 31 + i / 251) % 256);
        }
        Throwable failure = null;
        try {
            channel.writeInbound(new DefaultHttp2HeadersFrame(postHeaders(), false));
            int offset = 0;
            for (int frame = 0; offset < bodyBytes; frame++) {
                int length = Math.min(frame % 17 == 16 ? 1024 : 64, bodyBytes - offset);
                ByteBuf data = PooledByteBufAllocator.DEFAULT.buffer(length).writeBytes(body, offset, length);
                offset += length;
                channel.writeInbound(new DefaultHttp2DataFrame(data, offset == bodyBytes));
            }
            CompositeByteBuf content = capture.content();
            try {
                assertThat(ByteBufUtil.getBytes(content), is(body));
                assertThat(content.numComponents(), lessThanOrEqualTo(1024));
            } finally {
                content.release();
            }
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            try {
                channel.finishAndReleaseAll();
            } catch (RuntimeException e) {
                if (failure == null) {
                    throw e;
                }
                failure.addSuppressed(e);
            }
        }
    }

    @Test
    public void shouldBoundComponentsWhenAClientSendsOneByteDataFrames() {
        int frames = 20_000;
        byte[] body = new byte[frames];
        for (int i = 0; i < frames; i++) {
            body[i] = (byte) (i % 251);
        }
        EmbeddedChannel channel = streamChain(configuration());
        CapturingHandler capture = captureAfterAggregator(channel);
        channel.writeInbound(new DefaultHttp2HeadersFrame(postHeaders(), false));
        for (int i = 0; i < frames; i++) {
            channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(new byte[]{body[i]}), i == frames - 1));
        }
        CompositeByteBuf content = capture.content();
        try {
            assertThat(content.numComponents(), lessThanOrEqualTo(1024));
            assertThat(ByteBufUtil.getBytes(content), is(body));
        } finally {
            content.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldNotConsolidateABodyOfSixteenKibDataFrames() {
        // 4,096 frames: past Netty's default of 1,024 components, within the 6,553 allowed at 64 MiB; every frame
        // wraps the same array, so the 64 MiB body costs 16 KiB
        int frames = SIXTY_FOUR_MIB / SIXTEEN_KIB;
        byte[] frame = new byte[SIXTEEN_KIB];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (byte) (i % 251);
        }
        EmbeddedChannel channel = streamChain(configuration().maxRequestBodySize(SIXTY_FOUR_MIB));
        CapturingHandler capture = captureAfterAggregator(channel);
        channel.writeInbound(new DefaultHttp2HeadersFrame(postHeaders(), false));
        for (int i = 0; i < frames; i++) {
            channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame), i == frames - 1));
        }
        CompositeByteBuf content = capture.content();
        try {
            assertThat(content.readableBytes(), is(SIXTY_FOUR_MIB));
            assertThat(content.numComponents(), is(frames));
            assertThat(content.getByte(SIXTY_FOUR_MIB - 1), is(frame[SIXTEEN_KIB - 1]));
        } finally {
            content.release();
            channel.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel streamChain(Configuration configuration) {
        return streamChain(configuration, null);
    }

    private static EmbeddedChannel streamChain(Configuration configuration, ByteBufAllocator allocator) {
        EmbeddedChannel channel = new EmbeddedChannel();
        if (allocator != null) {
            channel.config().setAllocator(allocator);
        }
        Http2MultiplexChildInitializer.installReAggregatingChain(
            channel.pipeline(),
            configuration,
            new MockServerLogger(Http2StreamComponentLimitTest.class),
            false,
            null,
            channel,
            mock(CallbackWebSocketServerHandler.class),
            mock(DashboardWebSocketHandler.class),
            null,
            mock(TraceContextHandler.class),
            null,
            null,
            null,
            mock(HttpRequestHandler.class)
        );
        return channel;
    }

    private static CapturingHandler captureAfterAggregator(EmbeddedChannel channel) {
        CapturingHandler capture = new CapturingHandler();
        channel.pipeline().addAfter(channel.pipeline().context(HttpObjectAggregator.class).name(), "capture", capture);
        return capture;
    }

    private static DefaultHttp2Headers postHeaders() {
        DefaultHttp2Headers headers = new DefaultHttp2Headers();
        headers.method("POST");
        headers.path("/upload");
        headers.scheme("http");
        return headers;
    }

    /**
     * Captures the aggregated request, and any exception the aggregator raised: the handlers after it in this chain
     * are mocks, which would otherwise swallow it.
     */
    private static final class CapturingHandler extends ChannelInboundHandlerAdapter {
        private FullHttpRequest request;
        private Throwable failure;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            request = (FullHttpRequest) msg;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (failure == null) {
                failure = cause;
            }
        }

        CompositeByteBuf content() {
            if (failure != null) {
                throw new AssertionError("the aggregator raised an exception", failure);
            }
            assertThat("the aggregator should have produced a request", request, is(notNullValue()));
            assertThat(request.content(), instanceOf(CompositeByteBuf.class));
            return (CompositeByteBuf) request.content();
        }
    }

    private static final class TrackingAllocator extends AbstractByteBufAllocator {
        private final List<ByteBuf> buffers = new ArrayList<>();
        private long allocatedBytes;

        TrackingAllocator() {
            super(false);
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            allocatedBytes += initialCapacity;
            ByteBuf buffer = new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
            buffers.add(buffer);
            return buffer;
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            allocatedBytes += initialCapacity;
            ByteBuf buffer = new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
            buffers.add(buffer);
            return buffer;
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }

        void assertAllReleased() {
            for (ByteBuf buffer : buffers) {
                assertThat("allocated buffer released", buffer.refCnt(), is(0));
            }
        }
    }
}
