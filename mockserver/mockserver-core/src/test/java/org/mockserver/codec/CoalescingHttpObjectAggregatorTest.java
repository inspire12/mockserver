package org.mockserver.codec;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.AdaptiveByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * An HTTP/2 stream aggregator copies runs of small content pieces into 16 KiB blocks, so a body of tiny DATA frames
 * costs about one copy rather than a whole-body consolidation every 1,024 frames. Each corpus case is run through the coalescing
 * aggregator and a plain {@link HttpObjectAggregator} with the same limits, which must produce the same message, the
 * same body bytes, the same trailers and the same response to an oversized body, with every buffer released.
 */
public class CoalescingHttpObjectAggregatorTest {

    private static final int BLOCK = CoalescingHttpObjectAggregator.BLOCK_BYTES;
    private static final int SMALL = CoalescingHttpObjectAggregator.SMALL_PIECE_BYTES;
    private static final int RUN = CoalescingHttpObjectAggregator.RUN_PIECES;
    private static final int UNCOALESCED = CoalescingHttpObjectAggregator.COALESCE_AFTER_COMPONENTS;
    private static final int TEN_MIB = 10 * 1024 * 1024;
    private static final int CORPUS_MAX = 300_000;
    private static final int SMALL_LIMIT = 128;
    private static final int SMALL_LIMIT_MAX = 100_000;
    private static final int[][] CORPUS_PATTERNS = {
        {1}, {2}, {7}, {100}, {SMALL - 1}, {SMALL}, {1156}, {4096}, {8192},
        {BLOCK - 1}, {BLOCK}, {BLOCK + 1}, {65_536},
        {1, BLOCK}, {BLOCK, 1}, {1, BLOCK - 1, 2}, {BLOCK - 1, 1}, {3, 5, BLOCK + 7, 1, 1, 1},
        {1, 1, BLOCK}, {SMALL - 1, SMALL - 1, SMALL}, {BLOCK - 5, 3, 3},
        randomSizes(1, 64, 1), randomSizes(1, 40_000, 2), randomSizes(1, 2 * BLOCK, 3),
        runsOfSmallPiecesBetweenLargeOnes(4), runsOfSmallPiecesBetweenLargeOnes(5),
        runThen(100, RUN, SMALL), runThen(100, 2 * RUN - 1, BLOCK), runThen(SMALL - 1, RUN, SMALL), runThen(SMALL - 1, 2 * RUN, BLOCK)
    };

    @Test
    public void shouldAggregateTheSameAsAPlainAggregatorAcrossTheCorpus() {
        int cases = runCorpus(CORPUS_MAX, 0, 100_000, new int[]{63, 64, 65, 66, 130}, new int[1]);
        assertThat(cases, is(CORPUS_PATTERNS.length * 14 * Ending.values().length));
    }

    @Test
    public void shouldAggregateTheSameAsAPlainAggregatorWhenTheCorpusPassesASmallComponentLimit() {
        // with a limit of 128, cases in small pieces merge (or, for the plain aggregator, consolidate) mid-body, so
        // merges are compared with Netty's consolidation, including the trailers and a 413 after a merge
        int[] consolidated = new int[1];
        int cases = runCorpus(SMALL_LIMIT_MAX, SMALL_LIMIT, 50_000, new int[]{127, 128, 129, 130, 400}, consolidated);
        assertThat(cases, is(CORPUS_PATTERNS.length * 14 * Ending.values().length));
        // the corpus is deterministic: 188 cases pass the limit (the rest are too short or in pieces too large)
        assertThat("cases where the plain aggregator consolidated", consolidated[0], is(188));
    }

    private static int runCorpus(int maxContentLength, int componentLimit, int middleLength, int[] pieceCounts, int[] consolidated) {
        int cases = 0;
        for (int[] pattern : CORPUS_PATTERNS) {
            List<Integer> lengths = new ArrayList<>(List.of(0, 1, BLOCK - 1, BLOCK, BLOCK + 1, middleLength, maxContentLength - 1, maxContentLength, maxContentLength + 1));
            for (int pieces : pieceCounts) {
                long bytes = 0;
                for (int i = 0; i < pieces; i++) {
                    bytes += pattern[i % pattern.length];
                }
                lengths.add((int) Math.min(bytes, maxContentLength + 1));
            }
            for (int length : lengths) {
                for (Ending ending : Ending.values()) {
                    if (assertSameAsPlainAggregator(pattern, maxContentLength, componentLimit, length, ending)) {
                        consolidated[0]++;
                    }
                    cases++;
                }
            }
        }
        return cases;
    }

