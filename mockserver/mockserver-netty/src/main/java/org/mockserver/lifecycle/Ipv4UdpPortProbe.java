package org.mockserver.lifecycle;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Promise;

import java.io.IOException;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.StandardProtocolFamily;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * The UDP counterpart of the TCP listeners' {@code LoopbackShadowProbe}, for the HTTP/3 and DNS listeners. On macOS
 * a dual-stack UDP bind succeeds on a port another socket holds on {@code 0.0.0.0}, which then receives the
 * localhost datagrams. The probe must run BEFORE the server binds: on macOS and Linux an IPv4 bind also fails against
 * the same process's own dual-stack socket, so a later probe cannot tell the two apart; a socket that takes the port in
 * between is caught after the bind by {@link #loopbackReachesAnotherSocket}, which sends a datagram instead. No probe
 * sets {@code SO_REUSEADDR}. Internal, not API: public only so the listeners in other packages can share it.
 */
public final class Ipv4UdpPortProbe {

    static final String HANDLER_NAME = "mockServerUdpLoopbackProbe";
    static final boolean DUAL_STACK_BIND_CAN_SHARE_AN_IPV4_PORT = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    private static final InetAddress IPV4_WILDCARD = ipv4Wildcard();
    private static final InetAddress IPV4_LOOPBACK = ipv4Loopback();
    private static final byte[] PROBE_PAYLOAD = "mockserver udp port probe".getBytes(StandardCharsets.US_ASCII);
    private static final int PROBE_DATAGRAMS = 3;
    private static final long PROBE_TIMEOUT_MILLIS = 200;
    private static final long EVENT_LOOP_RESPONSE_TIMEOUT_MILLIS = 1000;

    private Ipv4UdpPortProbe() {
    }

    /**
     * @return the address a UDP listener binds: {@code localBoundIP} when it is set, as the TCP listeners do, otherwise
     * every address
     */
    public static InetSocketAddress listenerAddress(String localBoundIP, int port) {
        return isBlank(localBoundIP) ? new InetSocketAddress(port) : new InetSocketAddress(localBoundIP, port);
    }

    /**
     * Only a wildcard bind is checked: a bind of one address fails against a socket on {@code 0.0.0.0:port}, and a
     * socket on one address is sent every datagram for that address.
     *
     * @return true if the server's bind of {@code listenerAddress} would share its port with a socket on the IPv4
     * wildcard, which would then get the localhost datagrams
     */
    public static boolean shadowedOnIpv4(InetSocketAddress listenerAddress) {
        InetAddress address = listenerAddress.getAddress();
        return address != null && address.isAnyLocalAddress() && shadowedOnIpv4(listenerAddress.getPort());
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
     * The check after the bind, for a socket that took the port on {@code 0.0.0.0} between {@link #shadowedOnIpv4}
     * and the server's bind: a datagram is sent to {@code 127.0.0.1} on the bound port, and must arrive on
     * {@code boundChannel}. The probe datagram is taken off the channel's pipeline, so no handler sees it. Waits
     * uninterruptibly, for about a second at most, and keeps the caller's interrupt. Made only on macOS, where that
     * bind can succeed.
     *
     * @return true if the probe reached another socket; false if it reached {@code boundChannel}, the platform is not
     * macOS, the channel is not bound to a wildcard address, nothing listens on {@code 127.0.0.1} at that port, or the check could not be made
     * (its event loop stalled or IPv4 unavailable)
     */
    public static boolean loopbackReachesAnotherSocket(Channel boundChannel) {
        return loopbackReachesAnotherSocket(boundChannel, DUAL_STACK_BIND_CAN_SHARE_AN_IPV4_PORT);
    }

    /**
     * @param dualStackBindCanShare false skips the check: where a dual-stack bind fails against a socket on
     *                              {@code 0.0.0.0} (Linux) there is nothing to catch, and an iptables REDIRECT of
     *                              loopback UDP (a DNS sidecar) would divert the probe and refuse a good start
     */
    static boolean loopbackReachesAnotherSocket(Channel boundChannel, boolean dualStackBindCanShare) {
        if (!dualStackBindCanShare || !(boundChannel.localAddress() instanceof InetSocketAddress)) {
            return false;
        }
        InetSocketAddress bound = (InetSocketAddress) boundChannel.localAddress();
        if (bound.getAddress() == null || !bound.getAddress().isAnyLocalAddress()) {
            return false;
        }
        return reachesAnotherSocket(boundChannel, new InetSocketAddress(IPV4_LOOPBACK, bound.getPort()));
    }

    static boolean reachesAnotherSocket(Channel boundChannel, InetSocketAddress target) {
        boolean interrupted = Thread.interrupted();
        DatagramInterceptor interceptor = new DatagramInterceptor(boundChannel.eventLoop().newPromise());
        try {
            io.netty.util.concurrent.Future<?> install = boundChannel.eventLoop().submit(() -> boundChannel.pipeline().addFirst(HANDLER_NAME, interceptor));
            if (!awaitUninterruptibly(install, EVENT_LOOP_RESPONSE_TIMEOUT_MILLIS)) {
                if (!install.cancel(false)) {
                    // it already ran, or is running: queue the removal behind it
                    boundChannel.eventLoop().execute(() -> removeQuietly(boundChannel, interceptor));
                }
                return false;
            }
            return probeReachesAnotherSocket(boundChannel, target, interceptor);
        } catch (RuntimeException eventLoopShutDown) {
            return false;
        } finally {
            removeQuietly(boundChannel, interceptor);
            if (interrupted || Thread.interrupted()) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean probeReachesAnotherSocket(Channel boundChannel, InetSocketAddress target, DatagramInterceptor interceptor) {
        try (DatagramChannel probe = DatagramChannel.open(StandardProtocolFamily.INET)) {
            probe.bind(new InetSocketAddress(target.getAddress(), 0));
            // connected, so an ICMP port unreachable (nothing listens on the IPv4 loopback) fails the next write
            probe.connect(target);
            interceptor.source = (InetSocketAddress) probe.getLocalAddress();
            for (int attempt = 0; attempt < PROBE_DATAGRAMS; attempt++) {
                try {
                    probe.write(ByteBuffer.wrap(PROBE_PAYLOAD));
                } catch (PortUnreachableException nothingListening) {
                    return false;
                }
                if (awaitUninterruptibly(interceptor.received, PROBE_TIMEOUT_MILLIS)) {
                    return false;
                }
            }
            // a datagram not received by a stalled event loop is not evidence that another socket took it
            io.netty.util.concurrent.Future<?> eventLoopResponds = boundChannel.eventLoop().submit(() -> {
            });
            if (!awaitUninterruptibly(eventLoopResponds, EVENT_LOOP_RESPONSE_TIMEOUT_MILLIS)) {
                return false;
            }
            return !awaitUninterruptibly(interceptor.received, PROBE_TIMEOUT_MILLIS);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean awaitUninterruptibly(io.netty.util.concurrent.Future<?> future, long timeoutMillis) {
        return future.awaitUninterruptibly(timeoutMillis, MILLISECONDS) && future.isSuccess();
    }

    private static void removeQuietly(Channel boundChannel, DatagramInterceptor interceptor) {
        try {
            if (boundChannel.pipeline().context(interceptor) != null) {
                boundChannel.pipeline().remove(interceptor);
            }
        } catch (RuntimeException ignore) {
            // channel already closed
        }
    }

    private static final class DatagramInterceptor extends ChannelInboundHandlerAdapter {

        private final Promise<Void> received;
        private volatile InetSocketAddress source;

        private DatagramInterceptor(Promise<Void> received) {
            this.received = received;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            InetSocketAddress expected = source;
            if (expected != null && msg instanceof DatagramPacket && LoopbackShadowProbe.sameEndpoint(((DatagramPacket) msg).sender(), expected)) {
                ReferenceCountUtil.release(msg);
                received.trySuccess(null);
                return;
            }
            ctx.fireChannelRead(msg);
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

    private static InetAddress ipv4Loopback() {
        try {
            return Inet4Address.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
