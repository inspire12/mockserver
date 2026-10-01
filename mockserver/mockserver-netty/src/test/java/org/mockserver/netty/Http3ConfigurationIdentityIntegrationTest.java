package org.mockserver.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.netty.http3.Http3Server;
import org.mockserver.testing.socket.Ipv4DatagramChannelFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assume.assumeTrue;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The HTTP/3 server, and the {@code Http3MockServerHandler} it builds for every QUIC request stream,
 * must run on the server's {@code Configuration}, so a runtime {@code PUT /mockserver/configuration}
 * reaches requests served over HTTP/3. Runs only where the QUIC native is loadable;
 * {@link RuntimeConfigurationEveryConstructionPathIntegrationTest} covers the TCP paths.
 */
public class Http3ConfigurationIdentityIntegrationTest {

    private static final String RUNTIME_HEADER = "x-runtime-http3";

    private MockServer server;

    @Before
    public void startServer() throws Exception {
        assumeTrue("native QUIC not available on this platform", Http3Server.isQuicAvailable());
        int udpPort;
        try (DatagramSocket socket = new DatagramSocket(0)) {
            udpPort = socket.getLocalPort();
        }
        server = new MockServer(configuration().http3Port(udpPort), 0);
        assertThat("HTTP/3 must have started", server.getHttp3Port(), greaterThan(0));
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void theHttp3ServerMustHoldTheServersConfiguration() throws Exception {
        ServerConfigurationIdentity identity = ServerConfigurationIdentity.walk(server);

        assertThat(identity.divergent(), empty());
        assertThat(identity.matchedHolders(), hasItem("Http3Server.configuration"));
    }

    @Test
    public void aRuntimeDefaultResponseHeaderMustReachAResponseServedOverHttp3() throws Exception {
        // The per-stream Http3MockServerHandler builds the response writer that stamps
        // defaultResponseHeaders, so it must hold the instance the PUT writes.
        assertThat(sendHttp1("PUT", "/mockserver/expectation",
            "{\"httpRequest\": {\"path\": \"/h3-probe\"}, \"httpResponse\": {\"statusCode\": 200, \"body\": \"ok\"}}"), is(201));
        Http3Response baseline = sendHttp3("/h3-probe");
        assertThat(baseline.status, is("200"));
        assertThat("baseline: no default response header is configured", baseline.runtimeHeader, nullValue());

        assertThat(sendHttp1("PUT", "/mockserver/configuration", "{\"defaultResponseHeaders\": \"" + RUNTIME_HEADER + "=applied\"}"), is(200));

        Http3Response after = sendHttp3("/h3-probe");
        assertThat(after.status, is("200"));
        assertThat("a defaultResponseHeaders set at runtime must be stamped on a response served over HTTP/3",
            after.runtimeHeader, is("applied"));
    }

    private int sendHttp1(String method, String path, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + server.getLocalPort() + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setDoOutput(true);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int statusCode = connection.getResponseCode();
            try (InputStream in = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                if (in != null) {
                    in.transferTo(new ByteArrayOutputStream());
                }
            }
            return statusCode;
        } finally {
            connection.disconnect();
        }
    }

    private static final class Http3Response {
        volatile String status;
        volatile String runtimeHeader;
    }

    private Http3Response sendHttp3(String path) throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Channel datagramChannel = new Bootstrap()
                .group(group)
                .channelFactory(Ipv4DatagramChannelFactory.INSTANCE)
                .handler(Http3.newQuicClientCodecBuilder()
                    .sslContext(QuicSslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .applicationProtocols(Http3.supportedApplicationProtocols())
                        .build())
                    .maxIdleTimeout(30000, TimeUnit.MILLISECONDS)
                    .initialMaxData(10000000)
                    .initialMaxStreamDataBidirectionalLocal(1000000)
                    .initialMaxStreamsBidirectional(100)
                    .build())
                .bind(0).sync().channel();
            QuicChannel quicChannel = QuicChannel.newBootstrap(datagramChannel)
                .handler(new Http3ClientConnectionHandler())
                .remoteAddress(new InetSocketAddress("127.0.0.1", server.getHttp3Port()))
                .connect()
                .get(15, TimeUnit.SECONDS);

            Http3Response response = new Http3Response();
            CountDownLatch done = new CountDownLatch(1);
            QuicStreamChannel stream = Http3.newRequestStream(quicChannel, new Http3RequestStreamInboundHandler() {
                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
                    if (headersFrame.headers().status() != null) {
                        response.status = headersFrame.headers().status().toString();
                    }
                    CharSequence header = headersFrame.headers().get(RUNTIME_HEADER);
                    if (header != null) {
                        response.runtimeHeader = header.toString();
                    }
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
                    dataFrame.release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    done.countDown();
                    ctx.close();
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    done.countDown();
                }
            }).sync().getNow();

            DefaultHttp3HeadersFrame headers = new DefaultHttp3HeadersFrame();
            headers.headers().method("GET").path(path).scheme("https").authority("127.0.0.1:" + server.getHttp3Port());
            stream.writeAndFlush(headers).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT).sync();
            assertThat("the HTTP/3 response must complete", done.await(20, TimeUnit.SECONDS), is(true));

            quicChannel.close().sync();
            datagramChannel.close().sync();
            return response;
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }
}
