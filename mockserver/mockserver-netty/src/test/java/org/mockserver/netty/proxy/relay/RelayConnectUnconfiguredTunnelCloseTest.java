package org.mockserver.netty.proxy.relay;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.unification.PortUnificationHandler;
import org.mockserver.scheduler.Scheduler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;

/**
 * A CONNECT/SOCKS tunnel that has been established but has carried nothing yet has no relay handlers: they are added
 * once the client's first bytes show which protocol it speaks. Until then each leg must still close with the other, or
 * a client that connects and leaves holds a loopback connection open for good.
 * <p>
 * A real {@link RelayConnectHandler} over loopback sockets, with a socket standing in for MockServer's side of the
 * loopback.
 */
public class RelayConnectUnconfiguredTunnelCloseTest {

    private EventLoopGroup eventLoopGroup;
    private Channel proxyServerChannel;

    @Before
    public void createEventLoop() {
        eventLoopGroup = new NioEventLoopGroup(2, new Scheduler.SchedulerThreadFactory(RelayConnectUnconfiguredTunnelCloseTest.class.getSimpleName() + "-eventLoop"));
    }

    @After
    public void stopEventLoop() {
        if (proxyServerChannel != null) {
            proxyServerChannel.close().syncUninterruptibly();
        }
        eventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    @Test(timeout = 30000)
    public void shouldCloseTheLoopbackWhenTheClientLeavesBeforeSendingAnything() throws Exception {
        try (ServerSocket loopbackServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Integer> loopbackRead = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                try (Socket loopback = acceptAndAcknowledge(loopbackServer)) {
                    loopbackRead.complete(loopback.getInputStream().read());
                } catch (IOException e) {
                    loopbackRead.completeExceptionally(e);
                }
            });
            startProxy(loopbackServer);

            try (Socket proxyClient = new Socket(InetAddress.getLoopbackAddress(), proxyPort())) {
                proxyClient.setSoTimeout(10_000);
                assertThat(readUntil(proxyClient.getInputStream(), "\r\n\r\n"), containsString("200"));
            }

            assertThat("the loopback was closed", loopbackRead.get(10, SECONDS), is(-1));
        }
    }

    @Test(timeout = 30000)
    public void shouldCloseTheClientsLegWhenTheLoopbackClosesBeforeTheClientSendsAnything() throws Exception {
        try (ServerSocket loopbackServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            CountDownLatch tunnelEstablished = new CountDownLatch(1);
            CompletableFuture<Void> loopbackClosed = CompletableFuture.runAsync(() -> {
                try (Socket loopback = acceptAndAcknowledge(loopbackServer)) {
                    tunnelEstablished.await(10, SECONDS);
                } catch (IOException | InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
            startProxy(loopbackServer);

            try (Socket proxyClient = new Socket(InetAddress.getLoopbackAddress(), proxyPort())) {
                proxyClient.setSoTimeout(10_000);
                assertThat(readUntil(proxyClient.getInputStream(), "\r\n\r\n"), containsString("200"));
                tunnelEstablished.countDown();
                loopbackClosed.get(10, SECONDS);

                assertThat("the client's leg was closed", proxyClient.getInputStream().read(), is(-1));
            }
        }
    }

    private void startProxy(ServerSocket loopbackServer) {
        InetSocketAddress loopbackAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), loopbackServer.getLocalPort());
        proxyServerChannel = new ServerBootstrap()
            .group(eventLoopGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    ch.attr(REMOTE_SOCKET).set(loopbackAddress);
                    PortUnificationHandler.deferTlsDetection(ch);
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) {
                            ctx.fireChannelActive();
                            // as the proxy client's CONNECT or SOCKS request
                            ctx.fireChannelRead("CONNECT");
                        }
                    });
                    ch.pipeline().addLast(new TestRelayConnectHandler("localhost", loopbackAddress.getPort()));
                }
            })
            .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            .syncUninterruptibly()
            .channel();
    }

    private int proxyPort() {
        return ((InetSocketAddress) proxyServerChannel.localAddress()).getPort();
    }

    /**
     * MockServer's side of the loopback: acknowledges the PROXIED preamble, which establishes the tunnel.
     */
    private static Socket acceptAndAcknowledge(ServerSocket loopbackServer) throws IOException {
        Socket loopback = loopbackServer.accept();
        loopback.setSoTimeout(10_000);
        readUntil(loopback.getInputStream(), "localhost:" + loopbackServer.getLocalPort());
        loopback.getOutputStream().write(RelayConnectHandler.PROXIED_RESPONSE.getBytes(StandardCharsets.US_ASCII));
        loopback.getOutputStream().flush();
        return loopback;
    }

    private static String readUntil(InputStream in, String marker) throws IOException {
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            read.write(b);
            if (read.toString(StandardCharsets.US_ASCII.name()).endsWith(marker)) {
                break;
            }
        }
        return read.toString(StandardCharsets.US_ASCII.name());
    }

    private static class TestRelayConnectHandler extends RelayConnectHandler<String> {

        TestRelayConnectHandler(String host, int port) {
            super(configuration(), null, new MockServerLogger(), host, port);
        }

        @Override
        protected void removeCodecSupport(ChannelHandlerContext ctx) {
            ctx.pipeline().remove(this);
        }

        @Override
        protected Object successResponse(Object request) {
            return Unpooled.copiedBuffer("HTTP/1.1 200 Connection established\r\n\r\n", StandardCharsets.US_ASCII);
        }

        @Override
        protected Object failureResponse(Object request) {
            return Unpooled.copiedBuffer("HTTP/1.1 502 Bad Gateway\r\n\r\n", StandardCharsets.US_ASCII);
        }
    }
}
