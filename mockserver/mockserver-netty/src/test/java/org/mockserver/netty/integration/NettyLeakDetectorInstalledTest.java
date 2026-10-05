package org.mockserver.netty.integration;

import org.junit.Test;

/**
 * The unit-test fork must run with the build's leak detector, or the build's leak check at the end passes having
 * checked nothing. The integration-test fork is covered by the classes that call {@link NettyBufferLeaks}.
 */
public class NettyLeakDetectorInstalledTest {

    @Test
    public void theUnitTestForkTracksEveryBufferWithTheBuildsLeakDetector() {
        NettyBufferLeaks.assertEveryBufferIsTracked();
    }
}
