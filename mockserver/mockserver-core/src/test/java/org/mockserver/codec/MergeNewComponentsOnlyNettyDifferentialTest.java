package org.mockserver.codec;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * In {@link CoalescingHttpObjectAggregator#mergeNewComponentsOnly() merge-only mode} the aggregator merges at the same
 * appends as Netty's {@link HttpObjectAggregator} consolidates the whole body, each merge copying at most what
 * Netty's copies, so it never copies or holds more than Netty for any mix of piece sizes. Were the merged components
 * counted towards the limit, the merges would drift earlier than Netty's (after L + 1, 2L + 1, 3L, 4L - 2 pieces
 * against L + 1, 2L + 1, 3L + 1), and a mix that turns from tiny pieces to large ones at the shifted merge copies the
 * large pieces where Netty copies nothing.
 */
public class MergeNewComponentsOnlyNettyDifferentialTest {

    private static final int TEN_MIB = 10 * 1024 * 1024;

    @Test(timeout = 120_000)
    public void shouldCopyAndHoldNoMoreThanNettyWhenTinyPiecesTurnLargeJustBeforeNettysThirdMerge() {
        for (int maxContentLength : new int[]{1024 * 1024, TEN_MIB}) {
            int limit = HttpObjectAggregators.componentLimit(maxContentLength);
            // 2L + 1 one-byte chunks (two merges), then L - 1 chunks of 1,000 bytes: Netty's third merge would be the
            // next piece, so Netty copies only the one-byte chunks
            int[] pieces = new int[3 * limit];
            Arrays.fill(pieces, 0, 2 * limit + 1, 1);
            Arrays.fill(pieces, 2 * limit + 1, pieces.length, 1_000);
            // reads end at chunk boundaries, so each chunk is one piece and the merges fall where the counts say
            ChunkedWire wire = new ChunkedWire(pieces, 64 * 1024, true);
            Result merging = throughServerCodec(HttpObjectAggregators.httpObjectAggregator(maxContentLength), wire);
            Result netty = throughServerCodec(HttpObjectAggregators.limitComponents(new HttpObjectAggregator(maxContentLength)), wire);
            String description = "maxContentLength " + maxContentLength;
            assertThat(description, Arrays.equals(merging.body, wire.body), is(true));
            assertThat(description, merging.copiedBytes, lessThanOrEqualTo(netty.copiedBytes));
            assertThat(description, merging.peakLiveBytes, lessThanOrEqualTo(netty.peakLiveBytes));
        }
    }

    @Test(timeout = 300_000)
    public void shouldMergeAtTheSameAppendsAsNettyAndCopyNoMoreAtEachForRandomMixes() {
        for (int seed = 0; seed < 4_000; seed++) {
            Random random = new Random(seed);
            int limit = 2 + random.nextInt(47);
            int[] pieces = randomPieces(random, limit);
            List<long[]> merging = mergesOfPieces(withLimit(HttpObjectAggregators.httpObjectAggregator(TEN_MIB), limit), pieces);
            List<long[]> netty = mergesOfPieces(withLimit(new HttpObjectAggregator(TEN_MIB), limit), pieces);
            String description = "seed " + seed + " limit " + limit + " pieces " + pieces.length;
            assertThat(description, pieceIndexes(merging), is(pieceIndexes(netty)));
            for (int merge = 0; merge < merging.size(); merge++) {
                assertThat(description + " merge " + merge, merging.get(merge)[1], lessThanOrEqualTo(netty.get(merge)[1]));
            }
        }
    }

    @Test(timeout = 300_000)
    public void shouldHoldAtMostHalfTheLimitLessOneComponentsMoreThanNettyAfterEveryPiece() {
        // merged components count as one, so only the whole-body restart at half the limit merged bounds them
        int restarted = 0;
        for (int seed = 0; seed < 2_000; seed++) {
            Random random = new Random(seed);
            int limit = 2 + random.nextInt(47);
            int[] pieces = randomPieces(random, limit);
            int[] most = new int[1];
            CoalescingHttpObjectAggregator aggregator = new CoalescingHttpObjectAggregator(TEN_MIB) {
                @Override
                protected void aggregate(FullHttpMessage aggregated, HttpContent content) throws Exception {
                    super.aggregate(aggregated, content);
                    most[0] = Math.max(most[0], ((CompositeByteBuf) aggregated.content()).numComponents());
                }
            };
            aggregator.mergeNewComponentsOnly();
            List<long[]> merges = mergesOfPieces(withLimit(aggregator, limit), pieces);
            assertThat("seed " + seed + " limit " + limit, most[0], lessThanOrEqualTo(limit + limit / 2 - 1));
            // the restart is a message's (limit / 2 + 1)th merge; mergesOfPieces sends two identical messages
            if (merges.size() / 2 >= limit / 2 + 1) {
                restarted++;
            }
        }
        assertThat("seeds merged often enough to restart from the whole body", restarted, greaterThan(500));
    }

