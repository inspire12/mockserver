package org.mockserver.netty;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

import static org.junit.Assert.fail;

/**
 * Addresses for tests of {@code localBoundIP}, which bind MockServer to the loopback address and then send to
 * another address of this host, which a listener on every address would also answer.
 */
public final class LocalBoundIpAddresses {

    private LocalBoundIpAddresses() {
    }

    /**
     * @return an IPv4 address of an interface of this host that is up and not the loopback; fails the test if there
     * is none, rather than skipping it
     */
    public static InetAddress anotherAddressOfThisHost() throws Exception {
        for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                    return address;
                }
            }
        }
        fail("this test needs an IPv4 address of this host other than the loopback, and there is none");
        return null;
    }
}
