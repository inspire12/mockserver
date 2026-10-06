package org.mockserver.netty.integration.proxy.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ReferenceCountUtil;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpForward;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.NettyLogCapture;
import org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * What MockServer logs, and answers, when the connection it forwards a request on fails: while it is set up, while TLS
 * is being set up with the upstream, and on an HTTP/2 connection once it is. There is one entry about the connection in
 * MockServer's own log, at a level that fits the cause, and nothing through Netty's loggers, which report at
 * {@code WARN} with a stack trace whatever reaches the end of a pipeline, whatever stops the protocol being negotiated
 * and whatever stops a pipeline being built. When the request in flight fails with the cause, the request is logged
 * with it as an {@code ERROR}, its {@code 502} names it, and the connection's entry is {@code DEBUG}.
 */
public class ForwardConnectionErrorLoggingIntegrationTest {

    private static final String CHANNEL_INITIALIZER = "io.netty.channel.ChannelInitializer";
    private static final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private static final List<Channel> upstreamConnections = new CopyOnWriteArrayList<>();
    private static final AtomicInteger upstreamRequests = new AtomicInteger();
    private static final List<Long> goAwaysSentToTheUpstream = new CopyOnWriteArrayList<>();
    private static NettyLogCapture nettysLog;
    private static EventLoopGroup upstreamGroup;
    private static Channel http2Upstream;
    private static RawUpstream notTlsUpstream;
    private static RawUpstream resettingUpstream;
    private static RawUpstream refusingProxy;
    private static RawUpstream plainHttpUpstream;
    private static RawUpstream silentUpstream;
    private static SelfSignedCertificate http2UpstreamCertificate;
    private static Channel untrustedUpstream;
    private static MockServer validating;
    private static MockServerClient validatingClient;
    private static MockServer misconfigured;
    private static MockServerClient misconfiguredClient;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static MockServer throughProxy;
    private static MockServerClient throughProxyClient;

    private int nettysLogBeforeThisTest;

