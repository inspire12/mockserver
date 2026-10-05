package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.http3.Http3SettingsFrame;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;
import org.mockserver.testing.socket.Ipv4DatagramChannelFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * An HTTP/3 request's header section is limited by {@code maxHeaderSize}, as an HTTP/1.1 request's and an HTTP/2
 * request's are: the limit is what MockServer advertises as {@code SETTINGS_MAX_FIELD_SECTION_SIZE}, a section of
 * exactly that size is served, and one over it closes the connection with {@code H3_EXCESSIVE_LOAD} (Netty's HTTP/3
 * codec answers no {@code 431}). The size is the one RFC 9114 section 4.2.2 defines: each field's name and value plus
 * 32 bytes, the pseudo-header fields included. Needs the QUIC native library, and fails without it.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3HeaderListLimitIntegrationTest {

    // above the 8,192 bytes Netty limits a header section to when it is not told otherwise
    private static final int LIMIT = 32 * 1024;
    private static final int DEFAULT_LIMIT = 256 * 1024;
    private static final String FILLER = "x-filler";
    private static final int FIELD_OVERHEAD = 32;
    private static final long WAIT_SECONDS = 15;
    private static final int H3_EXCESSIVE_LOAD = Http3ErrorCode.H3_EXCESSIVE_LOAD.code();

    private static MockServer limited;
    private static MockServerClient limitedClient;
    private static MockServer defaults;
    private static MockServerClient defaultsClient;
    private static MockServer connectUdp;
    private static MockServerClient connectUdpClient;
    private static NioEventLoopGroup clientGroup;

    @BeforeClass
    public static void startServers() {
        // the test JVM defaults to ERROR, which would drop the WARN entries this class asserts on
        limited = startWithHttp3(configuration().http3MaxIdleTimeout(30000L).maxHeaderSize(LIMIT).logLevel("WARN"));
        limitedClient = new MockServerClient("127.0.0.1", limited.getLocalPort());
        defaults = startWithHttp3(configuration().http3MaxIdleTimeout(30000L).logLevel("WARN"));
        defaultsClient = new MockServerClient("127.0.0.1", defaults.getLocalPort());
        // CONNECT-UDP puts a handler of its own ahead of the request handler on every stream
        connectUdp = startWithHttp3(configuration().http3MaxIdleTimeout(30000L).http3ConnectUdpEnabled(true).maxHeaderSize(LIMIT).logLevel("WARN"));
        connectUdpClient = new MockServerClient("127.0.0.1", connectUdp.getLocalPort());
        clientGroup = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(limitedClient);
        stopQuietly(limited);
        stopQuietly(defaultsClient);
        stopQuietly(defaults);
        stopQuietly(connectUdpClient);
        stopQuietly(connectUdp);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Before
    public void resetServers() {
        limitedClient.reset();
        limitedClient.when(request().withPath("/limit")).respond(response().withBody("served"));
        defaultsClient.reset();
        defaultsClient.when(request().withPath("/limit")).respond(response().withBody("served"));
        connectUdpClient.reset();
        connectUdpClient.when(request().withPath("/limit")).respond(response().withBody("served"));
    }

    @Test
    public void shouldAdvertiseMaxHeaderSizeAsTheFieldSectionLimit() throws Exception {
        try (Http3Connection connection = Http3Connection.open(limited)) {
            assertThat(connection.serverSettings().get(Http3SettingsFrame.HTTP3_SETTINGS_MAX_FIELD_SECTION_SIZE), is((long) LIMIT));
        }
        try (Http3Connection connection = Http3Connection.open(defaults)) {
            assertThat(connection.serverSettings().get(Http3SettingsFrame.HTTP3_SETTINGS_MAX_FIELD_SECTION_SIZE), is((long) DEFAULT_LIMIT));
        }
    }

    @Test
    public void shouldServeAHeaderSectionOfExactlyMaxHeaderSize() throws Exception {
        try (Http3Connection connection = Http3Connection.open(limited)) {
            Exchange exchange = connection.send(headersOfSize(limited, LIMIT));

            assertThat(exchange.status(), is(200));
            assertThat(exchange.body(), is("served"));
        }
        assertThat(limitedClient.retrieveRecordedRequests(request().withPath("/limit"))[0].getFirstHeader(FILLER).length(), greaterThan(LIMIT - 256));
    }

    @Test
    public void shouldCloseTheConnectionForAHeaderSectionOneByteOver() throws Exception {
        assertClosedAndLoggedOnceForAHeaderSectionOneByteOver(limited, limitedClient);
    }

    @Test
    public void shouldCloseTheConnectionForAHeaderSectionOneByteOverWithConnectUdpEnabled() throws Exception {
        assertClosedAndLoggedOnceForAHeaderSectionOneByteOver(connectUdp, connectUdpClient);
    }

    private static void assertClosedAndLoggedOnceForAHeaderSectionOneByteOver(MockServer mockServer, MockServerClient mockServerClient) throws Exception {
        int clientPort;
        try (Http3Connection connection = Http3Connection.open(mockServer)) {
            clientPort = connection.localPort();
            Http3Headers overLimit = headersOfSize(mockServer, LIMIT + 1);

            connection.send(overLimit);

            QuicConnectionCloseEvent closed = connection.closedByServer();
            assertThat(closed.isApplicationClose(), is(true));
            assertThat(closed.error(), is(H3_EXCESSIVE_LOAD));
        }
        assertThat("never dispatched", mockServerClient.retrieveRecordedRequests(request().withPath("/limit")), emptyArray());
        assertThat(warnings(mockServerClient, "because a request's header section is larger than maxHeaderSize"), is(1L));
        assertThat("the warning names the client's address", warnings(mockServerClient, "127.0.0.1:" + clientPort), is(1L));
        assertThat("logged once, and not as an unexpected exception", warnings(mockServerClient, "exception in HTTP/3 request handler"), is(0L));

        try (Http3Connection next = Http3Connection.open(mockServer)) {
            assertThat("the server carries on", next.send(headersOfSize(mockServer, LIMIT)).status(), is(200));
        }
    }

    @Test
    public void shouldCloseTheConnectionForAHeadersFrameLongerThanTheLimitBeforeReadingIt() throws Exception {
        try (Http3Connection connection = Http3Connection.open(limited)) {
            // not compressible: Netty's HTTP/3 client sends a field it has no table entry for as it is
            connection.send(headersOfSize(limited, 3 * LIMIT));

            QuicConnectionCloseEvent closed = connection.closedByServer();
            assertThat(closed.isApplicationClose(), is(true));
            assertThat(closed.error(), is(H3_EXCESSIVE_LOAD));
        }
        assertThat("never dispatched", limitedClient.retrieveRecordedRequests(request().withPath("/limit")), emptyArray());
        assertThat(warnings(limitedClient, "because a request's header section is larger than maxHeaderSize"), is(1L));
    }

    /**
     * 100,000 fields the QPACK static table holds whole, so a byte each on the wire and 64 bytes each decoded:
     * about 100 KB sent, under the default limit so the frame is read, and 6.4 MB decoded, 24 times over it.
     */
    @Test
    public void shouldCloseTheConnectionForAHeaderSectionThatDecodesFarOverTheLimit() throws Exception {
        int references = 100_000;
        try (Http3Connection connection = Http3Connection.open(defaults)) {
            Http3Headers bomb = pseudoHeaders(defaults);
            for (int i = 0; i < references; i++) {
                bomb.add("accept-encoding", "gzip, deflate, br");
            }
            long decodedBytes = (long) references * ("accept-encoding".length() + "gzip, deflate, br".length() + FIELD_OVERHEAD);
            assertThat("far over the limit decoded", decodedBytes, greaterThan(20L * DEFAULT_LIMIT));

            connection.send(bomb);

            QuicConnectionCloseEvent closed = connection.closedByServer();
            assertThat(closed.error(), is(H3_EXCESSIVE_LOAD));
            // the decoded size was refused, not the frame's length, so the section was small on the wire
            assertThat(new String(closed.reason(), StandardCharsets.US_ASCII), startsWith("Header size exceeded max allowed size"));
        }
        assertThat("never dispatched", defaultsClient.retrieveRecordedRequests(request().withPath("/limit")), emptyArray());
        assertThat(warnings(defaultsClient, "because a request's header section is larger than maxHeaderSize"), is(1L));
    }

    /**
     * One request, with one large header, over each protocol: served by all three when it is within
     * {@code maxHeaderSize}, and refused by all three when it is over.
     */
    @Test
    public void shouldAcceptOrRefuseTheSameRequestOverEveryProtocol() throws Exception {
        assertSameOutcomeOverEveryProtocol(limited, LIMIT / 2, true);
        assertSameOutcomeOverEveryProtocol(limited, LIMIT + LIMIT / 8, false);
        // over the 8,192 bytes that HTTP/2 and HTTP/3 were limited to whatever maxHeaderSize was
        assertSameOutcomeOverEveryProtocol(defaults, 100 * 1024, true);
        assertSameOutcomeOverEveryProtocol(defaults, DEFAULT_LIMIT + DEFAULT_LIMIT / 8, false);
    }

    private static void assertSameOutcomeOverEveryProtocol(MockServer mockServer, int headerValueLength, boolean served) throws Exception {
        String value = "a".repeat(headerValueLength);
        String description = "a " + headerValueLength + " byte header with maxHeaderSize " + mockServer.getConfiguration().maxHeaderSize();

        String http1Response = http1Response(mockServer, "GET /limit HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n" + FILLER + ": " + value + "\r\n\r\n");
        assertThat(description + " over HTTP/1.1", http1Response, startsWith(served ? "HTTP/1.1 200 " : "HTTP/1.1 431 "));

        try (Http2TestClient client = Http2TestClient.h2c(clientGroup, mockServer.getLocalPort())) {
            Http2Headers headers = new DefaultHttp2Headers().method(HttpMethod.GET.asciiName()).scheme("http").authority("localhost").path("/limit").add(FILLER, value);
            assertThat(description + " over HTTP/2", client.send(headers, true).status(), is(served ? 200 : 431));
        }

        try (Http3Connection connection = Http3Connection.open(mockServer)) {
            Http3Headers headers = pseudoHeaders(mockServer);
            headers.add(FILLER, value);
            Exchange exchange = connection.send(headers);
            if (served) {
                assertThat(description + " over HTTP/3", exchange.status(), is(200));
            } else {
                assertThat(description + " over HTTP/3", connection.closedByServer().error(), is(H3_EXCESSIVE_LOAD));
            }
        }
    }

    private static String http1Response(MockServer mockServer, String request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", mockServer.getLocalPort()), 5_000);
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            OutputStream out = socket.getOutputStream();
            try {
                out.write(request.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (java.io.IOException refusedWhileStillSending) {
                // the response is still expected
            }
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
                response.write(buffer, 0, read);
            }
            return response.toString(StandardCharsets.US_ASCII);
        }
    }

    private static Http3Headers pseudoHeaders(MockServer mockServer) {
        return new DefaultHttp3Headers()
            .method(HttpMethod.GET.asciiName())
            .scheme("https")
            .authority("127.0.0.1:" + mockServer.getHttp3Port())
            .path("/limit");
    }

    /**
     * A request whose header section is exactly {@code size} bytes as RFC 9114 counts it, made up with one filler field.
     */
    private static Http3Headers headersOfSize(MockServer mockServer, int size) {
        Http3Headers headers = pseudoHeaders(mockServer);
        long fillerValueLength = size - fieldSectionSize(headers) - FILLER.length() - FIELD_OVERHEAD;
        headers.add(FILLER, "a".repeat((int) fillerValueLength));
        assertThat(fieldSectionSize(headers), is((long) size));
        return headers;
    }

    private static long fieldSectionSize(Http3Headers headers) {
        long size = 0;
        for (Map.Entry<CharSequence, CharSequence> field : headers) {
            size += field.getKey().length() + field.getValue().length() + FIELD_OVERHEAD;
        }
        return size;
    }

    private static long warnings(MockServerClient client, String text) {
        return Arrays.stream(client.retrieveLogMessagesArray(null)).filter(message -> message.contains(text)).count();
    }

    private static final class Exchange {
        private final CompletableFuture<Integer> status = new CompletableFuture<>();
        private final CompletableFuture<String> body = new CompletableFuture<>();

        int status() throws Exception {
            return status.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        String body() throws Exception {
            return body.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static final class Http3Connection implements AutoCloseable {
        private final Channel datagramChannel;
        private final QuicChannel quicChannel;
        private final CompletableFuture<Http3SettingsFrame> serverSettings;
        private final CompletableFuture<QuicConnectionCloseEvent> closedByServer;

        private Http3Connection(Channel datagramChannel, QuicChannel quicChannel, CompletableFuture<Http3SettingsFrame> serverSettings, CompletableFuture<QuicConnectionCloseEvent> closedByServer) {
            this.datagramChannel = datagramChannel;
            this.quicChannel = quicChannel;
            this.serverSettings = serverSettings;
            this.closedByServer = closedByServer;
        }

        static Http3Connection open(MockServer mockServer) throws Exception {
            assertThat("the HTTP/3 server started", mockServer.getHttp3Port(), greaterThan(0));
            QuicSslContext sslContext = QuicSslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols(Http3.supportedApplicationProtocols())
                .build();
            Channel datagramChannel = new Bootstrap()
                .group(clientGroup)
                .channelFactory(Ipv4DatagramChannelFactory.INSTANCE)
                .handler(Http3.newQuicClientCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(30000, TimeUnit.MILLISECONDS)
                    .initialMaxData(10000000)
                    .initialMaxStreamDataBidirectionalLocal(1000000)
                    .initialMaxStreamsBidirectional(100)
                    .build())
                .bind(0)
                .sync()
                .channel();
            CompletableFuture<Http3SettingsFrame> serverSettings = new CompletableFuture<>();
            CompletableFuture<QuicConnectionCloseEvent> closedByServer = new CompletableFuture<>();
            ChannelInboundHandlerAdapter controlStreamHandler = new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    if (msg instanceof Http3SettingsFrame) {
                        serverSettings.complete((Http3SettingsFrame) msg);
                    }
                    ReferenceCountUtil.release(msg);
                }

                @Override
                public boolean isSharable() {
                    return true;
                }
            };
            QuicChannel quicChannel = QuicChannel.newBootstrap(datagramChannel)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel ch) {
                        ch.pipeline().addLast(new Http3ClientConnectionHandler(controlStreamHandler, null, null, null, true));
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                                if (evt instanceof QuicConnectionCloseEvent) {
                                    closedByServer.complete((QuicConnectionCloseEvent) evt);
                                }
                                ctx.fireUserEventTriggered(evt);
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                // a connection the server closes is observed through closedByServer()
                            }
                        });
                    }
                })
                .remoteAddress(new InetSocketAddress("127.0.0.1", mockServer.getHttp3Port()))
                .connect()
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
            return new Http3Connection(datagramChannel, quicChannel, serverSettings, closedByServer);
        }

        int localPort() {
            return ((InetSocketAddress) datagramChannel.localAddress()).getPort();
        }

        Http3SettingsFrame serverSettings() throws Exception {
            return serverSettings.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        QuicConnectionCloseEvent closedByServer() throws Exception {
            return closedByServer.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        Exchange send(Http3Headers headers) throws Exception {
            Exchange exchange = new Exchange();
            QuicStreamChannel stream = Http3.newRequestStream(quicChannel, new Http3RequestStreamInboundHandler() {
                private final ByteArrayOutputStream body = new ByteArrayOutputStream();

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
                    if (frame.headers().status() != null) {
                        exchange.status.complete(Integer.parseInt(frame.headers().status().toString()));
                    }
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
                    body.writeBytes(ByteBufUtil.getBytes(frame.content()));
                    frame.release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    exchange.body.complete(body.toString(StandardCharsets.UTF_8));
                    ctx.close();
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) {
                    IllegalStateException closed = new IllegalStateException("stream closed without a response");
                    exchange.status.completeExceptionally(closed);
                    exchange.body.completeExceptionally(closed);
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    exchange.status.completeExceptionally(cause);
                    exchange.body.completeExceptionally(cause);
                }
            }).sync().getNow();
            stream.writeAndFlush(new DefaultHttp3HeadersFrame(headers)).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            return exchange;
        }

        @Override
        public void close() {
            quicChannel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
            datagramChannel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
    }
}
