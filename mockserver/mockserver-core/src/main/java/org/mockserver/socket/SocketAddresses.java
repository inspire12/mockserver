package org.mockserver.socket;

import io.netty.util.NetUtil;

import java.net.InetSocketAddress;

public final class SocketAddresses {

    private SocketAddresses() {
    }

    /**
     * An address for a host without looking its name up: an IP literal is parsed into a resolved address, and a
     * host name is left unresolved, to be resolved where the connection is made (by Netty's resolver, or by an
     * upstream proxy the connection is tunnelled through).
     */
    public static InetSocketAddress unresolvedUnlessIpLiteral(String host, int port) {
        return NetUtil.isValidIpV4Address(host) || NetUtil.isValidIpV6Address(host)
            ? new InetSocketAddress(host, port)
            : InetSocketAddress.createUnresolved(host, port);
    }
}
