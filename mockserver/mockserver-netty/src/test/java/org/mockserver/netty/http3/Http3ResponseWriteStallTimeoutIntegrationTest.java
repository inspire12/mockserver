package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.metrics.Metrics;
import org.mockserver.netty.MockServer;
import org.mockserver.testing.socket.Ipv4DatagramChannelFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * {@code responseWriteStallTimeoutMillis} over HTTP/3: a request stream whose client stops reading, and so stops granting
 * it flow-control credit, is reset, and a streamed response's upstream is closed; a client that keeps reading gets its
 * whole response. The client grants each stream 64 KiB of credit and reads only when told to.
 * <p>
 * Responses are forwarded from an upstream: {@code /fixed} is a 4 MiB body with a {@code Content-Length}, so it is
 * aggregated; {@code /trickle} is about 100 KB of server-sent events and then nothing, with the upstream left open.
 * The gRPC response is mocked: one 1 MiB message. Skips where the native QUIC transport is unavailable.
 */
public class Http3ResponseWriteStallTimeoutIntegrationTest {

    private static final long STALL_MILLIS = 3000;
    private static final long CUT_WITHIN_MILLIS = 3 * STALL_MILLIS + 5000;
    private static final long READ_PAUSE_MILLIS = STALL_MILLIS / 3;
    private static final int STREAM_CREDIT_BYTES = 64 * 1024;
    private static final Map<String, Channel> UPSTREAM_CHANNELS = new ConcurrentHashMap<>();

    private static byte[] fixedBody;
    private static byte[] grpcMessage;
    private static byte[] events;
    private static EventLoopGroup upstreamGroup;
    private static Channel upstreamChannel;
    private static int upstreamPort;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static int http3Port;

    private NioEventLoopGroup clientGroup;
    private Channel clientChannel;

    @BeforeClass
    public static void startServers() throws Exception {
        // first: setting http3Port without the QUIC native fails start-up rather than skipping
        assumeQuicAvailable();
        fixedBody = new byte[4 * 1024 * 1024];
        new Random(72).nextBytes(fixedBody);
        grpcMessage = new byte[1024 * 1024];
        new Random(93).nextBytes(grpcMessage);
        StringBuilder trickle = new StringBuilder();
        for (int id = 0; trickle.length() < 100_000; id++) {
            trickle.append("id: ").append(id).append("\ndata: ").append("x".repeat(800)).append("\n\n");
        }
        events = trickle.toString().getBytes(StandardCharsets.US_ASCII);

        upstreamGroup = new NioEventLoopGroup(1);
        UpstreamHandler handler = new UpstreamHandler();
        upstreamChannel = new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(1024 * 1024), handler);
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        upstreamPort = ((InetSocketAddress) upstreamChannel.localAddress()).getPort();

        mockServer = startWithHttp3(configuration()
            .logLevel("WARN")
            .metricsEnabled(true)
            .http3MaxIdleTimeout(60_000L)
            .streamingResponsesEnabled(true)
            .streamIdleTimeoutSeconds(120)
            .responseWriteStallTimeoutMillis(STALL_MILLIS));
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        http3Port = mockServer.getHttp3Port();
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (upstreamChannel != null) {
            upstreamChannel.close();
        }
        if (upstreamGroup != null) {
            upstreamGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Before
    public void resetExpectations() {
        assumeQuicAvailable();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);
        mockServerClient.reset();
        mockServerClient.when(request().withPath("/forward/.*")).forward(forward().withHost("127.0.0.1").withPort(upstreamPort));
    }

    @After
    public void stopClient() throws InterruptedException {
        if (clientChannel != null) {
            clientChannel.close().await(5, TimeUnit.SECONDS);
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void shouldResetAStalledHttp3StreamOfAnAggregatedResponse() throws Exception {
        long countedBefore = Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP3_STREAM);
        Http3Stream stream = open("/forward/fixed?test=http3-aggregated-stalled");
        TimeUnit.MILLISECONDS.sleep(CUT_WITHIN_MILLIS);

        stream.readAll();

        assertThat("the stream ended", stream.ended.await(10, TimeUnit.SECONDS), is(true));
        assertThat("the response was cut short", stream.dataBytes.get(), lessThan((long) fixedBody.length));
        assertThat("the stream did not end cleanly", stream.endedCleanly.get(), is(false));
        assertThat("the reset was counted", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP3_STREAM), greaterThan(countedBefore));
    }

    @Test
    public void shouldResetAStalledHttp3StreamOfAStreamedResponseAndCloseItsQuietUpstream() throws Exception {
        String uri = "/forward/trickle?test=http3-streamed-stalled";
        open(uri);
        Channel upstream = awaitUpstream(uri);
        // the upstream has sent everything and is quiet, so only closing it with the stream frees it before its idle timeout
        assertThat("the upstream was closed", upstream.closeFuture().await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
    }

    @Test
    public void shouldDeliverAnAggregatedResponseWholeToASlowButProgressingHttp3Reader() throws Exception {
        Http3Stream stream = open("/forward/fixed?test=http3-aggregated-slow");
        long started = System.nanoTime();
        while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3 * STALL_MILLIS) {
            TimeUnit.MILLISECONDS.sleep(READ_PAUSE_MILLIS);
            stream.channel.read();
        }
        assertThat("still in progress after the slow phase", stream.ended.getCount(), is(1L));

        stream.readAll();

        assertThat("the stream ended", stream.ended.await(30, TimeUnit.SECONDS), is(true));
        assertThat(stream.dataBytes.get(), is((long) fixedBody.length));
        assertThat("the stream ended cleanly", stream.endedCleanly.get(), is(true));
    }

