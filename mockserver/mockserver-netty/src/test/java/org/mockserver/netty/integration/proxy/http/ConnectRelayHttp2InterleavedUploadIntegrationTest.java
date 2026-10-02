package org.mockserver.netty.integration.proxy.http;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.util.ReferenceCountUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.HttpError;
import org.mockserver.netty.MockServer;
import org.mockserver.socket.tls.PEMToFile;
import org.mockserver.test.Http2FlowControlBodies;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.StringBody.exact;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.test.Http2FlowControlBodies.Size.OVER_WINDOW;

/**
 * HTTP/2 requests whose uploads finish out of order reach the CONNECT relay's loopback in the order they finish, so
 * each gets a loopback stream id of its own: reusing the client's ids, a stream that finished after a later one closed
 * the whole tunnel. Every response must come back on the client stream that asked for it, and the tunnel must stay up.
 */
public class ConnectRelayHttp2InterleavedUploadIntegrationTest {

    private static final String TARGET = "localhost:443";
    private static final String LARGE_UPLOAD = Http2FlowControlBodies.body(OVER_WINDOW, "slow-upload");
    private static final int LARGE_UPLOAD_CHUNKS = 16;
    private static final int SMALL_STREAMS = 3;

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static int mockServerPort;
    private static SslContext clientSslContext;
    private static EventLoopGroup clientGroup;