    @BeforeClass
    public static void startServers() throws Exception {
        nettysLog = new NettyLogCapture(NettyLogCapture.PIPELINE, NettyLogCapture.PROTOCOL_NEGOTIATION, CHANNEL_INITIALIZER);
        upstreamGroup = new NioEventLoopGroup(2);
        nettysLog.ignoreThreadsOf(upstreamGroup);
        http2UpstreamCertificate = new SelfSignedCertificate();
        http2Upstream = http2Upstream();
        // a subject the trusted certificate does not have, so no trusted certificate is taken for its issuer
        untrustedUpstream = tlsUpstream(new SelfSignedCertificate("untrusted.example"));
        // a plain HTTP server, answering a TLS ClientHello as a request it cannot parse
        plainHttpUpstream = new RawUpstream(socket -> {
            socket.getInputStream().read();
            socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\ncontent-length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            readUntilClosed(socket.getInputStream());
        });
        // accepts the connection and never answers the ClientHello
        silentUpstream = new RawUpstream(socket -> readUntilClosed(socket.getInputStream()));
        // answers a TLS ClientHello with bytes that are not TLS
        notTlsUpstream = new RawUpstream(socket -> {
            socket.getInputStream().read();
            socket.getOutputStream().write("x".repeat(400).getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            // until the client closes
            while (socket.getInputStream().read() != -1) {
                continue;
            }
        });
        // answers a TLS ClientHello with a TCP reset
        resettingUpstream = new RawUpstream(socket -> {
            socket.getInputStream().read();
            socket.setSoLinger(true, 0);
        });
        refusingProxy = new RawUpstream(socket -> {
            readRequestHead(socket.getInputStream());
            socket.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\ncontent-length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
        });

        // a clone: the event log clears the entry it is handed once it has copied it
        MockServerLogger.setGlobalLogEventListener(logEntry -> logged.add(logEntry.clone()));
        // the test JVM defaults to ERROR, which would drop the entries this class asserts on
        mockServer = new MockServer(forwarding(), 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        throughProxy = new MockServer(forwarding().forwardHttpsProxy(new InetSocketAddress("127.0.0.1", refusingProxy.port())), 0);
        throughProxyClient = new MockServerClient("127.0.0.1", throughProxy.getLocalPort());
        // trusts only the HTTP/2 upstream's certificate, which is not for 127.0.0.1
        validating = new MockServer(forwarding()
            .forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.CUSTOM)
            .forwardProxyTLSCustomTrustX509Certificates(http2UpstreamCertificate.certificate().getAbsolutePath())
            .socketConnectionTimeoutInMillis(1000L), 0);
        validatingClient = new MockServerClient("127.0.0.1", validating.getLocalPort());
        misconfigured = new MockServer(forwarding()
            .forwardProxyPrivateKey(notPem("forward-proxy-private-key").getAbsolutePath())
            .forwardProxyCertificateChain(notPem("forward-proxy-certificate-chain").getAbsolutePath()), 0);
        misconfiguredClient = new MockServerClient("127.0.0.1", misconfigured.getLocalPort());
        nettysLog.attach();
    }

    private static File notPem(String name) throws IOException {
        File file = File.createTempFile(name, ".pem");
        file.deleteOnExit();
        Files.write(file.toPath(), "-----BEGIN PRIVATE KEY-----\nnot base64 at all\n-----END PRIVATE KEY-----\n".getBytes(StandardCharsets.US_ASCII));
        return file;
    }

    private static Configuration forwarding() {
        return configuration().logLevel("DEBUG").forwardProxyHttp2Upgrade(true);
    }

    @AfterClass
    public static void stopServers() throws Exception {
        try {
            for (MockServerClient client : Arrays.asList(mockServerClient, throughProxyClient, validatingClient, misconfiguredClient)) {
                stopQuietly(client);
            }
            for (MockServer server : Arrays.asList(mockServer, throughProxy, validating, misconfigured)) {
                stopQuietly(server);
            }
            List<String> loggedByNettyWhileThisClassRan = nettysLog.all();
            nettysLog.assertThatItSeesWhatNettyLogs();
            assertThat("logged by Netty while this class ran", loggedByNettyWhileThisClassRan, empty());
        } finally {
            MockServerLogger.setGlobalLogEventListener(null);
            nettysLog.close();
            http2Upstream.close();
            untrustedUpstream.close();
            for (RawUpstream upstream : Arrays.asList(notTlsUpstream, resettingUpstream, refusingProxy, plainHttpUpstream, silentUpstream)) {
                upstream.close();
            }
            http2UpstreamCertificate.delete();
            upstreamGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Before
    public void forgetEarlierTests() {
        for (MockServerClient client : Arrays.asList(mockServerClient, throughProxyClient, validatingClient, misconfiguredClient)) {
            client.reset();
            client.when(request().withPath("/http2/.*")).forward(forward().withHost("127.0.0.1").withPort(port(http2Upstream)).withScheme(HttpForward.Scheme.HTTPS));
            client.when(request().withPath("/not-tls")).forward(forward().withHost("127.0.0.1").withPort(notTlsUpstream.port()).withScheme(HttpForward.Scheme.HTTPS));
            client.when(request().withPath("/resetting")).forward(forward().withHost("127.0.0.1").withPort(resettingUpstream.port()).withScheme(HttpForward.Scheme.HTTPS));
            client.when(request().withPath("/untrusted")).forward(forward().withHost("127.0.0.1").withPort(port(untrustedUpstream)).withScheme(HttpForward.Scheme.HTTPS));
            client.when(request().withPath("/plain-http")).forward(forward().withHost("127.0.0.1").withPort(plainHttpUpstream.port()).withScheme(HttpForward.Scheme.HTTPS));
            client.when(request().withPath("/silent")).forward(forward().withHost("127.0.0.1").withPort(silentUpstream.port()).withScheme(HttpForward.Scheme.HTTPS));
        }
        logged.clear();
        goAwaysSentToTheUpstream.clear();
        nettysLogBeforeThisTest = nettysLog.size();
    }

    @Test
    public void shouldLogAnHttp2ConnectionItsUpstreamResetOnceBelowAWarning() throws Exception {
        Response response = post(mockServer, "/http2/reset");

        assertThat(response.toString(), response.status, is(502));
        List<LogEntry> entries = awaitConnectionEntries(port(http2Upstream));
        assertThat(nettysLog.since(nettysLogBeforeThisTest), empty());
        assertThat(describe(entries), entries, hasSize(1));
        assertThat(entries.get(0).getLogLevel(), is(Level.DEBUG));
        assertThat(entries.get(0).getMessageFormat(), is("HTTP/2 connection to:{}closed by the upstream:{}"));
        assertThat(entries.get(0).getThrowable(), is(nullValue()));
    }

    @Test
    public void shouldLogAnIdleHttp2ConnectionItsUpstreamResetOnceBelowAWarningAndForwardOnANewOne() throws Exception {
        assertThat(post(mockServer, "/http2/served").status, is(200));
        int requestsBefore = upstreamRequests.get();

        for (Channel connection : upstreamConnections) {
            reset(connection);
        }

        List<LogEntry> entries = awaitConnectionEntries(port(http2Upstream));
        assertThat(nettysLog.since(nettysLogBeforeThisTest), empty());
        assertThat(describe(entries), entries, hasSize(1));
        assertThat(entries.get(0).getLogLevel(), is(Level.DEBUG));
        assertThat(entries.get(0).getMessageFormat(), is("HTTP/2 connection to:{}closed by the upstream:{}"));
        assertThat(post(mockServer, "/http2/served").status, is(200));
        assertThat(upstreamRequests.get() - requestsBefore, is(1));
    }

    @Test
    public void shouldFailAForwardWithItsHttp2ConnectionErrorAndStillSendGoAway() throws Exception {
        Response response = post(mockServer, "/http2/invalid-frame");

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, is("HTTP/2 error from the upstream: PROTOCOL_ERROR: Frame of type 0 must be associated with a stream."));
        assertForwardFailureLoggedOnceAsAnError(response.body);
        List<LogEntry> entries = awaitConnectionEntries(port(http2Upstream));
        assertThat(nettysLog.since(nettysLogBeforeThisTest), empty());
        assertThat(describe(entries), entries, hasSize(1));
        assertThat(entries.get(0).getLogLevel(), is(Level.DEBUG));
        assertThat(entries.get(0).getMessageFormat(), is("closing HTTP/2 connection to:{}for connection error:{}:{}"));
        assertThat(Arrays.asList(entries.get(0).getArguments()), hasItem(Http2Error.PROTOCOL_ERROR));
        assertThat(Arrays.asList(entries.get(0).getArguments()), hasItem("Frame of type 0 must be associated with a stream."));
        assertThat(entries.get(0).getThrowable(), is(nullValue()));
        awaitGoAway();
        assertThat(goAwaysSentToTheUpstream, contains(Http2Error.PROTOCOL_ERROR.code()));
    }

    @Test
    public void shouldCloseAnHttp2ConnectionSentBytesThatAreNotTlsAndFailTheForwardWithoutTheBytes() throws Exception {
        Response response = post(mockServer, "/http2/not-tls");

        assertThat(response.toString(), response.status, is(502));
        // after the handshake OpenSSL reads them as a record of an unknown version, the JDK as no record at all
        assertThat(response.body, startsWith("TLS with the upstream failed: "));
        assertThat(response.body, not(containsString("0000000000")));
        assertForwardFailureLoggedOnceAsAnError(response.body);
        List<LogEntry> entries = awaitConnectionEntries(port(http2Upstream));
        assertThat(nettysLog.since(nettysLogBeforeThisTest), empty());
        assertThat(describe(entries), entries, hasSize(1));
        assertThat(entries.get(0).getLogLevel(), is(Level.DEBUG));
        assertThat(entries.get(0).getMessageFormat(), containsString("for SSL or decoder fault "));
        assertThat(entries.get(0).getThrowable(), is(nullValue()));
        assertThat(describe(logged), describe(logged), not(containsString("0000000000")));
    }

    @Test
    public void shouldFailAForwardToAnUpstreamThatAnswersTheTlsHandshakeWithOtherBytesWithoutTheBytes() throws Exception {
        Response response = post(mockServer, "/not-tls");

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, startsWith("TLS with the upstream failed: NotSslRecordException: not an SSL/TLS record"));
        assertThat(response.body, not(containsString("7878787878")));
        assertForwardFailureLoggedOnceAsAnError(response.body);
        List<LogEntry> entries = awaitConnectionEntries(notTlsUpstream.port());
        assertThat(nettysLog.since(nettysLogBeforeThisTest), empty());
        assertThat(describe(entries), entries, hasSize(1));
        assertThat(entries.get(0).getLogLevel(), is(Level.DEBUG));
        assertThat(entries.get(0).getMessageFormat(), is("TLS could not be set up on connection to:{}"));
        // as text: with Netty's JDK TLS handler the throwable is a copy that leaves the upstream's bytes out
        assertThat(String.valueOf(entries.get(0).getThrowable()), startsWith("io.netty.handler.codec.DecoderException: io.netty.handler.ssl.NotSslRecordException: not an SSL/TLS record"));
        assertThat(describe(logged), describe(logged), not(containsString("7878787878")));
    }

    @Test
    public void shouldFailAForwardToAPlainHttpUpstreamWithTheHandshakesReason() throws Exception {
        Response response = post(mockServer, "/plain-http");

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, startsWith("TLS with the upstream failed: NotSslRecordException: not an SSL/TLS record"));
        assertThat("the upstream's answer, as a hex dump", response.body, not(containsString("485454502f")));
        assertForwardFailureLoggedOnceAsAnError(response.body);
        assertThat(nettysLog.since(nettysLogBeforeThisTest), empty());
    }