    @Test
    public void shouldDeliverALargeGrpcMessageWholeToASlowButProgressingHttp3Reader() throws Exception {
        // the gRPC writer sends its message as one DATA frame, so the stream shows progress only if that write does
        mockServerClient.when(request().withMethod("POST").withPath("/stall.Service/Large")).respond(response()
            .withHeader("content-type", "application/grpc")
            .withHeader("grpc-status", "0")
            .withBody(binary(grpcMessage)));
        Http3Stream stream = open("/stall.Service/Large", true);
        long started = System.nanoTime();
        while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3 * STALL_MILLIS) {
            TimeUnit.MILLISECONDS.sleep(READ_PAUSE_MILLIS);
            stream.channel.read();
        }
        assertThat("still in progress after the slow phase", stream.ended.getCount(), is(1L));

        stream.readAll();

        assertThat("the stream ended", stream.ended.await(30, TimeUnit.SECONDS), is(true));
        assertThat(stream.dataBytes.get(), is((long) grpcMessage.length));
        assertThat("the stream ended cleanly", stream.endedCleanly.get(), is(true));
    }

    private Http3Stream open(String uri) throws Exception {
        return open(uri, false);
    }

    private Http3Stream open(String uri, boolean grpc) throws Exception {
        clientGroup = new NioEventLoopGroup(1);
        QuicSslContext sslContext = QuicSslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .applicationProtocols(Http3.supportedApplicationProtocols())
            .build();
        clientChannel = new Bootstrap()
            .group(clientGroup)
            .channelFactory(Ipv4DatagramChannelFactory.INSTANCE)
            .handler(Http3.newQuicClientCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(60_000, TimeUnit.MILLISECONDS)
                .initialMaxData(10_000_000)
                .initialMaxStreamDataBidirectionalLocal(STREAM_CREDIT_BYTES)
                .initialMaxStreamsBidirectional(100)
                .build())
            .bind(0).sync().channel();
        QuicChannel quicChannel = QuicChannel.newBootstrap(clientChannel)
            .handler(new Http3ClientConnectionHandler())
            .remoteAddress(new InetSocketAddress("127.0.0.1", http3Port))
            .connect()
            .get(15, TimeUnit.SECONDS);

        Http3Stream stream = new Http3Stream();
        stream.channel = Http3.newRequestStream(quicChannel, stream.recorder()).sync().getNow();
        // nothing is read, and so no credit granted, until read() is called
        stream.channel.config().setAutoRead(false);
        DefaultHttp3HeadersFrame headers = new DefaultHttp3HeadersFrame();
        headers.headers().path(uri).scheme("https").authority("127.0.0.1:" + http3Port);
        if (grpc) {
            headers.headers().method("POST").add("content-type", "application/grpc").add("te", "trailers");
            stream.channel.write(headers);
            // an empty gRPC message: no compression, zero length
            stream.channel.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[5]))).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT).sync();
        } else {
            headers.headers().method("GET").add("accept", uri.startsWith("/forward/fixed") ? "application/octet-stream" : "text/event-stream");
            stream.channel.writeAndFlush(headers).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT).sync();
        }
        return stream;
    }

    private static Channel awaitUpstream(String uri) throws InterruptedException {
        for (int i = 0; i < 1000 && !UPSTREAM_CHANNELS.containsKey(uri); i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat("the upstream received " + uri, UPSTREAM_CHANNELS.containsKey(uri), is(true));
        return UPSTREAM_CHANNELS.get(uri);
    }

    private static final class Http3Stream {
        private final AtomicLong dataBytes = new AtomicLong();
        private final AtomicBoolean endedCleanly = new AtomicBoolean();
        private final CountDownLatch ended = new CountDownLatch(1);
        private QuicStreamChannel channel;

        void readAll() {
            channel.config().setAutoRead(true);
            channel.read();
        }

        ChannelHandler recorder() {
            return new Http3RequestStreamInboundHandler() {
                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
                    dataBytes.addAndGet(dataFrame.content().readableBytes());
                    dataFrame.release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    endedCleanly.set(true);
                    ended.countDown();
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    ended.countDown();
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) {
                    ended.countDown();
                }
            };
        }
    }

    /**
     * {@code /fixed}: the fixed body with a {@code Content-Length}; {@code /trickle}: the events, then nothing, with the
     * connection left open.
     */
    @ChannelHandler.Sharable
    private static final class UpstreamHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String uri = request.uri();
            UPSTREAM_CHANNELS.put(uri, ctx.channel());
            if (uri.startsWith("/forward/fixed")) {
                DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(fixedBody));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
                HttpUtil.setContentLength(response, fixedBody.length);
                ctx.writeAndFlush(response);
                return;
            }
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.write(head);
            ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(events)));
        }
    }

    private static void assumeQuicAvailable() {
        try {
            Assume.assumeTrue("native QUIC transport not available on this platform", io.netty.handler.codec.quic.Quic.isAvailable());
        } catch (Throwable t) {
            Assume.assumeNoException("native QUIC transport failed to load", t);
        }
    }
}
