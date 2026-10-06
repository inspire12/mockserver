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
import org.mockserver.socket.LingeringClose;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;

/**
 * A whole HTTP/2 request sent as the first bytes of a CONNECT/SOCKS tunnel by a client that then closes its connection.
 * The relay's handlers are installed while those bytes are being read, and the relay answers the client's preface with
 * its own settings, which a client that has gone resets.
 * <p>
 * A real {@link RelayConnectHandler} over loopback sockets, with a socket standing in for MockServer's side of the
 * loopback.
 */
public class RelayConnectFirstBytesTest {

    private static final byte[] HTTP2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HTTP2_EMPTY_SETTINGS = {0, 0, 0, 4, 0, 0, 0, 0, 0};
    private static final int BODY_BYTES = 16_000;
    private static final String TUNNEL_ESTABLISHED = "HTTP/1.1 200 Connection established\r\n\r\n";

    private EventLoopGroup eventLoopGroup;
    private Channel proxyServerChannel;

    @Before
    public void createEventLoop() {
        eventLoopGroup = new NioEventLoopGroup(2, new Scheduler.SchedulerThreadFactory(RelayConnectFirstBytesTest.class.getSimpleName() + "-eventLoop"));
    }

    @After
    public void stopEventLoop() {
        if (proxyServerChannel != null) {
            proxyServerChannel.close().syncUninterruptibly();
        }
        eventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    @Test(timeout = 30000)
    public void shouldRelayARequestSentWithTheFirstBytesOfTheTunnelByAClientThatLeavesAtOnce() throws Exception {
        try (ServerSocket loopbackServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            startProxy(loopbackServer);
            CompletableFuture<Void> clientLeft = sendRequestAsFirstBytesAndLeave();

            try (Socket loopback = acceptAndAcknowledge(loopbackServer)) {
                clientLeft.get(10, SECONDS);
                byte[] relayed = readUntilEndOfStream(loopback.getInputStream());

                assertThat("the relay's own preface", Arrays.copyOf(relayed, HTTP2_PREFACE.length), is(HTTP2_PREFACE));
                assertThat("the whole request body was relayed", bodyBytesIn(relayed), greaterThanOrEqualTo(BODY_BYTES));
            }
        }
    }

    @Test(timeout = 30000)
    public void shouldLeaveTheLoopbackOpenForMockServerToFinishReadingOnceTheClientHasLeft() throws Exception {
        try (ServerSocket loopbackServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            startProxy(loopbackServer);
            CompletableFuture<Void> clientLeft = sendRequestAsFirstBytesAndLeave();

            try (Socket loopback = acceptAndAcknowledge(loopbackServer)) {
                clientLeft.get(10, SECONDS);
                // only the relay's preface is taken before MockServer writes, as when its request arrives later
                readBytes(loopback.getInputStream(), HTTP2_PREFACE.length);
                awaitEverythingElseSent(loopback);

                // a socket the relay had closed answers the first of these with a reset, which fails the second
                loopback.getOutputStream().write(HTTP2_EMPTY_SETTINGS);
                loopback.getOutputStream().flush();
                Thread.sleep(200);
                loopback.getOutputStream().write(HTTP2_EMPTY_SETTINGS);
                loopback.getOutputStream().flush();

                assertThat("the request is still there to read", bodyBytesIn(readUntilEndOfStream(loopback.getInputStream())), greaterThanOrEqualTo(BODY_BYTES));
            }
        }
    }

    @Test(timeout = 30000)
    public void shouldCloseTheLoopbackWhenMockServerDoesNotCloseItsSide() throws Exception {
        try (ServerSocket loopbackServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            startProxy(loopbackServer);
            CompletableFuture<Void> clientLeft = sendRequestAsFirstBytesAndLeave();

            try (Socket loopback = acceptAndAcknowledge(loopbackServer)) {
                clientLeft.get(10, SECONDS);
                readUntilEndOfStream(loopback.getInputStream());
                long outputEnded = System.nanoTime();

                long closedAfterMillis = millisUntilAWriteFails(loopback, outputEnded, LingeringClose.LINGER_MILLIS + 10_000);

                assertThat("not before MockServer has had time to read to the end", closedAfterMillis, greaterThanOrEqualTo(LingeringClose.LINGER_MILLIS - 500));
                assertThat("and not left open for good", closedAfterMillis, lessThan(LingeringClose.LINGER_MILLIS + 10_000));
            }
        }
    }

    /**
     * Writes until a write fails, which is how this side sees that the relay has closed its socket: the write after the
     * close is answered with a reset, and the one after that fails.
     */
    private static long millisUntilAWriteFails(Socket loopback, long sinceNanos, long giveUpAfterMillis) throws InterruptedException {
        while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sinceNanos) < giveUpAfterMillis) {
            try {
                loopback.getOutputStream().write(HTTP2_EMPTY_SETTINGS);
                loopback.getOutputStream().flush();
            } catch (IOException closed) {
                break;
            }
            Thread.sleep(100);
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sinceNanos);
    }

    /**
     * As a client that has the tunnel and writes its preface, its settings and one whole POST in a single write, then
     * closes with nothing left unread, so that it sends a FIN and not a reset.
     */
    private CompletableFuture<Void> sendRequestAsFirstBytesAndLeave() {
        return CompletableFuture.runAsync(() -> {
            try (Socket proxyClient = new Socket(InetAddress.getLoopbackAddress(), proxyPort())) {
                proxyClient.setSoTimeout(10_000);
                assertThat(new String(readBytes(proxyClient.getInputStream(), TUNNEL_ESTABLISHED.length()), StandardCharsets.US_ASCII), containsString("200"));
                proxyClient.getOutputStream().write(http2Request());
                proxyClient.getOutputStream().flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static byte[] http2Request() {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(HTTP2_PREFACE, 0, HTTP2_PREFACE.length);
        request.write(HTTP2_EMPTY_SETTINGS, 0, HTTP2_EMPTY_SETTINGS.length);
        // HEADERS on stream 1 with END_HEADERS: POST, http and "/" from the HPACK static table, and the authority "a"
        byte[] headers = {(byte) 0x83, (byte) 0x86, (byte) 0x84, 0x01, 0x01, 'a'};
        request.write(new byte[]{0, 0, (byte) headers.length, 1, 4, 0, 0, 0, 1}, 0, 9);
        request.write(headers, 0, headers.length);
        // one DATA frame with END_STREAM
        request.write(new byte[]{0, (byte) (BODY_BYTES >> 8), (byte) BODY_BYTES, 0, 1, 0, 0, 0, 1}, 0, 9);
        byte[] body = new byte[BODY_BYTES];
        Arrays.fill(body, (byte) 'x');
        request.write(body, 0, body.length);
        return request.toByteArray();
    }

    /**
     * The body is all of one byte, of which the frames around it hold at most a few.
     */
    private static int bodyBytesIn(byte[] relayed) {
        int count = 0;
        for (byte b : relayed) {
            if (b == 'x') {
                count++;
            }
        }
        return count;
    }

    /**
     * Waits for the relay's FIN to arrive behind what is still unread, without reading any of it.
     */
    private static void awaitEverythingElseSent(Socket loopback) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (loopback.getInputStream().available() < BODY_BYTES && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat("the request has been relayed", loopback.getInputStream().available(), greaterThan(BODY_BYTES - 1));
        // the relay ends the leg as soon as it has flushed the request, in the same pass of its event loop
        Thread.sleep(500);
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
        String preamble = RelayConnectHandler.PROXIED + "localhost:" + loopbackServer.getLocalPort();
        assertThat(new String(readBytes(loopback.getInputStream(), preamble.length()), StandardCharsets.US_ASCII), is(preamble));
        loopback.getOutputStream().write(RelayConnectHandler.PROXIED_RESPONSE.getBytes(StandardCharsets.US_ASCII));
        loopback.getOutputStream().flush();
        return loopback;
    }

    private static byte[] readUntilEndOfStream(InputStream in) throws IOException {
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = in.read(buffer)) != -1; ) {
            read.write(buffer, 0, count);
        }
        return read.toByteArray();
    }

    private static byte[] readBytes(InputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        for (int read = 0; read < length; ) {
            int count = in.read(bytes, read, length - read);
            if (count == -1) {
                throw new IOException("closed after " + read + " of " + length + " bytes");
            }
            read += count;
        }
        return bytes;
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
            return Unpooled.copiedBuffer(TUNNEL_ESTABLISHED, StandardCharsets.US_ASCII);
        }

        @Override
        protected Object failureResponse(Object request) {
            return Unpooled.copiedBuffer("HTTP/1.1 502 Bad Gateway\r\n\r\n", StandardCharsets.US_ASCII);
        }
    }
}
