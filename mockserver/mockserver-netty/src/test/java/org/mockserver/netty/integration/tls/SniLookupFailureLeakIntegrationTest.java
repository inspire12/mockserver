package org.mockserver.netty.integration.tls;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.Future;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.netty.integration.NettyBufferLeaks;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.mockserver.socket.tls.SniHandler;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * A ClientHello is held while the server certificate for it is looked up. When the lookup fails, or the server stops
 * while it is still running, the held ClientHello is released, and a connection already closed by a failed lookup
 * does not start a second one, or report a second failure, when its buffered ClientHello is decoded again at close.
 */
public class SniLookupFailureLeakIntegrationTest {

    private static final long TIMEOUT_SECONDS = 60;

    private final CountDownLatch releaseGeneration = new CountDownLatch(1);
    private final List<Future<SslContext>> lookups = new CopyOnWriteArrayList<>();
    private final CountDownLatch inactive = new CountDownLatch(1);
    private final AtomicInteger failuresReported = new AtomicInteger();
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private int leaksBefore;

    @Before
    public void recordLeaks() {
        leaksBefore = NettyBufferLeaks.recorded();
    }

    @After
    public void stop() throws InterruptedException {
        // the provisioning pool is shared by the whole JVM, so never leave one of its threads blocked
        releaseGeneration.countDown();
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS).await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS).await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    public void releasesTheClientHelloWhenTheServerStopsDuringCertificateGeneration() throws Exception {
        // given a certificate generation that does not finish until the server has stopped
        CountDownLatch generating = new CountDownLatch(1);
        NettySslContextFactory factory = mock(NettySslContextFactory.class);
        doAnswer(invocation -> {
            generating.countDown();
            releaseGeneration.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            throw new IllegalStateException("certificate generation failed");
        }).when(factory).createServerSslContext();
        int port = startServer(factory);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port));
            socket.getOutputStream().write(clientHello("leak.sni.test"));
            socket.getOutputStream().flush();
            assertThat("certificate generation started", generating.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));

            // when the server stops, then the generation fails
            assertThat("server stopped", workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS).await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
            releaseGeneration.countDown();
            awaitEveryLookupDone();
        }

        // then
        lookups.clear();
        NettyBufferLeaks.assertNoneSince(leaksBefore);
    }

    @Test
    public void startsNoSecondCertificateGenerationForAConnectionClosedByAFailedLookup() throws Exception {
        // given a certificate generation that fails at once
        AtomicInteger generations = new AtomicInteger();
        NettySslContextFactory factory = mock(NettySslContextFactory.class);
        doAnswer(invocation -> {
            generations.incrementAndGet();
            throw new IllegalStateException("certificate generation failed");
        }).when(factory).createServerSslContext();
        int port = startServer(factory);

        // when the failed lookup closes the connection
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port));
            socket.getOutputStream().write(clientHello("closed.sni.test"));
            socket.getOutputStream().flush();
            assertThat("connection closed by the server", inactive.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
            awaitEveryLookupDone();
        }

        // then
        assertThat("certificate generations", generations.get(), is(1));
        assertThat("failures reported on the connection", failuresReported.get(), is(1));
        lookups.clear();
        NettyBufferLeaks.assertNoneSince(leaksBefore);
    }

    @Test
    public void releasesTheClientHelloDecodedAgainAtCloseWhenTheServerStopsDuringItsLookup() throws Exception {
        // given a first certificate generation that fails at once, and any later one that does not finish until the server has stopped
        AtomicInteger generations = new AtomicInteger();
        NettySslContextFactory factory = mock(NettySslContextFactory.class);
        doAnswer(invocation -> {
            if (generations.incrementAndGet() > 1) {
                releaseGeneration.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            throw new IllegalStateException("certificate generation failed");
        }).when(factory).createServerSslContext();
        int port = startServer(factory);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port));
            socket.getOutputStream().write(clientHello("stopped.sni.test"));
            socket.getOutputStream().flush();
            assertThat("connection closed by the server", inactive.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));

            // when the server stops before any further lookup completes
            assertThat("server stopped", workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS).await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
            releaseGeneration.countDown();
            awaitEveryLookupDone();
        }

        // then
        lookups.clear();
        NettyBufferLeaks.assertNoneSince(leaksBefore);
    }

    private int startServer(NettySslContextFactory factory) throws InterruptedException {
        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Channel serverChannel = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel channel) {
                    channel.pipeline().addLast(new SniHandler(configuration(), factory) {
                        @Override
                        protected Future<SslContext> lookup(ChannelHandlerContext ctx, String hostname) {
                            Future<SslContext> lookup = super.lookup(ctx, hostname);
                            lookups.add(lookup);
                            return lookup;
                        }
                    });
                    channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            failuresReported.incrementAndGet();
                            ctx.close();
                        }

                        @Override
                        public void channelInactive(ChannelHandlerContext ctx) {
                            inactive.countDown();
                        }
                    });
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0))
            .sync()
            .channel();
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    private void awaitEveryLookupDone() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (lookups.isEmpty() || !lookups.stream().allMatch(Future::isDone)) {
            assertThat("every lookup completed in time", System.nanoTime() < deadline, is(true));
            Thread.sleep(10);
        }
    }

    private static byte[] clientHello(String serverName) throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, null, null);
        SSLEngine engine = sslContext.createSSLEngine(serverName, 443);
        engine.setUseClientMode(true);
        SSLParameters parameters = engine.getSSLParameters();
        parameters.setServerNames(List.of(new SNIHostName(serverName)));
        engine.setSSLParameters(parameters);
        engine.beginHandshake();
        ByteBuffer out = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        engine.wrap(ByteBuffer.allocate(0), out);
        out.flip();
        byte[] bytes = new byte[out.remaining()];
        out.get(bytes);
        return bytes;
    }
}
