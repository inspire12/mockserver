package org.mockserver.netty.integration.proxy.http;

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
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2PriorityFrame;
import io.netty.handler.codec.http2.DefaultHttp2WindowUpdateFrame;
import io.netty.handler.codec.http2.Http2ChannelDuplexHandler;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2StreamFrame;
import io.netty.util.ReferenceCountUtil;
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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.testing.tls.SSLSocketFactory.sslSocketFactory;

/**
 * {@code responseWriteStallTimeoutMillis} ends a response whose client takes none of it for the timeout: an HTTP/1.1
 * connection (or the CONNECT tunnel it reads through) is closed and an HTTP/2 stream reset, and a streamed response's
 * upstream is closed. A client that keeps taking some of it at least once per timeout period gets its whole response,
 * and a disabled timeout leaves a stalled client alone.
 * <p>
 * Responses are forwarded from an upstream: {@code /fixed} is a 16 MiB body with a {@code Content-Length}, so it is
 * aggregated; {@code /big} is 16 MiB of server-sent events in one write, so it is streamed; {@code /trickle} is about
 * 100 KB of events and then nothing, with the upstream left open. Each is far more than the socket buffers between
 * MockServer and a client with a 32 KiB receive buffer, apart from {@code /trickle}, which is meant for an HTTP/2 client
 * that grants no flow-control window.
 * <p>
 * The progressing HTTP/1.1 reader pauses for a third of the timeout between reads of up to 1 MiB: a socket shows a
 * slow reader's progress to its writer only in bursts, once a share of the kernel's buffers is free, so reading a
 * few KB at a time would show MockServer nothing for longer than a short test timeout on some platforms.
 */
public class ResponseWriteStallTimeoutIntegrationTest {

    private static final long STALL_MILLIS = 3000;
    private static final long CUT_WITHIN_MILLIS = 3 * STALL_MILLIS + 5000;
    private static final long SLOW_PHASE_MILLIS = 3 * STALL_MILLIS;
    private static final long READ_PAUSE_MILLIS = STALL_MILLIS / 3;
    private static final int READ_BURST_BYTES = 1024 * 1024;
    private static final String TERMINATING_CHUNK = "0\r\n\r\n";
    private static final Map<String, Channel> UPSTREAM_CHANNELS = new ConcurrentHashMap<>();

    private static byte[] fixedBody;
    private static byte[][] events;
    private static EventLoopGroup upstreamGroup;
    private static EventLoopGroup clientGroup;
    private static Channel upstreamChannel;
    private static int upstreamPort;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static MockServer disabledMockServer;
    private static MockServerClient disabledMockServerClient;

