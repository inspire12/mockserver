package org.mockserver.codec;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Each piece is a slice of a larger buffer, as a frame or chunk is a slice of a socket read, so the aggregator's
 * copies of pieces held from mostly unused buffers run. Every case must produce exactly what Netty's aggregator
 * produces from the same bytes in pieces of their own, release every buffer, and keep the buffers it pins to at
 * most twice the bytes it holds plus one read and one block.
 */
public class CoalescingHttpObjectAggregatorSlicedInputTest {

    private static final int BLOCK = CoalescingHttpObjectAggregator.BLOCK_BYTES;
    private static final int SMALL = CoalescingHttpObjectAggregator.SMALL_PIECE_BYTES;
    private static final int READ = 32 * 1024;
    private static final int MAX = 300_000;
    private static final int[][] PATTERNS = {
        {1}, {7}, {SMALL - 1}, {SMALL}, {8191}, {8192}, {BLOCK - 1}, {BLOCK},
        {1, BLOCK}, {BLOCK, 1}, runThen(1, 15, SMALL), runThen(100, 31, SMALL), runThen(1, 31, BLOCK), random(7)
    };

    /**
     * How each piece is cut from a backing buffer.
     */
    private enum Slicing {
        /** a buffer of its own: the aggregator copies nothing */
        OWN,
        /** at the start of a buffer twice its size: exactly half used, kept */
        HALF,
        /** at the start of a buffer one byte over twice its size: under half used, copied */
        UNDER_HALF,
        /** at the start of a 32 KiB read whose other bytes belong to someone else */
        FOREIGN_READ,
        /** packed with the next pieces into 32 KiB reads, as one sender's frames fill its reads */
        PACKED_READS
    }

    @Test(timeout = 600_000)
    public void shouldAggregateExactlyAsNettyFromSlicedPiecesAndReleaseEverything() {
        int cases = 0;
        for (Slicing slicing : Slicing.values()) {
            for (boolean stream : new boolean[]{true, false}) {
                for (int componentLimit : new int[]{0, 128}) {
                    for (int[] pattern : PATTERNS) {
                        for (int length : lengths(pattern)) {
                            for (Ending ending : Ending.values()) {
                                check(slicing, stream, componentLimit, pattern, length, ending);
                                cases++;
                            }
                        }
                    }
                }
            }
        }
        assertThat(cases, greaterThan(3_000));
    }

    @Test(timeout = 120_000)
    public void shouldCopyEveryPieceHeldFromAnUnderHalfUsedReadAndNoPieceOfAPackedOne() {
        // pieces that each pin a mostly unused read are merged into buffers of their own; pieces that fill their
        // reads are never copied
        for (boolean stream : new boolean[]{true, false}) {
            Result foreign = aggregate(Slicing.FOREIGN_READ, stream, 0, new int[]{SMALL}, 64 * SMALL, Ending.EMPTY_LAST);
            assertThat(foreign.allocatedBytes, greaterThanOrEqualTo(63L * SMALL));
            assertThat(foreign.peakPinnedBytes, lessThanOrEqualTo(2L * 64 * SMALL + READ));
            Result packed = aggregate(Slicing.PACKED_READS, stream, 0, new int[]{BLOCK}, 64 * BLOCK, Ending.EMPTY_LAST);
            assertThat(packed.allocatedBytes, is(0L));
            Result half = aggregate(Slicing.HALF, stream, 0, new int[]{SMALL}, 64 * SMALL, Ending.EMPTY_LAST);
            assertThat(half.allocatedBytes, is(0L));
        }
    }

