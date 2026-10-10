package org.mockserver.test;

/**
 * A loopback port that reliably refuses connections, for tests that need a failed connect.
 * <p>
 * Do not use a "free" port from {@code bind(0)} / {@code PortFactory.findFreePort()} for this: a
 * concurrently running test can bind that number before the connect, which then succeeds. No
 * allocator hands out port 1. Holding the port with a bound-but-not-listening socket is not a
 * portable alternative either: Linux refuses the connect, but macOS drops the SYN so it times out.
 */
public final class ClosedPort {

    public static final int CLOSED_PORT = 1;

    private ClosedPort() {
    }
}
