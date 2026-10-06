package org.mockserver.lifecycle;

import java.io.IOException;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnknownHostException;
import java.nio.channels.DatagramChannel;

/**
 * The UDP counterpart of the TCP listeners' {@code LoopbackShadowProbe}, for the HTTP/3 and DNS listeners. On macOS
 * a dual-stack UDP bind succeeds on a port another socket holds on {@code 0.0.0.0}, which then receives the
 * localhost datagrams. The probe must run BEFORE the server binds: on macOS and Linux an IPv4 bind also fails against
 * the same process's own dual-stack socket, so a later probe cannot tell the two apart. No probe sets
 * {@code SO_REUSEADDR}. Internal, not API: public only so the listeners in other packages can share it.
 */
public final class Ipv4UdpPortProbe {

    private static final InetAddress IPV4_WILDCARD = ipv4Wildcard();

    private Ipv4UdpPortProbe() {
    }

    /**
     * @return true if a dual-stack wildcard bind of {@code port}, as the server makes, would succeed although another
     * socket holds the port on the IPv4 wildcard; false wherever that bind fails as well (Linux), so the server's own
     * bind then reports the conflict
     */
    public static boolean shadowedOnIpv4(int port) {
        return heldOnIpv4(port) && dualStackBindSucceeds(port);
    }

    /**
     * @return true if binding {@code 0.0.0.0:port} fails with a {@link BindException}, i.e. another socket
     * holds the port on IPv4 (or the port cannot be bound at all, which the server's own bind then reports);
     * false if the port was free on IPv4 or IPv4 is unavailable
     */
    public static boolean heldOnIpv4(int port) {
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
    public static boolean dualStackBindSucceeds(int port) {
        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    /**
     * @return a port the IPv4 allocator chose, so no socket held it on IPv4 when it was chosen, or 0 if IPv4 is
     * unavailable
     */
    public static int portFreeOnIpv4() {
        try (DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET)) {
            channel.bind(new InetSocketAddress(IPV4_WILDCARD, 0));
            return ((InetSocketAddress) channel.getLocalAddress()).getPort();
        } catch (IOException | UnsupportedOperationException e) {
            return 0;
        }
    }

    /**
     * @param traffic      what reaches the other socket, such as {@code HTTP/3 requests}
     * @param portProperty the configuration property that set the port
     */
    public static BindException ipv4WildcardConflict(int port, String traffic, String portProperty) {
        return new BindException("UDP port " + port + " is already in use by another socket listening on 0.0.0.0:" + port
            + ", so " + traffic + " to localhost:" + port + " would reach that socket instead of MockServer"
            + "; stop the application that holds it or choose a different " + portProperty + " (to find it run: lsof -nP -iUDP:" + port + ")");
    }

    private static InetAddress ipv4Wildcard() {
        try {
            return Inet4Address.getByAddress(new byte[]{0, 0, 0, 0});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