    @Test
    public void shouldFailAForwardToAnUntrustedUpstreamWithTheHandshakesReason() throws Exception {
        Response response = post(validating, "/untrusted");

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, startsWith("TLS with the upstream failed: SSLHandshakeException: "));
        // with the JDK's TLS provider in its message, with OpenSSL's in its cause
        assertThat(response.body, containsString("unable to find valid certification path to requested target"));
        assertForwardFailureLoggedOnceAsAnError(response.body);
        List<LogEntry> entries = awaitConnectionEntries(port(untrustedUpstream));
        assertThat(nettysLog.since(nettysLogBeforeThisTest), empty());
        assertThat(describe(entries), entries, hasSize(1));
        assertThat(entries.get(0).getLogLevel(), is(Level.DEBUG));
    }

    @Test
    public void shouldFailAForwardToAnUpstreamWithACertificateForAnotherHostWithTheHandshakesReason() throws Exception {
        Response response = post(validating, "/http2/served");

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, startsWith("TLS with the upstream failed: SSLHandshakeException: "));
        // which, as the JDK words it, depends on whether the TLS handler was given a name or an address
        assertThat(response.body, matchesPattern(".*No (name matching|subject alternative names).*"));
        assertForwardFailureLoggedOnceAsAnError(response.body);
        assertThat(nettysLog.since(nettysLogBeforeThisTest), empty());
    }

    @Test
    public void shouldFailAForwardWhoseHandshakeTimesOutWithTheTimeout() throws Exception {
        Response response = post(validating, "/silent");

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, is("TLS with the upstream failed: SslHandshakeTimeoutException: handshake timed out after 1000ms"));
        assertForwardFailureLoggedOnceAsAnError(response.body);
        assertThat(nettysLog.since(nettysLogBeforeThisTest), empty());
    }

    @Test
    public void shouldFailAForwardWhoseConnectionCannotBeSetUpFromTheConfigurationAsAConfigurationError() throws Exception {
        Response response = post(misconfigured, "/http2/served");

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, is("connection to the upstream could not be set up: RuntimeException: Exception creating SSL context for client"));
        assertForwardFailureLoggedOnceAsAnError(response.body);
        assertThat("logged by Netty's ChannelInitializer", nettysLog.since(nettysLogBeforeThisTest), empty());
    }

    @Test
    public void shouldLogAnUpstreamThatResetsDuringTheTlsHandshakeOnceBelowAWarning() throws Exception {
        Response response = post(mockServer, "/resetting");

        assertThat(response.toString(), response.status, is(502));
        List<LogEntry> entries = awaitConnectionEntries(resettingUpstream.port());
        assertThat(nettysLog.since(nettysLogBeforeThisTest), empty());
        assertThat(describe(entries), entries, hasSize(1));
        assertThat(entries.get(0).getLogLevel(), is(Level.DEBUG));
        assertThat(entries.get(0).getMessageFormat(), is("connection to:{}failed or was closed before TLS was set up:{}"));
        assertThat(entries.get(0).getThrowable(), is(nullValue()));
    }

    @Test
    public void shouldLogAProxyThatRefusesTheTunnelOnceBelowAWarning() throws Exception {
        Response response = post(throughProxy, "/http2/served");

        assertThat(response.toString(), response.status, is(502));
        List<LogEntry> entries = awaitConnectionEntries(port(http2Upstream));
        assertThat(nettysLog.since(nettysLogBeforeThisTest), empty());
        assertThat(describe(entries), entries, hasSize(1));
        // the failed forward is logged with the proxy's answer, as it was
        assertThat(entries.get(0).getLogLevel(), is(Level.DEBUG));
        assertThat(entries.get(0).getMessageFormat(), is("connection to:{}failed or was closed before TLS was set up:{}"));
        assertThat(String.valueOf(entries.get(0).getArguments()[1]), containsString("407"));
        assertThat(describe(logged), describe(logged), containsString("ERROR io.netty.handler.proxy.HttpProxyHandler$HttpProxyConnectException"));
    }

    /**
     * The forward's own entry: an {@code ERROR}, with the request, that names the reason its {@code 502} gives.
     */
    private static void assertForwardFailureLoggedOnceAsAnError(String reason) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (forwardFailures().isEmpty() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        List<LogEntry> failures = forwardFailures();
        assertThat(describe(logged), failures, hasSize(1));
        assertThat(failures.get(0).getHttpRequest(), is(notNullValue()));
        assertThat(Arrays.asList(failures.get(0).getArguments()), hasItem(reason));
        assertThat(failures.get(0).getThrowable(), is(notNullValue()));
    }

    private static List<LogEntry> forwardFailures() {
        return logged.stream()
            .filter(entry -> entry.getLogLevel() == Level.ERROR && "failed to forward request{}for action{}because:{}".equals(entry.getMessageFormat()))
            .collect(Collectors.toList());
    }

    private static void readUntilClosed(InputStream input) throws IOException {
        byte[] buffer = new byte[1024];
        while (input.read(buffer) != -1) {
            continue;
        }
    }

    /**
     * A TLS listener that presents {@code certificate} and does nothing else.
     */
    private static Channel tlsUpstream(SelfSignedCertificate certificate) throws Exception {
        SslContext sslContext = SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey()).build();
        return new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void exceptionCaught(ChannelHandlerContext connection, Throwable cause) {
                            connection.close();
                        }
                    });
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
    }

    private static void awaitGoAway() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (goAwaysSentToTheUpstream.isEmpty() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    /**
     * The entries MockServer logged about its connection to an upstream's port, as distinct from those about the
     * request it forwarded.
     */
    private static List<LogEntry> connectionEntries(int upstreamPort) {
        Pattern address = Pattern.compile(":" + upstreamPort + "(?!\\d)");
        return logged.stream()
            .filter(entry -> String.valueOf(entry.getMessageFormat()).contains("connection to"))
            .filter(entry -> address.matcher(entry.getMessageFormat() + Arrays.toString(entry.getArguments())).find())
            .collect(Collectors.toList());
    }

    private List<LogEntry> awaitConnectionEntries(int upstreamPort) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (connectionEntries(upstreamPort).isEmpty() && nettysLog.size() == nettysLogBeforeThisTest && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        // whatever else the same failure logs follows at once
        TimeUnit.MILLISECONDS.sleep(200);
        return connectionEntries(upstreamPort);
    }

    private static String describe(List<LogEntry> entries) {
        return entries.stream()
            .map(entry -> entry.getLogLevel() + " " + entry.getMessageFormat() + " " + Arrays.toString(entry.getArguments()) + " " + entry.getThrowable())
            .collect(Collectors.joining("\n", "\n", "\n"));
    }

    private static Response post(MockServer mockServer, String path) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort())) {
            socket.setSoTimeout(30_000);
            OutputStream output = socket.getOutputStream();
            // a POST is not sent again when its connection fails
            output.write(("POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + mockServer.getLocalPort() + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[16 * 1024];
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                received.write(buffer, 0, read);
            }
            return new Response(received.toString(StandardCharsets.ISO_8859_1.name()));
        }
    }

    private static int port(Channel listener) {
        return ((InetSocketAddress) listener.localAddress()).getPort();
    }

    private static void readRequestHead(InputStream input) throws IOException {
        int matched = 0;
        for (int read = input.read(); read != -1 && matched < 4; read = input.read()) {
            matched = read == "\r\n\r\n".charAt(matched) ? matched + 1 : (read == '\r' ? 1 : 0);
            if (matched == 4) {
                return;
            }
        }
    }

    /**
     * Closes a connection with a TCP reset: no GOAWAY and no TLS close_notify is sent first.
     */
    private static void reset(Channel connection) throws Exception {
        connection.config().setOption(ChannelOption.SO_LINGER, 0);
        // the transport's own close, which the HTTP/2 codec and the TLS handler do not see
        connection.eventLoop().submit(() -> connection.unsafe().close(connection.voidPromise())).get(10, TimeUnit.SECONDS);
    }

    private static final class Response {
        private final int status;
        private final String body;

        private Response(String raw) {
            int headEnd = raw.indexOf("\r\n\r\n");
            assertThat("a response: " + raw, headEnd, not(lessThan(0)));
            status = Integer.parseInt(raw.substring(9, 12));
            body = raw.substring(headEnd + 4);
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    /**
     * An HTTP/2 upstream over TLS that does to its connection what the path of the request asks for.
     */
    private static Channel http2Upstream() throws Exception {
        SslContext sslContext = SslContextBuilder.forServer(http2UpstreamCertificate.certificate(), http2UpstreamCertificate.privateKey())
            .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            .applicationProtocolConfig(new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                ApplicationProtocolNames.HTTP_2))
            .build();
        return new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    upstreamConnections.add(ch);
                    ch.closeFuture().addListener(closed -> upstreamConnections.remove(ch));
                    ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
                    ch.pipeline().addLast(new ApplicationProtocolNegotiationHandler("") {
                        @Override
                        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                            ctx.pipeline().addLast(Http2FrameCodecBuilder.forServer().build());
                            ctx.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                @Override
                                protected void initChannel(Channel stream) {
                                    stream.pipeline().addLast(new Http2UpstreamStream());
                                }
                            }));
                            ctx.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext connection, Object msg) {
                                    if (msg instanceof Http2GoAwayFrame) {
                                        goAwaysSentToTheUpstream.add(((Http2GoAwayFrame) msg).errorCode());
                                    }
                                    ReferenceCountUtil.release(msg);
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext connection, Throwable cause) {
                                    // MockServer's end of the connection is what the class is about
                                }
                            });
                        }
                    });
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
    }

    private static final class Http2UpstreamStream extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                if (msg instanceof Http2HeadersFrame) {
                    upstreamRequests.incrementAndGet();
                    String path = ((Http2HeadersFrame) msg).headers().path().toString();
                    Channel connection = ctx.channel().parent();
                    if (path.endsWith("/reset")) {
                        connection.config().setOption(ChannelOption.SO_LINGER, 0);
                        connection.unsafe().close(connection.voidPromise());
                    } else if (path.endsWith("/invalid-frame")) {
                        // a DATA frame on stream 0, which no stream can be blamed for
                        connection.pipeline().context(Http2FrameCodec.class).writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0}));
                    } else if (path.endsWith("/not-tls")) {
                        connection.pipeline().context(SslHandler.class).writeAndFlush(Unpooled.wrappedBuffer(new byte[2000]));
                    } else {
                        ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), false));
                        ctx.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.copiedBuffer("ok", StandardCharsets.UTF_8), true));
                    }
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    /**
     * Accepts connections on 127.0.0.1 and hands each to {@code serve}, then closes it.
     */
    private static final class RawUpstream {
        private final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));

        private RawUpstream(Serving serve) throws IOException {
            Thread acceptor = new Thread(() -> {
                while (!listener.isClosed()) {
                    try (Socket socket = listener.accept()) {
                        socket.setSoTimeout(30_000);
                        serve.serve(socket);
                    } catch (IOException closedOrFailed) {
                        // the next connection, or the end of the loop
                    }
                }
            }, "raw-upstream");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private int port() {
            return listener.getLocalPort();
        }

        private void close() throws IOException {
            listener.close();
        }
    }

    private interface Serving {
        void serve(Socket socket) throws IOException;
    }
}
