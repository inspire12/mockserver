package org.mockserver.netty.integration.proxy.http;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.BinaryMessage;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.mockserver.scheduler.Scheduler;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * A proxy whose forwardHttpsProxy or forwardSocksProxy is set leaves the destination's name to that upstream proxy,
 * as networks where only the upstream proxy can resolve external names need. The upstream here is a test proxy that
 * records the destination it is asked for and connects every name to this machine, so a destination named by a
 * name this JVM cannot resolve is reachable only if the name reaches the upstream unresolved.
 *
 * @author jamesdbloom
 */
public class UpstreamProxyResolvesDestinationIntegrationTest {

    // RFC 6761: names under .invalid never resolve
    private static final String UNRESOLVABLE = "destination-only-the-upstream-proxy-resolves.invalid";
    private static final long TIMEOUT_SECONDS = 30;

    private static EventLoopGroup clientEventLoopGroup;
    private static ClientAndServer destinationClientAndServer;

    private TunnelProxy upstreamProxy;
    private ClientAndServer proxyClientAndServer;

    @BeforeClass
    public static void startDestinationAndEventLoopGroup() {
        destinationClientAndServer = startClientAndServer();
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(UpstreamProxyResolvesDestinationIntegrationTest.class.getSimpleName() + "-eventLoop"));
    }

    @AfterClass
    public static void stopDestinationAndEventLoopGroup() {
        stopQuietly(destinationClientAndServer);
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    @Before
    public void resetDestination() {
        assertThrows("this test needs a name this JVM cannot resolve", UnknownHostException.class, () -> InetAddress.getByName(UNRESOLVABLE));
        destinationClientAndServer.reset();
        destinationClientAndServer
            .when(request().withPath("/target"))
            .respond(response().withStatusCode(200).withBody("destination"));
    }

    @After
    public void stopProxies() throws IOException {
        stopQuietly(proxyClientAndServer);
        if (upstreamProxy != null) {
            upstreamProxy.close();
        }
    }

    @Test
    public void shouldLeaveSecureDestinationNameToForwardHttpsProxy() throws Exception {
        upstreamProxy = new TunnelProxy(TunnelProxy.Protocol.CONNECT);
        proxyClientAndServer = startClientAndServer(configuration().forwardHttpsProxy(upstreamProxy.address()));

        HttpResponse response = sendTo(proxyClientAndServer, request().withPath("/target").withSecure(true).withHeader(HOST.toString(), UNRESOLVABLE + ":" + destinationPort()));

        assertReachedDestination(response);
        assertThat(upstreamProxy.destinations(), contains(UNRESOLVABLE + ":" + destinationPort()));
    }

    @Test
    public void shouldLeaveDestinationNameOfConnectTunnelToForwardHttpsProxy() throws Exception {
        upstreamProxy = new TunnelProxy(TunnelProxy.Protocol.CONNECT);
        proxyClientAndServer = startClientAndServer(configuration().forwardHttpsProxy(upstreamProxy.address()));

        // the client tunnels to the proxy with CONNECT, as a browser or HTTP client configured with an HTTPS proxy does
        HttpResponse response = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.HTTPS, new InetSocketAddress("127.0.0.1", proxyClientAndServer.getLocalPort()))), false)
            .sendRequest(request().withPath("/target").withSecure(true).withHeader(HOST.toString(), UNRESOLVABLE + ":" + destinationPort()))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertReachedDestination(response);
        assertThat(upstreamProxy.destinations(), contains(UNRESOLVABLE + ":" + destinationPort()));
    }

    @Test
    public void shouldLeavePlainDestinationNameToForwardSocksProxy() throws Exception {
        upstreamProxy = new TunnelProxy(TunnelProxy.Protocol.SOCKS5);
        proxyClientAndServer = startClientAndServer(configuration().forwardSocksProxy(upstreamProxy.address()));

        HttpResponse response = sendTo(proxyClientAndServer, request().withPath("/target").withHeader(HOST.toString(), UNRESOLVABLE + ":" + destinationPort()));

        assertReachedDestination(response);
        assertThat(upstreamProxy.destinations(), contains("domain " + UNRESOLVABLE + ":" + destinationPort()));
    }

    @Test
    public void shouldLeaveSecureDestinationNameToForwardSocksProxy() throws Exception {
        upstreamProxy = new TunnelProxy(TunnelProxy.Protocol.SOCKS5);
        proxyClientAndServer = startClientAndServer(configuration().forwardSocksProxy(upstreamProxy.address()));

        HttpResponse response = sendTo(proxyClientAndServer, request().withPath("/target").withSecure(true).withHeader(HOST.toString(), UNRESOLVABLE + ":" + destinationPort()));

        assertReachedDestination(response);
        assertThat(upstreamProxy.destinations(), contains("domain " + UNRESOLVABLE + ":" + destinationPort()));
    }

    @Test
    public void shouldNotLookUpDestinationNameThatResolvesLocally() throws Exception {
        upstreamProxy = new TunnelProxy(TunnelProxy.Protocol.SOCKS5);
        proxyClientAndServer = startClientAndServer(configuration().forwardSocksProxy(upstreamProxy.address()));

        HttpResponse response = sendTo(proxyClientAndServer, request().withPath("/target").withHeader(HOST.toString(), "localhost:" + destinationPort()));

        // a name looked up here would reach the SOCKS proxy as an address
        assertReachedDestination(response);
        assertThat(upstreamProxy.destinations(), contains("domain localhost:" + destinationPort()));
    }

    @Test
    public void shouldSendNameOfDestinationResolvedByCallerToForwardSocksProxy() throws Exception {
        upstreamProxy = new TunnelProxy(TunnelProxy.Protocol.SOCKS5);
        NettyHttpClient client = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, upstreamProxy.address())), true);

        HttpResponse response = client
            .sendRequest(request().withPath("/target").withHeader(HOST.toString(), "localhost:" + destinationPort()), new InetSocketAddress("localhost", destinationPort()))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertReachedDestination(response);
        assertThat(upstreamProxy.destinations(), contains("domain localhost:" + destinationPort()));
    }

    @Test
    public void shouldSendIpLiteralDestinationToForwardSocksProxyAsAnAddress() throws Exception {
        upstreamProxy = new TunnelProxy(TunnelProxy.Protocol.SOCKS5);
        proxyClientAndServer = startClientAndServer(configuration().forwardSocksProxy(upstreamProxy.address()));

        HttpResponse response = sendTo(proxyClientAndServer, request().withPath("/target").withHeader(HOST.toString(), "127.0.0.1:" + destinationPort()));

        assertReachedDestination(response);
        assertThat(upstreamProxy.destinations(), contains("ipv4 127.0.0.1:" + destinationPort()));
    }

    @Test
    public void shouldLeaveBinaryDestinationNameToForwardSocksProxy() throws Exception {
        upstreamProxy = new TunnelProxy(TunnelProxy.Protocol.SOCKS5);
        try (EchoServer echoServer = new EchoServer()) {
            NettyHttpClient client = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, upstreamProxy.address())), true);

            BinaryMessage response = client
                .sendRequest(BinaryMessage.bytes("ping".getBytes(StandardCharsets.UTF_8)), false, InetSocketAddress.createUnresolved(UNRESOLVABLE, echoServer.port()), TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(new String(response.getBytes(), StandardCharsets.UTF_8), is("ping"));

            // a destination the caller resolved is sent by name too
            response = client
                .sendRequest(BinaryMessage.bytes("pong".getBytes(StandardCharsets.UTF_8)), false, new InetSocketAddress("localhost", echoServer.port()), TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(new String(response.getBytes(), StandardCharsets.UTF_8), is("pong"));
            assertThat(upstreamProxy.destinations(), contains("domain " + UNRESOLVABLE + ":" + echoServer.port(), "domain localhost:" + echoServer.port()));
        }
    }

    @Test
    public void shouldStillBlockForwardToPrivateNetworkWhenUpstreamProxyResolves() throws Exception {
        upstreamProxy = new TunnelProxy(TunnelProxy.Protocol.CONNECT);
        proxyClientAndServer = startClientAndServer(configuration().forwardHttpsProxy(upstreamProxy.address()).forwardProxyBlockPrivateNetworks(true));
        // a name that resolves here to loopback, and one that does not resolve here: neither is vetted, so neither is sent
        for (String host : new String[]{"localhost", UNRESOLVABLE}) {
            proxyClientAndServer.reset();
            proxyClientAndServer
                .when(request().withPath("/forward"))
                .forward(forward().withHost(host).withPort(destinationPort()).withScheme(HttpForward.Scheme.HTTPS));

            HttpResponse response = sendTo(proxyClientAndServer, request().withPath("/forward").withHeader(HOST.toString(), "127.0.0.1:" + proxyClientAndServer.getLocalPort()));

            assertThat(host, response.getStatusCode(), is(502));
        }
        assertThat(upstreamProxy.destinations(), is(empty()));
        destinationClientAndServer.verify(request().withPath("/target"), exactly(0));
    }

    private static int destinationPort() {
        return destinationClientAndServer.getLocalPort();
    }

    private void assertReachedDestination(HttpResponse response) {
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("destination"));
        destinationClientAndServer.verify(request().withPath("/target"), exactly(1));
    }

    private static HttpResponse sendTo(ClientAndServer server, HttpRequest request) throws Exception {
        return new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false)
            .sendRequest(request, new InetSocketAddress("127.0.0.1", server.getLocalPort()))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * An upstream proxy, HTTP {@code CONNECT} or SOCKS5 without authentication, that records each destination it is
     * asked for and connects it to the same port on this machine, whatever its name.
     */
    private static final class TunnelProxy {
        enum Protocol {CONNECT, SOCKS5}

        private final Protocol protocol;
        private final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final List<String> destinations = new CopyOnWriteArrayList<>();

        private TunnelProxy(Protocol protocol) throws IOException {
            this.protocol = protocol;
            daemon(() -> {
                while (!listener.isClosed()) {
                    Socket client = listener.accept();
                    sockets.add(client);
                    daemon(() -> tunnel(client));
                }
            });
        }

        private InetSocketAddress address() {
            return new InetSocketAddress("127.0.0.1", listener.getLocalPort());
        }

        private List<String> destinations() {
            return destinations;
        }

        private void tunnel(Socket client) throws IOException {
            client.setSoTimeout((int) TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            int port = protocol == Protocol.CONNECT ? readConnect(client) : readSocks5(client);
            Socket upstream = new Socket("127.0.0.1", port);
            sockets.add(upstream);
            if (protocol == Protocol.CONNECT) {
                write(client, "HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            } else {
                write(client, new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
            }
            daemon(() -> copy(upstream, client));
            copy(client, upstream);
        }

        private int readConnect(Socket client) throws IOException {
            StringBuilder head = new StringBuilder();
            InputStream fromClient = client.getInputStream();
            while (head.indexOf("\r\n\r\n") < 0) {
                int read = fromClient.read();
                if (read == -1) {
                    throw new IOException("closed before CONNECT");
                }
                head.append((char) read);
            }
            String authority = head.substring("CONNECT ".length(), head.indexOf(" HTTP/1.1"));
            destinations.add(authority);
            return Integer.parseInt(authority.substring(authority.lastIndexOf(':') + 1));
        }

        private int readSocks5(Socket client) throws IOException {
            DataInputStream fromClient = new DataInputStream(client.getInputStream());
            // greeting: version, method count, methods; answer "no authentication"
            fromClient.readUnsignedByte();
            fromClient.readFully(new byte[fromClient.readUnsignedByte()]);
            write(client, new byte[]{5, 0});
            // request: version, command, reserved, address type, address, port
            fromClient.readUnsignedByte();
            fromClient.readUnsignedByte();
            fromClient.readUnsignedByte();
            int addressType = fromClient.readUnsignedByte();
            String destination;
            if (addressType == 3) {
                byte[] name = new byte[fromClient.readUnsignedByte()];
                fromClient.readFully(name);
                destination = "domain " + new String(name, StandardCharsets.US_ASCII);
            } else {
                byte[] address = new byte[addressType == 1 ? 4 : 16];
                fromClient.readFully(address);
                destination = (addressType == 1 ? "ipv4 " : "ipv6 ") + InetAddress.getByAddress(address).getHostAddress();
            }
            int port = fromClient.readUnsignedShort();
            destinations.add(destination + ":" + port);
            return port;
        }

        private void close() throws IOException {
            listener.close();
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }

    private static final class EchoServer implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        private EchoServer() throws IOException {
            daemon(() -> {
                while (!listener.isClosed()) {
                    Socket client = listener.accept();
                    sockets.add(client);
                    daemon(() -> copy(client, client));
                }
            });
        }

        private int port() {
            return listener.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            listener.close();
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }

    private static void write(Socket socket, byte[] bytes) throws IOException {
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    private static void copy(Socket from, Socket to) throws IOException {
        try {
            byte[] buffer = new byte[16 * 1024];
            InputStream input = from.getInputStream();
            OutputStream output = to.getOutputStream();
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } finally {
            from.close();
            to.close();
        }
    }

    private static void daemon(IoTask task) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (IOException closed) {
                // the listener or a tunnel was closed
            }
        }, "upstream-proxy");
        thread.setDaemon(true);
        thread.start();
    }

    private interface IoTask {
        void run() throws IOException;
    }
}
