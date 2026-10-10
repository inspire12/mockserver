package org.mockserver.echo.http;

import org.junit.After;
import org.junit.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * The echo server listens on IPv4 only. On macOS a dual-stack socket bound to port 0 can be given a port that
 * another process already listens on at 127.0.0.1; connections to {@code 127.0.0.1:port} then reach that
 * process instead of the echo server. An IPv4 socket is never given such a port.
 */
public class EchoServerIpv4ListenerTest {

    private final EchoServer echoServer = new EchoServer(false);

    @After
    public void stopEchoServer() {
        echoServer.stop();
    }

    @Test
    public void shouldAcceptConnectionsOverIPv4() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), echoServer.getPort()), 10_000);

            assertThat(socket.isConnected(), is(true));
        }
    }

    @Test
    public void shouldListenOnAnIPv4Address() {
        echoServer.getPort();

        // a dual-stack socket bound to port 0 reports the IPv6 wildcard
        assertThat(echoServer.localAddress().getAddress(), is(instanceOf(Inet4Address.class)));
    }
}
