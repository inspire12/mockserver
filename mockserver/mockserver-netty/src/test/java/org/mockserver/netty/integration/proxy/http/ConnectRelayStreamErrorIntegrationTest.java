package org.mockserver.netty.integration.proxy.http;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
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
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
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
import org.mockserver.configuration.Configuration;
import org.mockserver.model.HttpError;
import org.mockserver.netty.MockServer;
import org.mockserver.test.Http2FlowControlBodies;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.MockServerCaTrustTestSupport.caCertificate;
import static org.mockserver.netty.MockServerCaTrustTestSupport.caTrustingSslContext;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.test.Http2FlowControlBodies.Size.OVER_WINDOW;

/**
 * A response the CONNECT relay's loopback cannot relay reaches the proxy client at once, not at its own timeout: over
 * HTTP/2 the client's stream is reset and the connection's other streams carry on; over HTTP/1.1 the client gets a
 * {@code 502}, or, once the response head has gone, a connection closed before the terminating chunk. The responses are
 * MockServer's own, so they cross the relay's loopback leg, which decodes them.
 */
public class ConnectRelayStreamErrorIntegrationTest {

    // under PROMPT, so a reset that arrives only at a client's own timeout fails the bound rather than the test timing out
    private static final long PROMPT_MILLIS = 2_000;
    private static final int MAX_BODY_SIZE = 512 * 1024;
    private static final String TARGET = "localhost:443";
    private static final String HEALTHY = Http2FlowControlBodies.body(OVER_WINDOW, "relay-stream-error-healthy");
    private static final byte[] CORRUPT_GZIP = corruptGzip();
    private static final byte[] CORRUPT_ZSTD = corruptZstd();

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static int mockServerPort;
    private static SslContext clientSslContext;
    private static EventLoopGroup clientGroup;