    @Test
    public void shouldCopyABodyOfOneByteFramesAboutOnce() {
        int bodyBytes = 2 * 1024 * 1024;
        Result result = aggregate(true, TEN_MIB, new int[]{1}, bodyBytes, Ending.EMPTY_LAST);
        assertThat(result.body, is(expectedBody(bodyBytes)));
        // the default stream aggregator would copy about bodyBytes^2 / 2,048 bytes here: 2 GiB
        assertThat(result.allocatedBytes, lessThanOrEqualTo((long) bodyBytes + BLOCK));
        assertThat(result.components, lessThanOrEqualTo(UNCOALESCED + bodyBytes / BLOCK + RUN));
    }

    @Test
    public void shouldCoalesceRunsOfPiecesUnderOneKib() {
        int bodyBytes = 4 * 1024 * 1024;
        // one-byte pieces: 4,194,240 after the first 64, a whole number of runs, so 256 blocks and nothing left over
        Result result = aggregate(true, TEN_MIB, new int[]{1}, bodyBytes, Ending.EMPTY_LAST);
        assertThat(result.body, is(expectedBody(bodyBytes)));
        assertThat(result.components, is(UNCOALESCED + 256));
        assertThat(result.allocatedBytes, is(256L * BLOCK));
        for (int pieceBytes : new int[]{100, SMALL - 1}) {
            result = aggregate(true, TEN_MIB, new int[]{pieceBytes}, bodyBytes, Ending.EMPTY_LAST);
            int blocks = (bodyBytes + BLOCK - 1) / BLOCK;
            assertThat("pieces of " + pieceBytes, result.body, is(expectedBody(bodyBytes)));
            assertThat("pieces of " + pieceBytes, result.components, lessThanOrEqualTo(UNCOALESCED + blocks + RUN - 1));
            assertThat("pieces of " + pieceBytes, result.allocatedBytes, lessThanOrEqualTo((long) blocks * BLOCK));
        }
    }

    @Test
    public void shouldNotCopyPiecesOfOneKibOrMoreOrAnIsolatedSmallPiece() {
        // 16,383-byte pieces are what a 16 KiB-frame client sends when the flow-control window cuts a frame short
        int[] fifteenTinyThenLarge = new int[RUN];
        java.util.Arrays.fill(fifteenTinyThenLarge, 1);
        fifteenTinyThenLarge[RUN - 1] = BLOCK;
        int[][] patterns = {{SMALL}, {8192}, {BLOCK - 1}, {BLOCK, BLOCK, BLOCK, BLOCK - 1}, {BLOCK, 100}, {BLOCK, 1, 5000, 3}, fifteenTinyThenLarge};
        for (int[] pattern : patterns) {
            int bodyBytes = 0;
            int pieces = 0;
            for (; pieces < 1000; pieces++) {
                bodyBytes += pattern[pieces % pattern.length];
            }
            // 1,000 pieces of up to 16 KiB is more than 10 MiB; 64 MiB allows 6,553 components
            Result result = aggregate(true, 64 * 1024 * 1024, pattern, bodyBytes, Ending.EMPTY_LAST);
            String description = "pieces " + java.util.Arrays.toString(pattern);
            assertThat(description, result.body, is(expectedBody(bodyBytes)));
            assertThat(description, result.components, is(pieces));
            assertThat(description, result.allocatedBytes, is(0L));
        }
    }

    @Test
    public void shouldNotCopyALargeBodyOfSixteenKibFrames() {
        Result result = aggregate(true, TEN_MIB, new int[]{BLOCK}, TEN_MIB, Ending.EMPTY_LAST);
        assertThat(result.body, is(expectedBody(TEN_MIB)));
        assertThat(result.allocatedBytes, is(0L));
        assertThat(result.components, is(TEN_MIB / BLOCK));
    }

