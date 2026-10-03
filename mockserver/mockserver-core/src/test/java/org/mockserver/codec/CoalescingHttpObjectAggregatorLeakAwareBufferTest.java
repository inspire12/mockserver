package org.mockserver.codec;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ResourceLeakDetector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;

/**
 * Netty's leak detector hands a sampled allocation out inside a wrapper (every allocation at {@code PARANOID}, one in
 * 128 at the default level), and a composite buffer's components unwrap past it, so the aggregator must count its own
 * blocks and copies under the buffer they unwrap to or it stops freeing the reads a body pins. Sets the JVM-wide leak
 * detection level, so it runs in the sequential phase.
 */
public class CoalescingHttpObjectAggregatorLeakAwareBufferTest {

    private static final int KIB = 1024;
    private static final int READ = 32 * KIB;
    private static final int BLOCK = CoalescingHttpObjectAggregator.BLOCK_BYTES;

    private ResourceLeakDetector.Level originalLevel;

    @Before
    public void detectEveryAllocation() {
        originalLevel = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
    }

    @After
    public void restoreLevel() {
        ResourceLeakDetector.setLevel(originalLevel);
    }

    @Test(timeout = 120_000)
    public void shouldFreeReadsWhenBlocksAreLeakAwareWrappers() {
        // a 1 KiB piece and a run of 16 one-byte pieces per read: the read's pieces are found behind the block slice
        // the run was copied into, which must be recognised as a block
        int[] perRead = new int[17];
        perRead[0] = KIB;
        Arrays.fill(perRead, 1, 17, 1);
        assertBounded(true, 70, 800, perRead);
    }

    @Test(timeout = 120_000)
    public void shouldFreeReadsWhenCopiesAreLeakAwareWrappers() {
        // a piece of 1 KiB per read, on a stream and on a connection: each is copied into a buffer of its own, which
        // the allocator wraps too
        for (boolean stream : new boolean[]{true, false}) {
            assertBounded(stream, 0, 800, new int[]{KIB});
        }
    }

    @Test(timeout = 120_000)
    public void shouldFreeAReadABlockCopyLeavesUnderHalfUsedWhenBuffersAreLeakAwareWrappers() {
        int[] perRead = new int[17];
        perRead[0] = 1;
        perRead[1] = 1039;
        Arrays.fill(perRead, 2, 17, 1023);
        assertBounded(true, 0, 380, perRead);
    }

    private static void assertBounded(boolean stream, int leadingPieces, int reads, int[] perRead) {
        Usage usage = new Usage();
        LeakAwareAllocator channelAllocator = new LeakAwareAllocator(usage);
        LeakAwareAllocator readAllocator = new LeakAwareAllocator(usage);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAllocator(channelAllocator);
        channel.pipeline().addLast(stream
            ? HttpObjectAggregators.streamHttpObjectAggregator(10 * KIB * KIB)
            : HttpObjectAggregators.httpObjectAggregator(10 * KIB * KIB));
        channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
        long body = 0;
        for (int piece = 0; piece < leadingPieces; piece++) {
            channel.writeInbound(new DefaultHttpContent(readAllocator.buffer(KIB, KIB).writerIndex(KIB)));
            body += KIB;
        }
        for (int read = 0; read < reads; read++) {
            ByteBuf buffer = readAllocator.buffer(READ, READ);
            buffer.writerIndex(READ);
            int offset = 0;
            for (int length : perRead) {
                channel.writeInbound(new DefaultHttpContent(buffer.retainedSlice(offset, length)));
                offset += length;
            }
            body += offset;
            buffer.release();
        }
        channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
        FullHttpRequest request = channel.readInbound();
        String description = (stream ? "stream" : "connection") + ", " + reads + " reads of " + perRead.length
            + " pieces: pinned " + usage.peakLiveBytes + " for a body of " + body;
        assertThat(description, (long) request.content().readableBytes(), is(body));
        request.release();
        channel.finishAndReleaseAll();
        assertThat("an allocation was wrapped", channelAllocator.wrapped, not(is(0)));
        channelAllocator.assertAllReleased();
        readAllocator.assertAllReleased();
        assertThat(description, usage.peakLiveBytes, lessThanOrEqualTo(2 * body + 2 * READ + BLOCK));
    }

    private static final class Usage {
        private long liveBytes;
        private long peakLiveBytes;
    }

    /**
     * Returns every buffer as the leak detector's wrapper, as a pooled or unpooled allocator does for a sampled
     * allocation, counting the bytes live.
     */
    private static final class LeakAwareAllocator extends AbstractByteBufAllocator {
        private final List<ByteBuf> buffers = new ArrayList<>();
        private final Usage usage;
        private int wrapped;

        LeakAwareAllocator(Usage usage) {
            super(false);
            this.usage = usage;
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            usage.liveBytes += initialCapacity;
            usage.peakLiveBytes = Math.max(usage.peakLiveBytes, usage.liveBytes);
            ByteBuf buffer = new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity) {
                @Override
                protected void deallocate() {
                    usage.liveBytes -= initialCapacity;
                    super.deallocate();
                }
            };
            buffers.add(buffer);
            ByteBuf leakAware = toLeakAwareBuffer(buffer);
            if (leakAware != buffer) {
                wrapped++;
            }
            return leakAware;
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            return newHeapBuffer(initialCapacity, maxCapacity);
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
