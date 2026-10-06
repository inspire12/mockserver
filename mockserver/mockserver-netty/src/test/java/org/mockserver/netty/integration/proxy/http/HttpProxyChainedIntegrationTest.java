package org.mockserver.netty.integration.proxy.http;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.uuid.UUIDService;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.test.IsDebug.timeoutUnits;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * A proxy whose forwardHttpsProxy is an upstream MockServer: secure requests are tunnelled (HTTP CONNECT)
 * through the upstream, plain ones are not. Every server is local and configured per instance, so the
 * chain neither reaches the internet nor depends on process-wide configuration.
 *
 * @author jamesdbloom
 */
public class HttpProxyChainedIntegrationTest {

    private static EventLoopGroup clientEventLoopGroup;

    private static ClientAndServer destinationClientAndServer;

    @BeforeClass
    public static void startServer() {
        destinationClientAndServer = startClientAndServer();
    }

    @BeforeClass
    public static void startEventLoopGroup() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(HttpProxyChainedIntegrationTest.class.getSimpleName() + "-eventLoop"));
    }

    @AfterClass
    public static void stopEventLoopGroup() {
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(destinationClientAndServer);
    }

    @Before
    public void reset() {
        destinationClientAndServer.reset();
        destinationClientAndServer
            .when(request().withPath("/target"))
            .respond(response().withStatusCode(200).withBody("destination"));
    }

    @Test
    public void shouldAuthenticateForwardHTTPConnect() throws Exception {
        String username = UUIDService.getUUID();
        String password = UUIDService.getUUID();
        ClientAndServer upstreamClientAndServer = null;
        ClientAndServer proxyClientAndServer = null;
        try {
            upstreamClientAndServer = startClientAndServer(
                configuration()
                    .proxyAuthenticationUsername(username)
                    .proxyAuthenticationPassword(password)
            );
            proxyClientAndServer = startClientAndServer(
                chainedTo(upstreamClientAndServer)
                    .proxyAuthenticationUsername(username)
                    .proxyAuthenticationPassword(password)
                    .forwardProxyAuthenticationUsername(username)
                    .forwardProxyAuthenticationPassword(password)
            );

            HttpResponse httpResponse = sendViaProxy(
                proxyClientAndServer,
                request()
                    .withPath("/target")
                    .withSecure(true)
                    .withHeader(HOST.toString(), destinationHost())
                    .withHeader(PROXY_AUTHORIZATION.toString(), "Basic " + java.util.Base64.getEncoder().encodeToString((username + ':' + password).getBytes(StandardCharsets.UTF_8)))
            );

            // then - the destination's response came back along the whole chain
            assertThat(httpResponse.getStatusCode(), is(200));
            assertThat(httpResponse.getBodyAsString(), is("destination"));
            proxyClientAndServer.verify(request().withPath("/target"), exactly(1));
            upstreamClientAndServer.verify(request().withPath("/target"), exactly(1));
            destinationClientAndServer.verify(request().withPath("/target"), exactly(1));

        } finally {
            stopQuietly(proxyClientAndServer);
            stopQuietly(upstreamClientAndServer);
        }
    }

    @Test
    public void shouldForwardHTTPConnect() throws Exception {
        ClientAndServer upstreamClientAndServer = null;
        ClientAndServer proxyClientAndServer = null;
        try {
            upstreamClientAndServer = startClientAndServer(configuration());
            proxyClientAndServer = startClientAndServer(chainedTo(upstreamClientAndServer));

            HttpResponse httpResponse = sendViaProxy(
                proxyClientAndServer,
                request()
                    .withPath("/target")
                    .withSecure(true)
                    .withHeader(HOST.toString(), destinationHost())
            );

            // then - the destination's response came back along the whole chain
            assertThat(httpResponse.getStatusCode(), is(200));
            assertThat(httpResponse.getBodyAsString(), is("destination"));
            proxyClientAndServer.verify(request().withPath("/target"), exactly(1));
            upstreamClientAndServer.verify(request().withPath("/target"), exactly(1));
            destinationClientAndServer.verify(request().withPath("/target"), exactly(1));

        } finally {
            stopQuietly(proxyClientAndServer);
            stopQuietly(upstreamClientAndServer);
        }
    }

    @Test
    public void shouldNotForwardHTTPConnectIfNotSecure() throws Exception {
        ClientAndServer upstreamClientAndServer = null;
        ClientAndServer proxyClientAndServer = null;
        try {
            upstreamClientAndServer = startClientAndServer(configuration());
            proxyClientAndServer = startClientAndServer(chainedTo(upstreamClientAndServer));

            HttpResponse httpResponse = sendViaProxy(
                proxyClientAndServer,
                request()
                    .withPath("/target")
                    .withHeader(HOST.toString(), destinationHost())
            );

            // then - the proxy went straight to the destination, not through the upstream HTTPS proxy
            assertThat(httpResponse.getStatusCode(), is(200));
            assertThat(httpResponse.getBodyAsString(), is("destination"));
            proxyClientAndServer.verify(request().withPath("/target"), exactly(1));
            upstreamClientAndServer.verify(request().withPath("/target"), exactly(0));
            destinationClientAndServer.verify(request().withPath("/target"), exactly(1));

        } finally {
            stopQuietly(proxyClientAndServer);
            stopQuietly(upstreamClientAndServer);
        }
    }

    private static Configuration chainedTo(ClientAndServer upstreamClientAndServer) {
        return configuration().forwardHttpsProxy(new InetSocketAddress("localhost", upstreamClientAndServer.getLocalPort()));
    }

    private static String destinationHost() {
        return "127.0.0.1:" + destinationClientAndServer.getLocalPort();
    }

    private static HttpResponse sendViaProxy(ClientAndServer proxyClientAndServer, HttpRequest request) throws Exception {
        return new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false)
            .sendRequest(request, new InetSocketAddress(proxyClientAndServer.getLocalPort()))
            .get(10, timeoutUnits());
    }

}
