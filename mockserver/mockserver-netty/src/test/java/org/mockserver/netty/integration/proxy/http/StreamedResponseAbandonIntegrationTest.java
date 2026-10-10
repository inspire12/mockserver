package org.mockserver.netty.integration.proxy.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.mock.Expectation;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpForwardWithFallback.forwardWithFallback;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A streamed upstream response that is never written to the client (replaced by a chaos error, or by a fallback
 * for an upstream error status) still has its upstream reclaimed by the stream idle timeout (1 s here): nothing will
 * take the stream, so its waiting bytes must not count as waiting for a client. Its upstream sends a first piece and
 * then nothing, leaving the connection open, so only MockServer can close it; an endless stream whose client has gone
 * has its upstream closed too. A stream whose upstream closes or sends an undecodable body part-way through ends the
 * client's response incomplete rather than with a terminating chunk.
 */
public class StreamedResponseAbandonIntegrationTest {

    private static final String TERMINATING_CHUNK = "0\r\n\r\n";
    private static final Map<String, Channel> UPSTREAM_CHANNELS = new ConcurrentHashMap<>();

    private static byte[] gzipBurst;
    private static EventLoopGroup upstreamGroup;
    private static Channel upstreamChannel;
    private static int upstreamPort;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServers() throws Exception {
        // 8 MiB of events in gzip: far more than the read watermark once decoded
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            byte[] line = "data: 0000000000000000000000000000000000000000000000000000000000000000\n\n".getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < 8 * 1024 * 1024 / line.length; i++) {
                gzip.write(line);
            }
        }
        gzipBurst = compressed.toByteArray();

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

        mockServer = new MockServer(configuration()
            .streamingResponsesEnabled(true)
            .streamIdleTimeoutSeconds(1));
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (upstreamChannel != null) {
            upstreamChannel.close();
        }
        if (upstreamGroup != null) {
            upstreamGroup.shutdownGracefully();
        }
    }

    @Before
    public void resetExpectations() {
        UPSTREAM_CHANNELS.clear();
        mockServerClient.reset();
        mockServerClient.upsert(new Expectation(request().withPath("/chaos/.*"))
            .withChaos(httpChaosProfile().withErrorStatus(503).withErrorProbability(1.0))
            .thenForward(forward().withHost("127.0.0.1").withPort(upstreamPort)));
        mockServerClient.upsert(new Expectation(request().withPath("/fallback/.*"))
            .thenForwardWithFallback(forwardWithFallback()
                .withForward(forward().withHost("127.0.0.1").withPort(upstreamPort))
                .withFallback(response().withStatusCode(503).withBody("fallback"))
                .withFallbackOnStatusCodes(503)));
        mockServerClient.when(request().withPath("/forward/.*")).forward(forward().withHost("127.0.0.1").withPort(upstreamPort));
    }

    @Test
    public void shouldReclaimTheUpstreamOfAStreamReplacedByAChaosError() throws Exception {
        assertThat(firstRead("/chaos/plain"), startsWith("HTTP/1.1 503"));
        assertUpstreamClosed("/chaos/plain");
    }

    @Test
    public void shouldReclaimTheUpstreamOfACompressedStreamReplacedByAChaosError() throws Exception {
        assertThat(firstRead("/chaos/gzip"), startsWith("HTTP/1.1 503"));
        assertUpstreamClosed("/chaos/gzip");
    }

    @Test
    public void shouldReclaimTheUpstreamOfAStreamReplacedByAFallback() throws Exception {
        assertThat(firstRead("/fallback/error"), startsWith("HTTP/1.1 503"));
        assertUpstreamClosed("/fallback/error");
    }

    @Test
    public void shouldReclaimTheUpstreamOfACompressedStreamReplacedByAFallback() throws Exception {
        assertThat(firstRead("/fallback/error-gzip"), startsWith("HTTP/1.1 503"));
        assertUpstreamClosed("/fallback/error-gzip");
    }

    @Test
    public void shouldEndTheResponseIncompleteWhenTheUpstreamClosesMidStream() throws Exception {
        String received = readAll("/forward/close");
        assertThat(received, containsString("data: first"));
        assertThat("an upstream that closes mid-stream is not a complete response", received, not(endsWith(TERMINATING_CHUNK)));
    }

    @Test
    public void shouldEndTheResponseIncompleteWhenTheUpstreamSendsACorruptCompressedBody() throws Exception {
        String received = readAll("/forward/corrupt-gzip");
        assertThat(received, startsWith("HTTP/1.1 200"));
        assertThat("an undecodable stream is not a complete response", received, not(endsWith(TERMINATING_CHUNK)));
    }

    @Test
    public void shouldCloseTheUpstreamOfAnEndlessStreamWhoseClientHasGone() throws Exception {
        assertThat(firstRead("/forward/endless"), startsWith("HTTP/1.1 200"));
        // the client has closed its connection, while the upstream keeps sending
        assertUpstreamClosed("/forward/endless");
    }

    private static String firstRead(String path) throws IOException {
        try (Socket socket = connect(path)) {
            byte[] buffer = new byte[4096];
            int read = socket.getInputStream().read(buffer);
            return new String(buffer, 0, Math.max(read, 0), StandardCharsets.ISO_8859_1);
        }
    }

    private static String readAll(String path) throws IOException {
        try (Socket socket = connect(path)) {
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[4096];
            try {
                for (int read; (read = in.read(buffer)) != -1; ) {
                    received.write(buffer, 0, read);
                    if (received.toString(StandardCharsets.ISO_8859_1).endsWith(TERMINATING_CHUNK)) {
                        break;
                    }
                }
            } catch (IOException reset) {
                // a reset ends the response as incomplete as a close does
            }
            return received.toString(StandardCharsets.ISO_8859_1);
        }
    }

    private static Socket connect(String path) throws IOException {
        Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort());
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(20));
        socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + upstreamPort + "\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    private static void assertUpstreamClosed(String path) throws InterruptedException {
        for (int i = 0; i < 500 && !UPSTREAM_CHANNELS.containsKey(path); i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat("the upstream received " + path, UPSTREAM_CHANNELS.containsKey(path), is(true));
        assertThat("MockServer closed the abandoned upstream within 8 s", UPSTREAM_CHANNELS.get(path).closeFuture().await(8, TimeUnit.SECONDS), is(true));
    }

    /**
     * Answers as {@code text/event-stream} (503 for {@code /fallback/...}): a first event, or {@link #gzipBurst} for a
     * path ending {@code gzip}, then nothing with the connection left open; {@code .../endless} sends an event every
     * 20 ms for as long as the connection is open; {@code .../close} then closes it, and
     * {@code .../corrupt-gzip} sends a body that cannot be decompressed.
     */
    private static void sendEndlessly(ChannelHandlerContext ctx) {
        if (ctx.channel().isActive()) {
            ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer("data: more\n\n", StandardCharsets.US_ASCII)));
            ctx.executor().schedule(() -> sendEndlessly(ctx), 20, TimeUnit.MILLISECONDS);
        }
    }

    @ChannelHandler.Sharable
    private static final class UpstreamHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String path = new QueryStringDecoder(request.uri()).path();
            UPSTREAM_CHANNELS.put(path, ctx.channel());
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, path.startsWith("/fallback/") ? HttpResponseStatus.SERVICE_UNAVAILABLE : HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            HttpUtil.setTransferEncodingChunked(head, true);
            byte[] first = "data: first\n\n".getBytes(StandardCharsets.US_ASCII);
            if (path.endsWith("gzip")) {
                head.headers().set(HttpHeaderNames.CONTENT_ENCODING, "gzip");
                ctx.write(head);
                if (path.endsWith("/corrupt-gzip")) {
                    // a gzip header, then a deflate block of the reserved type 11, which no decoder accepts
                    ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(new byte[]{
                        0x1f, (byte) 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff})));
                } else {
                    ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(gzipBurst)));
                }
            } else {
                ctx.write(head);
                if (path.endsWith("/endless")) {
                    sendEndlessly(ctx);
                } else if (path.endsWith("/close")) {
                    ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(first))).addListener(ChannelFutureListener.CLOSE);
                } else {
                    ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(first)));
                }
            }
        }
    }
}
