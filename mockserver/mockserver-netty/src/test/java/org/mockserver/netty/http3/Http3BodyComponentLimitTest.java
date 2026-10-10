package org.mockserver.netty.http3;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;

import java.lang.reflect.Field;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * HTTP/3 hands a request body over in pieces of about one QUIC packet (1.1 KiB), whatever DATA frame size the
 * client sent, so an ordinary upload of a few MiB would pass the stream's component limit (1,024 at the default
 * 10 MiB). The body must stay under the limit, be copied about once whatever the piece sizes, and arrive unchanged.
 * The counting allocator's total is every buffer allocated for copies, unused block space included.
 */
public class Http3BodyComponentLimitTest {

    private static final int LIMIT = 1024;
    private static final int QUIC_PIECE_BYTES = 1156;

    @Test
    public void shouldCopyAnOrdinaryUploadAboutOnce() {
        int bodyBytes = 8 * 1024 * 1024;
        CountingAllocator allocator = new CountingAllocator();
        CompositeByteBuf composite = new CompositeByteBuf(allocator, false, Integer.MAX_VALUE);
        try {
            byte[] body = accumulate(composite, bodyBytes, QUIC_PIECE_BYTES);

            assertThat(ByteBufUtil.getBytes(composite), is(body));
            // CompositeByteBuf's own consolidation at 1,024 components would copy about 4x this body
            assertThat(allocator.allocatedBytes, allOf(greaterThan(0L), lessThanOrEqualTo((long) bodyBytes)));
        } finally {
            composite.release();
        }
    }

    @Test
    public void shouldCopyABodyOfOneBytePiecesAtTheDefaultLimitAboutOnce() {
        int bodyBytes = 10 * 1024 * 1024;
        CountingAllocator allocator = new CountingAllocator();
        CompositeByteBuf composite = new CompositeByteBuf(allocator, false, Integer.MAX_VALUE);
        try {
            byte[] body = accumulate(composite, bodyBytes, 1);

            assertThat(ByteBufUtil.getBytes(composite), is(body));
            assertThat(allocator.allocatedBytes, lessThanOrEqualTo((long) bodyBytes));
        } finally {
            composite.release();
        }
    }

    @Test
    public void shouldBoundComponentsAndCopyingForPiecesAlternatingTinyAndLarge() {
        // every large piece closes the block holding the tiny one before it, so this is the most components per byte
        int bodyBytes = 10 * 1024 * 1024;
        CountingAllocator allocator = new CountingAllocator();
        CompositeByteBuf composite = new CompositeByteBuf(allocator, false, Integer.MAX_VALUE);
        try {
            byte[] body = accumulate(composite, bodyBytes, 1, Http3RequestBridge.BLOCK_BYTES);

            assertThat(ByteBufUtil.getBytes(composite), is(body));
            assertThat(allocator.allocatedBytes, lessThanOrEqualTo(2L * bodyBytes));
        } finally {
            composite.release();
        }
    }

    @Test
    public void shouldNotCopyABodyOfFewPiecesOrOfLargePieces() {
        CountingAllocator allocator = new CountingAllocator();
        CompositeByteBuf composite = new CompositeByteBuf(allocator, false, Integer.MAX_VALUE);
        try {
            accumulate(composite, (Http3RequestBridge.COALESCE_AFTER_COMPONENTS - 1) * QUIC_PIECE_BYTES, QUIC_PIECE_BYTES);
            assertThat(composite.numComponents(), is(Http3RequestBridge.COALESCE_AFTER_COMPONENTS - 1));
            assertThat(allocator.allocatedBytes, is(0L));
        } finally {
            composite.release();
        }
        composite = new CompositeByteBuf(allocator, false, Integer.MAX_VALUE);
        try {
            accumulate(composite, (LIMIT - 1) * Http3RequestBridge.BLOCK_BYTES, Http3RequestBridge.BLOCK_BYTES);
            assertThat(composite.numComponents(), is(LIMIT - 1));
            assertThat(allocator.allocatedBytes, is(0L));
        } finally {
            composite.release();
        }
    }

