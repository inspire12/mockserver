package org.mockserver.netty.http3;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.LocalBoundIpAddresses.anotherAddressOfThisHost;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * {@code localBoundIP} keeps the HTTP/3 listener on that address too, as it does the TCP listeners.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3LocalBoundIpTest {

    // a handshake with the server takes milliseconds; one sent where nothing listens is never answered
    private static final long NO_HANDSHAKE_MILLIS = 3000;
    private static final long HANDSHAKE_MILLIS = 15000;

    private static NioEventLoopGroup clientGroup;

    @BeforeClass
    public static void createClientGroup() {
        Assume.assumeTrue("native QUIC not available on this platform", Http3Server.isQuicAvailable());
        clientGroup = new NioEventLoopGroup(1);
    }

    @AfterClass
    public static void stopClientGroup() {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void shouldServeHttp3OnlyOnTheLocalBoundIp() throws Exception {
        InetAddress anotherAddress = anotherAddressOfThisHost();
        withServer(configuration().localBoundIP("127.0.0.1"), mockServer -> {
            assertThat("a request to the local bound IP", statusOfRequestTo(new InetSocketAddress("127.0.0.1", mockServer.getHttp3Port())), is(200));

            assertThrows("a handshake with " + anotherAddress.getHostAddress(), TimeoutException.class,
                () -> Http3TestClient.open(clientGroup, new InetSocketAddress(anotherAddress, mockServer.getHttp3Port()), NO_HANDSHAKE_MILLIS).close());
        });
    }

    @Test
    public void shouldServeHttp3OnEveryAddressWithoutALocalBoundIp() throws Exception {
        InetAddress anotherAddress = anotherAddressOfThisHost();
        withServer(configuration(), mockServer ->
            assertThat(statusOfRequestTo(new InetSocketAddress(anotherAddress, mockServer.getHttp3Port())), is(200)));
    }

    private interface ServerTest {
        void run(MockServer mockServer) throws Exception;
    }

    private static void withServer(Configuration configuration, ServerTest test) throws Exception {
        MockServer mockServer = startWithHttp3(configuration);
        MockServerClient client = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        try {
            client.when(request().withPath("/served")).respond(response().withBody("served"));
            test.run(mockServer);
        } finally {
            stopQuietly(client);
            mockServer.stop();
        }
    }

    private static int statusOfRequestTo(InetSocketAddress server) throws Exception {
        try (Http3TestClient client = Http3TestClient.open(clientGroup, server, HANDSHAKE_MILLIS)) {
            return client.send(new DefaultHttp3Headers()
                .method(HttpMethod.GET.asciiName())
                .scheme("https")
                .authority(server.getAddress().getHostAddress() + ":" + server.getPort())
                .path("/served")).status();
        }
    }
}
