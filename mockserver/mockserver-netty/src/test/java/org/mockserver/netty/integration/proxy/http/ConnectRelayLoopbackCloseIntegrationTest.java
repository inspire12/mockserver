package org.mockserver.netty.integration.proxy.http;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2PingFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2SettingsAckFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ReferenceCountUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;
import org.mockserver.test.Http2FlowControlBodies;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.test.Http2FlowControlBodies.Size.OVER_WINDOW;

/**
 * When the CONNECT relay's HTTP/2 loopback connection goes away, or one request cannot be written to it, the proxy
 * client's streams are answered at once rather than at Netty's 30 s graceful-shutdown timeout: a stream whose request
 * never reached the loopback's server is refused ({@code REFUSED_STREAM}), one that may have is reset with
 * {@code INTERNAL_ERROR}, and a write that fails for one stream resets only that stream. A stream whose whole response
 * was relayed is not cut short: it is written out in full, then reset with {@code NO_ERROR} if the client is still
 * uploading. The loopback's server is
 * either a second MockServer (the relaying one's {@code proxyRemotePort}) or a Netty HTTP/2 server answering the relay's
 * {@code PROXIED_} handshake, so the tests control how the loopback fails.
 */
public class ConnectRelayLoopbackCloseIntegrationTest {

    // under PROMPT, so a stream answered only at the 30 s graceful-shutdown timeout fails the bound, not the test timeout
    private static final long PROMPT_MILLIS = 2_000;
    private static final String TARGET = "localhost:443";
    private static final String HEALTHY = Http2FlowControlBodies.body(OVER_WINDOW, "relay-loopback-close-healthy");
    // a gzip header, then a deflate block of the reserved type 3
    private static final byte[] CORRUPT_GZIP = {0x1f, (byte) 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
    // sixteen times the client's default flow-control window, so most of it is still queued when the loopback closes
    private static final byte[] BIG = new byte[1024 * 1024];

    static {
        for (int i = 0; i < BIG.length; i++) {
            BIG[i] = (byte) i;
        }
    }

    private static SslContext clientSslContext;
    private static SslContext upstreamSslContext;
    private static EventLoopGroup group;

    @BeforeClass
    public static void createContexts() throws Exception {
        ApplicationProtocolConfig h2Only = new ApplicationProtocolConfig(
            ApplicationProtocolConfig.Protocol.ALPN,
            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
            ApplicationProtocolNames.HTTP_2);
        clientSslContext = SslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            .applicationProtocolConfig(h2Only)
            .build();
        SelfSignedCertificate certificate = new SelfSignedCertificate();
        upstreamSslContext = SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey())
            .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            .applicationProtocolConfig(h2Only)
            .build();
        group = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void shutdown() {
        if (group != null) {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test(timeout = 30_000)
    public void shouldEndInFlightStreamsPromptlyWhenTheMockServerBehindTheLoopbackStops() throws Exception {
        MockServer loopbackServer = new MockServer(configuration().stopDrainMillis(0L));
        MockServer proxy = null;
        try {
            MockServerClient loopbackServerClient = new MockServerClient("localhost", loopbackServer.getLocalPort());
            loopbackServerClient.when(request().withPath("/slow")).respond(response().withBody(HEALTHY).withDelay(TimeUnit.SECONDS, 20));
            proxy = new MockServer(configuration(), loopbackServer.getLocalPort(), "127.0.0.1");
            try (RelayClient client = RelayClient.connect(proxy.getLocalPort())) {
                RelayStream inFlight = client.get("/slow");
                awaitRecorded(loopbackServerClient, "/slow");
                RelayStream uploading = client.startUpload("/upload");
                client.ping();

                long stoppedNanos = System.nanoTime();
                loopbackServer.stop();

                assertGoAwayCovering(client, uploading);
                assertReset(inFlight, Http2Error.INTERNAL_ERROR, stoppedNanos);
                assertReset(uploading, Http2Error.REFUSED_STREAM, stoppedNanos);
                assertGoAwayFirst(client, inFlight, uploading);
                assertClosedPromptly(client, stoppedNanos);
            }
        } finally {
            stopQuietly(proxy);
            stopQuietly(loopbackServer);
        }
    }

    @Test(timeout = 30_000)
    public void shouldEndInFlightStreamsPromptlyWhenTheLoopbackConnectionFails() throws Exception {
        try (Tunnel tunnel = Tunnel.open(null); RelayClient client = RelayClient.connect(tunnel.proxyPort())) {
            RelayStream inFlight = client.get("/hold");
            tunnel.upstream.awaitHeld("/hold");
            RelayStream uploading = client.startUpload("/upload");
            client.ping();

            long failedNanos = System.nanoTime();
            tunnel.upstream.resetConnection();

            assertGoAwayCovering(client, uploading);
            assertReset(inFlight, Http2Error.INTERNAL_ERROR, failedNanos);
            assertReset(uploading, Http2Error.REFUSED_STREAM, failedNanos);
            assertGoAwayFirst(client, inFlight, uploading);
            assertClosedPromptly(client, failedNanos);
        }
    }

    @Test(timeout = 30_000)
    public void shouldPassALoopbackGoAwayOnAndRefuseOnlyARequestCompletedAfterIt() throws Exception {
        try (Tunnel tunnel = Tunnel.open(null); RelayClient client = RelayClient.connect(tunnel.proxyPort())) {
            RelayStream inFlight = client.get("/hold");
            tunnel.upstream.awaitHeld("/hold");
            RelayStream uploading = client.startUpload("/upload");
            client.ping();

            tunnel.upstream.goAway();
            assertGoAwayCovering(client, uploading);

            // the client's request is complete only now, so the relay opens its loopback stream above the GOAWAY's
            long completedNanos = System.nanoTime();
            client.finishUpload(uploading, "after the GOAWAY".getBytes(StandardCharsets.UTF_8));
            assertReset(uploading, Http2Error.REFUSED_STREAM, completedNanos);

            tunnel.upstream.respond("/hold");
            assertHealthy(inFlight);
        }
    }

    @Test(timeout = 30_000)
    public void shouldRefuseAtOnceAStreamALoopbackGoAwaySaysWasNotProcessed() throws Exception {
        try (Tunnel tunnel = Tunnel.open(null); RelayClient client = RelayClient.connect(tunnel.proxyPort())) {
            RelayStream processed = client.get("/hold");
            tunnel.upstream.awaitHeld("/hold");
            RelayStream unprocessed = client.get("/hold-unprocessed");
            // the relay has opened the second loopback stream before the GOAWAY arrives
            tunnel.upstream.awaitHeld("/hold-unprocessed");

            long goAwayNanos = System.nanoTime();
            tunnel.upstream.goAwayProcessedUpTo("/hold");

            // not left until the loopback connection closes
            assertReset(unprocessed, Http2Error.REFUSED_STREAM, goAwayNanos);
            tunnel.upstream.respond("/hold");
            assertHealthy(processed);
        }
    }

    @Test(timeout = 30_000)
    public void shouldRefuseOnlyARequestPastTheLoopbackConcurrentStreamLimit() throws Exception {
        try (Tunnel tunnel = Tunnel.open(1L); RelayClient client = RelayClient.connect(tunnel.proxyPort())) {
            RelayStream inFlight = client.get("/hold");
            tunnel.upstream.awaitHeld("/hold");
            // the loopback has read the limit, so it refuses the next stream itself
            tunnel.upstream.awaitSettingsAcknowledged();

            long sentNanos = System.nanoTime();
            assertReset(client.get("/healthy"), Http2Error.REFUSED_STREAM, sentNanos);

            tunnel.upstream.respond("/hold");
            assertHealthy(inFlight);
            assertHealthy(client.get("/healthy"));
        }
    }

    @Test(timeout = 30_000)
    public void shouldResetOnlyTheStreamWhoseUploadFailsOnTheLoopback() throws Exception {
        try (Tunnel tunnel = Tunnel.open(null); RelayClient client = RelayClient.connect(tunnel.proxyPort())) {
            RelayStream inFlight = client.get("/hold");
            tunnel.upstream.awaitHeld("/hold");

            // the upstream answers with an undecodable body before reading the upload, so the loopback resets the
            // stream while the upload's DATA is still queued behind its flow-control window, and that write fails
            long sentNanos = System.nanoTime();
            RelayStream failed = client.startUpload(Upstream.EARLY_CORRUPT_RESPONSE);
            client.finishUpload(failed, HEALTHY.getBytes(StandardCharsets.UTF_8));
            assertReset(failed, Http2Error.INTERNAL_ERROR, sentNanos);

            tunnel.upstream.respond("/hold");
            assertHealthy(inFlight);
            assertHealthy(client.get("/healthy"));
        }
    }

    @Test(timeout = 30_000)
    public void shouldKeepTheTunnelWhenTheClientResetsAStreamWhoseUploadIsQueuedOnTheLoopback() throws Exception {
        try (Tunnel tunnel = Tunnel.open(null); RelayClient client = RelayClient.connect(tunnel.proxyPort())) {
            RelayStream inFlight = client.get("/hold");
            tunnel.upstream.awaitHeld("/hold");
            RelayStream stalled = client.startUpload(Upstream.STALLED_UPLOAD);
            client.finishUpload(stalled, HEALTHY.getBytes(StandardCharsets.UTF_8));
            // the relay has opened the loopback stream, and the upload's DATA past its window is queued
            tunnel.upstream.awaitHeld(Upstream.STALLED_UPLOAD);

            stalled.channel.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.CANCEL)).sync();
            client.ping();

            tunnel.upstream.respond("/hold");
            assertHealthy(inFlight);
            assertHealthy(client.get("/healthy"));
        }
    }

    @Test(timeout = 30_000)
    public void shouldNotRefuseAnExpectContinueRequestAlreadyRelayedWhenTheLoopbackFails() throws Exception {
        try (Tunnel tunnel = Tunnel.open(null); RelayClient client = RelayClient.connect(tunnel.proxyPort())) {
            // the client is still uploading, but the loopback's server has the request's headers, so may act on it
            RelayStream uploading = client.startExpectContinueUpload("/expect-upload");
            tunnel.upstream.awaitHeld("/expect-upload");
            client.ping();

            long failedNanos = System.nanoTime();
            tunnel.upstream.resetConnection();

            assertReset(uploading, Http2Error.INTERNAL_ERROR, failedNanos);
        }
    }

    @Test(timeout = 30_000)
    public void shouldNotRefuseAnExpectContinueRequestAlreadyAnsweredWhenTheLoopbackFails() throws Exception {
        try (Tunnel tunnel = Tunnel.open(null); RelayClient client = RelayClient.connect(tunnel.proxyPort())) {
            RelayStream uploading = client.startExpectContinueUpload("/expect-upload");
            tunnel.upstream.respond("/expect-upload");
            assertHealthy(uploading);
            client.ping();

            long failedNanos = System.nanoTime();
            tunnel.upstream.resetConnection();

            assertThat("the client is still uploading, so its answered stream is reset, only to stop the upload",
                uploading.resetCode.get(PROMPT_MILLIS, TimeUnit.MILLISECONDS), is(Http2Error.NO_ERROR.code()));
            assertThat(millisBetween(failedNanos, System.nanoTime()), lessThan(PROMPT_MILLIS));
        }
    }

    @Test(timeout = 30_000)
    public void shouldFinishAWholeResponseStillQueuedForTheClientWhenTheLoopbackCloses() throws Exception {
        try (Tunnel tunnel = Tunnel.open(null); RelayClient client = RelayClient.connect(tunnel.proxyPort())) {
            long sentNanos = System.nanoTime();
            RelayStream big = client.get(Upstream.BIG_THEN_CLOSE);

            assertWholeBig(big);
            assertGoAwayCovering(client, big);
            assertClosedPromptly(client, sentNanos);
        }
    }

    @Test(timeout = 30_000)
    public void shouldStopAnUploadOnlyOnceItsWholeQueuedResponseIsWrittenWhenTheLoopbackCloses() throws Exception {
        try (Tunnel tunnel = Tunnel.open(null); RelayClient client = RelayClient.connect(tunnel.proxyPort())) {
            long sentNanos = System.nanoTime();
            // the loopback's server answers the relayed headers, then closes, while the client is still uploading
            RelayStream uploading = client.startExpectContinueUpload(Upstream.BIG_THEN_CLOSE);

            assertWholeBig(uploading);
            assertThat(uploading.resetCode.get(PROMPT_MILLIS, TimeUnit.MILLISECONDS), is(Http2Error.NO_ERROR.code()));
            assertClosedPromptly(client, sentNanos);
        }
    }

    private static void awaitRecorded(MockServerClient mockServerClient, String path) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (mockServerClient.retrieveRecordedRequests(request().withPath(path)).length == 0) {
            assertThat("request " + path + " reached the loopback's server", System.nanoTime() < deadline, is(true));
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private static void assertReset(RelayStream stream, Http2Error expected, long sinceNanos) throws Exception {
        StreamOutcome outcome = stream.outcome.get(PROMPT_MILLIS, TimeUnit.MILLISECONDS);
        assertThat(outcome.toString(), outcome.resetCode, is(expected.code()));
        assertThat(outcome.toString(), outcome.status, is(-1));
        assertThat(outcome.toString(), millisBetween(sinceNanos, outcome.completedNanos), lessThan(PROMPT_MILLIS));
    }

    private static void assertHealthy(RelayStream stream) throws Exception {
        StreamOutcome outcome = stream.outcome.get(10, TimeUnit.SECONDS);
        assertThat(outcome.toString(), outcome.resetCode == null, is(true));
        assertThat(outcome.status, is(200));
        assertThat(new String(outcome.body, StandardCharsets.UTF_8), is(HEALTHY));
    }

    private static void assertWholeBig(RelayStream stream) throws Exception {
        StreamOutcome outcome = stream.outcome.get(10, TimeUnit.SECONDS);
        assertThat(outcome.toString(), outcome.resetCode == null, is(true));
        assertThat(outcome.status, is(200));
        assertThat(outcome.toString(), Arrays.equals(outcome.body, BIG), is(true));
    }

    private static void assertGoAwayCovering(RelayClient client, RelayStream stream) throws Exception {
        GoAway goAway = client.goAway.get(PROMPT_MILLIS, TimeUnit.MILLISECONDS);
        assertThat(goAway.errorCode(), is(Http2Error.NO_ERROR.code()));
        // every stream the client opened stays answerable, so none is lost to the GOAWAY without a signal of its own
        assertThat(goAway.lastStreamId(), greaterThanOrEqualTo(stream.channel.stream().id()));
    }

    private static void assertGoAwayFirst(RelayClient client, RelayStream... streams) {
        // so a client retrying a refused stream knows not to retry it on this connection
        for (RelayStream stream : streams) {
            assertThat("the GOAWAY arrives before the stream's reset", client.goAway.join().receivedNanos() <= stream.outcome.join().completedNanos, is(true));
        }
    }

    private static void assertClosedPromptly(RelayClient client, long sinceNanos) {
        assertThat("the client connection closes once its streams are answered",
            client.connection.closeFuture().awaitUninterruptibly(PROMPT_MILLIS - millisBetween(sinceNanos, System.nanoTime())), is(true));
    }

    private static long millisBetween(long startNanos, long endNanos) {
        return TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos);
    }

    /**
     * A relaying MockServer whose CONNECT/SOCKS loopback goes to {@link Upstream} ({@code proxyRemotePort}).
     */
    private static final class Tunnel implements AutoCloseable {
        private final Upstream upstream;
        private final MockServer proxy;

        private Tunnel(Upstream upstream, MockServer proxy) {
            this.upstream = upstream;
            this.proxy = proxy;
        }

        static Tunnel open(Long maxConcurrentStreams) throws Exception {
            Upstream upstream = Upstream.start(maxConcurrentStreams);
            return new Tunnel(upstream, new MockServer(configuration(), upstream.port(), "127.0.0.1"));
        }

        int proxyPort() {
            return proxy.getLocalPort();
        }

        @Override
        public void close() {
            stopQuietly(proxy);
            upstream.close();
        }
    }

    /**
     * An HTTP/2 server that answers the relay's {@code PROXIED_} handshake as MockServer does, then speaks TLS with ALPN
     * {@code h2}. A request to {@code /healthy} is answered at once; any other is held until {@link #respond}, except
     * {@link #EARLY_CORRUPT_RESPONSE}, which is answered with an undecodable body as soon as its HEADERS arrive, without
     * reading any of its DATA, {@link #STALLED_UPLOAD}, which is held as soon as its HEADERS arrive, without reading any
     * of its DATA, and {@link #BIG_THEN_CLOSE}, which is answered with {@link #BIG}, after which the whole connection
     * closes.
     */
    private static final class Upstream implements AutoCloseable {
        static final String EARLY_CORRUPT_RESPONSE = "/early-corrupt-response";
        static final String BIG_THEN_CLOSE = "/big-then-close";
        static final String STALLED_UPLOAD = "/stalled-upload";

        private final ConcurrentMap<String, CompletableFuture<Channel>> held = new ConcurrentHashMap<>();
        private final BlockingQueue<Channel> connections = new LinkedBlockingQueue<>();
        private final CompletableFuture<Void> settingsAcknowledged = new CompletableFuture<>();
        private Channel serverChannel;

        static Upstream start(Long maxConcurrentStreams) throws InterruptedException {
            Upstream upstream = new Upstream();
            Http2Settings settings = Http2Settings.defaultSettings();
            if (maxConcurrentStreams != null) {
                settings.maxConcurrentStreams(maxConcurrentStreams);
            }
            upstream.serverChannel = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        upstream.connections.add(ch);
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                // the relay's PROXIED_ line; it sends nothing more until it has this reply
                                ReferenceCountUtil.release(msg);
                                ctx.writeAndFlush(Unpooled.copiedBuffer("PROXIED_RESPONSE_" + TARGET, StandardCharsets.UTF_8));
                                ctx.pipeline().addLast(upstreamSslContext.newHandler(ctx.alloc()));
                                ctx.pipeline().addLast(Http2FrameCodecBuilder.forServer().initialSettings(settings).gracefulShutdownTimeoutMillis(0).build());
                                ctx.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                    @Override
                                    protected void initChannel(Channel stream) {
                                        stream.pipeline().addLast(upstream.new StreamHandler());
                                    }
                                }));
                                ctx.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                        if (msg instanceof Http2SettingsAckFrame) {
                                            upstream.settingsAcknowledged.complete(null);
                                        }
                                        ReferenceCountUtil.release(msg);
                                    }
                                });
                                ctx.pipeline().remove(this);
                            }
                        });
                    }
                })
                .bind("127.0.0.1", 0).sync().channel();
            return upstream;
        }

        int port() {
            return ((InetSocketAddress) serverChannel.localAddress()).getPort();
        }

        void awaitHeld(String path) throws Exception {
            heldStream(path).get(10, TimeUnit.SECONDS);
        }

        void awaitSettingsAcknowledged() throws Exception {
            settingsAcknowledged.get(10, TimeUnit.SECONDS);
        }

        void respond(String path) throws Exception {
            Channel stream = heldStream(path).get(10, TimeUnit.SECONDS);
            stream.eventLoop().execute(() -> writeHealthy(stream));
        }

        void goAway() throws Exception {
            connection().writeAndFlush(new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR)).sync();
        }

        /**
         * Sends a GOAWAY whose last-stream-id is the held stream for {@code path}, so the streams the upstream has opened
         * above it count as not processed. Written below the codec, which would cover every stream it has opened.
         */
        void goAwayProcessedUpTo(String path) throws Exception {
            int lastStreamId = ((Http2StreamChannel) heldStream(path).get(10, TimeUnit.SECONDS)).stream().id();
            Channel connection = connection();
            connection.eventLoop().submit(() -> {
                ByteBuf frame = connection.alloc().buffer(17)
                    .writeMedium(8).writeByte(0x7).writeByte(0).writeInt(0)
                    .writeInt(lastStreamId).writeInt((int) Http2Error.NO_ERROR.code());
                connection.pipeline().context(Http2FrameCodec.class).writeAndFlush(frame);
            }).sync();
        }

        /**
         * Closes the TCP connection with a reset and no GOAWAY, bypassing the codec's own graceful close.
         */
        void resetConnection() throws Exception {
            Channel connection = connection();
            connection.eventLoop().submit(() -> {
                connection.config().setOption(ChannelOption.SO_LINGER, 0);
                connection.unsafe().close(connection.voidPromise());
            }).sync();
        }

        private Channel connection() throws InterruptedException {
            Channel connection = connections.peek();
            assertThat("the relay's loopback connected", connection != null, is(true));
            return connection;
        }

        private CompletableFuture<Channel> heldStream(String path) {
            return held.computeIfAbsent(path, ignored -> new CompletableFuture<>());
        }

        private static void writeHealthy(Channel stream) {
            stream.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200")));
            stream.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.copiedBuffer(HEALTHY, StandardCharsets.UTF_8), true));
        }

        @Override
        public void close() {
            serverChannel.close().syncUninterruptibly();
            connections.forEach(Channel::close);
        }

        private final class StreamHandler extends ChannelInboundHandlerAdapter {
            private String path;

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                try {
                    boolean endOfRequest = false;
                    if (msg instanceof Http2HeadersFrame) {
                        path = ((Http2HeadersFrame) msg).headers().path().toString();
                        if (EARLY_CORRUPT_RESPONSE.equals(path)) {
                            // read nothing more, so the upload is held up by this stream's flow-control window
                            ctx.channel().config().setAutoRead(false);
                            ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200").set(HttpHeaderNames.CONTENT_ENCODING, "gzip")));
                            ctx.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(CORRUPT_GZIP), true));
                            return;
                        }
                        if (STALLED_UPLOAD.equals(path)) {
                            ctx.channel().config().setAutoRead(false);
                            heldStream(path).complete(ctx.channel());
                            return;
                        }
                        endOfRequest = ((Http2HeadersFrame) msg).isEndStream();
                    } else if (msg instanceof Http2DataFrame) {
                        endOfRequest = ((Http2DataFrame) msg).isEndStream();
                    }
                    if (endOfRequest) {
                        if (BIG_THEN_CLOSE.equals(path)) {
                            Channel connection = ctx.channel().parent();
                            ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200")));
                            // closed once the relay has read all of it
                            ctx.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(BIG), true)).addListener(written -> connection.close());
                        } else if ("/healthy".equals(path)) {
                            writeHealthy(ctx.channel());
                        } else {
                            heldStream(path).complete(ctx.channel());
                        }
                    }
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                if (evt instanceof Http2ResetFrame) {
                    // a stream that stopped reading only closes, releasing the frames it holds, when it is closed
                    ctx.close();
                }
                ReferenceCountUtil.release(evt);
            }
        }
    }

    private record GoAway(int lastStreamId, long errorCode, long receivedNanos) {
    }

    private static final class StreamOutcome {
        private final int status;
        private final byte[] body;
        private final Long resetCode;
        private final long completedNanos;

        private StreamOutcome(int status, byte[] body, Long resetCode, long completedNanos) {
            this.status = status;
            this.body = body;
            this.resetCode = resetCode;
            this.completedNanos = completedNanos;
        }

        @Override
        public String toString() {
            return "status " + status + ", " + body.length + " body bytes, reset " + resetCode;
        }
    }

    private static final class RelayStream {
        private final CompletableFuture<StreamOutcome> outcome = new CompletableFuture<>();
        // also after a complete response, which the client may receive while it is still uploading
        private final CompletableFuture<Long> resetCode = new CompletableFuture<>();
        private Http2StreamChannel channel;
    }

    /**
     * An HTTP/2 connection through MockServer's CONNECT proxy: CONNECT over HTTP/1.1, then TLS with ALPN {@code h2}.
     */
    private static final class RelayClient implements AutoCloseable {
        private final Channel connection;
        private final CompletableFuture<GoAway> goAway = new CompletableFuture<>();
        private final BlockingQueue<Long> pingAcks = new LinkedBlockingQueue<>();
        private long pings;

        private RelayClient(Channel connection) {
            this.connection = connection;
        }

        static RelayClient connect(int proxyPort) throws Exception {
            CompletableFuture<Integer> connectStatus = new CompletableFuture<>();
            Channel channel = new Bootstrap()
                .group(group)
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
                .connect("127.0.0.1", proxyPort).sync().channel();
            DefaultFullHttpRequest connectRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, TARGET);
            connectRequest.headers().set(HttpHeaderNames.HOST, TARGET);
            channel.writeAndFlush(connectRequest);
            assertThat(connectStatus.get(10, TimeUnit.SECONDS), is(200));

            RelayClient client = new RelayClient(channel);
            SslHandler sslHandler = clientSslContext.newHandler(channel.alloc(), "localhost", 443);
            // the codec goes in with the TLS handler, as the relay's SETTINGS can follow its handshake at once
            channel.pipeline().addLast(sslHandler);
            channel.pipeline().addLast(Http2FrameCodecBuilder.forClient().gracefulShutdownTimeoutMillis(0).build());
            channel.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel pushedStream) {
                    // MockServer does not push
                }
            }));
            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    try {
                        if (msg instanceof Http2GoAwayFrame) {
                            client.goAway.complete(new GoAway(((Http2GoAwayFrame) msg).lastStreamId(), ((Http2GoAwayFrame) msg).errorCode(), System.nanoTime()));
                        } else if (msg instanceof Http2PingFrame && ((Http2PingFrame) msg).ack()) {
                            client.pingAcks.add(((Http2PingFrame) msg).content());
                        }
                    } finally {
                        ReferenceCountUtil.release(msg);
                    }
                }
            });
            sslHandler.handshakeFuture().sync();
            assertThat("the tunnel carries HTTP/2", sslHandler.applicationProtocol(), is(ApplicationProtocolNames.HTTP_2));
            return client;
        }

        RelayStream get(String path) throws Exception {
            return start(HttpMethod.GET, path, true, false);
        }

        RelayStream startUpload(String path) throws Exception {
            return start(HttpMethod.POST, path, false, false);
        }

        void finishUpload(RelayStream stream, byte[] body) throws Exception {
            stream.channel.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(body), true)).sync();
        }

        /**
         * Returns once the relay has acknowledged a PING, so it has read every frame written before it.
         */
        void ping() throws Exception {
            long content = ++pings;
            connection.writeAndFlush(new DefaultHttp2PingFrame(content)).sync();
            assertThat(pingAcks.poll(10, TimeUnit.SECONDS), is(content));
        }

        /**
         * The relay hands a request with {@code Expect} on as its headers at once, before the body arrives.
         */
        RelayStream startExpectContinueUpload(String path) throws Exception {
            return start(HttpMethod.POST, path, false, true);
        }

        private RelayStream start(HttpMethod method, String path, boolean endStream, boolean expectContinue) throws Exception {
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
                                ByteBuf content = data.content();
                                body.writeBytes(ByteBufUtil.getBytes(content));
                                if (data.isEndStream()) {
                                    complete(null);
                                }
                            } else if (msg instanceof Http2ResetFrame) {
                                reset(((Http2ResetFrame) msg).errorCode());
                            }
                        } finally {
                            ReferenceCountUtil.release(msg);
                        }
                    }

                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                        if (evt instanceof Http2ResetFrame) {
                            reset(((Http2ResetFrame) evt).errorCode());
                        }
                        ReferenceCountUtil.release(evt);
                    }

                    @Override
                    public void channelInactive(ChannelHandlerContext ctx) {
                        relayStream.outcome.completeExceptionally(new IOException("stream closed without a response or a reset: " + body.size() + " body bytes"));
                    }

                    private void reset(long errorCode) {
                        relayStream.resetCode.complete(errorCode);
                        complete(errorCode);
                    }

                    private void complete(Long resetCode) {
                        relayStream.outcome.complete(new StreamOutcome(status, body.toByteArray(), resetCode, System.nanoTime()));
                    }
                })
                .open().sync().getNow();
            DefaultHttp2Headers headers = new DefaultHttp2Headers();
            headers.method(method.asciiName()).scheme(HttpScheme.HTTPS.name()).authority(TARGET).path(path);
            if (expectContinue) {
                headers.set(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE);
            }
            relayStream.channel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, endStream)).sync();
            return relayStream;
        }

        @Override
        public void close() {
            connection.close().syncUninterruptibly();
        }
    }
}
