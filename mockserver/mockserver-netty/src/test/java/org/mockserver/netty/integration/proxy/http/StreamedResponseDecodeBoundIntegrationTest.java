package org.mockserver.netty.integration.proxy.http;

import com.github.luben.zstd.ZstdOutputStream;
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
import io.netty.handler.codec.compression.Zstd;
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
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpForward;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A streamed ({@code text/event-stream}) response's decoded bytes waiting to be written to the client are bounded
 * ({@code maxResponseBodySize}, and {@code maxRequestBodySize} in the CONNECT relay; 1 MiB here). MockServer asks the
 * upstream for more only once that backlog has drained to a low watermark, so only a single read that decodes to more
 * than the bound can pass it: one network read of a {@code zstd} body decodes to about 2 GiB, of {@code gzip} to
 * megabytes. Each hostile response is written by the upstream in one write and the client does not read; without the
 * bound every decoded byte would be copied and queued, with it MockServer aborts the stream, closes the upstream and
 * ends the client's response incomplete. A legitimate stream many times the bound, whether in large or in many small
 * chunks, still reaches a slow client complete, forwarded and through a CONNECT tunnel.
 */
public class StreamedResponseDecodeBoundIntegrationTest {

    private static final long TWO_GIB = 2L * 1024 * 1024 * 1024;
    private static final int BOUNDED = 1024 * 1024;
    private static final int RECEIVE_BUFFER = 32 * 1024;
    // a client that does not read holds at most the kernel's socket buffers, far below the decoded total
    private static final int MOST_A_STALLED_CLIENT_RECEIVES = 64 * 1024 * 1024;
    private static final long TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);

    private static byte[] zstdBomb;
    private static byte[] gzipBomb;
    private static byte[] events;
    private static byte[] gzipEvents;
    private static byte[][] smallEvents;
    private static byte[] smallEventsJoined;
    private static final Map<String, Channel> UPSTREAM_CHANNELS = new ConcurrentHashMap<>();

    private static EventLoopGroup upstreamGroup;
    private static Channel plainUpstreamChannel;
    private static Channel tlsUpstreamChannel;
    private static int plainUpstreamPort;
    private static int tlsUpstreamPort;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServers() throws Exception {
        assertThat("zstd-jni reaches this module's classpath, as it does the shaded jar", Zstd.isAvailable(), is(true));
        zstdBomb = zstdOfZeros(TWO_GIB);
        gzipBomb = gzipOfZeros(TWO_GIB);
        assertThat("2 GiB of zstd fits one ordinary read", zstdBomb.length, lessThan(128 * 1024));
        events = events(16 * 1024 * 1024);
        gzipEvents = gzip(events);
        smallEvents = smallEvents(16 * 1024 * 1024);
        smallEventsJoined = join(smallEvents);

        upstreamGroup = new NioEventLoopGroup(2);
        UpstreamHandler handler = new UpstreamHandler();
        plainUpstreamChannel = new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(1024 * 1024), handler);
                }
            })
            .bind(0).sync().channel();
        plainUpstreamPort = ((InetSocketAddress) plainUpstreamChannel.localAddress()).getPort();

        SelfSignedCertificate certificate = new SelfSignedCertificate();
        SslContext tlsUpstreamSslContext = SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey())
            .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            .applicationProtocolConfig(new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                ApplicationProtocolNames.HTTP_2,
                ApplicationProtocolNames.HTTP_1_1))
            .build();
        tlsUpstreamChannel = new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(tlsUpstreamSslContext.newHandler(ch.alloc()));
                    ch.pipeline().addLast(new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
                        @Override
                        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                            if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                                ctx.pipeline().addLast(Http2FrameCodecBuilder.forServer().build());
                                ctx.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                    @Override
                                    protected void initChannel(Channel streamChannel) {
                                        streamChannel.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(true), new HttpObjectAggregator(1024 * 1024), handler);
                                    }
                                }));
                            } else {
                                ctx.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(1024 * 1024), handler);
                            }
                        }
                    });
                }
            })
            .bind(0).sync().channel();
        tlsUpstreamPort = ((InetSocketAddress) tlsUpstreamChannel.localAddress()).getPort();

        // maxResponseBodySize bounds a forwarded stream, maxRequestBodySize one relayed by the CONNECT relay
        mockServer = new MockServer(configuration()
            .streamingResponsesEnabled(true)
            .forwardProxyHttp2Upgrade(true)
            .maxResponseBodySize(BOUNDED)
            .maxRequestBodySize(BOUNDED));
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (plainUpstreamChannel != null) {
            plainUpstreamChannel.close();
        }
        if (tlsUpstreamChannel != null) {
            tlsUpstreamChannel.close();
        }
        if (upstreamGroup != null) {
            upstreamGroup.shutdownGracefully();
        }
    }

    @Before
    public void resetExpectations() {
        UPSTREAM_CHANNELS.clear();
        mockServerClient.reset();
        mockServerClient.when(request().withPath("/forward-h1/.*")).forward(forward().withHost("127.0.0.1").withPort(plainUpstreamPort));
        mockServerClient.when(request().withPath("/forward-h2/.*")).forward(forward().withHost("127.0.0.1").withPort(tlsUpstreamPort).withScheme(HttpForward.Scheme.HTTPS));
        // served by MockServer itself, so through a CONNECT tunnel the relay's loopback leg decodes it
        mockServerClient.when(request().withPath("/mock/stream/zstd-bomb")).respond(response()
            .withHeader("content-type", "text/event-stream")
            .withHeader("content-encoding", "zstd")
            .withBody(binary(zstdBomb)));
    }

    @Test
    public void shouldAbortAZstdStreamForwardedOverHttp1() throws Exception {
        assertAbortedIncomplete(stalledClient(null, "/forward-h1/stream/zstd-bomb", () -> awaitUpstreamClosed("/forward-h1/stream/zstd-bomb")));
    }

    @Test
    public void shouldAbortAGzipStreamForwardedOverHttp1() throws Exception {
        assertAbortedIncomplete(stalledClient(null, "/forward-h1/stream/gzip-bomb", () -> awaitUpstreamClosed("/forward-h1/stream/gzip-bomb")));
    }

    @Test
    public void shouldAbortAZstdStreamForwardedOverHttp2() throws Exception {
        assertAbortedIncomplete(stalledClient(null, "/forward-h2/stream/zstd-bomb", () -> awaitUpstreamClosed("/forward-h2/stream/zstd-bomb")));
        assertThat("the forward client negotiated HTTP/2 with the upstream", UPSTREAM_CHANNELS.get("/forward-h2/stream/zstd-bomb") instanceof Http2StreamChannel, is(true));
    }

    @Test
    public void shouldAbortAZstdStreamDecodedByTheConnectRelayOverHttp1() throws Exception {
        // the loopback decodes the whole response in its first read, so the abort comes long before the stall ends
        assertAbortedIncomplete(stalledClient("127.0.0.1:" + plainUpstreamPort, "/mock/stream/zstd-bomb", () -> TimeUnit.SECONDS.sleep(5)));
    }

    @Test
    public void shouldDeliverAStreamManyTimesTheBoundToASlowClient() throws Exception {
        assertDelivered(slowClient(null, "/forward-h1/stream/events"), events);
    }

    @Test
    public void shouldDeliverAStreamManyTimesTheBoundToASlowClientThroughTheConnectRelay() throws Exception {
        // no expectation matches, so MockServer forwards to the upstream and the relay's loopback relays the stream
        assertDelivered(slowClient("127.0.0.1:" + plainUpstreamPort, "/connect/stream/events"), events);
    }

    @Test
    public void shouldDeliverAStreamOfManySmallChunksPerReadToASlowClient() throws Exception {
        assertDelivered(slowClient(null, "/forward-h1/stream/small-events"), smallEventsJoined);
    }

    @Test
    public void shouldDeliverAStreamOfManySmallChunksPerReadToASlowClientThroughTheConnectRelay() throws Exception {
        assertDelivered(slowClient("127.0.0.1:" + plainUpstreamPort, "/connect/stream/small-events"), smallEventsJoined);
    }

    private static void assertDelivered(byte[] body, byte[] expected) {
        assertThat(body.length, is(expected.length));
        assertThat(Arrays.equals(body, expected), is(true));
    }

    private static void assertAbortedIncomplete(byte[] received) {
        String text = new String(received, StandardCharsets.ISO_8859_1);
        assertThat(text, startsWith("HTTP/1.1 200"));
        assertThat(received.length, lessThan(MOST_A_STALLED_CLIENT_RECEIVES));
        assertThat("the response has no terminating chunk", text, not(endsWith("0\r\n\r\n")));
        // MockServer still serves
        assertThat(mockServerClient.hasStarted(), is(true));
    }

    /**
     * MockServer closes its upstream connection (HTTP/2: stream) when it aborts; without the bound it closes it only
     * after the whole response is decoded and queued.
     */
    private static void awaitUpstreamClosed(String path) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!UPSTREAM_CHANNELS.containsKey(path) && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        Channel upstream = UPSTREAM_CHANNELS.get(path);
        assertThat("the upstream received " + path, upstream != null, is(true));
        assertThat("MockServer closed the upstream", upstream.closeFuture().await(TIMEOUT_MILLIS), is(true));
    }

    private interface Stall {
        void await() throws Exception;
    }

    /**
     * Sends the request to {@link #mockServer} and does not read until {@code stall} returns, then returns everything
     * received, up to {@link #MOST_A_STALLED_CLIENT_RECEIVES}.
     */
    private static byte[] stalledClient(String connectAuthority, String path, Stall stall) throws Exception {
        try (Socket socket = new Socket()) {
            socket.setReceiveBufferSize(RECEIVE_BUFFER);
            socket.connect(new InetSocketAddress("127.0.0.1", mockServer.getLocalPort()));
            socket.setSoTimeout((int) TIMEOUT_MILLIS);
            sendRequest(socket, connectAuthority, path);
            try {
                stall.await();
            } catch (AssertionError stallFailed) {
                socket.setSoTimeout(2000);
                byte[] received = readAvailable(socket.getInputStream());
                String text = new String(received, StandardCharsets.ISO_8859_1);
                throw new AssertionError(stallFailed.getMessage() + "; the client had received " + received.length
                    + " bytes, starting: " + text.substring(0, Math.min(200, text.length()))
                    + " ending: " + text.substring(Math.max(0, text.length() - 64)), stallFailed);
            }
            // a reset ends the response as incomplete as a close does
            return readAvailable(socket.getInputStream());
        }
    }

    /**
     * Reads the response a little at a time, pausing between reads, and returns its decoded chunked body.
     */
    private static byte[] slowClient(String connectAuthority, String path) throws Exception {
        try (Socket socket = new Socket()) {
            socket.setReceiveBufferSize(RECEIVE_BUFFER);
            socket.connect(new InetSocketAddress("127.0.0.1", mockServer.getLocalPort()));
            socket.setSoTimeout((int) TIMEOUT_MILLIS);
            sendRequest(socket, connectAuthority, path);
            InputStream in = new SlowInputStream(socket.getInputStream());
            String head = readHead(in);
            assertThat(head, startsWith("HTTP/1.1 200"));
            assertThat(head.toLowerCase(), containsString("transfer-encoding: chunked"));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (int size; (size = Integer.parseInt(readLine(in).trim(), 16)) > 0; ) {
                body.write(readFully(in, size));
                readLine(in);
            }
            return body.toByteArray();
        }
    }

    private static byte[] readAvailable(InputStream in) {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        try {
            for (int read; (read = in.read(buffer)) != -1 && received.size() < MOST_A_STALLED_CLIENT_RECEIVES; ) {
                received.write(buffer, 0, read);
            }
        } catch (IOException endOfWhatArrived) {
            // a timeout or reset ends what the client can report
        }
        return received.toByteArray();
    }

    private static void sendRequest(Socket socket, String connectAuthority, String path) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        String host = "127.0.0.1:" + plainUpstreamPort;
        if (connectAuthority != null) {
            out.write(("CONNECT " + connectAuthority + " HTTP/1.1\r\nHost: " + connectAuthority + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertThat(readHead(in), startsWith("HTTP/1.1 200"));
            host = connectAuthority;
        }
        out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        for (String line; !(line = readLine(in)).isEmpty(); ) {
            head.append(line).append("\r\n");
        }
        return head.toString();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int b; (b = in.read()) != '\n'; ) {
            if (b == -1) {
                throw new IOException("connection closed mid-line after: " + line);
            }
            if (b != '\r') {
                line.append((char) b);
            }
        }
        return line.toString();
    }

    private static byte[] readFully(InputStream in, int size) throws IOException {
        byte[] bytes = new byte[size];
        for (int offset = 0, read; offset < size; offset += read) {
            if ((read = in.read(bytes, offset, size - offset)) == -1) {
                throw new IOException("connection closed mid-chunk");
            }
        }
        return bytes;
    }

    /**
     * Reads at most 8 KiB at a time from the socket, sleeping a millisecond before each read, so MockServer's writes to
     * this client wait on it far longer than the upstream takes to send.
     */
    private static final class SlowInputStream extends InputStream {
        private final InputStream in;
        private final byte[] buffer = new byte[8 * 1024];
        private int position;
        private int limit;

        private SlowInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            if (!fill()) {
                return -1;
            }
            return buffer[position++] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (!fill()) {
                return -1;
            }
            int read = Math.min(length, limit - position);
            System.arraycopy(buffer, position, bytes, offset, read);
            position += read;
            return read;
        }

        private boolean fill() throws IOException {
            if (position < limit) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            try {
                limit = in.read(buffer);
            } catch (SocketTimeoutException timeout) {
                throw new IOException("no data from MockServer for " + TIMEOUT_MILLIS + " ms", timeout);
            }
            position = 0;
            return limit > 0;
        }
    }

    private static byte[] zstdOfZeros(long size) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (ZstdOutputStream zstd = new ZstdOutputStream(compressed, 3)) {
            writeZeros(zstd, size);
        }
        return compressed.toByteArray();
    }

    private static byte[] gzipOfZeros(long size) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed, 64 * 1024)) {
            writeZeros(gzip, size);
        }
        return compressed.toByteArray();
    }

    private static void writeZeros(OutputStream out, long size) throws IOException {
        byte[] zeros = new byte[1024 * 1024];
        for (long written = 0; written < size; written += zeros.length) {
            out.write(zeros, 0, (int) Math.min(zeros.length, size - written));
        }
    }

    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(plain);
        }
        return compressed.toByteArray();
    }

    // events with random payloads, so they compress about as little as real JSON events do
    private static byte[] events(int size) {
        Random random = new Random(63);
        ByteArrayOutputStream events = new ByteArrayOutputStream(size + 1024);
        byte[] payload = new byte[600];
        for (int id = 0; events.size() < size; id++) {
            random.nextBytes(payload);
            byte[] event = ("id: " + id + "\ndata: " + Base64.getEncoder().encodeToString(payload) + "\n\n").getBytes(StandardCharsets.US_ASCII);
            events.write(event, 0, event.length);
        }
        return events.toByteArray();
    }

    // uncompressed events of about 830 bytes, each sent as its own chunk, so one read holds dozens of them
    private static byte[][] smallEvents(int size) {
        Random random = new Random(830);
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

    private static byte[] join(byte[][] chunks) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            joined.write(chunk, 0, chunk.length);
        }
        return joined.toByteArray();
    }

    /**
     * Answers {@code .../small-events} with {@link #smallEvents}, uncompressed, one chunk per event, flushed together.
     */
    private static void writeSmallEvents(ChannelHandlerContext ctx) {
        DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
        HttpUtil.setTransferEncodingChunked(head, true);
        ctx.write(head);
        for (byte[] event : smallEvents) {
            ctx.write(new DefaultHttpContent(Unpooled.wrappedBuffer(event)));
        }
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    /**
     * Answers each request in one write, as {@code text/event-stream}: {@code .../zstd-bomb} and {@code .../gzip-bomb}
     * with 2 GiB of zeros compressed, and {@code .../events} with {@link #events} in gzip.
     */
    @ChannelHandler.Sharable
    private static final class UpstreamHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String path = new QueryStringDecoder(request.uri()).path();
            UPSTREAM_CHANNELS.put(path, ctx.channel());
            if (path.endsWith("/small-events")) {
                writeSmallEvents(ctx);
                return;
            }
            byte[] body;
            String encoding;
            if (path.endsWith("/zstd-bomb")) {
                body = zstdBomb;
                encoding = "zstd";
            } else if (path.endsWith("/gzip-bomb")) {
                body = gzipBomb;
                encoding = "gzip";
            } else {
                body = gzipEvents;
                encoding = "gzip";
            }
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            response.headers().set(HttpHeaderNames.CONTENT_ENCODING, encoding);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            ctx.writeAndFlush(response);
        }
    }
}
