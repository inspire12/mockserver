package org.mockserver.netty.unification;

import org.junit.Test;
import org.mockserver.netty.unification.Http2ConnectionMemoryHarness.Result;
import org.mockserver.netty.unification.Http2ConnectionMemoryHarness.Variant;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.fair;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.mix;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.oneStream;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.tracked;

/**
 * What an HTTP/2 upload holds through the real connection codec and the production stream chain, counting the
 * reads: a DATA frame is a slice of the read it arrived in, so a read stays allocated while any frame in it, from
 * any stream, is held. The stream aggregator copies runs of frames under 1 KiB into 16 KiB blocks, which frees the
 * reads tiny frames pin; where larger frames of the same stream keep those reads alive the copies add to them, so
 * it can hold more than Netty's aggregator, and these tests pin both sides of that trade.
 */
public class Http2StreamPeakHeldTest {

    private static final int KIB = 1024;
    private static final int MIB = 1024 * 1024;

    @Test(timeout = 300_000)
    public void shouldHoldEveryReadOfABodyInSixteenKibFramesWithoutCopying() {
        // nothing is copied, so every read stays allocated until the request completes; the reads' unused space
        // (the adaptive read buffers and a frame decoder that grows its buffer in powers of two to join a frame cut
        // by a read) is what takes it past the body
        for (int readBytes : new int[]{16 * KIB, 64 * KIB}) {
            Result production = tracked(Variant.PRODUCTION, readBytes, oneStream(MIB, 16 * KIB), 1);
            String description = "reads of " + readBytes + ": held " + production.peakHeldBytes
                + " for a body of " + production.bodyBytes;
            assertThat(description, production.aggregatorAllocatedBytes, is(0L));
            assertThat(description, production.peakHeldBytes, greaterThanOrEqualTo(production.wireBytes));
            assertThat(description, production.peakHeldBytes, lessThanOrEqualTo(2 * production.bodyBytes));
        }
    }

    @Test(timeout = 300_000)
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
            // each byte of the three tiny-frame streams is copied into a block once, the 16 KiB frames never; each
            // stream leaves at most one block part-used
            assertThat(description, production.aggregatorAllocatedBytes, lessThanOrEqualTo(3 * 256L * KIB + 4 * 16L * KIB));
        }
    }

    @Test(timeout = 300_000)
    public void shouldHoldAtMostAboutThreeTimesTheBodyWhenLargerFramesKeepTheReadsOfCopiedRunsAlive() {
        // runs of frames under 1 KiB between 1 KiB frames: the blocks copy the runs, the 1 KiB frames keep their
        // reads, so the body is held about twice besides the reads' unused space, more than Netty's aggregator holds
        for (int[] pattern : new int[][]{mix(16, 1023, 1, 1024, 15, 1, 1, 1024), mix(31, 100, 1, 1024)}) {
            for (int readBytes : new int[]{16 * KIB, 64 * KIB}) {
                Result production = tracked(Variant.PRODUCTION, readBytes, oneStream(MIB, pattern), 1);
                String description = "pattern " + Arrays.toString(Arrays.copyOf(pattern, 18)) + " reads of " + readBytes
                    + ": held " + production.peakHeldBytes + " for a body of " + production.bodyBytes;
                assertThat(description, 4 * production.peakHeldBytes, lessThanOrEqualTo(13 * production.bodyBytes));
            }
        }
    }
}
