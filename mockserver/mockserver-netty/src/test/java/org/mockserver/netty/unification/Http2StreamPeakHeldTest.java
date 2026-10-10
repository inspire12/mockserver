package org.mockserver.netty.unification;

import org.junit.Test;
import org.mockserver.netty.unification.Http2ConnectionMemoryHarness.Frame;
import org.mockserver.netty.unification.Http2ConnectionMemoryHarness.Result;
import org.mockserver.netty.unification.Http2ConnectionMemoryHarness.Variant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.eachInItsOwnRead;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.fair;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.mix;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.oneStream;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.pinning;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.tracked;

/**
 * What an HTTP/2 upload holds through the real connection codec and the production stream chain, counting the
 * reads: a DATA frame is a slice of the read it arrived in, so a read stays allocated while any frame in it, from
 * any stream, is held. The stream aggregator merges the frames a stream holds from a read it uses under half of, once
 * it moves on to the next read, so every read it pins is at least half its own: what it holds is at most about twice
 * its body plus the read it is on and a part-used block, whatever the frame sizes, and a body whose frames fill their
 * reads is never copied. These tests pin that bound against the shapes that defeat a per-frame rule.
 */
public class Http2StreamPeakHeldTest {

    private static final int KIB = 1024;
    private static final int MIB = 1024 * 1024;
    private static final int DEFAULT_WINDOW = 65_535;
    /**
     * The read a stream is on, a part-used block, the read being delivered and the filler request in flight.
     */
    private static final long SLACK = 64 * KIB + 16 * KIB + 2 * 64 * KIB;
    /**
     * A hang guard, not a speed limit: the build's leak detector records a stack trace at every access to a tracked
     * buffer, which multiplies the time the tiny-frame shapes take.
     */
    private static final long HANG_GUARD_MILLIS = 900_000;

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldHoldEveryReadOfABodyInSixteenKibFramesWithoutCopying() {
        // the frames fill their reads, so nothing is copied; the reads' unused space (the adaptive read buffers and a
        // frame decoder that grows its buffer in powers of two to join a frame cut by a read) takes it past the body
        for (int readBytes : new int[]{16 * KIB, 64 * KIB}) {
            Result production = tracked(Variant.PRODUCTION, readBytes, oneStream(MIB, 16 * KIB), 1);
            String description = "reads of " + readBytes + ": held " + production.peakHeldBytes
                + " for a body of " + production.bodyBytes;
            assertThat(description, production.aggregatorAllocatedBytes, is(0L));
            assertThat(description, production.peakHeldBytes, greaterThanOrEqualTo(production.wireBytes));
            assertThat(description, production.peakHeldBytes, lessThanOrEqualTo(2 * production.bodyBytes));
        }
    }

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldCopyAtMostAQuarterOfABodyInSixteenKibFramesCutByTheWindow() {
        // the flow-control window cuts every fourth frame a byte short; a frame cut by a read is joined into a buffer
        // twice its size, under half used when the frame is the short one, so such buffers are copied
        for (int readBytes : new int[]{16 * KIB, 64 * KIB}) {
            List<Frame> frames = oneStream(MIB, 16 * KIB, 16 * KIB, 16 * KIB, 16 * KIB - 1);
            Result production = tracked(Variant.PRODUCTION, readBytes, frames, 1);
            Result netty = tracked(Variant.NETTY, readBytes, frames, 1);
            String description = "reads of " + readBytes + ": production held " + production.peakHeldBytes
                + " and copied " + production.aggregatorAllocatedBytes + ", Netty's aggregator held " + netty.peakHeldBytes
                + ", body " + production.bodyBytes;
            assertThat(description, 4 * production.aggregatorAllocatedBytes, lessThanOrEqualTo(production.bodyBytes + 64 * KIB));
            assertThat(description, production.peakHeldBytes, lessThanOrEqualTo(netty.peakHeldBytes));
        }
    }

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldHoldLessThanNettysAggregatorWhenTinyFrameStreamsShareReadsWithALargeFrameStream() {
        int[] bodies = {256 * KIB, 256 * KIB, 256 * KIB, 256 * KIB};
        int[][] patterns = {{16 * KIB}, {1}, {100}, {1023}};
        for (int readBytes : new int[]{16 * KIB, 64 * KIB}) {
            Result production = tracked(Variant.PRODUCTION, readBytes, fair(bodies, patterns), bodies.length);
            Result netty = tracked(Variant.NETTY, readBytes, fair(bodies, patterns), bodies.length);
            String description = "reads of " + readBytes + ": production held " + production.peakHeldBytes
                + ", Netty's aggregator " + netty.peakHeldBytes + ", body " + production.bodyBytes
                + ", copied " + production.aggregatorAllocatedBytes;
            assertThat(description, 4 * production.peakHeldBytes, lessThanOrEqualTo(13 * production.bodyBytes));
            assertThat(description, 3 * production.peakHeldBytes, lessThanOrEqualTo(2 * netty.peakHeldBytes));
            // each stream holds a quarter or less of every read, so its frames are merged, then copied again into
            // blocks or at the component limit: a few copies of the body at most
            assertThat(description, production.aggregatorAllocatedBytes, lessThanOrEqualTo(4 * production.bodyBytes));
        }
    }

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldHoldAtMostThreeTimesTheBodyPlusSlackForRunsOfSmallFramesBetweenLargerOnes() {
        // runs of small frames between 1 KiB or 16 KiB frames of the same stream: a 16 KiB frame cut by a read is joined
        // into a 32 KiB buffer, half used and so kept, and the whole-body merge at the component limit briefly holds a
        // further copy
        for (int[] pattern : new int[][]{mix(16, 1023, 1, 1024, 15, 1, 1, 1024), mix(31, 100, 1, 1024), mix(31, 1, 1, 16 * KIB)}) {
            for (int readBytes : new int[]{16 * KIB, 64 * KIB}) {
                Result production = tracked(Variant.PRODUCTION, readBytes, oneStream(MIB, pattern), 1);
                String description = "pattern " + Arrays.toString(Arrays.copyOf(pattern, 18)) + " reads of " + readBytes
                    + ": held " + production.peakHeldBytes + " for a body of " + production.bodyBytes;
                assertThat(description, production.peakHeldBytes, lessThanOrEqualTo(3 * production.bodyBytes + SLACK));
            }
        }
    }

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldHoldAtMostTwiceTheBodiesPlusSlackForALargeFrameStreamBesideATinyFrameStream() {
        // 16 KiB frames sharing reads with one-byte frames
        Result production = tracked(Variant.PRODUCTION, 64 * KIB, fair(new int[]{MIB, MIB}, new int[][]{{16 * KIB}, {1}}), 2);
        String description = "held " + production.peakHeldBytes + " for bodies of " + production.bodyBytes;
        assertThat(description, production.peakHeldBytes, lessThanOrEqualTo(2 * production.bodyBytes + 2 * SLACK));
    }

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldNotPinWholeReadsWhenAStreamKeepsOneFramePerRead() {
        // one held frame per read of about 32 KiB, the rest of the read a completed request: under half of a read up to
        // 16 KiB, either side of the 4 KiB eighth a per-frame rule would have used
        int cycles = 1_000;
        for (int pieceBytes : new int[]{1, 100, 1023, 1024, 4096, 4097, 8 * KIB}) {
            Result production = tracked(Variant.PRODUCTION, 64 * KIB, pinning(cycles, pieceBytes), cycles + 1);
            Result netty = tracked(Variant.NETTY, 64 * KIB, pinning(cycles, pieceBytes), cycles + 1);
            assertHeldBounded("one " + pieceBytes + " B frame per read", production, netty, (long) cycles * pieceBytes, 1);
        }
    }

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldNotPinReadsWhenARunOfOneByteFramesIsBrokenByA1KibFrame() {
        // 15 one-byte frames then one of 1 KiB, each in a read of its own
        int cycles = 1_100;
        Result production = tracked(Variant.PRODUCTION, 64 * KIB, pinning(cycles, mix(15, 1, 1, KIB)), cycles + 1);
        Result netty = tracked(Variant.NETTY, 64 * KIB, pinning(cycles, mix(15, 1, 1, KIB)), cycles + 1);
        assertHeldBounded("15 x 1 B + 1 KiB per read", production, netty, cycles / 16 * (15 + KIB), 1);
    }

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldNotPinReadsAcrossManyStreamsEachKeepingOneFramePerRead() {
        // 20 streams each keep one frame of every read, the rest of the read a completed request
        int streams = 20;
        int cycles = 100;
        for (int pieceBytes : new int[]{1024, 4097}) {
            List<Frame> frames = new ArrayList<>();
            int filler = streams;
            for (int cycle = 0; cycle < cycles; cycle++) {
                for (int stream = 0; stream < streams; stream++) {
                    frames.add(new Frame(stream, pieceBytes));
                    if (cycle > 0) {
                        frames.add(new Frame(filler++, DEFAULT_WINDOW / 2 - pieceBytes - 128));
                    }
                    frames.add(Frame.DELIVER_NOW);
                }
            }
            Result production = tracked(Variant.PRODUCTION, 64 * KIB, frames, filler);
            Result netty = tracked(Variant.NETTY, 64 * KIB, frames, filler);
            assertHeldBounded(streams + " streams of " + pieceBytes + " B frames", production, netty, (long) streams * cycles * pieceBytes, streams);
        }
    }

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldNotPinReadsWhenStreamsSendTinyFramesEachInARead() {
        // 20 streams of 60 frames of 1 or 9 bytes, every frame in a read of its own and no other traffic
        for (int pieceBytes : new int[]{1, 9}) {
            int[] bodies = new int[20];
            int[][] patterns = new int[20][];
            Arrays.fill(bodies, 60 * pieceBytes);
            Arrays.fill(patterns, new int[]{pieceBytes});
            Result production = tracked(Variant.PRODUCTION, 64 * KIB, eachInItsOwnRead(fair(bodies, patterns)), bodies.length);
            Result netty = tracked(Variant.NETTY, 64 * KIB, eachInItsOwnRead(fair(bodies, patterns)), bodies.length);
            String description = "20 streams x 60 x " + pieceBytes + " B: production held " + production.peakHeldBytes
                + ", Netty's aggregator " + netty.peakHeldBytes + ", wire " + production.wireBytes;
            assertThat(description, production.peakHeldBytes, lessThanOrEqualTo(production.wireBytes));
            assertThat(description, 4 * production.peakHeldBytes, lessThanOrEqualTo(netty.peakHeldBytes));
        }
    }

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldFreeAReadKeptHalfUsedOnceABlockCopyTakesMostOfItsFrames() {
        // a 1 B, a 1,039 B and 15 frames of 1,023 B, repeated: a read can hold just over half its bytes in them, and the
        // next read's first frame completes a run of 16 whose block copy leaves that read 1,040 B used, so the read
        // must then be freed rather than stay pinned
        int cycles = 300;
        List<Frame> frames = new ArrayList<>();
        for (int cycle = 0; cycle < cycles; cycle++) {
            frames.add(new Frame(0, 1));
            frames.add(new Frame(0, 1039));
            for (int frame = 0; frame < 15; frame++) {
                frames.add(new Frame(0, 1023));
            }
            frames.add(Frame.DELIVER_NOW);
        }
        for (int readBytes : new int[]{32 * KIB, 64 * KIB}) {
            Result production = tracked(Variant.PRODUCTION, readBytes, frames, 1);
            Result netty = tracked(Variant.NETTY, readBytes, frames, 1);
            String description = "reads of " + readBytes + ": production held " + production.peakHeldBytes
                + ", Netty's aggregator " + netty.peakHeldBytes + ", body " + production.bodyBytes;
            assertThat(description, production.peakHeldBytes, lessThanOrEqualTo(2 * production.bodyBytes + SLACK));
            assertThat(description, production.peakHeldBytes, lessThanOrEqualTo(netty.peakHeldBytes));
        }
    }

    /**
     * Holds at most three times the bodies of the streams keeping frames (the body, the half-used reads it may pin, and
     * a merge being made) plus slack per such stream, and at most half what Netty's aggregator holds.
     */
    private static void assertHeldBounded(String description, Result production, Result netty, long heldBodies, int heldStreams) {
        String detail = description + ": production held " + production.peakHeldBytes + ", Netty's aggregator "
            + netty.peakHeldBytes + ", bodies kept " + heldBodies + ", wire " + production.wireBytes;
        assertThat(detail, production.peakHeldBytes, lessThanOrEqualTo(3 * heldBodies + heldStreams * SLACK));
        assertThat(detail, 2 * production.peakHeldBytes, lessThanOrEqualTo(netty.peakHeldBytes));
    }
}
