package org.mockserver.netty.http3;

import java.io.IOException;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnknownHostException;
import java.nio.channels.DatagramChannel;

/**
 * The UDP counterpart of the TCP listeners' {@code LoopbackShadowProbe}. On macOS a dual-stack UDP bind
 * succeeds on a port another socket holds on {@code 0.0.0.0}, which then receives the localhost datagrams.
 * The probe must run BEFORE the server binds: on macOS and Linux an IPv4 bind also fails against the same
 * process's own dual-stack socket, so a later probe cannot tell the two apart. No probe sets
 * {@code SO_REUSEADDR}.
 */
final class Ipv4UdpPortProbe {

    private static final InetAddress IPV4_WILDCARD = ipv4Wildcard();

    private Ipv4UdpPortProbe() {
    }

    /**
     * @return true if binding {@code 0.0.0.0:port} fails with a {@link BindException}, i.e. another socket
     * holds the port on IPv4 (or the port cannot be bound at all, which the server's own bind then reports);
     * false if the port was free on IPv4 or IPv4 is unavailable
     */
    static boolean heldOnIpv4(int port) {
        try (DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET)) {
            channel.bind(new InetSocketAddress(IPV4_WILDCARD, port));
            return false;
        } catch (BindException e) {
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    /**
     * @return true if a dual-stack wildcard bind of {@code port}, as the server makes, succeeds. The channel is never
     * registered with a selector, so closing it releases the port at once; a Netty channel's socket stays bound
     * until its event loop next deregisters it.
     */
    static boolean dualStackBindSucceeds(int port) {
        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    static BindException ipv4WildcardConflict(int port) {
        return new BindException("UDP port " + port + " is already in use by another socket listening on 0.0.0.0:" + port
            + ", so HTTP/3 requests to localhost:" + port + " would reach that socket instead of MockServer"
            + "; stop the application that holds it or choose a different http3Port (to find it run: lsof -nP -iUDP:" + port + ")");
    }

    private static InetAddress ipv4Wildcard() {
        try {
            return Inet4Address.getByAddress(new byte[]{0, 0, 0, 0});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
