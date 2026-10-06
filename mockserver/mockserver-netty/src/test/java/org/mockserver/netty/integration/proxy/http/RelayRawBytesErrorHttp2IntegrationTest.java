package org.mockserver.netty.integration.proxy.http;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Headers;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * An {@code error()} with raw bytes answered to an HTTP/2 client. Raw bytes cannot be written to an HTTP/2 stream, so
 * MockServer writes none, on a direct connection or through a tunnel: the stream is left open, or reset alone when the
 * error also drops the connection. Each tunnel must do what the direct connections in the same test do.
 */
public class RelayRawBytesErrorHttp2IntegrationTest {

    // long enough for a reset or a close that should not come to arrive
    private static final long SETTLE_MILLIS = 300;

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static EventLoopGroup clientGroup;

    @BeforeClass
    public static void startServer() {
        mockServer = new MockServer(configuration().logLevel("WARN"), 0);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        clientGroup = new NioEventLoopGroup(2);
        byte[] bytes = "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nseven!!".getBytes(StandardCharsets.US_ASCII);
        mockServerClient.when(request().withPath("/raw")).error(error().withResponseBytes(bytes));
        mockServerClient.when(request().withPath("/raw-drop")).error(error().withResponseBytes(bytes).withDropConnection(true));
        mockServerClient.when(request().withPath("/simple")).respond(response().withBody("simple"));
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
    }

    private enum Route {
        DIRECT_TLS(true), DIRECT_H2C(false), CONNECT_TLS(true), CONNECT_H2C(false), SOCKS5_TLS(true), SOCKS5_H2C(false), SOCKS4_TLS(true), SOCKS4_H2C(false);

        private final boolean tls;

        Route(boolean tls) {
            this.tls = tls;
        }

        Http2TestClient open(int port) throws Exception {
            switch (this) {
                case DIRECT_TLS:
                    return Http2TestClient.tls(clientGroup, port);
                case DIRECT_H2C:
                    return Http2TestClient.h2c(clientGroup, port);
                case CONNECT_TLS:
                case CONNECT_H2C:
                    return Http2TestClient.throughConnect(clientGroup, port, "localhost", port, tls);
                case SOCKS5_TLS:
                case SOCKS5_H2C:
                    return Http2TestClient.throughSocks5(clientGroup, port, "localhost", port, tls);
                default:
                    return Http2TestClient.throughSocks4(clientGroup, port, "127.0.0.1", port, tls);
            }
        }
    }

    @Test
    public void shouldSendNoBytesAndLeaveTheStreamOpenDirectlyAndThroughEveryTunnel() throws Exception {
        int port = mockServer.getLocalPort();
        for (Route route : Route.values()) {
            try (Http2TestClient client = route.open(port)) {
                Http2TestClient.Exchange raw = client.send(headers(route, "/raw"), true);

                // answered after it on the same connection, so MockServer has had the error action to apply
                Http2TestClient.Exchange simple = client.send(headers(route, "/simple"), true);
                assertThat(route + ": the connection carries on", simple.status(), is(200));
                assertThat(route + ": the connection carries on", simple.body(), is("simple"));
                Thread.sleep(SETTLE_MILLIS);

                assertThat(route + ": no bytes", raw.received(), is(""));
                assertThat(route + ": no response", raw.isComplete(), is(false));
                assertThat(route + ": the stream is left open", raw.isReset(), is(false));
                assertThat(route + ": the connection is left open", client.isOpen(), is(true));
            }
        }
    }

    @Test
    public void shouldResetOnlyTheStreamWhenTheErrorDropsTheConnectionDirectlyAndThroughEveryTunnel() throws Exception {
        int port = mockServer.getLocalPort();
        for (Route route : Route.values()) {
            try (Http2TestClient client = route.open(port)) {
                Http2TestClient.Exchange other = client.send(headers(route, "/raw"), true);
                Http2TestClient.Exchange dropped = client.send(headers(route, "/raw-drop"), true);

                assertThat(route + ": the stream is reset", dropped.resetErrorCode(), is(Http2Error.CANCEL.code()));
                assertThat(route + ": no bytes", dropped.received(), is(""));
                Http2TestClient.Exchange simple = client.send(headers(route, "/simple"), true);
                assertThat(route + ": the connection carries on", simple.body(), is("simple"));
                Thread.sleep(SETTLE_MILLIS);

                assertThat(route + ": the connection's other streams are left alone", other.isReset(), is(false));
                assertThat(route + ": the connection is left open", client.isOpen(), is(true));
            }
        }
    }

    private static Http2Headers headers(Route route, String path) {
        return new DefaultHttp2Headers()
            .method("GET")
            .scheme(route.tls ? "https" : "http")
            .authority("localhost:" + mockServer.getLocalPort())
            .path(path);
    }
}
