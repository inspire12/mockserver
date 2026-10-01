package org.mockserver.codec;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * A {@code zstd} body cannot make {@link MockServerHttpContentDecompressor} allocate the content size its frame
 * header declares. Lives in this module because zstd-jni is on its classpath (through {@code kafka-clients}, as in
 * the shaded jar) and not on {@code mockserver-core}'s.
 */
public class MockServerHttpContentDecompressorZstdTest {

    private static final int MAX_BODY_SIZE = 1024 * 1024;

    // magic, frame header (8-byte content size, not single-segment), 1 KiB window, content size 1.5 GiB, one empty last raw block
    private static final byte[] DECLARES_ONE_AND_A_HALF_GIB = {
        0x28, (byte) 0xB5, 0x2F, (byte) 0xFD,
        (byte) 0xC0,
        0x00,
        0x00, 0x00, 0x00, 0x60, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x00
    };

    private RecordingAllocator allocator;

    @Before
    public void requireZstd() {
        Assume.assumeTrue("zstd-jni is not on the classpath", Zstd.isAvailable());
        allocator = new RecordingAllocator();
    }

    @Test
    public void shouldNotAllocateTheContentSizeAFrameDeclares() {
        assertThat(DECLARES_ONE_AND_A_HALF_GIB.length, is(17));
        EmbeddedChannel channel = decompressingChannel();
        try {
            try {
                channel.writeInbound(zstdRequest(DECLARES_ONE_AND_A_HALF_GIB));
            } catch (RuntimeException rejected) {
                // a frame whose content does not match its declared size may be rejected; the allocation check below
                // still fails if the declared size was requested
            }

            assertThat(allocator.largestRequest, lessThanOrEqualTo(MockServerHttpContentDecompressor.ZSTD_MAX_ALLOCATION));
        } finally {
            releaseAll(channel);
        }
    }

    @Test
    public void shouldBoundADecompressionBombByTheBodySizeLimit() {
        byte[] bomb = com.github.luben.zstd.Zstd.compress(new byte[64 * 1024 * 1024], 3);
        assertThat(com.github.luben.zstd.Zstd.getFrameContentSize(bomb), is(64L * 1024 * 1024));
        EmbeddedChannel channel = decompressingChannel();
        try {
            channel.writeInbound(zstdRequest(bomb));

            assertThat(channel.inboundMessages(), is(empty()));
            Object refusal = channel.readOutbound();
            assertThat(refusal, instanceOf(FullHttpResponse.class));
            assertThat(((FullHttpResponse) refusal).status(), is(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE));
            ReferenceCountUtil.release(refusal);
            assertThat(allocator.largestRequest, lessThanOrEqualTo(MockServerHttpContentDecompressor.ZSTD_MAX_ALLOCATION));
        } finally {
            releaseAll(channel);
        }
    }

    @Test
    public void shouldStillDecompressAnOrdinaryBodyLargerThanOneAllocation() {
        byte[] plain = new byte[300 * 1024];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) ('a' + i % 26);
        }
        EmbeddedChannel channel = decompressingChannel();
        try {
            channel.writeInbound(zstdRequest(com.github.luben.zstd.Zstd.compress(plain, 3)));

            FullHttpRequest decoded = channel.readInbound();
            try {
                byte[] body = new byte[decoded.content().readableBytes()];
                decoded.content().getBytes(decoded.content().readerIndex(), body);
                assertThat(Arrays.equals(body, plain), is(true));
                assertThat(decoded.headers().contains(HttpHeaderNames.CONTENT_ENCODING), is(false));
            } finally {
                decoded.release();
            }
            assertThat(allocator.largestRequest, lessThanOrEqualTo(MockServerHttpContentDecompressor.ZSTD_MAX_ALLOCATION));
        } finally {
            releaseAll(channel);
        }
    }

    private EmbeddedChannel decompressingChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        // the zstd decoder allocates from this channel's config
        channel.config().setAllocator(allocator);
        channel.pipeline().addLast(new MockServerHttpContentDecompressor(MAX_BODY_SIZE), new HttpObjectAggregator(MAX_BODY_SIZE));
        return channel;
    }

    private static FullHttpRequest zstdRequest(byte[] body) {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", Unpooled.wrappedBuffer(body));
        request.headers().set(HttpHeaderNames.CONTENT_ENCODING, "zstd");
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        return request;
    }

    private static void releaseAll(EmbeddedChannel channel) {
        try {
            channel.finishAndReleaseAll();
        } catch (RuntimeException ignore) {
            // a rejected frame may rethrow on close; every buffer is still released
        }
    }

    /**
     * Records the largest buffer requested and refuses anything over 16 MiB, so an unbounded allocation fails this
     * test deterministically instead of depending on the test JVM's heap size.
     */
    private static final class RecordingAllocator extends AbstractByteBufAllocator {

        private static final int REFUSE_ABOVE = 16 * 1024 * 1024;

        private int largestRequest;

        RecordingAllocator() {
            super(false);
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            record(initialCapacity);
            return new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            record(initialCapacity);
            return new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }

        private void record(int initialCapacity) {
            largestRequest = Math.max(largestRequest, initialCapacity);
            if (initialCapacity > REFUSE_ABOVE) {
                throw new RefusedAllocation(initialCapacity);
            }
        }
    }

    private static final class RefusedAllocation extends RuntimeException {
        RefusedAllocation(int size) {
            super("refused an allocation of " + size + " bytes");
        }
    }
}
