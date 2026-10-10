package org.mockserver.netty.integration.proxy.http;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2ChannelDuplexHandler;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.netty.MockServer;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.LogEntrySerializer;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * An HTTP/2 CONNECT tunnel whose client leaves while MockServer still owes it a response, over real sockets: the
 * tunnel's loopback connection must close with the client's, not Netty's 30 s graceful-shutdown timeout later, a
 * request the client had sent in full must still be received, and a client that simply leaves is not worth a warning.
 */
public class RelayHttp2TunnelCloseIntegrationTest {

    private static final long PROMPTLY_MILLIS = 10_000;

    private static EventLoopGroup clientGroup;
    private MockServer mockServer;

    @BeforeClass
    public static void startClientGroup() {
        clientGroup = new NioEventLoopGroup(1);
    }

    @AfterClass
    public static void stopClientGroup() {
        clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    @Before
    public void startServer() {
        // WARN: the entries asserted on below must reach the event log whatever level the build runs tests at
        mockServer = new MockServer(configuration().logLevel("WARN").startupWarmup(false).proxySetup(false), 0);
        // answered with nothing, and the stream left open
        controlPlane("/mockserver/expectation", new ExpectationSerializer(new MockServerLogger()).serialize(
            new Expectation(request().withPath("/held")).thenError(error()),
            new Expectation(request().withPath("/upload")).thenError(error()),
            new Expectation(request().withPath("/answered")).thenRespond(response().withBody("answered")),
            new Expectation(request().withPath("/large")).thenRespond(response().withBody(new String(new char[200_000]).replace('\0', 'x')))
        ));
    }

    @After
    public void stopServer() {
        stopQuietly(mockServer);
    }

    @Test
    public void shouldCloseTheLoopbackWithTheClientThatLeavesWithARequestInFlight() throws Exception {
        leaveWithARequestInFlight(false);
    }

    @Test
    public void shouldCloseTheLoopbackOfATlsTunnelWithTheClientThatLeavesWithARequestInFlight() throws Exception {
        leaveWithARequestInFlight(true);
    }

    private void leaveWithARequestInFlight(boolean tls) throws Exception {
        Http2TunnelClient client = Http2TunnelClient.open(mockServer, 65_535, tls);
        client.request("/held");
        await("the request reached MockServer", () -> requestReceived("/held"));
        await("only the client's leg and the loopback leg are open", () -> mockServer.getInboundConnectionCount() == 2);
        List<String> loggedBefore = loggedAtWarnOrAbove();

        long left = System.nanoTime();
        client.channel.close().sync();

        await("both legs closed", () -> mockServer.getInboundConnectionCount() == 0);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - left), lessThan(PROMPTLY_MILLIS));
        assertThat("nothing logged at WARN or above for a client that left", loggedAtWarnOrAbove(), is(loggedBefore));
    }

    @Test
    public void shouldCloseTheLoopbackWithTheClientThatLeavesWithResponseDataQueuedAndAnotherRequestInFlight() throws Exception {
        // a stream window far below the response, which the client never extends
        Http2TunnelClient client = Http2TunnelClient.open(mockServer, 1024, false);
        client.request("/held");
        client.request("/large");
        await("the start of the response reached the client", () -> client.dataBytes.get() == 1024);
        await("the other request reached MockServer", () -> requestReceived("/held"));
        await("only the client's leg and the loopback leg are open", () -> mockServer.getInboundConnectionCount() == 2);
        List<String> loggedBefore = loggedAtWarnOrAbove();

        long left = System.nanoTime();
        client.channel.close().sync();

        await("both legs closed", () -> mockServer.getInboundConnectionCount() == 0);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - left), lessThan(PROMPTLY_MILLIS));
        assertThat("nothing logged at WARN or above for a client that left", loggedAtWarnOrAbove(), is(loggedBefore));
    }

    @Test
    public void shouldReceiveALargeRequestFromAClientThatLeavesAsSoonAsItHasSentIt() throws Exception {
        leaveAsSoonAsALargeRequestIsSent(false);
    }

    @Test
    public void shouldReceiveALargeRequestOverATlsTunnelFromAClientThatLeavesAsSoonAsItHasSentIt() throws Exception {
        leaveAsSoonAsALargeRequestIsSent(true);
    }

    private void leaveAsSoonAsALargeRequestIsSent(boolean tls) throws Exception {
        Http2TunnelClient client = Http2TunnelClient.open(mockServer, 65_535, tls);
        client.request("/answered");
        await("the tunnel has carried a whole exchange", () -> client.dataBytes.get() == "answered".length());
        await("only the client's leg and the loopback leg are open", () -> mockServer.getInboundConnectionCount() == 2);
        List<String> loggedBefore = loggedAtWarnOrAbove();

        // many times the 65,535 bytes MockServer lets a stream send before it extends the window, so most of it is
        // still to be written to the loopback when the client's close arrives
        client.postThenLeave("/upload", 1_000_000);
        assertThat("the client left", client.channel.closeFuture().await(10, TimeUnit.SECONDS), is(true));
        long left = System.nanoTime();

        await("the request reached MockServer", () -> requestReceived("/upload"));
        await("both legs closed", () -> mockServer.getInboundConnectionCount() == 0);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - left), lessThan(PROMPTLY_MILLIS));
        assertThat("nothing logged at WARN or above for a client that left", loggedAtWarnOrAbove(), is(loggedBefore));
    }

    @Test
    public void shouldReceiveARequestFromAClientThatLeavesWithAResponseOnAnotherStreamStillBeingWritten() throws Exception {
        leaveWithAResponseStillBeingWrittenAsSoonAsALargeRequestIsSent(false);
    }

    @Test
    public void shouldReceiveARequestOverATlsTunnelFromAClientThatLeavesWithAResponseOnAnotherStreamStillBeingWritten() throws Exception {
        leaveWithAResponseStillBeingWrittenAsSoonAsALargeRequestIsSent(true);
    }

    private void leaveWithAResponseStillBeingWrittenAsSoonAsALargeRequestIsSent(boolean tls) throws Exception {
        // a stream window far below the response, which the client never extends: the rest of it stays queued for the
        // client, and fails when the client leaves
        Http2TunnelClient client = Http2TunnelClient.open(mockServer, 1024, tls);
        client.request("/large");
        await("the start of the response reached the client", () -> client.dataBytes.get() == 1024);
        await("only the client's leg and the loopback leg are open", () -> mockServer.getInboundConnectionCount() == 2);
        List<String> loggedBefore = loggedAtWarnOrAbove();

        client.postThenLeave("/upload", 2_000_000);
        assertThat("the client left", client.channel.closeFuture().await(10, TimeUnit.SECONDS), is(true));
        long left = System.nanoTime();

        await("the request reached MockServer", () -> requestReceived("/upload"));
        await("both legs closed", () -> mockServer.getInboundConnectionCount() == 0);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - left), lessThan(PROMPTLY_MILLIS));
        assertThat("nothing logged at WARN or above for a client that left", loggedAtWarnOrAbove(), is(loggedBefore));
    }

    private boolean requestReceived(String path) {
        return controlPlane("/mockserver/retrieve?type=REQUESTS&format=JSON", "{\"path\":\"" + path + "\"}").contains("\"" + path + "\"");
    }

    private List<String> loggedAtWarnOrAbove() {
        List<String> messages = new ArrayList<>();
        for (LogEntry entry : new LogEntrySerializer(new MockServerLogger()).deserializeArray(controlPlane("/mockserver/retrieve?type=LOGS&format=LOG_ENTRIES", ""))) {
            if (entry.getLogLevel() != null && entry.getLogLevel().toInt() >= Level.WARN.toInt()) {
                messages.add(entry.getLogLevel() + " " + entry.getMessageFormat());
            }
        }
        return messages;
    }

    /**
     * One control-plane call on a connection of its own, closed with its response, so that the server's open connections
     * are soon the tunnel's alone (the Java client keeps its connections for a while).
     */
    private String controlPlane(String pathAndQuery, String json) {
        try (Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort())) {
            socket.setSoTimeout(10_000);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(("PUT " + pathAndQuery + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = socket.getInputStream().read(buffer)) != -1; ) {
                response.write(buffer, 0, count);
            }
            String text = response.toString(StandardCharsets.UTF_8.name());
            assertThat(text, startsWith("HTTP/1.1 20"));
            return text.substring(text.indexOf("\r\n\r\n") + 4);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Waits past Netty's 30 s graceful-shutdown timeout, so a leg that closes only then fails on how long it took.
     */
    private static void await(String reason, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(reason, condition.getAsBoolean(), is(true));
    }

    /**
     * Speaks HTTP/2 through a CONNECT tunnel, which MockServer's relay terminates: cleartext, or over TLS, which the
     * relay then also speaks on its loopback. It grants each stream the given window and never extends it.
     */
    private static final class Http2TunnelClient extends Http2ChannelDuplexHandler {
        private final AtomicLong dataBytes = new AtomicLong();
        private final boolean tls;
        private Channel channel;
        private ChannelHandlerContext ctx;

        private Http2TunnelClient(boolean tls) {
            this.tls = tls;
        }

        static Http2TunnelClient open(MockServer server, int streamWindow, boolean tls) throws Exception {
            Http2TunnelClient client = new Http2TunnelClient(tls);
            SslContext sslContext = tls
                ? SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                    ApplicationProtocolConfig.Protocol.ALPN,
                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                    ApplicationProtocolNames.HTTP_2))
                .build()
                : null;
            client.channel = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                // the tunnel's target is handed to the CONNECT proxy, not resolved here
                .resolver(NoopAddressResolverGroup.INSTANCE)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpProxyHandler(new InetSocketAddress("127.0.0.1", server.getLocalPort())));
                        if (sslContext != null) {
                            ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), "127.0.0.1", 443));
                        }
                        ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().initialSettings(Http2Settings.defaultSettings().initialWindowSize(streamWindow)).build(), client);
                    }
                })
                .connect(InetSocketAddress.createUnresolved("127.0.0.1", 443)).sync().channel();
            return client;
        }

        @Override
        protected void handlerAdded0(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        void request(String uri) throws Exception {
            ctx.executor().submit(() ->
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().method("GET").scheme(tls ? "https" : "http").authority("127.0.0.1:443").path(uri), true).stream(newStream()))
            ).get(5, TimeUnit.SECONDS);
        }

        /**
         * Sends a request with a body and closes the connection as soon as the last of it has been written.
         */
        void postThenLeave(String uri, int bodyBytes) throws Exception {
            ctx.executor().submit(() -> {
                Http2FrameStream stream = newStream();
                ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().method("POST").scheme(tls ? "https" : "http").authority("127.0.0.1:443").path(uri), false).stream(stream));
                // printable: the event log's JSON spends six characters on a zero byte, in each of its copies of the body
                byte[] body = new byte[bodyBytes];
                Arrays.fill(body, (byte) 'x');
                ctx.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(body), true).stream(stream))
                    .addListener(written -> ctx.channel().close());
            }).get(5, TimeUnit.SECONDS);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2DataFrame) {
                dataBytes.addAndGet(((Http2DataFrame) msg).content().readableBytes());
            }
            ReferenceCountUtil.release(msg);
        }
    }
}
