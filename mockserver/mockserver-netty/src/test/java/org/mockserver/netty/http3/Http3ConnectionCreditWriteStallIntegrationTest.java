package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.nio.NioEventLoopGroup;
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
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.metrics.Metrics;
import org.mockserver.netty.MockServer;
import org.mockserver.testing.socket.Ipv4DatagramChannelFactory;

import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * {@code responseWriteStallTimeoutMillis} over HTTP/3 when streams the client does not read hold the whole of the
 * connection's flow-control credit ({@code MAX_DATA}): those streams are reset, and a stream the client is reading,
 * which has been sent nothing because it was waiting for that credit, is delivered whole. The client grants the
 * connection 256 KiB and each stream 1 MiB; the unread responses are 512 KiB, more than the connection's credit, and
 * the read response is 96 KiB, under half of it.
 */
public class Http3ConnectionCreditWriteStallIntegrationTest {

    private static final long STALL_MILLIS = 3000;
    private static final int CONNECTION_CREDIT_BYTES = 256 * 1024;
    private static final int STREAM_CREDIT_BYTES = 1024 * 1024;

    private static byte[] heldBody;
    private static byte[] readBody;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static int http3Port;

    private NioEventLoopGroup clientGroup;
    private Channel clientChannel;
    private QuicChannel quicChannel;

    @BeforeClass
    public static void startServer() {
        heldBody = new byte[512 * 1024];
        new Random(105).nextBytes(heldBody);
        readBody = new byte[96 * 1024];
        new Random(106).nextBytes(readBody);
        mockServer = startWithHttp3(configuration()
            .logLevel("WARN")
            .metricsEnabled(true)
            .http3MaxIdleTimeout(60_000L)
            .responseWriteStallTimeoutMillis(STALL_MILLIS));
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        http3Port = mockServer.getHttp3Port();
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Before
    public void connect() throws Exception {
        mockServerClient.reset();
        mockServerClient.when(request().withPath("/held")).respond(response().withBody(binary(heldBody)));
        mockServerClient.when(request().withPath("/read")).respond(response().withBody(binary(readBody)));

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
                .initialMaxData(CONNECTION_CREDIT_BYTES)
                .initialMaxStreamDataBidirectionalLocal(STREAM_CREDIT_BYTES)
                .initialMaxStreamsBidirectional(100)
                .build())
            .bind(0).sync().channel();
        quicChannel = QuicChannel.newBootstrap(clientChannel)
            .handler(new Http3ClientConnectionHandler())
            .remoteAddress(new InetSocketAddress("127.0.0.1", http3Port))
            .connect()
            .get(15, TimeUnit.SECONDS);
    }

    @After
    public void stopClient() throws InterruptedException {
        if (quicChannel != null) {
            // a connection left to idle out would have its streams reset, and counted, during the next test
            quicChannel.close().await(5, TimeUnit.SECONDS);
        }
        if (clientChannel != null) {
            clientChannel.close().await(5, TimeUnit.SECONDS);
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void shouldResetTheUnreadStreamsHoldingTheConnectionCreditAndDeliverAStreamThatWaitedForItWhole() throws Exception {
        long countedBefore = stallsCounted();
        long receivedBefore = receivedBytes();
        // takes the whole connection credit; its reset returns the credit, which the second stream then takes and holds
        Http3Stream firstUnread = open("/held?stream=first-unread", false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (receivedBytes() - receivedBefore < CONNECTION_CREDIT_BYTES && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat("the first unread stream was sent the connection's credit", receivedBytes() - receivedBefore, greaterThanOrEqualTo((long) CONNECTION_CREDIT_BYTES));
        Http3Stream secondUnread = open("/held?stream=second-unread", false);
        TimeUnit.MILLISECONDS.sleep(200);
        // sent nothing until both are reset, and has by then waited longer than the second has held the credit
        Http3Stream read = open("/read", true);

        assertThat("the read stream ended", read.ended.await(5 * STALL_MILLIS, TimeUnit.MILLISECONDS), is(true));
        assertThat("the read stream was delivered whole", read.dataBytes.get(), is((long) readBody.length));
        assertThat("the read stream ended cleanly", read.endedCleanly.get(), is(true));

        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3 * STALL_MILLIS);
        while (stallsCounted() - countedBefore < 2 && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        // at least: the count is the JVM's, and the read stream ending cleanly shows it was not among them
        assertThat("both unread streams were reset", stallsCounted() - countedBefore, greaterThanOrEqualTo(2L));
        firstUnread.readAll();
        secondUnread.readAll();
        assertThat("the first unread stream ended", firstUnread.ended.await(10, TimeUnit.SECONDS), is(true));
        assertThat("the second unread stream ended", secondUnread.ended.await(10, TimeUnit.SECONDS), is(true));
        assertThat("the first unread stream was cut short", firstUnread.dataBytes.get(), lessThan((long) heldBody.length));
        assertThat("the second unread stream was cut short", secondUnread.dataBytes.get(), lessThan((long) heldBody.length));
    }

    /**
     * The bytes the client's connection has received, read or not: QUIC packets, so a little more than the stream data.
     */
    private long receivedBytes() throws Exception {
        return quicChannel.collectStats().get(5, TimeUnit.SECONDS).recvBytes();
    }

    private static long stallsCounted() {
        return Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP3_STREAM);
    }

    private Http3Stream open(String uri, boolean read) throws Exception {
        Http3Stream stream = new Http3Stream();
        stream.channel = Http3.newRequestStream(quicChannel, stream.recorder()).sync().getNow();
        // an unread stream is granted no more credit, and returns none to the connection, until readAll()
        stream.channel.config().setAutoRead(read);
        DefaultHttp3HeadersFrame headers = new DefaultHttp3HeadersFrame();
        headers.headers().method("GET").path(uri).scheme("https").authority("127.0.0.1:" + http3Port);
        stream.channel.writeAndFlush(headers).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT).sync();
        return stream;
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
}
