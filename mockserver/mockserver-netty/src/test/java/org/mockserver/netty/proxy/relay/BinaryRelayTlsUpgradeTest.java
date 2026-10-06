package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.SniCompletionEvent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.unification.PortUnificationHandler;
import org.mockserver.socket.ChannelReadPause;
import org.mockserver.socket.tls.KeyStoreFactory;
import org.slf4j.event.Level;

import javax.net.ssl.KeyManagerFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * A relayed binary connection whose client turns TLS on part way through: MockServer's own handshake with the
 * upstream starts on the same upstream connection, the client is not read while it runs, and a handshake that fails
 * closes both connections. The upstream is an embedded TLS server the test moves bytes to and from.
 */
public class BinaryRelayTlsUpgradeTest {

    private static final long HANDSHAKE_TIMEOUT_MILLIS = 5_000;
    private static SslContext upstreamServer;
    private static SslContext forwardClient;

    private BinaryRelayHarness relay;
    private EmbeddedChannel tlsServer;
    private final StringBuilder decryptedByUpstream = new StringBuilder();

    @BeforeClass
    public static void createTlsContexts() throws Exception {
        KeyStore keyStore = new KeyStoreFactory(configuration(), new MockServerLogger()).loadOrCreateKeyStore();
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore, KeyStoreFactory.KEY_STORE_PASSWORD.toCharArray());
        upstreamServer = SslContextBuilder.forServer(keys).sslProvider(SslProvider.JDK).build();
        forwardClient = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).sslProvider(SslProvider.JDK).build();
    }

    @After
    public void releaseBuffers() {
        if (relay != null) {
            relay.finish();
        }
        if (tlsServer != null) {
            tlsServer.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel relay(boolean connectAtOnce) {
        relay = new BinaryRelayHarness(connectAtOnce);
        when(relay.httpClient.newBinaryRelaySslHandler(any(ByteBufAllocator.class), any(InetSocketAddress.class))).thenAnswer(invocation -> {
            SslHandler sslHandler = forwardClient.newHandler(invocation.getArgument(0), "127.0.0.1", 1234);
            sslHandler.setHandshakeTimeoutMillis(HANDSHAKE_TIMEOUT_MILLIS);
            return sslHandler;
        });
        tlsServer = new EmbeddedChannel(upstreamServer.newHandler(ByteBufAllocator.DEFAULT), new SimpleChannelInboundHandler<ByteBuf>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf decrypted) {
                decryptedByUpstream.append(decrypted.toString(StandardCharsets.UTF_8));
            }
        });
        return relay.clientConnection();
    }

    /** As MockServer's pipeline does when the client's handshake begins; the event is what the SNI handler fires. */
    private static void clientTurnsTlsOn(EmbeddedChannel client, boolean withEvent) {
        PortUnificationHandler.enableSslUpstreamAndDownstream(client);
        if (withEvent) {
            client.pipeline().fireUserEventTriggered(new SniCompletionEvent("localhost"));
        }
    }

    /** Carries bytes between the relay's upstream connection and the TLS server until neither has more to send. */
    private void exchangeWithUpstream() {
        boolean moved = true;
        for (int round = 0; moved && round < 100; round++) {
            moved = false;
            relay.upstream.runPendingTasks();
            tlsServer.runPendingTasks();
            for (Object written; (written = relay.upstream.readOutbound()) != null; moved = true) {
                tlsServer.writeInbound(written);
            }
            for (Object written; (written = tlsServer.readOutbound()) != null; moved = true) {
                if (relay.upstream.isOpen()) {
                    relay.upstream.writeInbound(written);
                } else {
                    ReferenceCountUtil.release(written);
                }
            }
        }
        relay.client.runPendingTasks();
    }

    private void upstreamSendsOverTls(String message) {
        tlsServer.writeAndFlush(Unpooled.copiedBuffer(message, StandardCharsets.UTF_8));
        exchangeWithUpstream();
    }

    private static int holds(EmbeddedChannel channel) {
        return ChannelReadPause.holds(channel);
    }

    @Test
    public void shouldUpgradeTheUpstreamConnectionWhenTheClientTurnsTlsOn() {
        EmbeddedChannel client = relay(true);
        relay.clientSends("SSLRequest");
        assertThat("sent in the clear", relay.receivedByUpstream(), is("SSLRequest"));
        relay.upstreamSends("S");
        assertThat("the go-ahead is relayed", relay.receivedByClient(), is("S"));

        clientTurnsTlsOn(client, true);

        assertThat("the upstream handshake starts at once", relay.upstream.pipeline().first(), instanceOf(SslHandler.class));
        assertThat("the client is read until it sends over TLS", holds(client), is(0));
        relay.clientSends("startup");
        assertThat("and then held while the upstream handshake runs", holds(client), is(1));
        assertThat(BinaryRelayHarness.isReading(client), is(false));

        exchangeWithUpstream();

        assertThat("read again once it has succeeded", holds(client), is(0));
        assertThat("what was held went over TLS", decryptedByUpstream.toString(), is("startup"));
        upstreamSendsOverTls("ready");
        assertThat("and the upstream's answer is decrypted for the client", relay.receivedByClient(), is("ready"));
        relay.clientSends("query");
        assertThat("not held once the handshake is done", holds(client), is(0));
        exchangeWithUpstream();
        assertThat(decryptedByUpstream.toString(), is("startupquery"));

        assertThat("all of it on one upstream connection", relay.upstreamConnections, is(1));
        assertThat(relay.forwardedPerMessage, is(empty()));
        assertThat(relay.logged(Level.WARN), is(empty()));
    }

    @Test
    public void shouldUpgradeAtTheFirstMessageOverTlsWhenNoEventArrives() {
        EmbeddedChannel client = relay(true);
        relay.clientSends("SSLRequest");
        relay.receivedByUpstream();

        clientTurnsTlsOn(client, false);
        assertThat(relay.upstream.pipeline().get(SslHandler.class), is(nullValue()));
        relay.clientSends("startup");

        assertThat(relay.upstream.pipeline().first(), instanceOf(SslHandler.class));
        assertThat(holds(client), is(1));
        exchangeWithUpstream();
        assertThat(holds(client), is(0));
        assertThat(decryptedByUpstream.toString(), is("startup"));
        assertThat(relay.upstreamConnections, is(1));
    }

    @Test
    public void shouldSendInTheClearWhatWasWaitingForTheConnectAndOverTlsWhatCameAfterTheUpgrade() {
        EmbeddedChannel client = relay(false);
        relay.clientSends("SSLRequest");
        clientTurnsTlsOn(client, true);
        relay.clientSends("startup");
        assertThat("no handshake before the connection is made", relay.upstream.pipeline().get(SslHandler.class), is(nullValue()));

        relay.connect.setSuccess();
        relay.upstream.runPendingTasks();

        ByteBuf first = relay.upstream.readOutbound();
        assertThat("first, in the clear, what came before the upgrade", first.toString(StandardCharsets.UTF_8), is("SSLRequest"));
        first.release();
        assertThat(relay.upstream.pipeline().first(), instanceOf(SslHandler.class));
        exchangeWithUpstream();
        assertThat("then over TLS what came after it", decryptedByUpstream.toString(), is("startup"));
        assertThat(holds(client), is(0));
    }

    @Test
    public void shouldCloseBothConnectionsAndSaySoOnceWhenTheUpstreamDoesNotAnswerTheHandshakeWithTls() {
        EmbeddedChannel client = relay(true);
        relay.clientSends("SSLRequest");
        relay.receivedByUpstream();
        clientTurnsTlsOn(client, true);
        relay.clientSends("startup");
        ReferenceCountUtil.release(relay.upstream.readOutbound());

        // an upstream that refused the upgrade answers in the clear
        relay.upstreamSends("N and then some bytes that are not a TLS record");
        relay.client.runPendingTasks();

        assertThat(relay.upstream.isOpen(), is(false));
        assertThat(client.isOpen(), is(false));
        assertThat(holds(client), is(0));
        assertThat("nothing it sent reaches the client", relay.receivedByClient(), is(""));
        List<LogEntry> warnings = relay.logged(Level.WARN);
        assertThat("logged once, by the relay", warnings, hasSize(1));
        assertThat(warnings.get(0).getMessageFormat(), containsString("unable to upgrade the upstream connection"));
        assertThat(warnings.get(0).getThrowable(), is(notNullValue()));
        assertThat("what the upstream sent is counted, not dumped", String.valueOf(warnings.get(0).getArguments()[2]), not(containsString("4e20616e64")));
    }

    @Test
    public void shouldCloseBothConnectionsWhenTheUpstreamHandshakeTimesOut() {
        EmbeddedChannel client = relay(true);
        relay.clientSends("SSLRequest");
        relay.receivedByUpstream();
        clientTurnsTlsOn(client, true);
        relay.clientSends("startup");
        assertThat(holds(client), is(1));

        relay.upstream.advanceTimeBy(HANDSHAKE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        relay.upstream.runScheduledPendingTasks();
        relay.client.runPendingTasks();

        assertThat(relay.upstream.isOpen(), is(false));
        assertThat(client.isOpen(), is(false));
        assertThat(holds(client), is(0));
        assertThat(relay.logged(Level.WARN), hasSize(1));
        assertThat(relay.logged(Level.WARN).get(0).getMessageFormat(), containsString("unable to upgrade the upstream connection"));
    }

    @Test
    public void shouldCloseBothConnectionsWhenTheUpstreamTlsHandlerCannotBeMade() {
        EmbeddedChannel client = relay(true);
        when(relay.httpClient.newBinaryRelaySslHandler(any(ByteBufAllocator.class), any(InetSocketAddress.class))).thenThrow(new IllegalStateException("no TLS context"));
        relay.clientSends("SSLRequest");
        relay.receivedByUpstream();

        clientTurnsTlsOn(client, true);
        relay.client.runPendingTasks();

        assertThat(relay.upstream.isOpen(), is(false));
        assertThat(client.isOpen(), is(false));
        assertThat(relay.logged(Level.WARN), hasSize(1));
        assertThat(relay.logged(Level.WARN).get(0).getArguments()[2], is("no TLS context"));
    }

    @Test
    public void shouldDeliverWhatTheClientSentAndThenEndTheUpstreamWhenTheClientClosesDuringTheHandshake() {
        EmbeddedChannel client = relay(true);
        relay.clientSends("SSLRequest");
        relay.receivedByUpstream();
        clientTurnsTlsOn(client, true);
        relay.clientSends("terminate");
        assertThat(holds(client), is(1));

        client.close();

        assertThat("the hold is given up", holds(client), is(0));
        assertThat("the upstream connection waits for its handshake", relay.upstream.isOpen(), is(true));
        exchangeWithUpstream();
        assertThat("then has what the client sent", decryptedByUpstream.toString(), is("terminate"));
        assertThat("and is ended", relay.upstream.isOpen(), is(false));
        assertThat(relay.logged(Level.WARN), is(empty()));
    }

    @Test
    public void shouldNotCallItAFaultWhenTheHandshakeFailsAfterTheClientHasGone() {
        EmbeddedChannel client = relay(true);
        relay.clientSends("SSLRequest");
        relay.receivedByUpstream();
        clientTurnsTlsOn(client, true);
        relay.clientSends("terminate");
        client.close();
        ReferenceCountUtil.release(relay.upstream.readOutbound());

        relay.upstreamSends("not a TLS record");

        assertThat(relay.upstream.isOpen(), is(false));
        assertThat(holds(client), is(0));
        assertThat(relay.logged(Level.WARN), is(empty()));
    }

    @Test
    public void shouldLeaveAConnectionForwardedPerMessageAloneWhenItsClientTurnsTlsOn() {
        relay = new BinaryRelayHarness(true);
        when(relay.httpClient.forwardsThroughProxy()).thenReturn(true);
        EmbeddedChannel client = relay.clientConnection();
        relay.clientSends("SSLRequest");

        clientTurnsTlsOn(client, true);
        relay.clientSends("startup");

        assertThat(relay.upstreamConnections, is(0));
        assertThat(relay.forwardedPerMessage, hasSize(2));
        assertThat(relay.forwardedPerMessage.get(1), is("startup waiting, over TLS"));
        assertThat(client.isOpen(), is(true));
        client.checkException();
    }
}
