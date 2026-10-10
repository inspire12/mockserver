package org.mockserver.mock.action.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.proxyconfiguration.ForwardTargetBlockedException;
import org.mockserver.proxyconfiguration.HostLookups;
import org.mockserver.responsewriter.ResponseWriter;
import org.slf4j.event.Level;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;

/**
 * With forwardProxyBlockPrivateNetworks on, a name whose DNS answer changes between lookups (rebinding) must not
 * reach a blocked address: each send connects to the address it checked. The name's first answer here is public
 * (and unroutable), its second loopback, where the upstream listens; the connection's own lookup of "localhost",
 * which the check does not see, also answers loopback, as a rebinding name's later answer would.
 */
public class ForwardTargetRebindingTest {

    private static final InetAddress PUBLIC = address(203, 0, 113, 10);

    private final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private final MockServerLogger mockServerLogger = new MockServerLogger(ForwardTargetRebindingTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return true;
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry);
        }
    };
    private InetAddress localhost;
    private EventLoopGroup group;
    private Upstream upstream;

    private static InetAddress address(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Before
    public void startUpstream() throws IOException {
        localhost = InetAddress.getByName("localhost");
        group = new NioEventLoopGroup(2);
        upstream = new Upstream(localhost);
    }

    @After
    public void stopUpstream() throws IOException {
        upstream.close();
        group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }

    private Configuration blocking() {
        return configuration()
            .forwardProxyBlockPrivateNetworks(true)
            .socketConnectionTimeoutInMillis(500L)
            .forwardConnectionPoolEnabled(false);
    }

    private HttpRequest toUpstream() {
        return request("/rebind").withHeader("Host", "localhost:" + upstream.port());
    }

    @Test
    public void shouldConnectToTheAddressTheForwardClientChecked() {
        NettyHttpClient client = new NettyHttpClient(blocking(), mockServerLogger, group, null, true, null);

        try (HostLookups lookups = HostLookups.answer("localhost", PUBLIC, localhost)) {
            CompletableFuture<HttpResponse> sent = client.sendRequest(toUpstream(), InetSocketAddress.createUnresolved("localhost", upstream.port()));

            ExecutionException failure = assertThrows(ExecutionException.class, () -> sent.get(30, TimeUnit.SECONDS));
            assertThat(failure.getCause().getMessage(), containsString(PUBLIC.getHostAddress()));
            assertThat(lookups.lookups(), is(1));
        }
        assertThat(upstream.connections(), is(0));
    }

    @Test
    public void shouldRefuseAForwardActionWhoseNameRebindsAfterTheCheck() {
        NettyHttpClient client = new NettyHttpClient(blocking(), mockServerLogger, group, null, true, null);
        HttpForwardActionHandler forwardActionHandler = new HttpForwardActionHandler(mockServerLogger, blocking(), client);

        try (HostLookups lookups = HostLookups.answer("localhost", PUBLIC, localhost)) {
            HttpForwardActionResult result = forwardActionHandler.handle(forward().withHost("localhost").withPort(upstream.port()), toUpstream());

            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.getHttpResponse().get(30, TimeUnit.SECONDS));
            assertThat(ForwardTargetBlockedException.in(failure), notNullValue());
            assertThat(failure.getCause().getMessage(), containsString("loopback"));
            assertThat(lookups.lookups(), is(2));
        }
        assertThat(upstream.connections(), is(0));
    }

    @Test
    public void shouldRefuseALoadScenarioOrDriftAlertSendWhoseNameRebindsAfterTheCheck() {
        HttpActionHandler actionHandler = actionHandler(blocking());

        try (HostLookups lookups = HostLookups.answer("localhost", PUBLIC, localhost)) {
            CompletableFuture<HttpResponse> sent = actionHandler.getRequestSender().apply(toUpstream());

            ExecutionException failure = assertThrows(ExecutionException.class, () -> sent.get(30, TimeUnit.SECONDS));
            assertThat(ForwardTargetBlockedException.in(failure), notNullValue());
            assertThat(lookups.lookups(), is(1));
        }
        assertThat(upstream.connections(), is(0));
    }

    @Test
    public void shouldStillReachTheUpstreamByNameWithTheSettingOff() throws Exception {
        Configuration notBlocking = blocking().forwardProxyBlockPrivateNetworks(false);
        NettyHttpClient client = new NettyHttpClient(notBlocking, mockServerLogger, group, null, true, null);

        try (HostLookups lookups = HostLookups.answer("localhost", PUBLIC)) {
            HttpResponse response = client.sendRequest(toUpstream(), InetSocketAddress.createUnresolved("localhost", upstream.port())).get(30, TimeUnit.SECONDS);

            assertThat(response.getStatusCode(), is(200));
            assertThat(lookups.lookups(), is(0));
        }
        assertThat(upstream.connections(), is(1));
    }

    @Test
    public void shouldAnswerAForwardRefusedByTheClientWith502AndOneWarning() {
        HttpActionHandler actionHandler = actionHandler(blocking());
        ResponseWriter responseWriter = mock(ResponseWriter.class);
        HttpRequest request = request("/rebind");

        actionHandler.handleExceptionDuringForwardingRequest(forward().withHost("localhost"), request, responseWriter,
            new java.util.concurrent.CompletionException(new ForwardTargetBlockedException("Forward to loopback address blocked: localhost")));

        ArgumentCaptor<HttpResponse> response = ArgumentCaptor.forClass(HttpResponse.class);
        verify(responseWriter).writeResponse(eq(request), response.capture(), eq(false));
        assertThat(response.getValue().getStatusCode(), is(502));
        List<String> warnings = logged.stream()
            .filter(entry -> entry.getLogLevel() == Level.WARN || entry.getLogLevel() == Level.ERROR)
            .map(LogEntry::getMessage)
            .collect(Collectors.toList());
        assertThat(warnings.size(), is(1));
        assertThat(warnings, hasItem(containsString("forward blocked by SSRF policy")));
    }

    @Test
    public void shouldRelayAWebSocketUpgradeToTheAddressItChecked() throws Exception {
        WebSocketProxyRelayHandler relayHandler = new WebSocketProxyRelayHandler(blocking(), mockServerLogger, null);
        HttpRequest upgrade = request("/socket")
            .withMethod("GET")
            .withHeader("Host", "localhost:" + upstream.port())
            .withHeader("Upgrade", "websocket")
            .withHeader("Connection", "Upgrade")
            .withHeader("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
            .withHeader("Sec-WebSocket-Version", "13");
        AtomicInteger lookups = new AtomicInteger(-1);
        // the relay runs on the client connection's event loop, so the lookup is replaced there
        Channel relayServer = new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) {
                    try (HostLookups answers = HostLookups.answer("localhost", PUBLIC, localhost)) {
                        relayHandler.relay(upgrade, ctx, "localhost", upstream.port(), false);
                        lookups.set(answers.lookups());
                    }
                }
            })
            .bind(InetAddress.getLoopbackAddress(), 0).syncUninterruptibly().channel();
        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), ((InetSocketAddress) relayServer.localAddress()).getPort())) {
            client.setSoTimeout(30_000);
            // the relay closes the client connection once the upstream connection has failed
            assertThat(client.getInputStream().read(), is(-1));
        } finally {
            relayServer.close().syncUninterruptibly();
        }
        assertThat(lookups.get(), is(1));
        assertThat(upstream.connections(), is(0));
        assertThat(logged.stream().map(LogEntry::getMessage).collect(Collectors.toList()), hasItem(containsString(PUBLIC.getHostAddress())));
    }

    private HttpActionHandler actionHandler(Configuration configuration) {
        HttpState httpState = mock(HttpState.class);
        when(httpState.getMockServerLogger()).thenReturn(mockServerLogger);
        when(httpState.getUniqueLoopPreventionHeaderName()).thenReturn("x-forwarded-by");
        when(httpState.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_rebinding");
        return new HttpActionHandler(configuration, () -> group, httpState, null, null);
    }

    /**
     * Counts the connections it accepts and answers each with an empty 200.
     */
    private static final class Upstream implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final AtomicInteger connections = new AtomicInteger();
        private final Thread acceptor;

        private Upstream(InetAddress address) throws IOException {
            serverSocket = new ServerSocket(0, 50, address);
            acceptor = new Thread(this::accept, "rebinding-upstream");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private void accept() {
            while (!serverSocket.isClosed()) {
                try (Socket socket = serverSocket.accept()) {
                    connections.incrementAndGet();
                    socket.setSoTimeout(5_000);
                    InputStream in = socket.getInputStream();
                    byte[] buffer = new byte[4096];
                    // enough of the request to answer it; the content is not checked
                    in.read(buffer);
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (IOException ignored) {
                    // closed, or a client went away
                }
            }
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        int connections() {
            return connections.get();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}