    @Test
    public void shouldNotCopyABodyOfFewSmallFrames() {
        // the 65th piece is the first that may be coalesced, and a run is copied at its 16th piece
        int pieces = UNCOALESCED + RUN - 1;
        Result result = aggregate(true, TEN_MIB, new int[]{1}, pieces, Ending.EMPTY_LAST);
        assertThat(result.allocatedBytes, is(0L));
        assertThat(result.components, is(pieces));
        result = aggregate(true, TEN_MIB, new int[]{1}, pieces + 1, Ending.EMPTY_LAST);
        assertThat(result.allocatedBytes, is((long) BLOCK));
        assertThat(result.components, is(UNCOALESCED + 1));
    }

    @Test
    public void shouldStayUnderTheComponentLimitForFramesAlternatingTinyAndLarge() {
        // a tiny frame between two large ones is never copied, so this is two components per 16 KiB and the stream
        // limit (1,024 at 10 MiB) is reached; it is kept by merging only the components added since the last merge
        Result result = aggregate(true, TEN_MIB, new int[]{1, BLOCK}, TEN_MIB, Ending.EMPTY_LAST);
        assertThat(result.body, is(expectedBody(TEN_MIB)));
        assertThat(result.components, lessThanOrEqualTo(HttpObjectAggregators.streamComponentLimit(TEN_MIB)));
        assertThat(result.allocatedBytes, lessThanOrEqualTo(2L * TEN_MIB));
    }

