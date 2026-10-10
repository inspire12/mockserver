package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.NettyLogCapture;
import org.mockserver.testing.socket.Ipv4DatagramChannelFactory;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A client whose SETTINGS allow more blocked QPACK streams than fit an {@code int}, which RFC 9204 permits, against a
 * server with the QPACK dynamic table enabled. Netty's encoder cannot take such a value and fails the QPACK encoder
 * stream MockServer's side opens, where no MockServer handler can be, so Netty would log the failure at {@code WARN}
 * and close the connection. Needs the QUIC native library, and fails without it.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3QpackBlockedStreamsIntegrationTest {

    private static final long WAIT_SECONDS = 15;
    private static final long MORE_THAN_AN_INT = 1L << 31;

    private static final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private static NettyLogCapture nettysLog;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static NioEventLoopGroup clientGroup;

    @BeforeClass
    public static void startServer() throws Exception {
        nettysLog = new NettyLogCapture(NettyLogCapture.PIPELINE);
        clientGroup = new NioEventLoopGroup(1);
        nettysLog.ignoreThreadsOf(clientGroup);
        // a clone: the event log clears the entry it is handed once it has copied it
        MockServerLogger.setGlobalLogEventListener(logEntry -> logged.add(logEntry.clone()));
        mockServer = startWithHttp3(configuration().http3QpackMaxTableCapacity(4096L).http3MaxIdleTimeout(30000L).logLevel("DEBUG"));
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        mockServerClient.when(request().withPath("/")).respond(response().withBody("served"));
        nettysLog.attach();
    }

    @AfterClass
    public static void stopServer() {
        try {
            stopQuietly(mockServerClient);
            stopQuietly(mockServer);
        } finally {
            MockServerLogger.setGlobalLogEventListener(null);
            nettysLog.close();
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Test
    public void shouldServeAClientThatAllowsMoreBlockedStreamsThanFitAnIntAndLogItOnceAtDebug() throws Exception {
        int nettysLogBefore = nettysLog.size();
        try (RawHttp3Client client = new RawHttp3Client()) {
            // SETTINGS with SETTINGS_QPACK_BLOCKED_STREAMS (0x07) as an eight-byte variable-length integer
            byte[] blockedStreams = {(byte) 0xC0, 0, 0, 0, (byte) (MORE_THAN_AN_INT >>> 24), 0, 0, 0};
            client.sendOnANewUnidirectionalStream(concat(new byte[]{0x00, 0x04, 0x09, 0x07}, blockedStreams));

            String received = client.getRoot();

            assertThat(nettysLog.since(nettysLogBefore), empty());
            assertThat("the server did not close the connection", client.closedByServer.isDone(), is(false));
            assertThat(received, containsString("served"));
            List<LogEntry> entries = logged.stream()
                .filter(entry -> String.valueOf(entry.getMessageFormat()).contains("QPACK"))
                .collect(Collectors.toList());
            assertThat(entries.toString(), entries, hasSize(1));
            assertThat(entries.get(0).getLogLevel(), is(Level.DEBUG));
            assertThat(Arrays.asList(entries.get(0).getArguments()), hasItem(MORE_THAN_AN_INT));
            assertThat(Arrays.toString(entries.get(0).getArguments()), containsString(":" + client.localPort()));
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] both = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    /**
     * A QUIC connection with no HTTP/3 codec, so that it can send SETTINGS Netty's own client would refuse to build.
     */
    private static final class RawHttp3Client implements AutoCloseable {

        private final Channel datagramChannel;
        private final QuicChannel quicChannel;
        private final CompletableFuture<QuicConnectionCloseEvent> closedByServer = new CompletableFuture<>();

        private RawHttp3Client() throws Exception {
            datagramChannel = new Bootstrap()
                .group(clientGroup)
                .channelFactory(Ipv4DatagramChannelFactory.INSTANCE)
                .handler(Http3.newQuicClientCodecBuilder()
                    .sslContext(QuicSslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .applicationProtocols(Http3.supportedApplicationProtocols())
                        .build())
                    .maxIdleTimeout(30000, TimeUnit.MILLISECONDS)
                    .initialMaxData(10000000)
                    .initialMaxStreamDataBidirectionalLocal(1000000)
                    .build())
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .sync()
                .channel();
            quicChannel = QuicChannel.newBootstrap(datagramChannel)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                        if (evt instanceof QuicConnectionCloseEvent) {
                            closedByServer.complete((QuicConnectionCloseEvent) evt);
                        }
                        ctx.fireUserEventTriggered(evt);
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        // a connection the server closes is observed through closedByServer
                    }
                })
                // the server's control and QPACK streams
                .streamHandler(new Discard())
                .remoteAddress(new InetSocketAddress("127.0.0.1", mockServer.getHttp3Port()))
                .connect()
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        private int localPort() {
            return ((InetSocketAddress) datagramChannel.localAddress()).getPort();
        }

        private void sendOnANewUnidirectionalStream(byte[] content) throws Exception {
            QuicStreamChannel stream = quicChannel.createStream(QuicStreamType.UNIDIRECTIONAL, new Discard()).get(WAIT_SECONDS, TimeUnit.SECONDS);
            stream.writeAndFlush(Unpooled.wrappedBuffer(content)).get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        /**
         * @return what the server sent for {@code GET /}, as ISO-8859-1, once it ends the stream or the connection
         */
        private String getRoot() throws Exception {
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            CompletableFuture<Void> ended = new CompletableFuture<>();
            QuicStreamChannel stream = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    ByteBuf bytes = (ByteBuf) msg;
                    try {
                        synchronized (received) {
                            bytes.readBytes(received, bytes.readableBytes());
                        }
                    } catch (Exception e) {
                        ended.completeExceptionally(e);
                    } finally {
                        bytes.release();
                    }
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) {
                    ended.complete(null);
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    ended.complete(null);
                }
            }).get(WAIT_SECONDS, TimeUnit.SECONDS);
            // a field section of static-table references and literals only (RFC 9204), so no QPACK stream is needed
            byte[] authority = "localhost".getBytes(StandardCharsets.US_ASCII);
            byte[] fieldSection = concat(new byte[]{
                0x00, 0x00,               // required insert count and base: 0
                (byte) 0xD1,              // :method GET, static index 17
                (byte) 0xD7,              // :scheme https, static index 23
                (byte) 0xC1,              // :path /, static index 1
                0x50, (byte) authority.length // :authority (static name index 0) with a literal value
            }, authority);
            byte[] headersFrame = concat(new byte[]{0x01, (byte) fieldSection.length}, fieldSection);
            stream.writeAndFlush(Unpooled.wrappedBuffer(headersFrame)).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            CompletableFuture.anyOf(ended, closedByServer).get(WAIT_SECONDS, TimeUnit.SECONDS);
            synchronized (received) {
                return received.toString(StandardCharsets.ISO_8859_1.name());
            }
        }

        @Override
        public void close() {
            quicChannel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
            datagramChannel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
    }

    @ChannelHandler.Sharable
    private static final class Discard extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ReferenceCountUtil.release(msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // the server's end of its streams is what the class is about
        }
    }
}
