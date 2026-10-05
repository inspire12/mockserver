package org.mockserver.netty.integration.proxy.http;

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
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * {@code streamIdleTimeoutSeconds} (1 s here) bounds an upstream that sends nothing while MockServer is reading it,
 * not the time MockServer withholds reads because its client has not yet taken what is waiting. A fast upstream and a
 * client slower than one read's drain per timeout, or a client that pauses for several timeouts, must get the whole
 * stream; an upstream that goes quiet still has the stream aborted, and the client's response then ends without its
 * terminating chunk rather than looking complete.
 */
public class StreamIdleTimeoutBackpressureIntegrationTest {

    private static final int IDLE_TIMEOUT_SECONDS = 1;
    private static final int CLIENT_READ_BYTES = 4096;
    private static final long CLIENT_PAUSE_MILLIS = 125;
    private static final String TERMINATING_CHUNK = "0\r\n\r\n";
    private static final Map<String, Channel> UPSTREAM_CHANNELS = new ConcurrentHashMap<>();

    private static byte[][] events;
    private static byte[] eventsJoined;
    private static EventLoopGroup upstreamGroup;
    private static Channel upstreamChannel;
    private static int upstreamPort;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServers() throws Exception {
        events = events(1024 * 1024);
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] event : events) {
            joined.write(event, 0, event.length);
        }
        eventsJoined = joined.toByteArray();

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
            .streamIdleTimeoutSeconds(IDLE_TIMEOUT_SECONDS));
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
        mockServerClient.when(request().withPath("/forward/.*")).forward(forward().withHost("127.0.0.1").withPort(upstreamPort));
    }

    @Test
    public void shouldDeliverAFastUpstreamToAClientSlowerThanTheIdleTimeoutPerRead() throws Exception {
        // about 32 KiB a second, so one read's decoded output takes longer than the timeout to drain
        long started = System.nanoTime();
        try (Socket socket = connect("/forward/events")) {
            String received = new String(readPaced(socket.getInputStream(), CLIENT_PAUSE_MILLIS), StandardCharsets.ISO_8859_1);
            assertThat("the stream took longer than the idle timeout to drain",
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) > 4L * IDLE_TIMEOUT_SECONDS, is(true));
            assertThat("the response ended with its terminating chunk", received, endsWith(TERMINATING_CHUNK));
            assertThat(new String(dechunk(received), StandardCharsets.ISO_8859_1), is(new String(eventsJoined, StandardCharsets.ISO_8859_1)));
        }
    }

    @Test
    public void shouldAbortAStreamWhoseUpstreamGoesQuiet() throws Exception {
        long started = System.nanoTime();
        try (Socket socket = connect("/forward/quiet")) {
            String received = new String(readPaced(socket.getInputStream(), 0), StandardCharsets.ISO_8859_1);
            assertThat(received, containsString("data: first"));
            assertThat("an idle abort is not a complete response", received, not(endsWith(TERMINATING_CHUNK)));
            assertThat("aborted after about the idle timeout",
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started), lessThan(15L));
        }
        assertThat(UPSTREAM_CHANNELS.get("/forward/quiet").closeFuture().await(10, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void shouldDeliverTheWholeStreamToAClientThatPausesForSeveralIdleTimeouts() throws Exception {
        try (Socket socket = connect("/forward/big")) {
            awaitUpstream("/forward/big");
            // the client takes nothing for four timeouts, while far more than the socket buffers waits for it
            TimeUnit.SECONDS.sleep(4L * IDLE_TIMEOUT_SECONDS);
            assertThat("MockServer kept the upstream open", UPSTREAM_CHANNELS.get("/forward/big").isActive(), is(true));
            String received = new String(readPaced(socket.getInputStream(), 0), StandardCharsets.ISO_8859_1);
            assertThat("the response ended with its terminating chunk", received, endsWith(TERMINATING_CHUNK));
            assertThat(dechunk(received).length, is(16 * eventsJoined.length));
        }
    }

    private static Socket connect(String path) throws IOException {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(32 * 1024);
        socket.connect(new InetSocketAddress("127.0.0.1", mockServer.getLocalPort()));
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(60));
        socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + upstreamPort + "\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    private static void awaitUpstream(String path) throws InterruptedException {
        for (int i = 0; i < 1000 && !UPSTREAM_CHANNELS.containsKey(path); i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat("the upstream received " + path, UPSTREAM_CHANNELS.containsKey(path), is(true));
    }

    /**
     * Reads {@link #CLIENT_READ_BYTES} at a time, pausing between reads, until the terminating chunk, the end of the
     * connection or a reset.
     */
    private static byte[] readPaced(InputStream in, long pauseMillis) throws InterruptedException {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buffer = new byte[CLIENT_READ_BYTES];
        try {
            for (int read; (read = in.read(buffer)) != -1; ) {
                received.write(buffer, 0, read);
                if (received.toString(StandardCharsets.ISO_8859_1).endsWith(TERMINATING_CHUNK)) {
                    break;
                }
                TimeUnit.MILLISECONDS.sleep(pauseMillis);
            }
        } catch (IOException reset) {
            // a reset ends the response as incomplete as a close does
        }
        return received.toByteArray();
    }

    private static byte[] dechunk(String response) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int index = response.indexOf("\r\n\r\n") + 4;
        for (int size; (size = Integer.parseInt(response.substring(index, response.indexOf("\r\n", index)).trim(), 16)) > 0; ) {
            int start = response.indexOf("\r\n", index) + 2;
            byte[] chunk = response.substring(start, start + size).getBytes(StandardCharsets.ISO_8859_1);
            body.write(chunk, 0, chunk.length);
            index = start + size + 2;
        }
        return body.toByteArray();
    }

    // events of about 815 bytes with random payloads
    private static byte[][] events(int size) {
        Random random = new Random(815);
        List<byte[]> chunks = new ArrayList<>();
        byte[] payload = new byte[600];
        for (int id = 0, total = 0; total < size; id++) {
            random.nextBytes(payload);
            byte[] event = ("id: " + id + "\ndata: " + Base64.getEncoder().encodeToString(payload) + "\n\n").getBytes(StandardCharsets.US_ASCII);
            chunks.add(event);
            total += event.length;
        }
        return chunks.toArray(new byte[0][]);
    }

    /**
     * {@code .../events}: every event, one chunk each, in one write; {@code .../big}: the same 16 times.
     * {@code .../quiet}: one event, then nothing, with the connection left open.
     */
    @ChannelHandler.Sharable
    private static final class UpstreamHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String path = new QueryStringDecoder(request.uri()).path();
            UPSTREAM_CHANNELS.put(path, ctx.channel());
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.write(head);
            if (path.endsWith("/quiet")) {
                ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer("data: first\n\n", StandardCharsets.US_ASCII)));
                return;
            }
            // far more than the socket buffers between MockServer and a client that does not read
            int repeats = path.endsWith("/big") ? 16 : 1;
            for (int i = 0; i < repeats; i++) {
                for (byte[] event : events) {
                    ctx.write(new DefaultHttpContent(Unpooled.wrappedBuffer(event)));
                }
            }
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        }
    }
}