    @Test
    public void shouldStayUnderTheLimitOnceHalfOfItIsMergedBlocks() {
        // a limit derived from maxRequestBodySize never reaches this, so shown with a limit of 8
        CompositeByteBuf composite = new CompositeByteBuf(new CountingAllocator(), false, Integer.MAX_VALUE);
        try {
            byte[] body = accumulateWithLimit(composite, 8, 100 * Http3RequestBridge.BLOCK_BYTES, Http3RequestBridge.BLOCK_BYTES);

            assertThat(ByteBufUtil.getBytes(composite), is(body));
        } finally {
            composite.release();
        }
    }

    @Test
    public void shouldApplyTheStreamLimitToTheHandlersBody() throws Exception {
        Http3MockServerHandler handler = new Http3MockServerHandler(
            configuration(), new MockServerLogger(Http3BodyComponentLimitTest.class), mock(HttpState.class), mock(HttpActionHandler.class), new Metrics(configuration())
        );
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("POST");
        headersFrame.headers().path("/upload");
        headersFrame.headers().scheme("https");
        handler.channelRead(ctx, headersFrame);

        // a large piece closes the block holding the tiny one before it, so this body passes the stream limit (1,024
        // at the default 10 MiB) and only the handler's limitComponents call keeps it under
        int pairs = 600;
        int bodyBytes = pairs * (1 + Http3RequestBridge.BLOCK_BYTES);
        byte[] body = new byte[bodyBytes];
        for (int i = 0; i < bodyBytes; i++) {
            body[i] = (byte) (i % 251);
        }
        for (int offset = 0, piece = 0; offset < bodyBytes; piece++) {
            int length = piece % 2 == 0 ? 1 : Http3RequestBridge.BLOCK_BYTES;
            handler.channelRead(ctx, new DefaultHttp3DataFrame(Unpooled.copiedBuffer(body, offset, length)));
            offset += length;
        }

        Field accumulatorField = Http3MockServerHandler.class.getDeclaredField("bodyAccumulator");
        accumulatorField.setAccessible(true);
        CompositeByteBuf accumulator = (CompositeByteBuf) accumulatorField.get(handler);
        assertThat(ByteBufUtil.getBytes(accumulator), is(body));
        assertThat(accumulator.numComponents(), lessThan(LIMIT));
        Field mergedField = Http3MockServerHandler.class.getDeclaredField("mergedBodyComponents");
        mergedField.setAccessible(true);
        assertThat("the handler's limitComponents merged components", (int) mergedField.get(handler), greaterThan(0));
        handler.handlerRemoved(ctx);
        assertThat(accumulator.refCnt(), is(0));
    }

    /**
     * Adds {@code bodyBytes} in pieces of the given sizes, repeated, checking the component count after every piece,
     * and returns the bytes sent.
     */
    private static byte[] accumulate(CompositeByteBuf composite, int bodyBytes, int... pieceSizes) {
        return accumulateWithLimit(composite, LIMIT, bodyBytes, pieceSizes);
    }

    private static byte[] accumulateWithLimit(CompositeByteBuf composite, int limit, int bodyBytes, int... pieceSizes) {
        byte[] body = new byte[bodyBytes];
        for (int i = 0; i < bodyBytes; i++) {
            body[i] = (byte) (i % 251);
        }
        int merged = 0;
        int piece = 0;
        for (int offset = 0; offset < bodyBytes; piece++) {
            int length = Math.min(pieceSizes[piece % pieceSizes.length], bodyBytes - offset);
            DefaultHttp3DataFrame frame = new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(body, offset, length));
            try {
                Http3RequestBridge.accumulateBody(composite, frame);
            } finally {
                frame.release();
            }
            offset += length;
            merged = Http3RequestBridge.limitComponents(composite, limit, merged);
            if (composite.numComponents() >= limit) {
                throw new AssertionError("expected fewer than " + limit + " components but held " + composite.numComponents());
            }
        }
        return body;
    }

    private static final class CountingAllocator extends AbstractByteBufAllocator {
        private long allocatedBytes;

        CountingAllocator() {
            super(false);
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            allocatedBytes += initialCapacity;
            return new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            allocatedBytes += initialCapacity;
            return new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }
    }
}