    @BeforeClass
    public static void startServer() throws Exception {
        mockServer = new MockServer(configuration());
        mockServerPort = mockServer.getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
        // each answer names its request and needs its exact upload, so a crossed or truncated stream reads as a 404
        mockServerClient.when(request().withMethod("POST").withPath("/large").withBody(exact(LARGE_UPLOAD))).respond(response().withBody(answer("/large")));
        mockServerClient.when(request().withPath("/expect")).respond(response().withBody(answer("/expect")));
        mockServerClient.when(request().withPath("/expect-slow")).respond(response().withBody(answer("/expect-slow")).withDelay(TimeUnit.MILLISECONDS, 1_000));
        mockServerClient.when(request().withMethod("GET").withPath("/slow-get")).respond(response().withBody(answer("/slow-get")).withDelay(TimeUnit.MILLISECONDS, 1_500));
        mockServerClient.when(request().withMethod("GET").withPath("/get")).respond(response().withBody(answer("/get")));
        for (int i = 0; i <= SMALL_STREAMS; i++) {
            mockServerClient.when(request().withMethod("POST").withPath("/small-" + i).withBody(exact(upload("/small-" + i)))).respond(response().withBody(answer("/small-" + i)));
        }
        mockServerClient.when(request().withPath("/held")).respond(response().withBody(answer("/held")).withDelay(TimeUnit.MILLISECONDS, 1_000));
        mockServerClient.when(request().withPath("/reset-cancel")).error(error().withStreamError(HttpError.StreamErrorCode.CANCEL));

        clientSslContext = SslContextBuilder.forClient()
            .trustManager(mockServerCaCertificate())
            .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            .applicationProtocolConfig(new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                ApplicationProtocolNames.HTTP_2))
            .build();
        clientGroup = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30_000)
    public void shouldRelayUploadsThatFinishOutOfOrderOverTls() throws Exception {
        try (RelayClient client = RelayClient.connect(true)) {
            assertInterleavedUploadsAnswered(client);
        }
    }

    @Test(timeout = 30_000)
    public void shouldRelayUploadsThatFinishOutOfOrderOverH2c() throws Exception {
        try (RelayClient client = RelayClient.connect(false)) {
            assertInterleavedUploadsAnswered(client);
        }
    }

    @Test(timeout = 30_000)
    public void shouldAnswerAnUploadThatFinishesAfterAGetOpenedBehindIt() throws Exception {
        long start = System.nanoTime();
        try (RelayClient client = RelayClient.connect(true)) {
            RelayStream upload = client.open("/small-1");
            RelayStream get = client.open("/get", HttpMethod.GET, true);
            assertAnswered(get, "/get");

            upload.send(upload("/small-1"), true);
            assertAnswered(upload, "/small-1");
        }
        // a tunnel closed under the upload answers only at Netty's 30 s graceful-shutdown timeout
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), lessThan(5_000L));
    }

    @Test(timeout = 30_000)
    public void shouldNotRelayARequestSentWithExpectTwice() throws Exception {
        try (RelayClient client = RelayClient.connect(true)) {
            RelayStream expecting = client.open("/expect", HttpMethod.POST, false, "expect", "100-continue");
            // the client-facing adapter hands the headers on at once, and MockServer answers them
            assertAnswered(expecting, "/expect");
            TimeUnit.MILLISECONDS.sleep(200);
            expecting.send(upload("/expect"), true);

            RelayStream after = client.open("/get", HttpMethod.GET, true);
            assertAnswered(after, "/get");
            // a second /expect would be on its way to the log by now; give it time to land
            TimeUnit.MILLISECONDS.sleep(500);
            assertThat(mockServerClient.retrieveRecordedRequests(request().withPath("/expect")).length, is(1));
        }
    }

    @Test(timeout = 30_000)
    public void shouldKeepTheTunnelWhenAnExpectRequestsBodyFollowsAtOnce() throws Exception {
        try (RelayClient client = RelayClient.connect(true)) {
            RelayStream slowGet = client.open("/slow-get", HttpMethod.GET, true);
            RelayStream expecting = client.open("/expect-slow", HttpMethod.POST, false, "expect", "100-continue");
            // the body arrives while MockServer is still answering the headers the relay has already sent on
            expecting.send(upload("/expect-slow"), true);

            assertAnswered(slowGet, "/slow-get");
            assertAnswered(expecting, "/expect-slow");
            RelayStream after = client.open("/get", HttpMethod.GET, true);
            assertAnswered(after, "/get");
            assertThat("the tunnel is still open", client.connection.isActive(), is(true));
        }
    }

    @Test(timeout = 30_000)
    public void shouldRelayMockServersResetOnARemappedStreamToItsOwnClientStream() throws Exception {
        try (RelayClient client = RelayClient.connect(true)) {
            // the client's Http2FrameCodec (DefaultHttp2Connection's DefaultEndpoint) keeps stream 1 for an upgrade, so
            // client streams start at 3: this one is 3 and holds no loopback stream
            RelayStream large = client.open("/large");
            large.send(LARGE_UPLOAD.substring(0, LARGE_UPLOAD.length() / 2), false);
            // client 5 -> loopback 1, client 7 -> loopback 3, client 9 -> loopback 5: the reset stream's loopback id
            // is held's client id, so a reset relayed by raw id would hit held
            RelayStream held = client.get("/held");
            RelayStream otherHeld = client.get("/held");
            RelayStream reset = client.get("/reset-cancel");

            StreamOutcome resetOutcome = reset.outcome.get(5, TimeUnit.SECONDS);
            assertThat(resetOutcome.toString(), resetOutcome.resetCode, is(Http2Error.CANCEL.code()));
            assertAnswered(held, "/held");
            assertAnswered(otherHeld, "/held");
            large.send(LARGE_UPLOAD.substring(LARGE_UPLOAD.length() / 2), true);
            assertAnswered(large, "/large");
        }
    }

    @Test(timeout = 30_000)
    public void shouldResetTheRemappedLoopbackStreamOfAStreamTheClientResets() throws Exception {
        try (RelayClient client = RelayClient.connect(true)) {
            // the client's Http2FrameCodec (DefaultHttp2Connection's DefaultEndpoint) keeps stream 1 for an upgrade, so
            // client streams start at 3: this one is 3 and holds no loopback stream
            RelayStream large = client.open("/large");
            large.send(LARGE_UPLOAD.substring(0, LARGE_UPLOAD.length() / 2), false);
            // client 5 -> loopback 1, client 7 -> loopback 3, client 9 -> loopback 5: sameIdAsCancelled's loopback id
            // is cancelled's client id, so a reset relayed by raw id would hit sameIdAsCancelled
            RelayStream cancelled = client.get("/held");
            RelayStream kept = client.get("/held");
            RelayStream sameIdAsCancelled = client.get("/held");
            TimeUnit.MILLISECONDS.sleep(200);

            cancelled.channel.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.CANCEL)).sync();

            assertAnswered(kept, "/held");
            assertAnswered(sameIdAsCancelled, "/held");
            large.send(LARGE_UPLOAD.substring(LARGE_UPLOAD.length() / 2), true);
            assertAnswered(large, "/large");
        }
    }

    @Test(timeout = 30_000)
    public void shouldKeepTheTunnelWhenTheRelayResetsAStreamWhoseExpectHeadersItHasRelayed() throws Exception {
        try (RelayClient client = RelayClient.connect(true)) {
            RelayStream kept = client.get("/held");
            // relayed as its headers at once; its body then breaks the content-length it declared, which is a stream
            // error in the relay, not a reset from the client
            RelayStream broken = client.open("/held", HttpMethod.POST, false, "expect", "100-continue", "content-length", "1");
            TimeUnit.MILLISECONDS.sleep(200);
            broken.send("longer than declared", true);

            StreamOutcome brokenOutcome = broken.outcome.get(5, TimeUnit.SECONDS);
            assertThat(brokenOutcome.toString(), brokenOutcome.resetCode, is(Http2Error.PROTOCOL_ERROR.code()));
            assertAnswered(kept, "/held");
            // past the delay of the answer MockServer was preparing for the broken stream
            TimeUnit.MILLISECONDS.sleep(1_200);
            assertAnswered(client.open("/get", HttpMethod.GET, true), "/get");
            assertThat("the tunnel is still open", client.connection.isActive(), is(true));
        }
    }

    private static void assertInterleavedUploadsAnswered(RelayClient client) throws Exception {
        // the first stream opened sends its upload slowly; it finishes after every later stream
        RelayStream large = client.open("/large");
        int chunk = LARGE_UPLOAD.length() / LARGE_UPLOAD_CHUNKS;
        large.send(LARGE_UPLOAD.substring(0, chunk), false);

        List<RelayStream> smalls = new ArrayList<>();
        for (int i = 1; i <= SMALL_STREAMS; i++) {
            RelayStream small = client.open("/small-" + i);
            small.send(upload("/small-" + i), true);
            smalls.add(small);
        }
        for (int i = 1; i <= SMALL_STREAMS; i++) {
            assertAnswered(smalls.get(i - 1), "/small-" + i);
        }

        for (int i = 1; i < LARGE_UPLOAD_CHUNKS; i++) {
            TimeUnit.MILLISECONDS.sleep(10);
            boolean last = i == LARGE_UPLOAD_CHUNKS - 1;
            large.send(LARGE_UPLOAD.substring(i * chunk, last ? LARGE_UPLOAD.length() : (i + 1) * chunk), last);
        }
        assertAnswered(large, "/large");

        // the tunnel is still up for a stream opened after the out-of-order one
        RelayStream after = client.open("/small-0");
        after.send(upload("/small-0"), true);
        assertAnswered(after, "/small-0");
        assertThat("the tunnel is still open", client.connection.isActive(), is(true));
    }

    private static void assertAnswered(RelayStream stream, String path) throws Exception {
        StreamOutcome outcome = stream.outcome.get(10, TimeUnit.SECONDS);
        assertThat(path + ": " + outcome, outcome.resetCode == null, is(true));
        assertThat(path + ": " + outcome, outcome.status, is(200));
        assertThat(path, new String(outcome.body, StandardCharsets.UTF_8), is(answer(path)));
    }

    private static String upload(String path) {
        return "upload for " + path;
    }

    private static String answer(String path) {
        return "answer for " + path;
    }

    private static X509Certificate mockServerCaCertificate() throws Exception {
        try (InputStream in = ConnectRelayHttp2InterleavedUploadIntegrationTest.class.getClassLoader()
            .getResourceAsStream(ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE)) {
            if (in == null) {
                throw new IllegalStateException("could not load MockServer CA certificate from classpath");
            }
            return PEMToFile.x509FromPEM(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static final class StreamOutcome {
        private final int status;
        private final byte[] body;
        private final Long resetCode;

        private StreamOutcome(int status, byte[] body, Long resetCode) {
            this.status = status;
            this.body = body;
            this.resetCode = resetCode;
        }

        @Override
        public String toString() {
            return "status " + status + ", " + body.length + " body bytes, reset " + resetCode;
        }
    }

    private static final class RelayStream {
        private final CompletableFuture<StreamOutcome> outcome = new CompletableFuture<>();
        private Http2StreamChannel channel;

        void send(String data, boolean endOfStream) throws Exception {
            channel.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.copiedBuffer(data, StandardCharsets.UTF_8), endOfStream)).sync();
        }
    }

    /**
     * An HTTP/2 connection through MockServer's CONNECT proxy: CONNECT over HTTP/1.1, then TLS with ALPN {@code h2},
     * or cleartext HTTP/2 with prior knowledge.
     */
    private static final class RelayClient implements AutoCloseable {
        private final Channel connection;
        private final HttpScheme scheme;

        private RelayClient(Channel connection, HttpScheme scheme) {
            this.connection = connection;
            this.scheme = scheme;
        }

        static RelayClient connect(boolean tls) throws Exception {
            CompletableFuture<Integer> connectStatus = new CompletableFuture<>();
            Channel channel = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("http-codec", new HttpClientCodec());
                        ch.pipeline().addLast("http-aggregator", new HttpObjectAggregator(64 * 1024));
                        ch.pipeline().addLast("connect-response", new SimpleChannelInboundHandler<FullHttpResponse>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
                                ctx.pipeline().remove("http-codec");
                                ctx.pipeline().remove("http-aggregator");
                                ctx.pipeline().remove(this);
                                connectStatus.complete(response.status().code());
                            }
                        });
                    }
                })
                .connect("127.0.0.1", mockServerPort).sync().channel();
            DefaultFullHttpRequest connectRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, TARGET);
            connectRequest.headers().set(HttpHeaderNames.HOST, TARGET);
            channel.writeAndFlush(connectRequest);
            assertThat(connectStatus.get(10, TimeUnit.SECONDS), is(200));

            SslHandler sslHandler = tls ? clientSslContext.newHandler(channel.alloc(), "localhost", 443) : null;
            if (sslHandler != null) {
                // the codec goes in with the TLS handler, as the relay's SETTINGS can follow its handshake at once
                channel.pipeline().addLast(sslHandler);
            }
            channel.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
            channel.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel pushedStream) {
                    // MockServer does not push
                }
            }));
            if (sslHandler != null) {
                sslHandler.handshakeFuture().sync();
                assertThat("the tunnel carries HTTP/2", sslHandler.applicationProtocol(), is(ApplicationProtocolNames.HTTP_2));
            }
            return new RelayClient(channel, tls ? HttpScheme.HTTPS : HttpScheme.HTTP);
        }

        RelayStream get(String path) throws Exception {
            RelayStream stream = open(path);
            stream.send("", true);
            return stream;
        }

        RelayStream open(String path) throws Exception {
            return open(path, HttpMethod.POST, false);
        }

        RelayStream open(String path, HttpMethod method, boolean endOfStream, CharSequence... extraHeaders) throws Exception {
            RelayStream relayStream = new RelayStream();
            relayStream.channel = new Http2StreamChannelBootstrap(connection)
                .handler(new ChannelInboundHandlerAdapter() {
                    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
                    private int status = -1;

                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        try {
                            if (msg instanceof Http2HeadersFrame) {
                                Http2HeadersFrame headers = (Http2HeadersFrame) msg;
                                if (headers.headers().status() != null) {
                                    status = Integer.parseInt(headers.headers().status().toString());
                                }
                                if (headers.isEndStream()) {
                                    complete(null);
                                }
                            } else if (msg instanceof Http2DataFrame) {
                                Http2DataFrame data = (Http2DataFrame) msg;
                                body.writeBytes(ByteBufUtil.getBytes(data.content()));
                                if (data.isEndStream()) {
                                    complete(null);
                                }
                            } else if (msg instanceof Http2ResetFrame) {
                                complete(((Http2ResetFrame) msg).errorCode());
                            }
                        } finally {
                            ReferenceCountUtil.release(msg);
                        }
                    }

                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                        if (evt instanceof Http2ResetFrame) {
                            complete(((Http2ResetFrame) evt).errorCode());
                        }
                    }

                    @Override
                    public void channelInactive(ChannelHandlerContext ctx) {
                        relayStream.outcome.completeExceptionally(new IOException("stream closed without a response or a reset: " + body.size() + " body bytes"));
                    }

                    private void complete(Long resetCode) {
                        relayStream.outcome.complete(new StreamOutcome(status, body.toByteArray(), resetCode));
                    }
                })
                .open().sync().getNow();
            DefaultHttp2Headers headers = new DefaultHttp2Headers();
            headers.method(method.asciiName()).scheme(scheme.name()).authority(TARGET).path(path);
            for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
                headers.add(extraHeaders[i], extraHeaders[i + 1]);
            }
            relayStream.channel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, endOfStream)).sync();
            return relayStream;
        }

        @Override
        public void close() {
            connection.close().syncUninterruptibly();
        }
    }
}
