package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3Headers;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.MockServer;
import org.mockserver.testing.socket.Ipv4DatagramChannelFactory;
import org.slf4j.event.Level;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * What the CONNECT-UDP relay logs: each entry goes to MockServer's log and event log, at its level, where it went to
 * the handler's own SLF4J logger before; a client that ends its tunnel by resetting the stream is {@code DEBUG}.
 * Skips when the native QUIC transport is not available, as the other HTTP/3 server tests do.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3ConnectUdpLoggingIntegrationTest {

    private static final long WAIT_SECONDS = 15;
    private static final String ESTABLISHED = "CONNECT-UDP tunnel from:{}established to:{}";
    private static final String RESET = "CONNECT-UDP stream of HTTP/3 connection from:{}closed or reset by its client:{}";
    private static final String RELAY_SOCKET_CLOSED = "CONNECT-UDP relay socket to:{}closed";
    private static final String TARGET_REFUSED = "CONNECT-UDP target from:{}refused:{}";
    private static final String FRAME_DROPPED = "CONNECT-UDP relay socket for tunnel from:{}is closed, dropping a DATA frame of:{}bytes";
    private static final String RELAY_SOCKET_FAILED = "CONNECT-UDP relay socket to:{}for HTTP/3 connection from:{}failed and is closed:{}";

    private final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private MockServer mockServer;
    private MockServerClient mockServerClient;
    private NioEventLoopGroup clientGroup;
    private NioEventLoopGroup echoGroup;
    private Channel udpEchoChannel;

    @Before
    public void setUp() {
        assumeQuicAvailable();
        clientGroup = new NioEventLoopGroup(1);
        // a clone: the event log clears the entry it is handed once it has copied it
        MockServerLogger.setGlobalLogEventListener(logEntry -> {
            if (String.valueOf(logEntry.getMessageFormat()).contains("CONNECT-UDP")) {
                logged.add(logEntry.clone());
            }
        });
    }

    @After
    public void tearDown() {
        try {
            stopQuietly(mockServerClient);
            stopQuietly(mockServer);
        } finally {
            MockServerLogger.setGlobalLogEventListener(null);
            if (udpEchoChannel != null) {
                udpEchoChannel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
            }
            if (echoGroup != null) {
                echoGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            }
            if (clientGroup != null) {
                clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void shouldLogATunnelInMockServersLogAndItsClientsResetBelowAWarning() throws Exception {
        int echoPort = startUdpEchoServer();
        start(configuration());

        try (Http3TestClient connection = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange tunnel = connection.start(connectUdp("127.0.0.1:" + echoPort));
            assertThat(tunnel.status(), is(200));
            tunnel.data("ping".getBytes(StandardCharsets.UTF_8));
            awaitReceived(tunnel, "ping");

            tunnel.reset(Http3ErrorCode.H3_REQUEST_CANCELLED);

            LogEntry reset = await(format(RESET));
            assertThat(reset.getLogLevel(), is(Level.DEBUG));
            assertThat(reset.getThrowable(), is(nullValue()));
            assertThat(String.valueOf(reset.getArguments()[0]), containsString(":" + connection.localPort()));
            assertThat(await(format(RELAY_SOCKET_CLOSED)).getLogLevel(), is(Level.DEBUG));

            LogEntry established = await(format(ESTABLISHED));
            assertThat(established.getLogLevel(), is(Level.INFO));
            assertThat(String.valueOf(established.getArguments()[0]), containsString(":" + connection.localPort()));
            assertThat(String.valueOf(established.getArguments()[1]), containsString("127.0.0.1:" + echoPort));
            List<LogEntry> warnings = logged.stream()
                .filter(entry -> entry.getLogLevel().toInt() >= Level.WARN.toInt())
                .collect(Collectors.toList());
            assertThat(warnings.toString(), warnings, empty());
        }
        assertThat("in the event log the dashboard shows", Arrays.asList(mockServerClient.retrieveLogMessagesArray(null)),
            hasItem(containsString("established to:")));
    }

    @Test
    public void shouldLogARefusedTargetAsAWarningInMockServersLog() throws Exception {
        int echoPort = startUdpEchoServer();
        start(configuration().http3ConnectUdpAllowedTargets("allowed.example.com:443"));

        try (Http3TestClient connection = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange tunnel = connection.start(connectUdp("127.0.0.1:" + echoPort));
            assertThat(tunnel.status(), is(403));

            LogEntry refused = await(format(TARGET_REFUSED));
            assertThat(refused.getLogLevel(), is(Level.WARN));
            assertThat(String.valueOf(refused.getArguments()[1]), is("not in http3ConnectUdpAllowedTargets: 127.0.0.1:" + echoPort));
        }
        assertThat("in the event log the dashboard shows", Arrays.asList(mockServerClient.retrieveLogMessagesArray(null)),
            hasItem(containsString("not in http3ConnectUdpAllowedTargets: 127.0.0.1:" + echoPort)));
    }

    @Test
    public void shouldLogARelaySocketTheTargetRefusesOnceAsAWarningWithoutAStackTrace() throws Exception {
        int closedPort;
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            closedPort = socket.getLocalPort();
        }
        start(configuration());

        try (Http3TestClient connection = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange tunnel = connection.start(connectUdp("127.0.0.1:" + closedPort));
            assertThat(tunnel.status(), is(200));

            // nothing listens on the target port, so its ICMP port unreachable fails the relay socket's next read
            tunnel.data("ping".getBytes(StandardCharsets.UTF_8));

            LogEntry failed = await(format(RELAY_SOCKET_FAILED));
            assertThat(failed.getLogLevel(), is(Level.WARN));
            assertThat(String.valueOf(failed.getArguments()[2]), containsString("PortUnreachableException"));
            assertThat(failed.getThrowable(), is(nullValue()));
            assertThat(String.valueOf(failed.getArguments()[1]), containsString(":" + connection.localPort()));

            tunnel.data("pong".getBytes(StandardCharsets.UTF_8));
            assertThat("a frame after the failure is dropped below a warning", await(format(FRAME_DROPPED)).getLogLevel(), is(Level.DEBUG));
            assertThat(logged.stream().filter(entry -> entry.getLogLevel().toInt() >= Level.WARN.toInt()).count(), is(1L));
        }
    }

    private void start(Configuration configuration) {
        // the test JVM defaults to ERROR, which would drop the entries this class asserts on
        mockServer = startWithHttp3(configuration.http3MaxIdleTimeout(30000L).http3ConnectUdpEnabled(true).logLevel("DEBUG"));
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
    }

    private Http3Headers connectUdp(String authority) {
        return new DefaultHttp3Headers().method("CONNECT").protocol("connect-udp").scheme("https").path("/").authority(authority);
    }

    private static Predicate<LogEntry> format(String messageFormat) {
        return entry -> messageFormat.equals(entry.getMessageFormat());
    }

    private LogEntry await(Predicate<LogEntry> matching) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            for (LogEntry entry : logged) {
                if (matching.test(entry)) {
                    return entry;
                }
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat("no matching entry among " + logged, null, notNullValue());
        return null;
    }

    private static void awaitReceived(Http3TestClient.Exchange exchange, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!new String(exchange.receivedByteArray(), StandardCharsets.UTF_8).equals(expected) && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(new String(exchange.receivedByteArray(), StandardCharsets.UTF_8), is(expected));
    }

    private int startUdpEchoServer() throws InterruptedException {
        echoGroup = new NioEventLoopGroup(1);
        udpEchoChannel = new Bootstrap()
            .group(echoGroup)
            .channelFactory(Ipv4DatagramChannelFactory.INSTANCE)
            .handler(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    if (msg instanceof DatagramPacket) {
                        DatagramPacket packet = (DatagramPacket) msg;
                        ctx.writeAndFlush(new DatagramPacket(packet.content().retain(), packet.sender()));
                        packet.release();
                    }
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0))
            .sync()
            .channel();
        return ((InetSocketAddress) udpEchoChannel.localAddress()).getPort();
    }

    private static void assumeQuicAvailable() {
        boolean available;
        try {
            available = io.netty.handler.codec.quic.Quic.isAvailable();
        } catch (Throwable t) {
            available = false;
        }
        Assume.assumeTrue("native QUIC transport not available on this platform -- skipping HTTP/3 test", available);
    }
}