    @Test(timeout = 300_000)
    public void shouldNeverCopyOrHoldMoreThanNettyThroughTheServerCodecForRandomMixesAndReads() {
        for (int seed = 0; seed < 3_000; seed++) {
            Random random = new Random(seed);
            int limit = 2 + random.nextInt(47);
            int[] pieces = randomPieces(random, limit);
            int[] readSizes = {1 + random.nextInt(64), 64 + random.nextInt(4096), 64 * 1024, Integer.MAX_VALUE};
            int readSize = readSizes[random.nextInt(readSizes.length)];
            if (readSize < 64 && Arrays.stream(pieces).sum() > 32 * 1024) {
                readSize = 64 + random.nextInt(4096);
            }
            Wire wire = random.nextInt(4) == 0 ? new ContentLengthWire(pieces) : new ChunkedWire(pieces, readSize, random.nextBoolean());
            Result merging = throughServerCodec(withLimit(HttpObjectAggregators.httpObjectAggregator(TEN_MIB), limit), wire);
            Result netty = throughServerCodec(withLimit(new HttpObjectAggregator(TEN_MIB), limit), wire);
            String description = "seed " + seed + " limit " + limit + " pieces " + pieces.length + " " + wire;
            assertThat(description, Arrays.equals(merging.body, wire.body), is(true));
            assertThat(description, Arrays.equals(netty.body, wire.body), is(true));
            assertThat(description, merging.copiedBytes, lessThanOrEqualTo(netty.copiedBytes));
            assertThat(description, merging.peakLiveBytes, lessThanOrEqualTo(netty.peakLiveBytes));
        }
    }

    /**
     * Segments of tiny, small or large pieces, of up to three times the limit each, enough in all to reach the
     * whole-body restart at half the limit merged for the smaller limits.
     */
    private static int[] randomPieces(Random random, int limit) {
        int total = Math.min(1_200, limit * (limit / 2 + 4));
        List<Integer> pieces = new ArrayList<>();
        while (pieces.size() < total) {
            int count = 1 + random.nextInt(3 * limit);
            int kind = random.nextInt(3);
            for (int i = 0; i < count && pieces.size() < total; i++) {
                pieces.add(kind == 0 ? 1 + random.nextInt(4) : kind == 1 ? 5 + random.nextInt(200) : 500 + random.nextInt(1_500));
            }
        }
        return pieces.stream().mapToInt(Integer::intValue).toArray();
    }

    private static <T extends HttpObjectAggregator> T withLimit(T aggregator, int limit) {
        aggregator.setMaxCumulationBufferComponents(limit);
        return aggregator;
    }

    private static List<Long> pieceIndexes(List<long[]> merges) {
        List<Long> indexes = new ArrayList<>();
        for (long[] merge : merges) {
            indexes.add(merge[0]);
        }
        return indexes;
    }