    @Test
    public void shouldMergeOnlyTheNewComponentsEachTimeTheLimitIsReached() {
        // with a limit of 128 the alternating body reaches it about ten times; merging the whole body each time, as
        // CompositeByteBuf's own consolidation does, would copy about five times the body
        int limit = 128;
        TrackingAllocator allocator = new TrackingAllocator();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAllocator(allocator);
        CoalescingHttpObjectAggregator aggregator = new CoalescingHttpObjectAggregator(TEN_MIB);
        aggregator.setMaxCumulationBufferComponents(limit);
        aggregator.coalesceSmallContent();
        channel.pipeline().addLast(aggregator);
        try {
            FullHttpRequest request = send(channel, new int[]{1, BLOCK}, TEN_MIB, Ending.EMPTY_LAST, null);
            try {
                assertThat(ByteBufUtil.getBytes(request.content()), is(expectedBody(TEN_MIB)));
                assertThat(((CompositeByteBuf) request.content()).numComponents(), lessThanOrEqualTo(limit));
            } finally {
                request.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
        assertThat(allocator.allocatedBytes, lessThanOrEqualTo(2L * TEN_MIB));
        allocator.assertAllReleased();
    }

    @Test
    public void shouldFirstMergeAtTheSameComponentAsNetty() {
        int limit = HttpObjectAggregators.streamComponentLimit(TEN_MIB);
        for (int pieces : new int[]{limit, limit + 1}) {
            Result coalesced = aggregate(true, TEN_MIB, new int[]{2 * SMALL}, pieces * 2 * SMALL, Ending.EMPTY_LAST);
            Result plain = aggregate(false, TEN_MIB, new int[]{2 * SMALL}, pieces * 2 * SMALL, Ending.EMPTY_LAST);
            assertThat(pieces + " pieces", coalesced.components, is(plain.components));
            assertThat(pieces + " pieces", coalesced.allocatedBytes, is(plain.allocatedBytes));
            assertThat(pieces + " pieces", coalesced.allocatedBytes, is(pieces == limit ? 0L : (long) pieces * 2 * SMALL));
        }
    }

    @Test
    public void shouldHoldAtMostOnePartUsedBlockWhenRunsAreInterruptedByLargerPieces() {
        // each run of 16 one-byte pieces is copied, then a 1 KiB piece interrupts it: the runs share one block's tail
        int[] pattern = new int[RUN + 1];
        java.util.Arrays.fill(pattern, 1);
        pattern[RUN] = SMALL;
        int maxContentLength = 512 * 1024;
        Result result = aggregate(true, maxContentLength, pattern, maxContentLength, Ending.EMPTY_LAST);
        assertThat(result.body, is(expectedBody(maxContentLength)));
        assertThat(result.peakLiveBytes, lessThanOrEqualTo((long) maxContentLength + 2 * BLOCK));
    }

    @Test
    public void shouldCopyAndHoldAtMostAboutTwiceTheBodyForRunsOfHundredBytePieces() {
        // the first merge copies the whole body so far, and bytes in blocks are copied again when merged, so some
        // mixes copy and hold more than Netty did; both stay within about twice the body plus one block
        int[] pattern = runThen(100, 2 * RUN - 1, SMALL);
        for (int maxContentLength : new int[]{256 * 1024, 1024 * 1024, TEN_MIB}) {
            Usage usage = new Usage();
            TrackingAllocator allocator = new TrackingAllocator(usage);
            EmbeddedChannel channel = channel(true, maxContentLength, allocator);
            try {
                FullHttpRequest request = send(channel, pattern, maxContentLength, Ending.EMPTY_LAST, null, new TrackingAllocator(usage));
                try {
                    assertThat(ByteBufUtil.getBytes(request.content()), is(expectedBody(maxContentLength)));
                } finally {
                    request.release();
                }
            } finally {
                channel.finishAndReleaseAll();
            }
            allocator.assertAllReleased();
            String description = "limit " + maxContentLength;
            assertThat(description, allocator.allocatedBytes, lessThanOrEqualTo(2L * maxContentLength + BLOCK));
            assertThat(description, usage.peakLiveBytes, lessThanOrEqualTo(2L * maxContentLength + BLOCK));
        }
    }

    @Test
    public void shouldKeepTheBodyIntactOnPooledAllocatorsWhenRunsAreCopiedBeforeAndAfterAMerge() {
        // a released pooled block cannot be written (or, once reused, belongs to another body), so the block must
        // be forgotten when a merge releases it; runs of 16 x 64 bytes between 1 KiB pieces merge first near 1 MB
        int[] pattern = runThen(64, RUN, SMALL);
        int bodyBytes = 3 * 1024 * 1024;
        for (ByteBufAllocator allocator : new ByteBufAllocator[]{PooledByteBufAllocator.DEFAULT, new AdaptiveByteBufAllocator()}) {
            for (int message = 0; message < 2; message++) {
                EmbeddedChannel channel = new EmbeddedChannel();
                channel.config().setAllocator(allocator);
                channel.pipeline().addLast(HttpObjectAggregators.streamHttpObjectAggregator(TEN_MIB));
                runThenFinish(channel, () -> {
                    FullHttpRequest request = send(channel, pattern, bodyBytes, Ending.EMPTY_LAST, null, allocator);
                    try {
                        assertThat(allocator.getClass().getSimpleName(), ByteBufUtil.getBytes(request.content()), is(expectedBody(bodyBytes)));
                    } finally {
                        request.release();
                    }
                });
            }
        }
    }

    @Test
    public void shouldMergeTheWholeBodyOnceHalfTheLimitIsMergedComponents() {
        // isolated one-byte pieces between 1 KiB ones are never copied into blocks, so every merge adds one merged
        // component; at 128 the 64th merge comes after about 3 MiB, and without merging the whole body then, the
        // merged components alone would pass the limit
        int limit = 128;
        TrackingAllocator allocator = new TrackingAllocator();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAllocator(allocator);
        CoalescingHttpObjectAggregator aggregator = new CoalescingHttpObjectAggregator(TEN_MIB);
        aggregator.setMaxCumulationBufferComponents(limit);
        aggregator.coalesceSmallContent();
        channel.pipeline().addLast(aggregator);
        int bodyBytes = 6 * 1024 * 1024;
        try {
            FullHttpRequest request = send(channel, new int[]{1, SMALL}, bodyBytes, Ending.EMPTY_LAST, null);
            try {
                assertThat(ByteBufUtil.getBytes(request.content()), is(expectedBody(bodyBytes)));
                assertThat(((CompositeByteBuf) request.content()).numComponents(), lessThanOrEqualTo(limit));
            } finally {
                request.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
        allocator.assertAllReleased();
    }

    @Test
    public void shouldCopyRunsOnlyOnStreamAggregatorsAndMergeNewComponentsOnEveryProductionAggregator() {
        CoalescingHttpObjectAggregator plain = new CoalescingHttpObjectAggregator(TEN_MIB);
        assertThat(plain.isCoalescingSmallContent() || plain.isMergingNewComponentsOnly(), is(false));
        assertThat(((CoalescingHttpObjectAggregator) HttpObjectAggregators.streamHttpObjectAggregator(TEN_MIB)).isCoalescingSmallContent(), is(true));
        assertThat(HttpObjectAggregators.limitStreamComponents(new StreamingAwareHttpObjectAggregator(TEN_MIB)).isCoalescingSmallContent(), is(true));
        for (CoalescingHttpObjectAggregator connection : new CoalescingHttpObjectAggregator[]{
            HttpObjectAggregators.httpObjectAggregator(TEN_MIB),
            new StreamingAwareHttpObjectAggregator(TEN_MIB),
            new StreamingAwareHttpObjectAggregator(TEN_MIB, null, null),
            new StreamingAwareHttpObjectAggregator(TEN_MIB, null, null, true)
        }) {
            assertThat(connection.getClass().getSimpleName(), connection.isMergingNewComponentsOnly(), is(true));
            assertThat(connection.getClass().getSimpleName(), connection.isCoalescingSmallContent(), is(false));
        }
    }

    @Test
    public void shouldCopyABodyOfOneBytePiecesAboutOnceAtTheConnectionLimit() {
        // the HTTP/1.1 aggregator merges every 10,240 pieces only what arrived since its last merge; consolidating the
        // whole body each time, as Netty does, would copy about bodyBytes^2 / 20,480 bytes: 214 MB here
        int bodyBytes = 2 * 1024 * 1024;
        TrackingAllocator allocator = new TrackingAllocator();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAllocator(allocator);
        channel.pipeline().addLast(HttpObjectAggregators.httpObjectAggregator(TEN_MIB));
        try {
            FullHttpRequest request = send(channel, new int[]{1}, bodyBytes, Ending.EMPTY_LAST, null);
            try {
                assertThat(ByteBufUtil.getBytes(request.content()), is(expectedBody(bodyBytes)));
                assertThat(((CompositeByteBuf) request.content()).numComponents(), lessThanOrEqualTo(10_240));
            } finally {
                request.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
        assertThat(allocator.allocatedBytes, allOf(lessThanOrEqualTo((long) bodyBytes), greaterThan((long) bodyBytes - 10_240)));
        allocator.assertAllReleased();
    }

    @Test
    public void shouldMergeAtTheConnectionLimitExactlyAsNettyUntilItsFirstMerge() {
        int limit = HttpObjectAggregators.componentLimit(TEN_MIB);
        int piece = SMALL / 2;
        for (int pieces : new int[]{limit, limit + 1}) {
            Result merging = aggregate(HttpObjectAggregators.httpObjectAggregator(TEN_MIB), new int[]{piece}, pieces * piece);
            Result plain = aggregate(HttpObjectAggregators.limitComponents(new HttpObjectAggregator(TEN_MIB)), new int[]{piece}, pieces * piece);
            assertThat(pieces + " pieces", merging.components, is(plain.components));
            assertThat(pieces + " pieces", merging.allocatedBytes, is(plain.allocatedBytes));
            assertThat(pieces + " pieces", merging.allocatedBytes, is(pieces == limit ? 0L : (long) pieces * piece));
        }
    }

    @Test
    public void shouldHoldAtMostTwiceTheBodyAndCopyNoMoreThanNettyAtTheConnectionLimit() {
        // the body pieces and every copy are held, as in the stream tests; without blocks each byte is held once, in a
        // piece or a merged component, plus the one merge being made, so at most twice the body
        int[][] patterns = {
            {1}, {100}, {1, BLOCK}, runThen(100, 31, SMALL), mix(16, 64, 1, SMALL, 5, 10, 1, SMALL),
            mix(16, SMALL - 1, 1, SMALL, 15, 1, 1, SMALL), mix(64, SMALL - 1, 1, SMALL, 15, 1, 1, SMALL, 15, 1, 1, SMALL, 15, 1, 1, SMALL, 15, 1, 1, SMALL)
        };
        for (int maxContentLength : new int[]{256 * 1024, 1024 * 1024, TEN_MIB}) {
            int limit = HttpObjectAggregators.componentLimit(maxContentLength);
            // tiny pieces past two merges that turn large just before Netty's third (MergeNewComponentsOnlyNettyDifferentialTest)
            int[][] withPhaseShift = java.util.Arrays.copyOf(patterns, patterns.length + 1);
            withPhaseShift[patterns.length] = mix(2 * limit + 1, 1, limit - 1, 1_000);
            for (int[] pattern : withPhaseShift) {
                if (pattern.length == 1 && pattern[0] == 1 && maxContentLength == TEN_MIB) {
                    // Netty's arm would copy 5.4 GB; shouldCopyABodyOfOneBytePiecesAboutOnceAtTheConnectionLimit covers it
                    continue;
                }
                String description = "limit " + maxContentLength + " pieces " + java.util.Arrays.toString(java.util.Arrays.copyOf(pattern, Math.min(pattern.length, 8)));
                Result merging = aggregateCountingPieces(HttpObjectAggregators.httpObjectAggregator(maxContentLength), pattern, maxContentLength);
                Result plain = aggregateCountingPieces(HttpObjectAggregators.limitComponents(new HttpObjectAggregator(maxContentLength)), pattern, maxContentLength);
                assertThat(description, merging.body, is(expectedBody(maxContentLength)));
                assertThat(description, merging.peakLiveBytes, lessThanOrEqualTo(2L * maxContentLength));
                assertThat(description, merging.allocatedBytes, lessThanOrEqualTo(plain.allocatedBytes));
            }
        }
    }

    private static Result aggregate(HttpObjectAggregator aggregator, int[] pattern, int length) {
        TrackingAllocator allocator = new TrackingAllocator();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAllocator(allocator);
        channel.pipeline().addLast(aggregator);
        Result result = new Result();
        try {
            FullHttpRequest request = send(channel, pattern, length, Ending.EMPTY_LAST, null);
            try {
                result.body = ByteBufUtil.getBytes(request.content());
                result.components = ((CompositeByteBuf) request.content()).numComponents();
            } finally {
                request.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
        result.allocatedBytes = allocator.allocatedBytes;
        allocator.assertAllReleased();
        return result;
    }

    /**
     * Like {@link #aggregate(HttpObjectAggregator, int[], int)}, with the body pieces allocated from an allocator that
     * shares the live and peak count; {@code allocatedBytes} counts the copies only.
     */
    private static Result aggregateCountingPieces(HttpObjectAggregator aggregator, int[] pattern, int length) {
        Usage usage = new Usage();
        TrackingAllocator allocator = new TrackingAllocator(usage);
        TrackingAllocator pieceAllocator = new TrackingAllocator(usage);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAllocator(allocator);
        channel.pipeline().addLast(aggregator);
        Result result = new Result();
        try {
            FullHttpRequest request = send(channel, pattern, length, Ending.EMPTY_LAST, null, pieceAllocator);
            try {
                result.body = ByteBufUtil.getBytes(request.content());
            } finally {
                request.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
        allocator.assertAllReleased();
        pieceAllocator.assertAllReleased();
        result.allocatedBytes = allocator.allocatedBytes;
        result.peakLiveBytes = usage.peakLiveBytes;
        return result;
    }

    /**
     * Pairs of (count, size): {@code count} pieces of {@code size} bytes, in order.
     */
    private static int[] mix(int... countsAndSizes) {
        List<Integer> sizes = new ArrayList<>();
        for (int i = 0; i < countsAndSizes.length; i += 2) {
            for (int piece = 0; piece < countsAndSizes[i]; piece++) {
                sizes.add(countsAndSizes[i + 1]);
            }
        }
        return sizes.stream().mapToInt(Integer::intValue).toArray();
    }

    @Test
    public void shouldCoalesceASecondMessageOnTheSameAggregator() {
        TrackingAllocator allocator = new TrackingAllocator();
        EmbeddedChannel channel = channel(true, TEN_MIB, allocator);
        try {
            for (int message = 0; message < 2; message++) {
                FullHttpRequest request = send(channel, new int[]{1}, 100_000, Ending.EMPTY_LAST, new ArrayList<>());
                try {
                    assertThat(ByteBufUtil.getBytes(request.content()), is(expectedBody(100_000)));
                    assertThat(((CompositeByteBuf) request.content()).numComponents(), lessThanOrEqualTo(UNCOALESCED + 100_000 / BLOCK + RUN));
                } finally {
                    request.release();
                }
            }
        } finally {
            channel.finishAndReleaseAll();
        }
        allocator.assertAllReleased();
    }

    /**
     * @return whether the plain aggregator copied anything, that is whether the case passed the component limit
     */
    private static boolean assertSameAsPlainAggregator(int[] pattern, int maxContentLength, int componentLimit, int length, Ending ending) {
        String description = "pieces " + java.util.Arrays.toString(pattern.length > 8 ? java.util.Arrays.copyOf(pattern, 8) : pattern)
            + " length " + length + " ending " + ending + " limit " + componentLimit;
        Result coalesced = aggregate(true, maxContentLength, componentLimit, pattern, length, ending);
        Result plain = aggregate(false, maxContentLength, componentLimit, pattern, length, ending);
        assertThat(description, coalesced.body, is(plain.body));
        assertThat(description, coalesced.headers, is(plain.headers));
        assertThat(description, coalesced.trailers, is(plain.trailers));
        assertThat(description, coalesced.outbound, is(plain.outbound));
        assertThat(description, coalesced.open, is(plain.open));
        if (length <= maxContentLength) {
            assertThat(description, coalesced.body, is(expectedBody(length)));
            if (componentLimit > 0) {
                assertThat(description, coalesced.components, lessThanOrEqualTo(componentLimit));
            }
        } else {
            assertThat(description, coalesced.body, is(nullValue()));
            assertThat(description, coalesced.outbound, contains("413"));
        }
        return plain.allocatedBytes > 0;
    }

    private enum Ending {
        EMPTY_LAST, LAST_WITH_DATA, LAST_WITH_TRAILERS
    }

    private static Result aggregate(boolean coalescing, int maxContentLength, int[] pattern, int length, Ending ending) {
        return aggregate(coalescing, maxContentLength, 0, pattern, length, ending);
    }

    /**
     * @param componentLimit the aggregator's component limit, or 0 for the stream limit of {@code maxContentLength}
     */
    private static Result aggregate(boolean coalescing, int maxContentLength, int componentLimit, int[] pattern, int length, Ending ending) {
        TrackingAllocator allocator = new TrackingAllocator();
        EmbeddedChannel channel = channel(coalescing, maxContentLength, componentLimit, allocator);
        // tracking every input piece of a multi-MiB body of one-byte frames would need hundreds of MiB of heap
        List<ByteBuf> inputs = length <= CORPUS_MAX + 1 ? new ArrayList<>() : null;
        Result result = new Result();
        try {
            FullHttpRequest request = send(channel, pattern, length, ending, inputs);
            if (request != null) {
                try {
                    result.body = ByteBufUtil.getBytes(request.content());
                    result.components = ((CompositeByteBuf) request.content()).numComponents();
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
            result.open = channel.isOpen();
        } finally {
            channel.finishAndReleaseAll();
        }
        result.allocatedBytes = allocator.allocatedBytes;
        result.peakLiveBytes = allocator.usage.peakLiveBytes;
        allocator.assertAllReleased();
        if (inputs != null) {
            for (ByteBuf input : inputs) {
                assertThat("input piece released", input.refCnt(), is(0));
            }
        }
        return result;
    }

    private static EmbeddedChannel channel(boolean coalescing, int maxContentLength, TrackingAllocator allocator) {
        return channel(coalescing, maxContentLength, 0, allocator);
    }

    private static EmbeddedChannel channel(boolean coalescing, int maxContentLength, int componentLimit, TrackingAllocator allocator) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAllocator(allocator);
        HttpObjectAggregator aggregator = coalescing
            ? HttpObjectAggregators.streamHttpObjectAggregator(maxContentLength)
            : new HttpObjectAggregator(maxContentLength);
        aggregator.setMaxCumulationBufferComponents(componentLimit > 0 ? componentLimit : HttpObjectAggregators.streamComponentLimit(maxContentLength));
        channel.pipeline().addLast(aggregator);
        return channel;
    }

    private static FullHttpRequest send(EmbeddedChannel channel, int[] pattern, int length, Ending ending, List<ByteBuf> inputs) {
        return send(channel, pattern, length, ending, inputs, UnpooledByteBufAllocator.DEFAULT);
    }

    private static FullHttpRequest send(EmbeddedChannel channel, int[] pattern, int length, Ending ending, List<ByteBuf> inputs, ByteBufAllocator inputAllocator) {
        byte[] body = expectedBody(length);
        channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"));
        int offset = 0;
        int lastDataBytes = ending == Ending.LAST_WITH_DATA ? Math.min(length, pattern[0]) : 0;
        for (int piece = 0; offset < length - lastDataBytes; piece++) {
            int pieceLength = Math.min(pattern[piece % pattern.length], length - lastDataBytes - offset);
            ByteBuf content = inputAllocator.buffer(pieceLength).writeBytes(body, offset, pieceLength);
            if (inputs != null) {
                inputs.add(content);
            }
            write(channel, new DefaultHttpContent(content));
            offset += pieceLength;
        }
        ByteBuf lastContent = inputAllocator.buffer(length - offset).writeBytes(body, offset, length - offset);
        if (inputs != null) {
            inputs.add(lastContent);
        }
        DefaultLastHttpContent last = new DefaultLastHttpContent(lastContent);
        if (ending == Ending.LAST_WITH_TRAILERS) {
            last.trailingHeaders().add("x-checksum", "abc").add("x-empty", "");
        }
        write(channel, last);
        Object inbound = channel.readInbound();
        assertThat(channel.readInbound(), is(nullValue()));
        return (FullHttpRequest) inbound;
    }

    /**
     * Runs {@code test}, then finishes the channel; when the test failed mid-body, finishing it raises
     * {@code PrematureChannelClosureException}, which is added to the test's failure rather than replacing it.
     */
    static void runThenFinish(EmbeddedChannel channel, Runnable test) {
        Throwable failure = null;
        try {
            test.run();
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

    /**
     * An oversized body gets a 413 and the stream is closed, so the rest of it is released unsent, as the closed
     * stream would.
     */
    private static void write(EmbeddedChannel channel, HttpContent content) {
        if (channel.isOpen()) {
            channel.writeInbound(content);
        } else {
            content.release();
        }
    }

    private static byte[] expectedBody(int length) {
        // every byte value, including the non-ASCII ones, at every offset modulo 251
        byte[] body = new byte[length];
        for (int i = 0; i < length; i++) {
            body[i] = (byte) ((i * 31 + i / 251) % 256);
        }
        return body;
    }

    /**
     * {@code count} pieces of {@code small} bytes, then one of {@code large} bytes.
     */
    private static int[] runThen(int small, int count, int large) {
        int[] pattern = new int[count + 1];
        java.util.Arrays.fill(pattern, small);
        pattern[count] = large;
        return pattern;
    }

    private static int[] randomSizes(int min, int max, long seed) {
        Random random = new Random(seed);
        int[] sizes = new int[500];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = min + random.nextInt(max - min + 1);
        }
        return sizes;
    }

    /**
     * Runs of 1 to 40 pieces under 1 KiB, each followed by one piece of 1 KiB to 40 KB: runs both shorter and longer
     * than the 16 pieces that are copied into a block, with a block left part-full before each large piece.
     */
    private static int[] runsOfSmallPiecesBetweenLargeOnes(long seed) {
        Random random = new Random(seed);
        List<Integer> sizes = new ArrayList<>();
        while (sizes.size() < 2_000) {
            int run = 1 + random.nextInt(40);
            for (int i = 0; i < run; i++) {
                sizes.add(1 + random.nextInt(SMALL - 1));
            }
            sizes.add(SMALL + random.nextInt(40_000 - SMALL));
        }
        return sizes.stream().mapToInt(Integer::intValue).toArray();
    }

    private static final class Result {
        private byte[] body;
        private int components;
        private String headers;
        private String trailers;
        private final List<String> outbound = new ArrayList<>();
        private boolean open;
        private long allocatedBytes;
        private long peakLiveBytes;
    }

    private static final class Usage {
        private long liveBytes;
        private long peakLiveBytes;
    }

    /**
     * Counts every byte it allocates (block and consolidation copies; a composite allocates nothing itself) and the
     * most it had allocated and not yet freed at once, and checks every buffer it handed out was released.
     */
    private static final class TrackingAllocator extends AbstractByteBufAllocator {
        private final List<ByteBuf> buffers = new ArrayList<>();
        private final Usage usage;
        private long allocatedBytes;

        TrackingAllocator() {
            this(new Usage());
        }

        /**
         * @param usage live and peak bytes, shared with another allocator to count both (body pieces and copies)
         */
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
