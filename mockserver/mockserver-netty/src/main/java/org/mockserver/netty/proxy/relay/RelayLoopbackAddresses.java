package org.mockserver.netty.proxy.relay;

import io.netty.channel.Channel;

import java.net.SocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The local addresses of the loopback connections {@link RelayConnectHandler} opens to MockServer, so the accepting
 * side can tell its own relay's loopback leg from a client that merely sends the same {@code PROXIED_} preamble. Each
 * address is held only while its connection is open.
 */
public final class RelayLoopbackAddresses {

    private static final Set<SocketAddress> LOCAL_ADDRESSES = ConcurrentHashMap.newKeySet();

    private RelayLoopbackAddresses() {
    }

    /**
     * Must be called once the loopback is connected and before it sends its preamble.
     */
    static void register(Channel loopback) {
        SocketAddress localAddress = loopback.localAddress();
        if (localAddress != null && LOCAL_ADDRESSES.add(localAddress)) {
            loopback.closeFuture().addListener(future -> LOCAL_ADDRESSES.remove(localAddress));
        }
    }

    /**
     * @return whether an accepted connection is the loopback leg of one of this JVM's relays
     */
    public static boolean isRelayLoopback(Channel accepted) {
        SocketAddress remoteAddress = accepted.remoteAddress();
        return remoteAddress != null && LOCAL_ADDRESSES.contains(remoteAddress);
    }
}