    /**
     * Sends the pieces as two messages on one channel, so state carried from one message to the next would show.
     *
     * @return each merge (or consolidation) as {piece index, bytes copied}: with the pieces written straight to the
     * aggregator, its copies are the channel allocator's only allocations
     */
    private static List<long[]> mergesOfPieces(HttpObjectAggregator aggregator, int[] pieces) {
        Usage usage = new Usage();
        TrackingAllocator allocator = new TrackingAllocator(usage);
        TrackingAllocator pieceAllocator = new TrackingAllocator(usage);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAllocator(allocator);
        channel.pipeline().addLast(aggregator);
        try {
            for (int message = 0; message < 2; message++) {
                channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
                for (int piece = 0; piece < pieces.length; piece++) {
                    allocator.piece = message * pieces.length + piece;
                    ByteBuf content = pieceAllocator.buffer(pieces[piece]);
                    content.writerIndex(pieces[piece]);
                    channel.writeInbound(new DefaultHttpContent(content));
                }
                channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
                FullHttpRequest request = channel.readInbound();
                assertThat(request.content().readableBytes(), is(Arrays.stream(pieces).sum()));
                request.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
        allocator.assertAllReleased();
        pieceAllocator.assertAllReleased();
        return allocator.allocations;
    }

    /**
     * Writes the wire's reads, each from a read allocator sharing the live count, through {@link HttpServerCodec} and
     * the aggregator; {@code copiedBytes} is what the channel allocator allocated (the aggregator's copies and any
     * the decoder makes to join reads, the same for both arms while they hold the same reads).
     */
    private static Result throughServerCodec(HttpObjectAggregator aggregator, Wire wire) {
        Usage usage = new Usage();
        TrackingAllocator allocator = new TrackingAllocator(usage);
        TrackingAllocator readAllocator = new TrackingAllocator(usage);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAllocator(allocator);
        channel.pipeline().addLast(new HttpServerCodec(), aggregator);
        Result result = new Result();
        try {
            for (byte[] read : wire.reads) {
                channel.writeInbound(readAllocator.buffer(read.length).writeBytes(read));
            }
            FullHttpRequest request = channel.readInbound();
            assertThat(wire.toString(), request, notNullValue());
            result.body = ByteBufUtil.getBytes(request.content());
            request.release();
        } finally {
            channel.finishAndReleaseAll();
        }
        allocator.assertAllReleased();
        readAllocator.assertAllReleased();
        result.copiedBytes = allocator.allocatedBytes;
        result.peakLiveBytes = usage.peakLiveBytes;
        return result;
    }

    private abstract static class Wire {
        final List<byte[]> reads = new ArrayList<>();
        byte[] body;

        static byte[] body(int[] pieces) {
            byte[] body = new byte[Arrays.stream(pieces).sum()];
            for (int i = 0; i < body.length; i++) {
                body[i] = (byte) ((i * 31 + i / 251) % 256);
            }
            return body;
        }
    }

    /**
     * A chunked request, one chunk per piece, cut into reads of {@code readSize} bytes after the head, or with
     * {@code atChunkEnds} into reads of whole chunks, each ending at the first chunk end at least {@code readSize}
     * bytes in.
     */
    private static final class ChunkedWire extends Wire {
        private final int readSize;
        private final boolean atChunkEnds;

        ChunkedWire(int[] pieces, int readSize, boolean atChunkEnds) {
            this.readSize = readSize;
            this.atChunkEnds = atChunkEnds;
            body = body(pieces);
            reads.add("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream wire = new ByteArrayOutputStream();
            int offset = 0;
            for (int piece : pieces) {
                wire.writeBytes((Integer.toHexString(piece) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                wire.write(body, offset, piece);
                wire.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
                offset += piece;
                if (atChunkEnds && wire.size() >= readSize) {
                    reads.add(wire.toByteArray());
                    wire.reset();
                }
            }
            wire.writeBytes("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            byte[] bytes = wire.toByteArray();
            int readBytes = atChunkEnds ? bytes.length : readSize;
            for (int start = 0; start < bytes.length; start += readBytes) {
                reads.add(Arrays.copyOfRange(bytes, start, (int) Math.min(bytes.length, (long) start + readBytes)));
            }
        }

        @Override
        public String toString() {
            return "chunked in reads of " + readSize + (atChunkEnds ? " or more, ending at chunk ends" : "");
        }
    }

    /**
     * A Content-Length request whose body arrives one piece per read.
     */
    private static final class ContentLengthWire extends Wire {
        ContentLengthWire(int[] pieces) {
            body = body(pieces);
            reads.add(("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            int offset = 0;
            for (int piece : pieces) {
                reads.add(Arrays.copyOfRange(body, offset, offset + piece));
                offset += piece;
            }
        }

        @Override
        public String toString() {
            return "Content-Length, one read per piece";
        }
    }

    private static final class Result {
        private byte[] body;
        private long copiedBytes;
        private long peakLiveBytes;
    }

    private static final class Usage {
        private long liveBytes;
        private long peakLiveBytes;
    }

    private static final class TrackingAllocator extends AbstractByteBufAllocator {
        private final List<ByteBuf> buffers = new ArrayList<>();
        private final List<long[]> allocations = new ArrayList<>();
        private final Usage usage;
        private long allocatedBytes;
        private int piece;

        TrackingAllocator(Usage usage) {
            super(false);
            this.usage = usage;
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            allocated(initialCapacity);
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
            allocated(initialCapacity);
            ByteBuf buffer = new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity) {
                @Override
                protected void deallocate() {
                    usage.liveBytes -= initialCapacity;
                    super.deallocate();
                }
            };
            buffers.add(buffer);
            return buffer;
        }

        private void allocated(int bytes) {
            allocations.add(new long[]{piece, bytes});
            allocatedBytes += bytes;
            usage.liveBytes += bytes;
            usage.peakLiveBytes = Math.max(usage.peakLiveBytes, usage.liveBytes);
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