    @Test(timeout = 120_000)
    public void shouldFreeAReadKeptHalfUsedOnceABlockCopyTakesMostOfItsPieces() {
        // each 32 KiB read holds one byte over half in a 1 B piece, a 1,039 B piece and 15 pieces of 1,023 B; the next
        // read's first piece completes the run of 16, whose block copy leaves the read 1,040 B used
        int[] perRead = new int[17];
        perRead[0] = 1;
        perRead[1] = 1039;
        Arrays.fill(perRead, 2, 17, 1023);
        for (int reads : new int[]{100, 380, 600}) {
            Usage usage = new Usage();
            TrackingAllocator channelAllocator = new TrackingAllocator(usage);
            TrackingAllocator readAllocator = new TrackingAllocator(usage);
            EmbeddedChannel channel = new EmbeddedChannel();
            channel.config().setAllocator(channelAllocator);
            channel.pipeline().addLast(HttpObjectAggregators.streamHttpObjectAggregator(10 * 1024 * 1024));
            write(channel, new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
            long body = 0;
            for (int read = 0; read < reads; read++) {
                ByteBuf buffer = readAllocator.buffer(READ, READ);
                buffer.writerIndex(READ);
                int offset = 0;
                for (int length : perRead) {
                    write(channel, new DefaultHttpContent(buffer.retainedSlice(offset, length)));
                    offset += length;
                }
                body += offset;
                buffer.release();
            }
            write(channel, LastHttpContent.EMPTY_LAST_CONTENT);
            FullHttpRequest request = channel.readInbound();
            String description = reads + " reads: pinned " + usage.peakLiveBytes + " for a body of " + body;
            assertThat(description, (long) request.content().readableBytes(), is(body));
            request.release();
            channel.finishAndReleaseAll();
            channelAllocator.assertAllReleased();
            readAllocator.assertAllReleased();
            assertThat(description, usage.peakLiveBytes, lessThanOrEqualTo(2 * body + 2 * READ + BLOCK));
        }
    }

    private static void check(Slicing slicing, boolean stream, int componentLimit, int[] pattern, int length, Ending ending) {
        String description = slicing + (stream ? " stream" : " connection") + " limit " + componentLimit
            + " pieces " + Arrays.toString(Arrays.copyOf(pattern, Math.min(pattern.length, 6))) + " length " + length + " " + ending;
        Result sliced = aggregate(slicing, stream, componentLimit, pattern, length, ending);
        Result plain = aggregateWithNetty(componentLimit, stream, pattern, length, ending);
        assertThat(description, sliced.body, is(plain.body));
        assertThat(description, sliced.headers, is(plain.headers));
        assertThat(description, sliced.trailers, is(plain.trailers));
        assertThat(description, sliced.outbound, is(plain.outbound));
        if (length <= MAX) {
            assertThat(description, sliced.body, is(body(length)));
            // every buffer pinned is at least half used, except the latest read, one part-used block and the bytes of
            // a merge being made; the read being delivered is live too
            assertThat(description, sliced.peakPinnedBytes, lessThanOrEqualTo(3L * length + 2 * READ + BLOCK));
        }
        boolean underHalf = slicing == Slicing.UNDER_HALF || slicing == Slicing.FOREIGN_READ && Arrays.stream(pattern).min().getAsInt() * 2 < READ;
        boolean copies = underHalf && pieces(pattern, length) > 2;
        if (copies && length <= MAX) {
            assertThat(description + " copied nothing", sliced.allocatedBytes, greaterThan(0L));
        }
    }

    private static Result aggregate(Slicing slicing, boolean stream, int componentLimit, int[] pattern, int length, Ending ending) {
        Usage usage = new Usage();
        TrackingAllocator channelAllocator = new TrackingAllocator(usage);
        TrackingAllocator readAllocator = new TrackingAllocator(usage);
        CoalescingHttpObjectAggregator aggregator = stream
            ? (CoalescingHttpObjectAggregator) HttpObjectAggregators.streamHttpObjectAggregator(MAX)
            : HttpObjectAggregators.httpObjectAggregator(MAX);
        if (componentLimit > 0) {
            aggregator.setMaxCumulationBufferComponents(componentLimit);
        }
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAllocator(channelAllocator);
        channel.pipeline().addLast(aggregator);
        Result result = run(channel, pattern, length, ending, new Slicer(slicing, readAllocator));
        result.allocatedBytes = channelAllocator.allocatedBytes;
        result.peakPinnedBytes = usage.peakLiveBytes;
        channelAllocator.assertAllReleased();
        readAllocator.assertAllReleased();
        return result;
    }

    private static Result aggregateWithNetty(int componentLimit, boolean stream, int[] pattern, int length, Ending ending) {
        HttpObjectAggregator aggregator = new HttpObjectAggregator(MAX);
        aggregator.setMaxCumulationBufferComponents(componentLimit > 0 ? componentLimit
            : stream ? HttpObjectAggregators.streamComponentLimit(MAX) : HttpObjectAggregators.componentLimit(MAX));
        EmbeddedChannel channel = new EmbeddedChannel(aggregator);
        return run(channel, pattern, length, ending, new Slicer(Slicing.OWN, new TrackingAllocator(new Usage())));
    }

    private static Result run(EmbeddedChannel channel, int[] pattern, int length, Ending ending, Slicer slicer) {
        byte[] body = body(length);
        Result result = new Result();
        try {
            write(channel, new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"));
            int offset = 0;
            int lastDataBytes = ending == Ending.LAST_WITH_DATA ? Math.min(length, pattern[0]) : 0;
            for (int piece = 0; offset < length - lastDataBytes; piece++) {
                int pieceLength = Math.min(pattern[piece % pattern.length], length - lastDataBytes - offset);
                write(channel, new DefaultHttpContent(slicer.piece(body, offset, pieceLength)));
                offset += pieceLength;
            }
            DefaultLastHttpContent last = new DefaultLastHttpContent(slicer.piece(body, offset, length - offset));
            if (ending == Ending.LAST_WITH_TRAILERS) {
                last.trailingHeaders().add("x-checksum", "abc");
            }
            write(channel, last);
            Object inbound = channel.readInbound();
            if (inbound instanceof FullHttpRequest) {
                FullHttpRequest request = (FullHttpRequest) inbound;
                try {
                    result.body = ByteBufUtil.getBytes(request.content());
                    result.headers = request.headers().entries().toString();
                    result.trailers = request.trailingHeaders().entries().toString();
                } finally {
                    request.release();
                }
            }
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                result.outbound.add(outbound instanceof HttpResponse ? String.valueOf(((HttpResponse) outbound).status().code()) : outbound.getClass().getSimpleName());
                ReferenceCountUtil.release(outbound);
            }
        } finally {
            slicer.close();
            channel.finishAndReleaseAll();
        }
        return result;
    }

    private static void write(EmbeddedChannel channel, Object message) {
        if (channel.isOpen()) {
            channel.writeInbound(message);
        } else {
            ReferenceCountUtil.release(message);
        }
    }

    private static final class Slicer {
        private final Slicing slicing;
        private final TrackingAllocator allocator;
        private ByteBuf packed;

        Slicer(Slicing slicing, TrackingAllocator allocator) {
            this.slicing = slicing;
            this.allocator = allocator;
        }

        ByteBuf piece(byte[] body, int offset, int length) {
            switch (slicing) {
                case HALF:
                    return cut(Math.max(2 * length, 1), body, offset, length);
                case UNDER_HALF:
                    return cut(2 * length + 1, body, offset, length);
                case FOREIGN_READ:
                    return cut(Math.max(READ, length), body, offset, length);
                case PACKED_READS:
                    if (packed == null || packed.writableBytes() < length) {
                        close();
                        packed = allocator.buffer(Math.max(READ, length));
                    }
                    packed.writeBytes(body, offset, length);
                    return packed.retainedSlice(packed.writerIndex() - length, length);
                default:
                    return allocator.buffer(length).writeBytes(body, offset, length);
            }
        }

        private ByteBuf cut(int capacity, byte[] body, int offset, int length) {
            ByteBuf read = allocator.buffer(capacity);
            read.writeBytes(body, offset, length);
            read.writerIndex(read.capacity());
            try {
                return read.retainedSlice(0, length);
            } finally {
                read.release();
            }
        }

        void close() {
            if (packed != null) {
                packed.release();
                packed = null;
            }
        }
    }

    private static List<Integer> lengths(int[] pattern) {
        List<Integer> lengths = new ArrayList<>(List.of(1, BLOCK + 1, 100_000, MAX, MAX + 1));
        for (int pieces : new int[]{63, 64, 65, 130}) {
            long bytes = 0;
            for (int i = 0; i < pieces; i++) {
                bytes += pattern[i % pattern.length];
            }
            lengths.add((int) Math.min(bytes, MAX + 1));
        }
        return lengths;
    }

    private static int pieces(int[] pattern, int length) {
        int pieces = 0;
        for (int sent = 0; sent < length; pieces++) {
            sent += pattern[pieces % pattern.length];
        }
        return pieces;
    }

    private static byte[] body(int length) {
        byte[] body = new byte[length];
        for (int i = 0; i < length; i++) {
            body[i] = (byte) ((i * 31 + i / 251) % 256);
        }
        return body;
    }

    private static int[] runThen(int small, int count, int large) {
        int[] pattern = new int[count + 1];
        Arrays.fill(pattern, small);
        pattern[count] = large;
        return pattern;
    }

    private static int[] random(long seed) {
        Random random = new Random(seed);
        int[] sizes = new int[400];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = 1 + random.nextInt(2 * BLOCK);
        }
        return sizes;
    }

    private enum Ending {
        EMPTY_LAST, LAST_WITH_DATA, LAST_WITH_TRAILERS
    }

    private static final class Result {
        private byte[] body;
        private String headers;
        private String trailers;
        private final List<String> outbound = new ArrayList<>();
        private long allocatedBytes;
        private long peakPinnedBytes;
    }

    private static final class Usage {
        private long liveBytes;
        private long peakLiveBytes;
    }

    /**
     * Counts the bytes of every buffer it allocates and frees, and checks each was released.
     */
    private static final class TrackingAllocator extends AbstractByteBufAllocator {
        private final List<ByteBuf> buffers = new ArrayList<>();
        private final Usage usage;
        private long allocatedBytes;

        TrackingAllocator(Usage usage) {
            super(false);
            this.usage = usage;
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            allocatedBytes += initialCapacity;
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
            return buffer;
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
