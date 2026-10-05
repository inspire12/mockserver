package org.mockserver.netty.integration;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ResourceLeakDetector;
import org.mockserver.test.FailOnLeakResourceLeakDetector;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The buffer leaks the build's leak detector has recorded in this JVM, for a test class to check that what it did
 * leaked none without waiting for the build's own check at the end. A leak is recorded once the leaked buffer has been
 * collected and another buffer is allocated, so the check collects and allocates first.
 */
public final class NettyBufferLeaks {

    private static final int NOT_TRACKED = -1;

    private NettyBufferLeaks() {
    }

    /**
     * @return the number of leaks recorded so far, to pass to {@link #assertNoneSince(int)}
     */
    public static int recorded() {
        if (!detectorInstalled()) {
            return NOT_TRACKED;
        }
        assertEveryBufferIsTracked();
        return FailOnLeakResourceLeakDetector.leakCount();
    }

    public static void assertNoneSince(int recordedBefore) throws InterruptedException {
        if (recordedBefore == NOT_TRACKED || !detectorInstalled()) {
            return;
        }
        assertEveryBufferIsTracked();
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(50);
            ByteBufAllocator.DEFAULT.buffer(1).release();
        }
        assertThat("buffers leaked in this JVM since this class started: " + FailOnLeakResourceLeakDetector.recordedLeaks(),
            FailOnLeakResourceLeakDetector.leakCount(), is(recordedBefore));
    }

    /**
     * A build that replaces {@code mockserver.testArgLine} on its command line runs without the detector, and there
     * is then nothing to check.
     */
    private static boolean detectorInstalled() {
        return FailOnLeakResourceLeakDetector.CLASS_NAME.equals(System.getProperty("io.netty.customResourceLeakDetector"));
    }

    /**
     * Below {@code paranoid} a leak could go unrecorded and the check pass.
     */
    private static void assertEveryBufferIsTracked() {
        assertThat("every buffer is tracked", ResourceLeakDetector.getLevel(), is(ResourceLeakDetector.Level.PARANOID));
    }
}
