package org.mockserver.httpclient;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.mockserver.socket.NettyAllocator;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;

/**
 * The upstream connection of a binary connection that keeps one for its life: made directly, on the event loop it
 * is given, and not made at all when it would go around an upstream proxy or to an address that is blocked.
 */
public class NettyHttpClientBinaryRelayConnectTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static EventLoopGroup clientConnections;

    @BeforeClass
    public static void startGroup() {
        clientConnections = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopGroup() {
        clientConnections.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }

    private static NettyHttpClient client(Configuration configuration, List<ProxyConfiguration> proxyConfigurations, Supplier<EventLoopGroup> forwardClientGroup) {
        return new NettyHttpClient(configuration, new MockServerLogger(), forwardClientGroup, proxyConfigurations, true, null);
    }

    private static final ChannelHandler NOTHING = new SharableNothing();

    @ChannelHandler.Sharable
    private static final class SharableNothing extends ChannelInboundHandlerAdapter {
    }

    @Test
    public void shouldConnectDirectlyOnTheGivenEventLoopWithTheHandlerItIsGiven() throws Exception {
        AtomicInteger forwardClientGroupsAskedFor = new AtomicInteger();
        CompletableFuture<String> received = new CompletableFuture<>();
        EventLoop eventLoop = clientConnections.next();
        try (ServerSocket upstream = new ServerSocket(0, 1, LOOPBACK)) {
            ChannelFuture connect = client(configuration(), null, () -> {
                forwardClientGroupsAskedFor.incrementAndGet();
                return clientConnections;
            }).connectBinaryRelay(eventLoop, new InetSocketAddress(LOOPBACK, upstream.getLocalPort()), new SimpleChannelInboundHandler<ByteBuf>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, ByteBuf bytes) {
                    received.complete(bytes.toString(StandardCharsets.UTF_8));
                }
            });
            Channel channel = connect.channel();
            try (Socket accepted = upstream.accept()) {
                assertThat(connect.await(10, TimeUnit.SECONDS), is(true));
                assertThat(connect.isSuccess(), is(true));
                assertThat("both legs of the relay run on one thread", channel.eventLoop(), is(sameInstance(eventLoop)));
                assertThat("the forward client's own threads are not started for it", forwardClientGroupsAskedFor.get(), is(0));
                assertThat(channel.config().getOption(ChannelOption.ALLOCATOR), is(sameInstance(NettyAllocator.ALLOCATOR)));
                assertThat(channel.config().getWriteBufferHighWaterMark(), is(32 * 1024));
                assertThat(channel.config().getWriteBufferLowWaterMark(), is(8 * 1024));

                channel.writeAndFlush(Unpooled.copiedBuffer("to the upstream", StandardCharsets.UTF_8)).sync();
                accepted.setSoTimeout(10_000);
                assertThat(new String(accepted.getInputStream().readNBytes("to the upstream".length()), StandardCharsets.UTF_8), is("to the upstream"));
                accepted.getOutputStream().write("from the upstream".getBytes(StandardCharsets.UTF_8));
                accepted.getOutputStream().flush();
                assertThat("in the clear, with nothing between the socket and the handler", received.get(10, TimeUnit.SECONDS), is("from the upstream"));
            } finally {
                channel.close().sync();
            }
        }
    }

    @Test
    public void shouldUseTheSocketConnectionTimeout() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 1, LOOPBACK)) {
            ChannelFuture connect = client(configuration().socketConnectionTimeoutInMillis(4_321L), null, null)
                .connectBinaryRelay(clientConnections.next(), new InetSocketAddress(LOOPBACK, upstream.getLocalPort()), NOTHING);
            try {
                assertThat(connect.channel().config().getConnectTimeoutMillis(), is(4_321));
            } finally {
                connect.channel().close().sync();
            }
        }
    }

    @Test
    public void shouldNotConnectAroundAnUpstreamProxy() throws Exception {
        for (ProxyConfiguration.Type type : ProxyConfiguration.Type.values()) {
            try (ServerSocket upstream = new ServerSocket(0, 1, LOOPBACK); ServerSocket proxy = new ServerSocket(0, 1, LOOPBACK)) {
                upstream.setSoTimeout(200);
                proxy.setSoTimeout(200);
                NettyHttpClient client = client(configuration(), Collections.singletonList(proxyConfiguration(type, new InetSocketAddress(LOOPBACK, proxy.getLocalPort()))), null);

                IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> client.connectBinaryRelay(clientConnections.next(), new InetSocketAddress(LOOPBACK, upstream.getLocalPort()), NOTHING));

                assertThat(type.name(), refused.getMessage(), containsString("an upstream proxy is configured"));
                assertThrows(type + ": nothing connects to the upstream", java.net.SocketTimeoutException.class, upstream::accept);
                assertThrows(type + ": nor to the proxy", java.net.SocketTimeoutException.class, proxy::accept);
            }
        }
    }

    @Test
    public void shouldNotConnectToAnAddressThatMayNotBeForwardedTo() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 1, LOOPBACK)) {
            upstream.setSoTimeout(200);
            NettyHttpClient client = client(configuration().forwardProxyBlockPrivateNetworks(true), null, null);

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> client.connectBinaryRelay(clientConnections.next(), new InetSocketAddress(LOOPBACK, upstream.getLocalPort()), NOTHING));

            assertThat(refused.getMessage(), containsString("loopback"));
            assertThrows("nothing connects to the upstream", java.net.SocketTimeoutException.class, upstream::accept);
        }
    }
}
