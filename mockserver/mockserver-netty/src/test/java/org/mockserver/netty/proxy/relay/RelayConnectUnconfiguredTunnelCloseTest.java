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
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * A CONNECT/SOCKS tunnel that has been established but has carried nothing yet has no relay handlers: they are added
 * once the client's first bytes show which protocol it speaks. Until then each leg must still close with the other, or
 * a client that connects and leaves holds a loopback connection open for good, and each leg's exceptions must still be
 * logged in MockServer's log, not left to reach the end of its pipeline, where Netty logs them through its own.
 * <p>
 * A real {@link RelayConnectHandler} over loopback sockets, with a socket standing in for MockServer's side of the
 * loopback.
 */
public class RelayConnectUnconfiguredTunnelCloseTest {

    private static final String EVENT_LOOP = RelayConnectUnconfiguredTunnelCloseTest.class.getSimpleName() + "-eventLoop";
    private static final String NETTYS_PIPELINE_LOGGER = "io.netty.channel.DefaultChannelPipeline";
    private static final String REACHED_THE_END = "reached at the tail of the pipeline";

    private EventLoopGroup eventLoopGroup;
    private Channel proxyServerChannel;
    private final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private final MockServerLogger mockServerLogger = new MockServerLogger(configuration().logLevel("DEBUG"), RelayConnectHandler.class) {
        @Override
        public void logEvent(LogEntry logEntry) {
            if (isEnabledForInstance(logEntry.getLogLevel())) {
                logged.add(logEntry);
            }
        }
    };
    private final List<String> reachedTheEndOfAPipeline = new CopyOnWriteArrayList<>();
    private final Handler nettysLogCapture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            // only this class's event loops: other classes' servers may still be running in this JVM
            if (String.valueOf(record.getMessage()).contains(REACHED_THE_END) && Thread.currentThread().getName().contains(EVENT_LOOP)) {
                reachedTheEndOfAPipeline.add(String.valueOf(record.getThrown()));
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    // held here: java.util.logging keeps only a weak reference to a logger, and the handler would go with it
    private Logger nettysPipelineLogger;

    @Before
    public void createEventLoop() throws Exception {
        eventLoopGroup = new NioEventLoopGroup(2, new Scheduler.SchedulerThreadFactory(EVENT_LOOP));
        nettysPipelineLogger = Logger.getLogger(NETTYS_PIPELINE_LOGGER);
        nettysPipelineLogger.addHandler(nettysLogCapture);
        assertThatTheCaptureSeesWhatReachesTheEndOfAPipeline();
    }

    @After
    public void stopEventLoop() {
        try {
            if (proxyServerChannel != null) {
                proxyServerChannel.close().syncUninterruptibly();
            }
            eventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        } finally {
            nettysPipelineLogger.removeHandler(nettysLogCapture);
        }
    }

    /**
     * Without this the tests would pass for as long as Netty logged somewhere this capture does not look.
     */
    private void assertThatTheCaptureSeesWhatReachesTheEndOfAPipeline() throws Exception {
        Channel unconnected = eventLoopGroup.register(new NioSocketChannel()).sync().channel();
        try {
            unconnected.pipeline().fireExceptionCaught(new IllegalStateException("capture check"));
            unconnected.eventLoop().submit(() -> {
            }).get(10, SECONDS);
            assertThat(reachedTheEndOfAPipeline, contains("java.lang.IllegalStateException: capture check"));
        } finally {
            unconnected.close().sync();
            reachedTheEndOfAPipeline.clear();
        }
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

    @Test(timeout = 30000)
    public void shouldLogAClientThatResetsTheTunnelBeforeSendingAnythingOnceBelowAWarning() throws Exception {
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

            InetSocketAddress client;
            try (Socket proxyClient = new Socket(InetAddress.getLoopbackAddress(), proxyPort())) {
                proxyClient.setSoTimeout(10_000);
                client = (InetSocketAddress) proxyClient.getLocalSocketAddress();
                assertThat(readUntil(proxyClient.getInputStream(), "\r\n\r\n"), containsString("200"));
                // closed with a reset
                proxyClient.setSoLinger(true, 0);
            }

            assertThat("the loopback was closed", loopbackRead.get(10, SECONDS), is(-1));
            assertLoggedOnceBelowAWarning("client", client);
        }
    }

    @Test(timeout = 30000)
    public void shouldLogALoopbackResetBeforeTheClientSendsAnythingOnceBelowAWarning() throws Exception {
        try (ServerSocket loopbackServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            CountDownLatch tunnelEstablished = new CountDownLatch(1);
            CompletableFuture<InetSocketAddress> loopbackReset = CompletableFuture.supplyAsync(() -> {
                try (Socket loopback = acceptAndAcknowledge(loopbackServer)) {
                    tunnelEstablished.await(10, SECONDS);
                    loopback.setSoLinger(true, 0);
                    return (InetSocketAddress) loopback.getLocalSocketAddress();
                } catch (IOException | InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
            startProxy(loopbackServer);

            try (Socket proxyClient = new Socket(InetAddress.getLoopbackAddress(), proxyPort())) {
                proxyClient.setSoTimeout(10_000);
                assertThat(readUntil(proxyClient.getInputStream(), "\r\n\r\n"), containsString("200"));
                tunnelEstablished.countDown();
                InetSocketAddress loopback = loopbackReset.get(10, SECONDS);

                assertThat("the client's leg was closed", proxyClient.getInputStream().read(), is(-1));
                assertLoggedOnceBelowAWarning("loopback", loopback);
            }
        }
    }

    /**
     * Once the first bytes have set the relay up, its own handlers take each leg's exceptions, as before.
     */
    @Test(timeout = 30000)
    public void shouldLeaveTheExceptionsOfATunnelThatHasCarriedARequestToTheRelay() throws Exception {
        try (ServerSocket loopbackServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            CountDownLatch requestRelayed = new CountDownLatch(1);
            CompletableFuture<Integer> loopbackReadAfterTheRequest = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                try (Socket loopback = acceptAndAcknowledge(loopbackServer)) {
                    readUntil(loopback.getInputStream(), "\r\n\r\n");
                    requestRelayed.countDown();
                    loopbackReadAfterTheRequest.complete(loopback.getInputStream().read());
                } catch (IOException e) {
                    loopbackReadAfterTheRequest.completeExceptionally(e);
                }
            });
            startProxy(loopbackServer);

            InetSocketAddress client;
            try (Socket proxyClient = new Socket(InetAddress.getLoopbackAddress(), proxyPort())) {
                proxyClient.setSoTimeout(10_000);
                client = (InetSocketAddress) proxyClient.getLocalSocketAddress();
                assertThat(readUntil(proxyClient.getInputStream(), "\r\n\r\n"), containsString("200"));
                proxyClient.getOutputStream().write("GET /relayed HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                proxyClient.getOutputStream().flush();
                assertThat("the relay's handlers relayed the request", requestRelayed.await(10, SECONDS), is(true));
                proxyClient.setSoLinger(true, 0);
            }

            assertThat("the loopback was closed", loopbackReadAfterTheRequest.get(10, SECONDS), is(-1));
            eventLoopGroup.submit(() -> {
            }).get(10, SECONDS);
            assertThat(entriesAbout(client), is(empty()));
            assertThat(reachedTheEndOfAPipeline, is(empty()));
        }
    }

    private void assertLoggedOnceBelowAWarning(String leg, InetSocketAddress peer) throws Exception {
        long deadline = System.nanoTime() + SECONDS.toNanos(10);
        while (entriesAbout(peer).isEmpty() && System.nanoTime() < deadline) {
            MILLISECONDS.sleep(20);
        }
        // the leg's event loop has run whatever it had queued when the entry was logged
        eventLoopGroup.submit(() -> {
        }).get(10, SECONDS);
        List<LogEntry> entries = entriesAbout(peer);
        assertThat(entries, hasSize(1));
        assertThat(entries.get(0).getLogLevel(), is(org.slf4j.event.Level.DEBUG));
        assertThat(entries.get(0).getMessageFormat(), is("tunnel's{}connection from:{}closed by its peer before its relay was set up:{}"));
        assertThat(entries.get(0).getArguments()[0], is(leg));
        assertThat(entries.get(0).getThrowable(), is(nullValue()));
        assertThat(reachedTheEndOfAPipeline, is(empty()));
        assertThat(logged.stream().filter(entry -> entry.getLogLevel().toInt() >= org.slf4j.event.Level.WARN.toInt()).map(LogEntry::getMessageFormat).collect(Collectors.toList()), is(empty()));
    }

    private List<LogEntry> entriesAbout(InetSocketAddress peer) {
        return logged.stream()
            .filter(entry -> entry.getArguments() != null && Arrays.asList(entry.getArguments()).contains(peer))
            .collect(Collectors.toList());
    }

    private void startProxy(ServerSocket loopbackServer) {
        proxyServerChannel = new ServerBootstrap()
            .group(eventLoopGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    PortUnificationHandler.deferTlsDetection(ch);
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) {
                            ctx.fireChannelActive();
                            // as the proxy client's CONNECT or SOCKS request
                            ctx.fireChannelRead("CONNECT");
                        }
                    });
                    ch.pipeline().addLast(new TestRelayConnectHandler(mockServerLogger, "localhost", loopbackServer.getLocalPort()));
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

        TestRelayConnectHandler(MockServerLogger mockServerLogger, String host, int port) {
            super(configuration(), RelayLoopbackServer.listeningOn(port), mockServerLogger, host, port);
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
