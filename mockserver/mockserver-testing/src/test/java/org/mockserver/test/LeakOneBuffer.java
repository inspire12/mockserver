package org.mockserver.test;

import io.netty.buffer.ByteBufAllocator;

/**
 * Run in a child JVM by {@link FailOnLeakResourceLeakDetectorTest}: with {@code leak} it drops one buffer without
 * releasing it, otherwise it releases it, then gives the detector the chance to report.
 */
public final class LeakOneBuffer {

    private LeakOneBuffer() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length > 0 && args[0].equals("leak")) {
            allocateAndDrop();
        } else {
            ByteBufAllocator.DEFAULT.buffer(16).writeByte(1).release();
        }
        // a leak is reported once the buffer is collected and a later allocation polls the detector's queue
        for (int i = 0; i < 20 && FailOnLeakResourceLeakDetector.leakCount() == 0; i++) {
            System.gc();
            Thread.sleep(50);
            ByteBufAllocator.DEFAULT.buffer(1).release();
        }
    }

    private static void allocateAndDrop() {
        ByteBufAllocator.DEFAULT.buffer(16).writeByte(1);
    }
}
