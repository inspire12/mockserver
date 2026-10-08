package org.mockserver.netty.proxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslHandler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.model.BinaryMessage;
import org.mockserver.netty.MockServerUnificationInitializer;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.ChannelReadPause;
import org.mockserver.socket.tls.NettySslContextFactory;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.netty.proxy.BinaryRequestProxyingHandler.MAX_WAITING_MESSAGES;
import static org.mockserver.testing.tls.SSLSocketFactory.sslSocketFactory;

/**
 * A binary connection whose client is being held back (too much waiting to be forwarded) and which then
 * upgrades to TLS: the hold taken before the upgrade is given up, the handshake goes through once reading
 * resumes, and holding back works the same over TLS, with every hold given up by the end.
 */
public class BinaryBackpressureAcrossTlsUpgradeTest {

    private static HttpState httpState;

    private final Configuration configuration = configuration().forwardBinaryRequestsUseSingleConnection(false).forwardBinaryRequestsWithoutWaitingForResponse(true);
    private final NettyHttpClient httpClient = mock(NettyHttpClient.class);
    private final List<Integer> forwarded = new CopyOnWriteArrayList<>();
    private final List<Boolean> forwardedOverTls = new CopyOnWriteArrayList<>();
    private final AtomicBoolean upstreamAccepts = new AtomicBoolean(false);
    private final AtomicReference<Consumer<Throwable>> notYetReportedSent = new AtomicReference<>();
    private final AtomicInteger delivered = new AtomicInteger();
    private final AtomicReference<Channel> connection = new AtomicReference<>();
    private int sent;

    @BeforeClass
    public static void startServerState() {
        httpState = new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class));
    }

    @AfterClass
    public static void stopServerState() {
        httpState.stop();
        httpState = null;
    }

    @Test
    public void shouldHoldAClientBackBeforeAndAfterItUpgradesToTls() throws Exception {
        when(httpClient.sendRequest(any(BinaryMessage.class), anyBoolean(), any(InetSocketAddress.class), any(), any()))
            .thenAnswer(invocation -> {
                forwarded.add(ByteBuffer.wrap(invocation.<BinaryMessage>getArgument(0).getBytes()).getInt());
                forwardedOverTls.add(invocation.getArgument(1));
                Consumer<Throwable> reportSent = invocation.getArgument(4);
                if (upstreamAccepts.get()) {
                    reportSent.accept(null);
                } else {
                    notYetReportedSent.set(reportSent);
                }
                return new CompletableFuture<BinaryMessage>();
            });
        HttpActionHandler actionHandler = mock(HttpActionHandler.class);
        when(actionHandler.getHttpClient()).thenReturn(httpClient);
        MockServerUnificationInitializer initializer = new MockServerUnificationInitializer(
            configuration, mock(LifeCycle.class), httpState, actionHandler, new NettySslContextFactory(configuration, new MockServerLogger(), true)
        );
        EventLoopGroup group = new NioEventLoopGroup(1);
        ExecutorService handshakes = Executors.newSingleThreadExecutor();
        try {
            Channel server = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childAttr(REMOTE_SOCKET, new InetSocketAddress("127.0.0.1", 1234))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        connection.set(channel);
                        channel.pipeline().addLast(initializer);
                        // after detection and TLS, before the handler that forwards: every binary message passes
                        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object message) {
                                if (message instanceof ByteBuf) {
                                    ctx.fireChannelRead(message);
                                    delivered.incrementAndGet();
                                } else {
                                    ctx.fireChannelRead(message);
                                }
                            }
                        });
                    }
                })
                .bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0)).sync().channel();
            try (Socket client = new Socket()) {
                client.setTcpNoDelay(true);
                client.setSoTimeout(20_000);
                client.connect(server.localAddress(), 10_000);

                // in the clear, with the upstream accepting nothing: one message is being forwarded, the rest wait
                sendUntilHeldBack(client);
                assertThat("held back once more than the limit wait", sent, is(MAX_WAITING_MESSAGES + 2));

                // the handshake cannot begin: its first record is not read while the client is held back
                Future<SSLSocket> handshake = handshakes.submit(() -> sslSocketFactory().wrapSocket(client));
                TimeUnit.MILLISECONDS.sleep(300);
                assertThat(handshake.isDone(), is(false));

                // the upstream accepts, the queue drains, reading resumes: the hold is given up and TLS begins
                upstreamAccepts.set(true);
                notYetReportedSent.getAndSet(null).accept(null);
                SSLSocket tls = handshake.get(30, TimeUnit.SECONDS);
                awaitHolds(0);
                assertThat(onEventLoop(() -> connection.get().pipeline().get(SslHandler.class)), is(notNullValue()));
                assertThat("everything sent in the clear was forwarded", forwarded.size(), is(sent));

                // over TLS, with the upstream accepting nothing again
                upstreamAccepts.set(false);
                int sentInTheClear = sent;
                sendUntilHeldBack(tls);
                assertThat("held back at the same point over TLS", sent - sentInTheClear, is(MAX_WAITING_MESSAGES + 2));

                upstreamAccepts.set(true);
                notYetReportedSent.getAndSet(null).accept(null);
                awaitHolds(0);
                assertThat(onEventLoop(() -> connection.get().config().isAutoRead()), is(true));
                send(tls);
                awaitDelivered();

                assertThat("nothing was lost", forwarded.size(), is(sent));
                for (int i = 0; i < sent; i++) {
                    assertThat("in the order sent", forwarded.get(i), is(i));
                    assertThat("message " + i + " goes upstream in the clear before the upgrade and over TLS after it", forwardedOverTls.get(i), is(i >= sentInTheClear));
                }
            }
            awaitHolds(0);
            server.close().sync();
        } finally {
            handshakes.shutdownNow();
            initializer.getMcpSessionManager().shutdown();
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    /** Each message is its position among those sent, so order and loss can both be seen. */
    private void send(Socket socket) throws IOException {
        socket.getOutputStream().write(ByteBuffer.allocate(8).putInt(sent++).array());
        socket.getOutputStream().flush();
    }

    private void awaitDelivered() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (delivered.get() < sent && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(1);
        }
        assertThat("message " + (sent - 1) + " was read within 20 seconds", delivered.get(), is(sent));
    }

    /**
     * Sends one message at a time, each only once the last has been read, so that when reading stops nothing
     * the client sent is left unread behind it.
     */
    private void sendUntilHeldBack(Socket socket) throws Exception {
        for (int i = 0; i < MAX_WAITING_MESSAGES * 3; i++) {
            send(socket);
            awaitDelivered();
            if (holds() == 1) {
                assertThat(onEventLoop(() -> connection.get().config().isAutoRead()), is(false));
                return;
            }
        }
        throw new AssertionError("the client was never held back");
    }

    private <T> T onEventLoop(java.util.concurrent.Callable<T> read) throws Exception {
        return connection.get().eventLoop().submit(read).get(10, TimeUnit.SECONDS);
    }

    private int holds() throws Exception {
        return onEventLoop(() -> ChannelReadPause.holds(connection.get()));
    }

    private void awaitHolds(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (holds() != expected && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertThat("holds on the connection's reads", holds(), is(expected));
    }
}
