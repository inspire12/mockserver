package org.mockserver.httpclient;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.HttpProxyHandler.HttpProxyConnectException;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.proxy.ProxyConnectionEvent;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.resolver.NoopAddressResolverGroup;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@link HttpConnectProxyHandler} against Netty's {@link HttpProxyHandler}, which it replaces so that the proxy's
 * response headers can be read up to {@code maxHeaderSize}: the same {@code CONNECT} request and the same failures,
 * and a response with large headers opens the tunnel. Each handler talks to a scripted proxy over an in-JVM channel.
 */
public class HttpConnectProxyHandlerTest {

    private static final int LIMIT = 32 * 1024;
    private static final String TUNNELLED = "bytes from the destination";

    private static EventLoopGroup group;

    private final List<String> logged = new CopyOnWriteArrayList<>();
    private final MockServerLogger mockServerLogger = new MockServerLogger(HttpConnectProxyHandlerTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return true;
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry.getLogLevel() + " " + logEntry.getArguments()[1]);
        }
    };

    @BeforeClass
    public static void startEventLoop() {
        group = new DefaultEventLoopGroup(2);
    }

    @AfterClass
    public static void stopEventLoop() {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    @Test
    public void shouldSendTheConnectRequestNettysHandlerSends() throws Exception {
        List<InetSocketAddress> destinations = Arrays.asList(
            InetSocketAddress.createUnresolved("upstream.example", 443),
            InetSocketAddress.createUnresolved("upstream.example", 8443),
            InetSocketAddress.createUnresolved("::1", 443),
            new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 8080)
        );
        for (InetSocketAddress destination : destinations) {
            Exchange nettys = connect(destination, "HTTP/1.1 200 Connection established\r\n\r\n", HttpProxyHandler::new);
            Exchange mockServers = connect(destination, "HTTP/1.1 200 Connection established\r\n\r\n", proxy -> new HttpConnectProxyHandler(proxy, null, null, mockServerLogger, LIMIT));

            assertThat(mockServers.connectRequest, is(nettys.connectRequest));
            assertThat(mockServers.connectRequest, containsString("CONNECT "));
            assertThat(mockServers.authScheme, is(nettys.authScheme));
            assertThat(mockServers.failure, nullValue());

            Exchange nettysWithCredentials = connect(destination, "HTTP/1.1 200 Connection established\r\n\r\n", proxy -> new HttpProxyHandler(proxy, "user", "pässword"));
            Exchange mockServersWithCredentials = connect(destination, "HTTP/1.1 200 Connection established\r\n\r\n", proxy -> new HttpConnectProxyHandler(proxy, "user", "pässword", mockServerLogger, LIMIT));

            assertThat(mockServersWithCredentials.connectRequest, is(nettysWithCredentials.connectRequest));
            assertThat(mockServersWithCredentials.connectRequest, containsString("proxy-authorization: Basic "));
            assertThat(mockServersWithCredentials.authScheme, is(nettysWithCredentials.authScheme));
        }
    }

    @Test
    public void shouldFailAsNettysHandlerDoesWhenTheProxyRefuses() throws Exception {
        InetSocketAddress destination = InetSocketAddress.createUnresolved("upstream.example", 443);
        String refusal = "HTTP/1.1 407 Proxy Authentication Required\r\nproxy-authenticate: Basic realm=\"proxy\"\r\ncontent-length: 0\r\n\r\n";

        Exchange nettys = connect(destination, refusal, HttpProxyHandler::new);
        Exchange mockServers = connect(destination, refusal, proxy -> new HttpConnectProxyHandler(proxy, null, null, mockServerLogger, LIMIT));

        assertThat(mockServers.failure, instanceOf(HttpProxyConnectException.class));
        assertThat(nettys.failure, instanceOf(HttpProxyConnectException.class));
        assertThat(withoutProxyAddress(mockServers), is(withoutProxyAddress(nettys)));
        assertThat(mockServers.failure.getMessage(), containsString("status: 407 Proxy Authentication Required"));
        assertThat(((HttpProxyConnectException) mockServers.failure).headers().get("proxy-authenticate"), is("Basic realm=\"proxy\""));
        assertThat(mockServers.channelFailures, contains(mockServers.failure));
        assertThat(logged, empty());
    }

    @Test
    public void shouldRefuseEveryStatusButTwoHundredAsNettysHandlerDoes() throws Exception {
        InetSocketAddress destination = InetSocketAddress.createUnresolved("upstream.example", 443);
        for (String status : new String[]{"201 Created", "204 No Content", "299 Tunnel", "302 Found"}) {
            String response = "HTTP/1.1 " + status + "\r\ncontent-length: 0\r\n\r\n";

            Exchange nettys = connect(destination, response, HttpProxyHandler::new);
            Exchange mockServers = connect(destination, response, proxy -> new HttpConnectProxyHandler(proxy, null, null, mockServerLogger, LIMIT));

            assertThat(status, mockServers.failure, instanceOf(HttpProxyConnectException.class));
            assertThat(status, nettys.failure, instanceOf(HttpProxyConnectException.class));
            assertThat(status, withoutProxyAddress(mockServers), is(withoutProxyAddress(nettys)));
            assertThat(status, mockServers.failure.getMessage(), containsString("status: " + status));
            assertThat(status, mockServers.read, is(nettys.read));
        }
        assertThat(logged, empty());
    }

    /**
     * The codec ends every response it starts, so it never hands these sequences on; each handler is given them
     * directly.
     */
    @Test
    public void shouldReportResponsesOutOfSequenceAsNettysHandlerDoes() throws Exception {
        LocalAddress proxy = new LocalAddress("connect-proxy-" + UUID.randomUUID());
        Object[] twoResponses = {new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK), new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)};
        Object[] contentWithoutAResponse = {LastHttpContent.EMPTY_LAST_CONTENT};

        Throwable tooMany = handleResponses(new HttpConnectProxyHandler(proxy, null, null, mockServerLogger, LIMIT), twoResponses);
        Throwable missing = handleResponses(new HttpConnectProxyHandler(proxy, null, null, mockServerLogger, LIMIT), contentWithoutAResponse);

        assertThat(tooMany, instanceOf(HttpProxyConnectException.class));
        assertThat(tooMany.getMessage(), containsString("too many responses"));
        assertThat(tooMany.getMessage(), is(handleResponses(new HttpProxyHandler(proxy), twoResponses).getMessage()));
        assertThat(missing, instanceOf(HttpProxyConnectException.class));
        assertThat(missing.getMessage(), containsString("missing response"));
        assertThat(missing.getMessage(), is(handleResponses(new HttpProxyHandler(proxy), contentWithoutAResponse).getMessage()));
    }

    /**
     * @return what the handler throws for the last of {@code responses}, or {@code null}
     */
    private static Throwable handleResponses(ProxyHandler handler, Object... responses) throws Exception {
        Method handleResponse = ProxyHandler.class.getDeclaredMethod("handleResponse", ChannelHandlerContext.class, Object.class);
        handleResponse.setAccessible(true);
        for (int i = 0; i < responses.length; i++) {
            try {
                handleResponse.invoke(handler, null, responses[i]);
            } catch (InvocationTargetException thrown) {
                assertThat("only the last is refused", i, is(responses.length - 1));
                return thrown.getCause();
            }
        }
        return null;
    }

    @Test
    public void shouldOpenTheTunnelWhenTheProxysResponseHeadersAreLargeAndLeaveNoCodecBehind() throws Exception {
        InetSocketAddress destination = InetSocketAddress.createUnresolved("upstream.example", 443);
        // "x-proxy: " is 9 bytes
        String response = "HTTP/1.1 200 Connection established\r\nx-proxy: " + "a".repeat(LIMIT - 9) + "\r\n\r\n";

        Exchange exchange = connect(destination, response, proxy -> new HttpConnectProxyHandler(proxy, null, null, mockServerLogger, LIMIT));

        assertThat(exchange.failure, nullValue());
        assertThat("what follows the response is the tunnel's", exchange.read, is(TUNNELLED));
        assertThat("what the client writes is the tunnel's", exchange.tunnelledToProxy, is("bytes to the destination"));
        assertThat(exchange.codecLeftInPipeline, is(false));
        assertThat(exchange.channelFailures, empty());
        assertThat(logged, empty());
    }

    @Test
    public void shouldFailTheConnectionWhenTheProxysResponseHeadersAreOverTheLimit() throws Exception {
        InetSocketAddress destination = InetSocketAddress.createUnresolved("upstream.example", 443);
        String response = "HTTP/1.1 200 Connection established\r\nx-proxy: " + "a".repeat(LIMIT - 9 + 1) + "\r\n\r\n";
        long started = System.nanoTime();

        Exchange exchange = connect(destination, response, proxy -> new HttpConnectProxyHandler(proxy, null, null, mockServerLogger, LIMIT));

        assertThat(exchange.failure, instanceOf(ProxyConnectException.class));
        assertThat(HeaderLimitExceededException.in(exchange.failure).getMessage(), is("the upstream proxy's CONNECT response headers are larger than maxHeaderSize (" + LIMIT + " bytes)"));
        assertThat(exchange.channelFailures, contains(exchange.failure));
        assertThat(exchange.read, is(""));
        assertThat(logged, contains("WARN the upstream proxy's CONNECT response headers are larger than maxHeaderSize (" + LIMIT + " bytes)"));
        assertThat("failed at once, not by the connect timeout", TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started), lessThan(5L));
    }

    private static String withoutProxyAddress(Exchange exchange) {
        return exchange.failure.getMessage().replace(exchange.proxyAddress.toString(), "proxy");
    }

    /**
     * Connects to {@code destination} through a proxy that reads one {@code CONNECT} request and answers
     * {@code proxyResponse} followed by {@link #TUNNELLED}; if the tunnel opens, the client writes to it.
     */
    private Exchange connect(InetSocketAddress destination, String proxyResponse, Function<LocalAddress, ProxyHandler> proxyHandler) throws Exception {
        Exchange exchange = new Exchange();
        exchange.proxyAddress = new LocalAddress("connect-proxy-" + UUID.randomUUID());
        CompletableFuture<String> connectRequest = new CompletableFuture<>();
        CompletableFuture<String> tunnelledToProxy = new CompletableFuture<>();
        Channel proxy = new ServerBootstrap()
            .group(group)
            .channel(LocalServerChannel.class)
            .childHandler(new ChannelInboundHandlerAdapter() {
                private final StringBuilder received = new StringBuilder();

                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    received.append(((ByteBuf) msg).toString(StandardCharsets.ISO_8859_1));
                    ((ByteBuf) msg).release();
                    int endOfRequest = received.indexOf("\r\n\r\n");
                    if (endOfRequest >= 0 && !connectRequest.isDone()) {
                        connectRequest.complete(received.substring(0, endOfRequest + 4));
                        received.delete(0, endOfRequest + 4);
                        ctx.writeAndFlush(Unpooled.copiedBuffer(proxyResponse + TUNNELLED, StandardCharsets.ISO_8859_1));
                    }
                    if (connectRequest.isDone() && received.length() > 0) {
                        tunnelledToProxy.complete(received.toString());
                    }
                }
            })
            .bind(exchange.proxyAddress).sync().channel();
        ProxyHandler handler = proxyHandler.apply(exchange.proxyAddress);
        StringBuffer read = new StringBuffer();
        List<Throwable> channelFailures = new CopyOnWriteArrayList<>();
        Channel client = new Bootstrap()
            .group(group)
            .channel(LocalChannel.class)
            .resolver(NoopAddressResolverGroup.INSTANCE)
            .handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    ch.pipeline().addLast(handler);
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            read.append(((ByteBuf) msg).toString(StandardCharsets.ISO_8859_1));
                            ((ByteBuf) msg).release();
                        }

                        @Override
                        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                            if (evt instanceof ProxyConnectionEvent) {
                                exchange.authScheme = ((ProxyConnectionEvent) evt).authScheme();
                            }
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            channelFailures.add(cause);
                        }
                    });
                }
            })
            .connect(destination).sync().channel();
        try {
            exchange.connectRequest = connectRequest.get(10, TimeUnit.SECONDS);
            try {
                handler.connectFuture().get(10, TimeUnit.SECONDS);
                client.writeAndFlush(Unpooled.copiedBuffer("bytes to the destination", StandardCharsets.ISO_8859_1)).sync();
                exchange.tunnelledToProxy = tunnelledToProxy.get(10, TimeUnit.SECONDS);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (read.length() < TUNNELLED.length() && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
            } catch (ExecutionException failed) {
                exchange.failure = failed.getCause();
                client.closeFuture().get(10, TimeUnit.SECONDS);
            }
            exchange.codecLeftInPipeline = client.eventLoop().submit(() -> client.pipeline().get(HttpClientCodec.class) != null).get(10, TimeUnit.SECONDS);
            exchange.read = read.toString();
            exchange.channelFailures = new ArrayList<>(channelFailures);
            return exchange;
        } finally {
            client.close().sync();
            proxy.close().sync();
        }
    }

    private static final class Exchange {
        private LocalAddress proxyAddress;
        private String connectRequest;
        private String authScheme;
        private Throwable failure;
        private String read;
        private String tunnelledToProxy;
        private boolean codecLeftInPipeline;
        private List<Throwable> channelFailures;
    }
}
