package org.mockserver.netty.integration;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import org.mockserver.test.FailOnLeakResourceLeakDetector;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * The buffer leaks the build's leak detector has recorded in this JVM, for a test class to check that what it did
 * leaked none without waiting for the build's own check at the end. A leak is recorded once the leaked buffer has been
 * collected and another buffer is allocated, so the check collects and allocates first.
 */
public final class NettyBufferLeaks {

    static final String VM_OPTIONS = "-Dio.netty.leakDetection.level=paranoid -Dio.netty.customResourceLeakDetector=" + FailOnLeakResourceLeakDetector.CLASS_NAME;

    private NettyBufferLeaks() {
    }

    /**
     * @return the number of leaks recorded so far, to pass to {@link #assertNoneSince(int)}
     */
    public static int recorded() {
        assertEveryBufferIsTracked();
        return FailOnLeakResourceLeakDetector.leakCount();
    }

    public static void assertNoneSince(int recordedBefore) throws InterruptedException {
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
     * Without the detector no leak is counted, and below {@code paranoid} one could go unrecorded: either way the
     * check would pass having checked nothing, so it fails instead. Maven installs both in every fork of this module.
     */
    static void assertEveryBufferIsTracked() {
        assertThat("this JVM does not have the build's Netty leak detector, so a leaked buffer would go unnoticed. "
                + "Maven installs it (mockserver.leakArgLine in mockserver-netty/pom.xml); to run from an IDE add the VM options: " + VM_OPTIONS,
            ResourceLeakDetectorFactory.instance().newResourceLeakDetector(ByteBuf.class), instanceOf(FailOnLeakResourceLeakDetector.class));
        assertThat("every buffer is tracked (Netty leak detection level; the build sets paranoid)",
            ResourceLeakDetector.getLevel(), is(ResourceLeakDetector.Level.PARANOID));
    }
}
