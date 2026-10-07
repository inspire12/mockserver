package org.mockserver.lifecycle;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.testing.socket.TestPortFactory.findFreeUdpPort;

/**
 * The check a UDP listener makes after its bind: a datagram sent to {@code 127.0.0.1} on its port must reach it, not
 * another socket that took the port on IPv4 between the probe before the bind and the bind itself.
 */
public class Ipv4UdpLoopbackProbeTest {

    private static final long DEADLINE_MILLIS = 5000;

    private static EventLoopGroup group;

    @BeforeClass
    public static void createEventLoopGroup() {
        group = new NioEventLoopGroup(1);
    }

    @AfterClass
    public static void shutdownEventLoopGroup() {
        group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    public void shouldFindThatTheLoopbackReachesAnUncontestedWildcardChannelAndPassNoProbeOn() throws Exception {
        AtomicInteger datagrams = new AtomicInteger();
        Channel server = bindWildcard(datagrams);
        try {
            assertThat(Ipv4UdpPortProbe.loopbackReachesAnotherSocket(server, true), is(false));

            assertThat("the probe datagram reached the listener's own handlers", datagrams.get(), is(0));
            assertThat(server.pipeline().get(Ipv4UdpPortProbe.HANDLER_NAME), is(nullValue()));
            try (DatagramChannel client = DatagramChannel.open(StandardProtocolFamily.INET)) {
                client.send(ByteBuffer.wrap("after".getBytes(StandardCharsets.US_ASCII)), new InetSocketAddress("127.0.0.1", port(server)));
                long deadline = System.currentTimeMillis() + DEADLINE_MILLIS;
                while (datagrams.get() == 0 && System.currentTimeMillis() < deadline) {
                    TimeUnit.MILLISECONDS.sleep(10);
                }
            }
            assertThat("a datagram sent after the check", datagrams.get(), is(1));
        } finally {
            server.close().syncUninterruptibly();
        }
    }

    @Test
    public void shouldFindThatTheLoopbackReachesAnotherSocket() throws Exception {
        AtomicInteger datagrams = new AtomicInteger();
        Channel server = bindWildcard(datagrams);
        try (DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET)) {
            otherApplication.bind(new InetSocketAddress("127.0.0.1", 0));

            assertThat(Ipv4UdpPortProbe.reachesAnotherSocket(server, (InetSocketAddress) otherApplication.getLocalAddress()), is(true));

            otherApplication.configureBlocking(false);
            SocketAddress probeSource = null;
            long deadline = System.currentTimeMillis() + DEADLINE_MILLIS;
            while (probeSource == null && System.currentTimeMillis() < deadline) {
                probeSource = otherApplication.receive(ByteBuffer.allocate(512));
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertThat("the other socket received the probe", probeSource != null, is(true));
            assertThat(datagrams.get(), is(0));
            assertThat(server.pipeline().get(Ipv4UdpPortProbe.HANDLER_NAME), is(nullValue()));
        } finally {
            server.close().syncUninterruptibly();
        }
    }

    // a listener that reads nothing looks as if another socket took the probe, which shows whether the check ran
    @Test
    public void shouldCheckOnlyWhereADualStackBindCanShareAPortWithAnIpv4Socket() throws Exception {
        Channel server = bindWildcard(new AtomicInteger());
        try {
            server.config().setAutoRead(false);

            assertThat("checked", Ipv4UdpPortProbe.loopbackReachesAnotherSocket(server, true), is(true));
            assertThat("not checked", Ipv4UdpPortProbe.loopbackReachesAnotherSocket(server, false), is(false));
            assertThat("checked on macOS only", Ipv4UdpPortProbe.DUAL_STACK_BIND_CAN_SHARE_AN_IPV4_PORT,
                is(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")));
        } finally {
            server.close().syncUninterruptibly();
        }
    }

    // a dual-stack socket that does not take IPv4 traffic (IPV6_V6ONLY) must not be refused for it
    @Test
    public void shouldNotReportAPortNothingListensOnAsReachingAnotherSocket() throws Exception {
        Channel server = bindWildcard(new AtomicInteger());
        try {
            assertThat(Ipv4UdpPortProbe.reachesAnotherSocket(server, new InetSocketAddress("127.0.0.1", findFreeUdpPort())), is(false));
        } finally {
            server.close().syncUninterruptibly();
        }
    }

    @Test
    public void shouldNotReportAnotherSocketWhenTheEventLoopIsStalledBeforeTheCheck() throws Exception {
        assertInconclusiveWhileStalled(0);
    }

    @Test
    public void shouldNotReportAnotherSocketWhenTheEventLoopStallsDuringTheCheck() throws Exception {
        assertInconclusiveWhileStalled(50);
    }

    /**
     * The probe goes to another socket, which would report it taken; but a datagram a stalled event loop did not read
     * is no evidence of that, so the check is inconclusive.
     */
    private static void assertInconclusiveWhileStalled(long stallAfterMillis) throws Exception {
        EventLoopGroup stalling = new NioEventLoopGroup(1);
        CountDownLatch release = new CountDownLatch(1);
        try (DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET)) {
            otherApplication.bind(new InetSocketAddress("127.0.0.1", 0));
            Channel server = bindWildcard(stalling, new AtomicInteger());
            try {
                server.eventLoop().schedule(() -> {
                    release.await(DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
                    return null;
                }, stallAfterMillis, TimeUnit.MILLISECONDS);
                if (stallAfterMillis == 0) {
                    TimeUnit.MILLISECONDS.sleep(50);
                }

                assertThat(Ipv4UdpPortProbe.reachesAnotherSocket(server, (InetSocketAddress) otherApplication.getLocalAddress()), is(false));
            } finally {
                release.countDown();
                server.close().syncUninterruptibly();
            }
        } finally {
            stalling.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private static Channel bindWildcard(AtomicInteger datagrams) {
        return bindWildcard(group, datagrams);
    }

    /**
     * A dual-stack listener on every address, as the HTTP/3 and DNS listeners bind, counting the datagrams its own
     * handlers receive.
     */
    private static Channel bindWildcard(EventLoopGroup eventLoopGroup, AtomicInteger datagrams) {
        Bootstrap bootstrap = new Bootstrap()
            .group(eventLoopGroup)
            .channel(NioDatagramChannel.class)
            .handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                            datagrams.incrementAndGet();
                        }
                    });
                }
            });
        Throwable lastFailure = null;
        // a found port can be taken before it is bound
        for (int attempt = 0; attempt < 5; attempt++) {
            ChannelFuture bind = bootstrap.bind(new InetSocketAddress(findFreeUdpPort())).awaitUninterruptibly();
            if (bind.isSuccess()) {
                return bind.channel();
            }
            lastFailure = bind.cause();
        }
        throw new IllegalStateException("could not bind a listener on any of 5 found UDP ports", lastFailure);
    }

    private static int port(Channel channel) {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }
}