    @BeforeClass
    public static void startServers() throws Exception {
        fixedBody = new byte[16 * 1024 * 1024];
        new Random(72).nextBytes(fixedBody);
        events = StreamedEvents.events(1024 * 1024);

        upstreamGroup = new NioEventLoopGroup(1);
        clientGroup = new NioEventLoopGroup(2);
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
            .bind(0).sync().channel();
        upstreamPort = ((InetSocketAddress) upstreamChannel.localAddress()).getPort();

        // a stream idle timeout far longer than any test, so only the write-stall timeout can close an upstream; WARN
        // keeps the 16 MiB forwarded bodies out of the event log
        mockServer = new MockServer(configuration()
            .logLevel("WARN")
            .streamingResponsesEnabled(true)
            .streamIdleTimeoutSeconds(120)
            .responseWriteStallTimeoutMillis(STALL_MILLIS));
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        disabledMockServer = new MockServer(configuration()
            .logLevel("WARN")
            .streamingResponsesEnabled(true)
            .streamIdleTimeoutSeconds(120)
            .responseWriteStallTimeoutMillis(0L));
        disabledMockServerClient = new MockServerClient("localhost", disabledMockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        stopQuietly(disabledMockServerClient);
        stopQuietly(disabledMockServer);
        if (upstreamChannel != null) {
            upstreamChannel.close();
        }
        if (upstreamGroup != null) {
            upstreamGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Before
    public void resetExpectations() {
        for (MockServerClient client : new MockServerClient[]{mockServerClient, disabledMockServerClient}) {
            client.reset();
            client.when(request().withPath("/forward/.*")).forward(forward().withHost("127.0.0.1").withPort(upstreamPort));
        }
    }

    // ---- HTTP/1.1 ----

    @Test
    public void shouldEndAStalledHttp1ReadersAggregatedResponseIncomplete() throws Exception {
        try (Socket socket = connect(mockServer, "/forward/fixed?test=http1-aggregated-stalled")) {
            TimeUnit.MILLISECONDS.sleep(CUT_WITHIN_MILLIS);
            Received received = read(socket, 0, Received::aggregatedResponseComplete);
            assertThat("the response was cut short", received.isAggregatedResponseComplete(), is(false));
            assertThat("MockServer closed the connection", received.endedBy, is(Ending.CLOSED));
        }
    }

    @Test
    public void shouldEndAStalledHttp1ReadersStreamedResponseIncompleteAndCloseItsUpstream() throws Exception {
        String uri = "/forward/big?test=http1-streamed-stalled";
        try (Socket socket = connect(mockServer, uri)) {
            Channel upstream = awaitUpstream(uri);
            assertThat("the upstream was closed", upstream.closeFuture().await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            Received received = read(socket, 0, Received::streamedResponseComplete);
            assertThat("no terminating chunk made the response look complete", received.text(), not(endsWith(TERMINATING_CHUNK)));
            assertThat("MockServer closed the connection", received.endedBy, is(Ending.CLOSED));
        }
    }

    @Test
    public void shouldDeliverAnAggregatedResponseWholeToASlowButProgressingHttp1Reader() throws Exception {
        try (Socket socket = connect(mockServer, "/forward/fixed?test=http1-aggregated-slow")) {
            Received received = read(socket, SLOW_PHASE_MILLIS, Received::aggregatedResponseComplete);
            assertThat(received.endedBy, is(Ending.COMPLETE));
            assertThat("still in progress after pausing for several timeouts in all", received.bytesAfterSlowPhase < received.bytes.size(), is(true));
            assertThat("the body arrived intact", java.util.Arrays.equals(received.body(), fixedBody), is(true));
        }
    }

    @Test
    public void shouldDeliverAStreamedResponseWholeToASlowButProgressingHttp1Reader() throws Exception {
        try (Socket socket = connect(mockServer, "/forward/big?test=http1-streamed-slow")) {
            Received received = read(socket, SLOW_PHASE_MILLIS, Received::streamedResponseComplete);
            assertThat(received.endedBy, is(Ending.COMPLETE));
            assertThat("still in progress after pausing for several timeouts in all", received.bytesAfterSlowPhase < received.bytes.size(), is(true));
            assertThat(StreamedEvents.dechunk(received.text()).length, is(16 * StreamedEvents.joinedLength(events)));
        }
    }

    @Test
    public void shouldLeaveAStalledHttp1ReaderAloneWhenTheTimeoutIsDisabled() throws Exception {
        try (Socket socket = connect(disabledMockServer, "/forward/fixed?test=http1-aggregated-disabled")) {
            TimeUnit.MILLISECONDS.sleep(CUT_WITHIN_MILLIS);
            Received received = read(socket, 0, Received::aggregatedResponseComplete);
            assertThat(received.endedBy, is(Ending.COMPLETE));
            assertThat("the body arrived intact", java.util.Arrays.equals(received.body(), fixedBody), is(true));
        }
    }

    // ---- HTTP/1.1 inside a CONNECT tunnel ----

    @Test
    public void shouldCloseAStalledConnectTunnelReadersTunnelAndTheStreamedResponsesUpstream() throws Exception {
        String uri = "/forward/big?test=tunnel-streamed-stalled";
        try (Socket socket = connectThroughTunnel(mockServer, uri)) {
            Channel upstream = awaitUpstream(uri);
            assertThat("the upstream was closed", upstream.closeFuture().await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            Received received = read(socket, 0, Received::streamedResponseComplete);
            assertThat("no terminating chunk made the response look complete", received.text(), not(endsWith(TERMINATING_CHUNK)));
            assertThat("MockServer closed the tunnel", received.endedBy, is(Ending.CLOSED));
        }
    }

    @Test
    public void shouldCloseTheTunnelAtOnceForAReaderThatResumesJustAfterItsStallIsCut() throws Exception {
        // the relay holds this client's TLS, and closing through it would leave the tunnel open, refusing the rest of
        // the response, while its close_notify waited behind the bytes not taken
        try (Socket socket = connectThroughTunnel(mockServer, "/forward/big?test=tunnel-streamed-stalled-then-reading")) {
            TimeUnit.MILLISECONDS.sleep(2 * STALL_MILLIS);
            Received received = read(socket, 0, Received::streamedResponseComplete);
            assertThat("MockServer closed the tunnel", received.endedBy, is(Ending.CLOSED));
            assertThat("no terminating chunk made the response look complete", received.text(), not(endsWith(TERMINATING_CHUNK)));
            assertThat("the relay was not left writing the rest of the response to a tunnel that refuses it",
                mockServerClient.retrieveLogMessages(null), not(containsString("exception while returning writing")));
        }
    }

    @Test
    public void shouldDeliverAStreamedResponseWholeToASlowButProgressingConnectTunnelReader() throws Exception {
        // MockServer's relay reads its loopback only as fast as this client reads, so the loopback must not be cut either
        try (Socket socket = connectThroughTunnel(mockServer, "/forward/big?test=tunnel-streamed-slow")) {
            Received received = read(socket, SLOW_PHASE_MILLIS, Received::streamedResponseComplete);
            assertThat(received.endedBy, is(Ending.COMPLETE));
            assertThat("still in progress after pausing for several timeouts in all", received.bytesAfterSlowPhase < received.bytes.size(), is(true));
            assertThat(StreamedEvents.dechunk(received.text()).length, is(16 * StreamedEvents.joinedLength(events)));
        }
    }

    @Test
    public void shouldNotExemptAClientThatSendsTheRelayLoopbackPreambleItself() throws Exception {
        // only MockServer's own relay loopback is exempt, so a client cannot opt out by sending the loopback's preamble
        Socket socket = new Socket();
        socket.setReceiveBufferSize(32 * 1024);
        socket.connect(new InetSocketAddress("127.0.0.1", mockServer.getLocalPort()));
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(15));
        try {
            String preamble = "PROXIED_127.0.0.1:" + upstreamPort;
            socket.getOutputStream().write(preamble.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] answer = new byte[("PROXIED_RESPONSE_" + preamble).length()];
            for (int read = 0; read < answer.length; ) {
                int n = socket.getInputStream().read(answer, read, answer.length - read);
                assertThat("MockServer answered the preamble", n, not(is(-1)));
                read += n;
            }
            get(socket, "/forward/fixed?test=preamble-aggregated-stalled");
            TimeUnit.MILLISECONDS.sleep(CUT_WITHIN_MILLIS);
            Received received = read(socket, 0, Received::aggregatedResponseComplete);
            assertThat("the response was cut short", received.isAggregatedResponseComplete(), is(false));
            assertThat("MockServer closed the connection", received.endedBy, is(Ending.CLOSED));
        } finally {
            socket.close();
        }
    }

    // ---- HTTP/2 ----

    @Test
    public void shouldResetAStalledHttp2StreamOfAnAggregatedResponse() throws Exception {
        try (Http2Client client = Http2Client.open(mockServer)) {
            Http2Client.Stream stream = client.request("/forward/fixed?test=http2-aggregated-stalled");
            assertThat("the stream ended", stream.ended.await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            assertThat("the stream was reset", stream.resetErrorCode.get(), is(Http2Error.CANCEL.code()));
            assertThat("the connection, whose socket kept being read, stayed open", client.channel.isActive(), is(true));
        }
    }

    @Test
    public void shouldResetAStalledHttp2StreamOfAStreamedResponseAndCloseItsQuietUpstream() throws Exception {
        String uri = "/forward/trickle?test=http2-streamed-stalled";
        try (Http2Client client = Http2Client.open(mockServer)) {
            Http2Client.Stream stream = client.request(uri);
            Channel upstream = awaitUpstream(uri);
            assertThat("the stream ended", stream.ended.await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            assertThat("the stream was reset", stream.resetErrorCode.get(), is(Http2Error.CANCEL.code()));
            // the upstream has sent everything and is quiet, so only closing it with the stream frees it before its idle timeout
            assertThat("the upstream was closed", upstream.closeFuture().await(5, TimeUnit.SECONDS), is(true));
        }
    }

    @Test
    public void shouldDeliverAnAggregatedResponseWholeToASlowButProgressingHttp2Reader() throws Exception {
        try (Http2Client client = Http2Client.open(mockServer)) {
            Http2Client.Stream stream = client.request("/forward/fixed?test=http2-aggregated-slow");
            consumeSlowly(client, stream);
            assertThat("still in progress after the slow phase", stream.ended.getCount(), is(1L));
            client.consumeAll(stream);
            assertCompleteWithFixedBody(stream);
        }
    }

    @Test
    public void shouldNotResetAnHttp2StreamQueuedBehindAStreamItsClientIsTaking() throws Exception {
        try (Http2Client client = Http2Client.open(mockServer)) {
            Http2Client.Stream first = client.request("/forward/fixed?test=http2-priority-first");
            // depends exclusively on the first, so it gets no data, and its window does not change, while the first has any to send
            Http2Client.Stream queued = client.request("/forward/fixed?test=http2-priority-queued", first);
            consumeSlowly(client, first);
            assertThat("the queued stream is still in progress", queued.ended.getCount(), is(1L));
            assertThat("the queued stream got nothing while the first had data to send", queued.dataBytes.get(), is(0L));
            client.consumeAll(first, queued);
            assertCompleteWithFixedBody(first);
            assertCompleteWithFixedBody(queued);
        }
    }

    // the client sends a WINDOW_UPDATE for what it has received about once a second, so the stream gets more window that often
    private static void consumeSlowly(Http2Client client, Http2Client.Stream stream) throws Exception {
        long started = System.nanoTime();
        while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < SLOW_PHASE_MILLIS) {
            TimeUnit.MILLISECONDS.sleep(READ_PAUSE_MILLIS);
            client.consumeReceived(stream);
        }
    }

    private static void assertCompleteWithFixedBody(Http2Client.Stream stream) throws InterruptedException {
        assertThat("the stream ended", stream.ended.await(30, TimeUnit.SECONDS), is(true));
        assertThat(stream.resetErrorCode.get(), is(nullValue()));
        assertThat(stream.endStream.get(), is(true));
        assertThat(stream.dataBytes.get(), is((long) fixedBody.length));
    }

    // ---- harness ----

    private static Socket connect(MockServer server, String uri) throws IOException {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(32 * 1024);
        socket.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(15));
        return get(socket, uri);
    }

    /**
     * Opens a CONNECT tunnel, which MockServer answers itself through its relay and loopback, and sends the request
     * over TLS inside it.
     */
    private static Socket connectThroughTunnel(MockServer server, String uri) throws IOException {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(32 * 1024);
        socket.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(15));
        socket.getOutputStream().write("CONNECT 127.0.0.1:443 HTTP/1.1\r\nHost: 127.0.0.1:443\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        // byte by byte, so nothing of the TLS handshake that follows is consumed
        StringBuilder connectResponse = new StringBuilder();
        InputStream in = socket.getInputStream();
        for (int b; !connectResponse.toString().endsWith("\r\n\r\n") && (b = in.read()) != -1; ) {
            connectResponse.append((char) b);
        }
        assertThat(connectResponse.toString(), startsWith("HTTP/1.1 200"));
        return get(sslSocketFactory().wrapSocket(socket), uri);
    }

    private static Socket get(Socket socket, String uri) throws IOException {
        socket.getOutputStream().write(("GET " + uri + " HTTP/1.1\r\nHost: 127.0.0.1:" + upstreamPort + "\r\nAccept: " + accept(uri) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    // asking for an event stream makes MockServer stream the response, so only the event-stream paths ask for one
    private static String accept(String uri) {
        return uri.startsWith("/forward/fixed") ? "application/octet-stream" : "text/event-stream";
    }

    private static Channel awaitUpstream(String uri) throws InterruptedException {
        for (int i = 0; i < 1000 && !UPSTREAM_CHANNELS.containsKey(uri); i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat("the upstream received " + uri, UPSTREAM_CHANNELS.containsKey(uri), is(true));
        return UPSTREAM_CHANNELS.get(uri);
    }

    enum Ending {COMPLETE, CLOSED, TIMED_OUT}

    /**
     * For {@code slowPhaseMillis}, pauses for {@link #READ_PAUSE_MILLIS} between reads of up to {@link #READ_BURST_BYTES};
     * then reads as fast as it can, until the response is complete, the connection ends or nothing arrives for the
     * socket timeout.
     */
    private static Received read(Socket socket, long slowPhaseMillis, Predicate<Received> complete) throws InterruptedException {
        Received received = new Received();
        long started = System.nanoTime();
        byte[] buffer = new byte[64 * 1024];
        try {
            InputStream in = socket.getInputStream();
            int burst = 0;
            while (true) {
                int read = in.read(buffer);
                if (read == -1) {
                    received.endedBy = Ending.CLOSED;
                    break;
                }
                received.write(buffer, read);
                if (complete.test(received)) {
                    received.endedBy = Ending.COMPLETE;
                    break;
                }
                if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < slowPhaseMillis) {
                    burst += read;
                    if (burst >= READ_BURST_BYTES) {
                        burst = 0;
                        TimeUnit.MILLISECONDS.sleep(READ_PAUSE_MILLIS);
                    }
                    received.bytesAfterSlowPhase = received.bytes.size();
                }
            }
        } catch (SocketTimeoutException timeout) {
            received.endedBy = Ending.TIMED_OUT;
        } catch (IOException reset) {
            received.endedBy = Ending.CLOSED;
        }
        return received;
    }

    private static final class Received {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final byte[] tail = new byte[TERMINATING_CHUNK.length()];
        private int headLength = -1;
        private Ending endedBy;
        private int bytesAfterSlowPhase;

        void write(byte[] buffer, int length) {
            bytes.write(buffer, 0, length);
            int fromBuffer = Math.min(length, tail.length);
            int keepOld = tail.length - fromBuffer;
            System.arraycopy(tail, tail.length - keepOld, tail, 0, keepOld);
            System.arraycopy(buffer, length - fromBuffer, tail, keepOld, fromBuffer);
            if (headLength < 0) {
                int end = text(Math.min(bytes.size(), 16 * 1024)).indexOf("\r\n\r\n");
                headLength = end < 0 ? -1 : end + 4;
            }
        }

        private String text(int length) {
            return new String(bytes.toByteArray(), 0, length, StandardCharsets.ISO_8859_1);
        }

        String text() {
            return bytes.toString(StandardCharsets.ISO_8859_1);
        }

        byte[] body() {
            byte[] all = bytes.toByteArray();
            byte[] body = new byte[all.length - headLength];
            System.arraycopy(all, headLength, body, 0, body.length);
            return body;
        }

        boolean isAggregatedResponseComplete() {
            return aggregatedResponseComplete(this);
        }

        static boolean aggregatedResponseComplete(Received received) {
            return received.headLength > 0 && received.bytes.size() >= received.headLength + fixedBody.length;
        }

        static boolean streamedResponseComplete(Received received) {
            return received.bytes.size() >= TERMINATING_CHUNK.length() && new String(received.tail, StandardCharsets.ISO_8859_1).equals(TERMINATING_CHUNK);
        }
    }

    /**
     * An h2c client that consumes (and so grants window for) only what it is told to: until then each stream's window
     * and the connection's run out and MockServer's flow controller holds the rest, while the socket itself keeps being
     * read.
     */
    private static final class Http2Client extends Http2ChannelDuplexHandler implements AutoCloseable {
        private final Map<Http2FrameStream, Stream> streams = new ConcurrentHashMap<>();
        private Channel channel;
        private ChannelHandlerContext ctx;

        static final class Stream {
            private final AtomicLong dataBytes = new AtomicLong();
            private final AtomicBoolean endStream = new AtomicBoolean();
            private final AtomicReference<Long> resetErrorCode = new AtomicReference<>();
            private final CountDownLatch ended = new CountDownLatch(1);
            private Http2FrameStream frameStream;
            private int unconsumedBytes;
            private boolean consumeAll;
        }

        static Http2Client open(MockServer server) throws Exception {
            Http2Client client = new Http2Client();
            client.channel = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build(), client);
                    }
                })
                .connect("127.0.0.1", server.getLocalPort()).sync().channel();
            return client;
        }

        @Override
        protected void handlerAdded0(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        Stream request(String uri) throws Exception {
            return request(uri, null);
        }

        /**
         * @param dependsOn if not null, the new stream depends exclusively on this one
         */
        Stream request(String uri, Stream dependsOn) throws Exception {
            Stream stream = new Stream();
            ctx.executor().submit(() -> {
                stream.frameStream = newStream();
                streams.put(stream.frameStream, stream);
                ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()
                    .method(HttpMethod.GET.asciiName())
                    .scheme(HttpScheme.HTTP.name())
                    .authority("127.0.0.1:" + upstreamPort)
                    .path(uri)
                    .add("accept", accept(uri)), true).stream(stream.frameStream));
                if (dependsOn != null) {
                    ctx.write(new DefaultHttp2PriorityFrame(dependsOn.frameStream.id(), (short) 16, true).stream(stream.frameStream));
                }
                ctx.flush();
            }).get(5, TimeUnit.SECONDS);
            return stream;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                Stream stream = msg instanceof Http2StreamFrame && ((Http2StreamFrame) msg).stream() != null ? streams.get(((Http2StreamFrame) msg).stream()) : null;
                if (stream == null) {
                    return;
                }
                if (msg instanceof Http2DataFrame) {
                    Http2DataFrame data = (Http2DataFrame) msg;
                    stream.dataBytes.addAndGet(data.content().readableBytes());
                    stream.unconsumedBytes += data.initialFlowControlledBytes();
                    if (stream.consumeAll) {
                        consume(stream);
                    }
                    if (data.isEndStream()) {
                        stream.endStream.set(true);
                        stream.ended.countDown();
                    }
                } else if (msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream()) {
                    stream.endStream.set(true);
                    stream.ended.countDown();
                } else if (msg instanceof Http2ResetFrame) {
                    stream.resetErrorCode.set(((Http2ResetFrame) msg).errorCode());
                    stream.ended.countDown();
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        void consumeReceived(Stream stream) throws Exception {
            ctx.executor().submit(() -> consume(stream)).get(5, TimeUnit.SECONDS);
        }

        void consumeAll(Stream... toConsume) throws Exception {
            ctx.executor().submit(() -> {
                for (Stream stream : toConsume) {
                    stream.consumeAll = true;
                    consume(stream);
                }
            }).get(5, TimeUnit.SECONDS);
        }

        private void consume(Stream stream) {
            if (stream.unconsumedBytes > 0 && stream.ended.getCount() > 0) {
                ctx.writeAndFlush(new DefaultHttp2WindowUpdateFrame(stream.unconsumedBytes).stream(stream.frameStream));
                stream.unconsumedBytes = 0;
            }
        }

        @Override
        public void close() {
            channel.close();
        }
    }

    /**
     * {@code /fixed}: the fixed body with a {@code Content-Length}; {@code /big}: every event 16 times in one write,
     * then the end of the stream; {@code /trickle}: about 100 KB of events, then nothing, with the connection left open.
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
            if (uri.startsWith("/forward/trickle")) {
                for (int i = 0, written = 0; written < 100_000; i++) {
                    ctx.write(new DefaultHttpContent(Unpooled.wrappedBuffer(events[i])));
                    written += events[i].length;
                }
                ctx.flush();
                return;
            }
            for (int i = 0; i < 16; i++) {
                for (byte[] event : events) {
                    ctx.write(new DefaultHttpContent(Unpooled.wrappedBuffer(event)));
                }
            }
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        }
    }

    static final class StreamedEvents {

        // events of about 815 bytes with random payloads
        static byte[][] events(int size) {
            Random random = new Random(815);
            java.util.List<byte[]> chunks = new java.util.ArrayList<>();
            byte[] payload = new byte[600];
            for (int id = 0, total = 0; total < size; id++) {
                random.nextBytes(payload);
                byte[] event = ("id: " + id + "\ndata: " + java.util.Base64.getEncoder().encodeToString(payload) + "\n\n").getBytes(StandardCharsets.US_ASCII);
                chunks.add(event);
                total += event.length;
            }
            return chunks.toArray(new byte[0][]);
        }

        static int joinedLength(byte[][] events) {
            int length = 0;
            for (byte[] event : events) {
                length += event.length;
            }
            return length;
        }

        static byte[] dechunk(String response) {
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
    }
}
