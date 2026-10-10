package org.mockserver.httpclient;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Frame;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.CharsetUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;
import org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager;
import org.slf4j.event.Level;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Over a real TLS socket, what the forward client does with its pooled HTTP/2 connection to an upstream when an
 * exception it does not recognise reaches the end of that connection's pipeline: one {@code ERROR} entry, a forward
 * in flight failed with the cause, {@code GOAWAY(INTERNAL_ERROR)} once, the close without waiting for the stream in
 * flight, and the next forward on a new connection. An {@code EmbeddedChannel} cannot show the deferred close: it
 * runs pending tasks after every write.
 */
public class Http2ForwardConnectionUnexpectedExceptionTest {

    private static final long DEADLINE_SECONDS = 20;

    private final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private final MockServerLogger mockServerLogger = new MockServerLogger(Http2ForwardConnectionUnexpectedExceptionTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return true;
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry);
        }
    };
    private EventLoopGroup clientEventLoopGroup;
    private Upstream upstream;
    private NettyHttpClient client;

    @Before
    public void start() throws Exception {
        clientEventLoopGroup = new NioEventLoopGroup(2);
        upstream = new Upstream();
        Configuration configuration = configuration()
            .forwardConnectionPoolEnabled(true)
            .forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.ANY)
            .forwardProxyTLSHostnameVerificationEnabled(false)
            // well beyond the deadlines, so a stream's read timeout does not close the connection for the test
            .maxSocketTimeoutInMillis(TimeUnit.SECONDS.toMillis(120));
        client = new NettyHttpClient(configuration, mockServerLogger, clientEventLoopGroup, null, true);
    }

    @After
    public void stop() {
        upstream.stop();
        clientEventLoopGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }

    @Test
    public void shouldCloseAnIdlePooledConnectionWithGoAwayAndOpenANewOneForTheNextForward() throws Exception {
        assertThat(forward("/").get(DEADLINE_SECONDS, TimeUnit.SECONDS).getStatusCode(), is(200));
        Channel connection = onlyPooledConnection();
        IllegalStateException unexpected = new IllegalStateException("unexpected");

        connection.eventLoop().submit(() -> connection.pipeline().fireExceptionCaught(unexpected)).get(DEADLINE_SECONDS, TimeUnit.SECONDS);

        assertThat("handed out again", pooledConnections(), empty());
        assertThat("closed", connection.closeFuture().await(DEADLINE_SECONDS, TimeUnit.SECONDS), is(true));
        assertThat("the upstream's side closed", upstream.closed.await(DEADLINE_SECONDS, TimeUnit.SECONDS), is(true));
        assertThat(upstream.goAways, contains("INTERNAL_ERROR"));
        assertLoggedOnceAsAnError(unexpected);

        assertThat(forward("/").get(DEADLINE_SECONDS, TimeUnit.SECONDS).getStatusCode(), is(200));
        assertThat(upstream.accepted.get(), is(2));
    }

    @Test
    public void shouldFailAForwardInFlightWithTheCauseAndCloseWithoutWaitingForItsStream() throws Exception {
        assertThat(forward("/").get(DEADLINE_SECONDS, TimeUnit.SECONDS).getStatusCode(), is(200));
        Channel connection = onlyPooledConnection();
        // not idempotent, so the failure is not sent again on a new connection
        CompletableFuture<HttpResponse> held = forward(request("/hold").withMethod("POST").withBody("body"));
        assertThat("the upstream has the request", upstream.holding.await(DEADLINE_SECONDS, TimeUnit.SECONDS), is(true));
        IllegalStateException unexpected = new IllegalStateException("unexpected");

        // where the codec and the handlers after it raise one: past the handler that fails a forward with any exception
        connection.eventLoop().execute(() -> connection.pipeline().context(Http2FrameCodec.class).fireExceptionCaught(unexpected));

        Throwable failure = assertThrows(ExecutionException.class, () -> held.get(DEADLINE_SECONDS, TimeUnit.SECONDS)).getCause();
        assertThat(failure, instanceOf(SocketConnectionException.class));
        InetSocketAddress remote = connection.attr(NettyHttpClient.REMOTE_SOCKET).get();
        assertThat(failure.getMessage(), is("HTTP/2 connection to " + remote.getHostString() + ":" + upstream.port() + " failed: unexpected exception: IllegalStateException: unexpected"));
        assertThat(failure.getCause(), is(sameInstance(unexpected)));
        // a graceful close would wait 30 s for the stream the upstream holds open
        assertThat("closed", connection.closeFuture().await(DEADLINE_SECONDS, TimeUnit.SECONDS), is(true));
        assertThat("the upstream's side closed", upstream.closed.await(DEADLINE_SECONDS, TimeUnit.SECONDS), is(true));
        assertThat(upstream.goAways, contains("INTERNAL_ERROR"));
        assertLoggedOnceAsAnError(unexpected);
    }

    @Test
    public void shouldCloseAConnectionWhoseForwardInFlightTheConnectionErrorHandlerFailedWithTheCause() throws Exception {
        assertThat(forward("/").get(DEADLINE_SECONDS, TimeUnit.SECONDS).getStatusCode(), is(200));
        Channel connection = onlyPooledConnection();
        CompletableFuture<HttpResponse> held = forward(request("/hold").withMethod("POST").withBody("body"));
        assertThat("the upstream has the request", upstream.holding.await(DEADLINE_SECONDS, TimeUnit.SECONDS), is(true));
        IllegalStateException unexpected = new IllegalStateException("unexpected");

        connection.eventLoop().execute(() -> connection.pipeline().fireExceptionCaught(unexpected));

        Throwable failure = assertThrows(ExecutionException.class, () -> held.get(DEADLINE_SECONDS, TimeUnit.SECONDS)).getCause();
        assertThat(failure, is(sameInstance(unexpected)));
        assertThat("closed", connection.closeFuture().await(DEADLINE_SECONDS, TimeUnit.SECONDS), is(true));
        assertThat("the upstream's side closed", upstream.closed.await(DEADLINE_SECONDS, TimeUnit.SECONDS), is(true));
        assertThat(upstream.goAways, contains("INTERNAL_ERROR"));
        assertLoggedOnceAsAnError(unexpected);
    }

    @Test
    public void shouldLeaveTheGoAwayToTheCodecForAnExceptionItCaught() throws Exception {
        assertThat(forward("/").get(DEADLINE_SECONDS, TimeUnit.SECONDS).getStatusCode(), is(200));
        Channel connection = onlyPooledConnection();
        IllegalStateException unexpected = new IllegalStateException("unexpected");

        // as the codec handles an exception it caught while decoding: down the pipeline, then its own GOAWAY
        connection.eventLoop().submit(() -> {
            Http2FrameCodec codec = connection.pipeline().get(Http2FrameCodec.class);
            codec.onError(connection.pipeline().context(codec), false, unexpected);
        }).get(DEADLINE_SECONDS, TimeUnit.SECONDS);

        assertThat("closed", connection.closeFuture().await(DEADLINE_SECONDS, TimeUnit.SECONDS), is(true));
        assertThat("the upstream's side closed", upstream.closed.await(DEADLINE_SECONDS, TimeUnit.SECONDS), is(true));
        assertThat(upstream.goAways, contains("INTERNAL_ERROR"));
        assertThat("the codec's, with the message as debug data", upstream.goAwayDebugData, contains("unexpected"));
        assertLoggedOnceAsAnError(unexpected);
    }

    private CompletableFuture<HttpResponse> forward(String path) {
        return forward(request(path));
    }

    private CompletableFuture<HttpResponse> forward(HttpRequest request) {
        return client.sendRequest(request.withSecure(true).withProtocol(Protocol.HTTP_2).withHeader("Host", "127.0.0.1:" + upstream.port()));
    }

    private void assertLoggedOnceAsAnError(Throwable cause) {
        List<LogEntry> errors = logged.stream().filter(entry -> entry.getLogLevel() == Level.ERROR).collect(Collectors.toList());
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0).getMessageFormat(), is("closing HTTP/2 connection to:{}for unexpected exception"));
        assertThat(Arrays.asList(errors.get(0).getArguments()), contains(new InetSocketAddress("127.0.0.1", upstream.port())));
        assertThat(errors.get(0).getThrowable(), is(sameInstance(cause)));
    }

    private Channel onlyPooledConnection() throws Exception {
        List<Channel> pooled = pooledConnections();
        assertThat(pooled, hasSize(1));
        return pooled.get(0);
    }

    /**
     * The client's idle connections, reached by reflection: the pool exposes none of them without handing it out.
     */
    @SuppressWarnings("unchecked")
    private List<Channel> pooledConnections() throws Exception {
        Field poolField = NettyHttpClient.class.getDeclaredField("connectionPool");
        poolField.setAccessible(true);
        HttpForwardConnectionPool pool = (HttpForwardConnectionPool) poolField.get(client);
        Field idleField = HttpForwardConnectionPool.class.getDeclaredField("idleChannels");
        idleField.setAccessible(true);
        List<Channel> pooled = new ArrayList<>();
        for (Deque<Channel> deque : ((Map<String, Deque<Channel>>) idleField.get(pool)).values()) {
            synchronized (deque) {
                pooled.addAll(deque);
            }
        }
        return pooled;
    }

    /**
     * A TLS HTTP/2 upstream that answers {@code 200} on every stream except {@code /hold}, which it holds open, and
     * records each {@code GOAWAY} it receives and the close of its side of the connection.
     */
    private static final class Upstream {

        private final EventLoopGroup group = new NioEventLoopGroup(2);
        private final AtomicInteger accepted = new AtomicInteger();
        private final List<String> goAways = new CopyOnWriteArrayList<>();
        private final List<String> goAwayDebugData = new CopyOnWriteArrayList<>();
        private final CountDownLatch holding = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final Channel serverChannel;

        Upstream() throws Exception {
            SelfSignedCertificate certificate = new SelfSignedCertificate();
            SslContext sslContext = SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey())
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                    ApplicationProtocolConfig.Protocol.ALPN,
                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                    ApplicationProtocolNames.HTTP_2))
                .build();
            serverChannel = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (accepted.incrementAndGet() == 1) {
                            ch.closeFuture().addListener(closing -> closed.countDown());
                        }
                        ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
                        ch.pipeline().addLast(new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_2) {
                            @Override
                            protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                                ctx.pipeline().addLast(Http2FrameCodecBuilder.forServer().build());
                                ctx.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                    @Override
                                    protected void initChannel(Channel stream) {
                                        stream.pipeline().addLast(new StreamHandler());
                                    }
                                }));
                                ctx.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                        if (msg instanceof Http2GoAwayFrame) {
                                            Http2GoAwayFrame goAway = (Http2GoAwayFrame) msg;
                                            goAways.add(Http2Error.valueOf(goAway.errorCode()).name());
                                            goAwayDebugData.add(goAway.content().toString(CharsetUtil.UTF_8));
                                        }
                                        io.netty.util.ReferenceCountUtil.release(msg);
                                    }
                                });
                            }
                        });
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 0)).syncUninterruptibly().channel();
        }

        private final class StreamHandler extends SimpleChannelInboundHandler<Http2Frame> {

            private boolean hold;

            @Override
            protected void channelRead0(ChannelHandlerContext ctx, Http2Frame frame) {
                if (frame instanceof Http2HeadersFrame && "/hold".contentEquals(((Http2HeadersFrame) frame).headers().path())) {
                    hold = true;
                }
                boolean endStream = (frame instanceof Http2HeadersFrame && ((Http2HeadersFrame) frame).isEndStream())
                    || (frame instanceof Http2DataFrame && ((Http2DataFrame) frame).isEndStream());
                if (endStream && hold) {
                    holding.countDown();
                } else if (endStream) {
                    ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), false));
                    ctx.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.copiedBuffer("OK", CharsetUtil.UTF_8), true));
                }
            }
        }

        int port() {
            return ((InetSocketAddress) serverChannel.localAddress()).getPort();
        }

        void stop() {
            serverChannel.close().syncUninterruptibly();
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }
}
