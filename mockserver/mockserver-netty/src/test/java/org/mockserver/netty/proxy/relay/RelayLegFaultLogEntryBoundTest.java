package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

/**
 * The tunnel relay's entries for a failed TLS handshake with the proxy client and for a write that failed hold the
 * count of the bytes that were not a TLS record, not a hex dump of them. Netty fails the handshake, and every write
 * waiting on it, with the exception that carries the dump.
 */
public class RelayLegFaultLogEntryBoundTest {

    private static final int BYTES_READ = 20_000;
    private static final String COUNTED = "not an SSL/TLS record: " + BYTES_READ + " bytes";

    private final List<LogEntry> logged = new ArrayList<>();
    private final MockServerLogger mockServerLogger = new MockServerLogger(RelayLegFaultLogEntryBoundTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return true;
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry);
        }
    };

    private static Throwable notATlsRecord() {
        return new DecoderException(new NotSslRecordException("not an SSL/TLS record: " + "41".repeat(BYTES_READ)));
    }

    private void assertOneBoundedEntry() {
        assertThat(logged, hasSize(1));
        Throwable attached = logged.get(0).getThrowable();
        StringBuilder messages = new StringBuilder();
        for (Throwable throwable = attached; throwable != null; throwable = throwable.getCause()) {
            messages.append(throwable).append('\n');
        }
        assertThat(messages.toString(), not(containsString("4141")));
        assertThat(messages.toString(), containsString(COUNTED));
        assertThat(messages.length(), lessThan(1_000));
    }

    @Test
    public void shouldLogAFailedClientHandshakeWithTheCountOfTheBytesThatWereNotATlsRecord() throws Exception {
        Throwable handshakeFailure = failedServerHandshake();
        assertThat("Netty fails the handshake with the dump", handshakeFailure, instanceOf(NotSslRecordException.class));
        assertThat(handshakeFailure.getMessage(), containsString("4141"));

        RelayConnectHandler.logFailedClientHandshake(mockServerLogger, handshakeFailure);

        assertOneBoundedEntry();
        assertThat(logged.get(0).getLogLevel(), is(Level.TRACE));
        assertThat(logged.get(0).getThrowable().getStackTrace(), is(handshakeFailure.getStackTrace()));
    }

    @Test
    public void shouldLogARequestThatCouldNotBeWrittenToTheLoopbackWithoutTheDump() {
        EmbeddedChannel loopback = failingWrites(notATlsRecord());
        EmbeddedChannel proxyClientParent = new EmbeddedChannel();
        EmbeddedChannel proxyClient = new EmbeddedChannel(new UpstreamProxyRelayHandler(mockServerLogger, proxyClientParent, loopback, "localhost", 443, 0));

        proxyClient.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", Unpooled.copiedBuffer(new byte[]{1, 2, 3})));
        proxyClient.runPendingTasks();

        assertOneBoundedEntry();
        assertThat(logged.get(0).getLogLevel(), is(Level.ERROR));
        proxyClient.finishAndReleaseAll();
        loopback.finishAndReleaseAll();
        proxyClientParent.finishAndReleaseAll();
    }

    @Test
    public void shouldLogAResponseThatCouldNotBeWrittenToTheProxyClientWithoutTheDump() {
        EmbeddedChannel proxyClient = failingWrites(notATlsRecord());
        EmbeddedChannel loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(mockServerLogger, proxyClient));

        loopback.writeInbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer("body", StandardCharsets.UTF_8)));
        loopback.runPendingTasks();

        assertOneBoundedEntry();
        assertThat(logged.get(0).getLogLevel(), is(Level.ERROR));
        loopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    private static EmbeddedChannel failingWrites(Throwable failure) {
        return new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                promise.setFailure(failure);
            }
        });
    }

    /**
     * What the relay's TLS handler for the proxy client fails its handshake with when a real ClientHello is followed by
     * bytes that are not a TLS record: the JDK's handler, which MockServer uses without the OpenSSL native (OpenSSL's
     * message holds no dump).
     */
    private static Throwable failedServerHandshake() throws Exception {
        SelfSignedCertificate certificate = new SelfSignedCertificate("relay.example");
        EmbeddedChannel client = new EmbeddedChannel(SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build().newHandler(io.netty.buffer.ByteBufAllocator.DEFAULT, "relay.example", 443));
        EmbeddedChannel server = new EmbeddedChannel();
        try {
            SslHandler serverTls = SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey()).sslProvider(SslProvider.JDK).build().newHandler(server.alloc());
            server.pipeline().addLast(serverTls);
            server.pipeline().addLast(new io.netty.channel.ChannelInboundHandlerAdapter() {
                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    // the handshake future carries it
                }
            });
            ByteBuf clientHello = Unpooled.buffer();
            for (ByteBuf written; (written = client.readOutbound()) != null; ) {
                clientHello.writeBytes(written);
                written.release();
            }
            byte[] notTls = new byte[BYTES_READ];
            java.util.Arrays.fill(notTls, (byte) 'A');
            server.writeInbound(Unpooled.wrappedBuffer(clientHello, Unpooled.wrappedBuffer(notTls)));
            server.runPendingTasks();
            assertThat("the handshake has failed", serverTls.handshakeFuture().isDone(), is(true));
            return serverTls.handshakeFuture().cause();
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
            certificate.delete();
        }
    }
}