    @BeforeClass
    public static void startServer() throws Exception {
        assertThrows("the gzip body is corrupt", IOException.class, () -> new GZIPInputStream(new ByteArrayInputStream(CORRUPT_GZIP)).readAllBytes());
        assertThrows("the zstd body is corrupt", RuntimeException.class, () -> com.github.luben.zstd.Zstd.decompress(CORRUPT_ZSTD, 5));

        Configuration configuration = configuration();
        mockServer = new MockServer(configuration);
        mockServerPort = mockServer.getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
        mockServerClient.when(request().withPath("/healthy")).respond(response().withBody(HEALTHY));
        mockServerClient.when(request().withPath("/slow")).respond(response().withBody(HEALTHY).withDelay(TimeUnit.MILLISECONDS, 500));
        mockServerClient.when(request().withPath("/corrupt-gzip")).respond(response().withHeader("content-encoding", "gzip").withBody(binary(CORRUPT_GZIP)));
        mockServerClient.when(request().withPath("/corrupt-gzip-stream")).respond(response().withHeader("content-type", "text/event-stream").withHeader("content-encoding", "gzip").withBody(binary(CORRUPT_GZIP)));
        mockServerClient.when(request().withPath("/corrupt-zstd")).respond(response().withHeader("content-encoding", "zstd").withBody(binary(CORRUPT_ZSTD)));
        mockServerClient.when(request().withPath("/oversized")).respond(response().withBody(binary(new byte[2 * MAX_BODY_SIZE])));
        mockServerClient.when(request().withPath("/reset-cancel")).error(error().withStreamError(HttpError.StreamErrorCode.CANCEL));
        mockServerClient.when(request().withPath("/reset-unregistered-code")).error(error().withStreamError(0x77L));
        // lowered once the expectations are in, since each expectation's own upload is bounded by it
        configuration.maxRequestBodySize(MAX_BODY_SIZE);

        clientSslContext = SslContextBuilder.forClient()
            .trustManager(caCertificate())
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
    public void shouldResetTheClientStreamWhenTheResponseFailsToDecode() throws Exception {
        assertResetPromptlyAlongsideAHealthyStream("/corrupt-zstd", Http2Error.INTERNAL_ERROR.code());
    }

    @Test(timeout = 30_000)
    public void shouldResetTheClientStreamWhenTheResponseIsOverMaxRequestBodySize() throws Exception {
        assertResetPromptlyAlongsideAHealthyStream("/oversized", Http2Error.INTERNAL_ERROR.code());
    }

    @Test(timeout = 30_000)
    public void shouldResetTheClientStreamWhenTheGzipResponseIsCorrupt() throws Exception {
        assertResetPromptlyAlongsideAHealthyStream("/corrupt-gzip", Http2Error.INTERNAL_ERROR.code());
    }

    @Test(timeout = 30_000)
    public void shouldRelayAnUpstreamStreamResetWithItsErrorCode() throws Exception {
        assertResetPromptlyAlongsideAHealthyStream("/reset-cancel", Http2Error.CANCEL.code());
        assertResetPromptlyAlongsideAHealthyStream("/reset-unregistered-code", 0x77L);
    }

    @Test(timeout = 30_000)
    public void shouldKeepTheConnectionWhenTheClientResetsAStreamBeforeItsResponse() throws Exception {
        try (RelayClient client = RelayClient.connect()) {
            client.start("/slow").channel.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.CANCEL)).sync();
            // past the cancelled stream's 500 ms delay, so its response would have arrived by now
            TimeUnit.MILLISECONDS.sleep(1_000);
            assertHealthy(client.get("/healthy").get(10, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 30_000)
    public void shouldAnswerA502WhenAnAggregatedHttp1ResponseFailsToDecode() throws Exception {
        long start = System.nanoTime();
        HttpResponse<byte[]> response = throughConnectOverHttp1("/corrupt-gzip");
        assertThat(response.statusCode(), is(502));
        assertThat(elapsedMillisSince(start), lessThan(PROMPT_MILLIS));
    }

    @Test(timeout = 30_000)
    public void shouldAnswerA502WhenAnHttp1ResponseIsOverMaxRequestBodySize() throws Exception {
        long start = System.nanoTime();
        HttpResponse<byte[]> response = throughConnectOverHttp1("/oversized");
        assertThat(response.statusCode(), is(502));
        assertThat(elapsedMillisSince(start), lessThan(PROMPT_MILLIS));
    }

    @Test(timeout = 30_000)
    public void shouldCloseWithoutTheTerminatingChunkWhenAStreamedHttp1ResponseFailsToDecode() {
        long start = System.nanoTime();
        // the head has gone to the client, so the only signal left is an incomplete response
        IOException incomplete = assertThrows(IOException.class, () -> throughConnectOverHttp1("/corrupt-gzip-stream"));
        assertThat(incomplete.toString(), elapsedMillisSince(start), lessThan(PROMPT_MILLIS));
    }

    private static void assertResetPromptlyAlongsideAHealthyStream(String path, long expectedResetCode) throws Exception {
        try (RelayClient client = RelayClient.connect()) {
            CompletableFuture<StreamOutcome> inFlight = client.get("/slow");
            StreamOutcome failed = client.get(path).get(PROMPT_MILLIS, TimeUnit.MILLISECONDS);
            assertThat(failed.toString(), failed.resetCode, is(expectedResetCode));
            assertThat(failed.status, is(-1));
            assertThat(failed.elapsedMillis, lessThan(PROMPT_MILLIS));
            // the stream in flight on the same connection when the other was reset, and one opened after it
            assertHealthy(inFlight.get(10, TimeUnit.SECONDS));
            assertHealthy(client.get("/healthy").get(10, TimeUnit.SECONDS));
        }
    }

    private static void assertHealthy(StreamOutcome outcome) {
        assertThat(outcome.toString(), outcome.resetCode == null, is(true));
        assertThat(outcome.status, is(200));
        assertThat(new String(outcome.body, StandardCharsets.UTF_8), is(HEALTHY));
    }

    private static HttpResponse<byte[]> throughConnectOverHttp1(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", mockServerPort)))
            .sslContext(caTrustingSslContext())
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        return client.send(java.net.http.HttpRequest.newBuilder(URI.create("https://" + TARGET + path)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static long elapsedMillisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static byte[] corruptGzip() {
        // a gzip header, then a deflate block of the reserved type 3
        return new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
    }

    private static byte[] corruptZstd() {
        // magic, a single-segment frame header declaring 5 bytes, then a last block of the reserved type 3
        return new byte[]{0x28, (byte) 0xB5, 0x2F, (byte) 0xFD, 0x20, 0x05, 0x07, 0x00, 0x00};
    }

    private static final class StreamOutcome {
        private final int status;
        private final byte[] body;
        private final Long resetCode;
        private final long elapsedMillis;

        private StreamOutcome(int status, byte[] body, Long resetCode, long elapsedMillis) {
            this.status = status;
            this.body = body;
            this.resetCode = resetCode;
            this.elapsedMillis = elapsedMillis;
        }

        @Override
        public String toString() {
            return "status " + status + ", " + body.length + " body bytes, reset " + resetCode + ", after " + elapsedMillis + " ms";
        }
    }

    private static final class RelayStream {
        private final CompletableFuture<StreamOutcome> outcome = new CompletableFuture<>();
        private Http2StreamChannel channel;
        private volatile long startNanos;
    }

    /**
     * An HTTP/2 connection through MockServer's CONNECT proxy: CONNECT over HTTP/1.1, then TLS with ALPN {@code h2}.
     */
    private static final class RelayClient implements AutoCloseable {
        private final Channel connection;

        private RelayClient(Channel connection) {
            this.connection = connection;
        }

        static RelayClient connect() throws Exception {
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

            SslHandler sslHandler = clientSslContext.newHandler(channel.alloc(), "localhost", 443);
            // the codec goes in with the TLS handler, as the relay's SETTINGS can follow its handshake at once;
            // h2 is the only protocol offered, and the preface waits in the TLS handler until the handshake is done
            channel.pipeline().addLast(sslHandler);
            channel.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
            channel.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel pushedStream) {
                    // MockServer does not push
                }
            }));
            sslHandler.handshakeFuture().sync();
            assertThat("the tunnel carries HTTP/2", sslHandler.applicationProtocol(), is(ApplicationProtocolNames.HTTP_2));
            return new RelayClient(channel);
        }

        CompletableFuture<StreamOutcome> get(String path) throws Exception {
            return start(path).outcome;
        }

        RelayStream start(String path) throws Exception {
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
                        relayStream.outcome.complete(new StreamOutcome(status, body.toByteArray(), resetCode, elapsedMillisSince(relayStream.startNanos)));
                    }
                })
                .open().sync().getNow();
            DefaultHttp2Headers headers = new DefaultHttp2Headers();
            headers.method(HttpMethod.GET.asciiName()).scheme(HttpScheme.HTTPS.name()).authority(TARGET).path(path);
            relayStream.startNanos = System.nanoTime();
            relayStream.channel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true)).sync();
            return relayStream;
        }

        @Override
        public void close() {
            connection.close().syncUninterruptibly();
        }
    }
}
