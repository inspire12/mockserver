package org.mockserver.netty.proxy.relay;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.handler.codec.http.HttpServerCodec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * MockServer's side of a relay loopback tells the tunnel's client leg of an exchange it has ended with nothing the
 * client could read as its response, and of no other.
 */
public class LoopbackExchangeEndedHandlerTest {

    // unique: the relay's loopback addresses are held JVM-wide and tests run in parallel
    private final SocketAddress loopbackAddress = new LocalAddress("loopback-" + UUID.randomUUID());
    private final List<Object> seenOnClientLegAfterCodec = new ArrayList<>();
    private final List<Object> seenOnLoopbackAfterHandler = new ArrayList<>();
    private EmbeddedChannel proxyClient;
    private EmbeddedChannel relayLoopback;
    private EmbeddedChannel acceptedLoopback;

    @Before
    public void openTunnel() {
        proxyClient = new EmbeddedChannel(new HttpServerCodec(), recorder(seenOnClientLegAfterCodec));
        relayLoopback = new EmbeddedChannel() {
            @Override
            protected SocketAddress localAddress0() {
                return loopbackAddress;
            }
        };
        acceptedLoopback = acceptedFrom(loopbackAddress);
        RelayLoopbackAddresses.register(relayLoopback, proxyClient);
    }

    @After
    public void closeTunnel() {
        acceptedLoopback.finishAndReleaseAll();
        relayLoopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    @Test
    public void shouldTellTheClientLegOfAnExchangeEndedWithNoResponse() {
        acceptedLoopback.pipeline().fireUserEventTriggered(HttpExchangeEndedEvent.INSTANCE);

        assertThat("not on the loopback's thread: the client leg's exchange count is confined to its own event loop", seenOnClientLegAfterCodec, is(empty()));
        proxyClient.runPendingTasks();

        assertThat("fired from the client leg's codec, so the handlers after it end their oldest exchange", seenOnClientLegAfterCodec, contains((Object) HttpExchangeEndedEvent.INSTANCE));
        assertThat("and still seen by the loopback's own handlers", seenOnLoopbackAfterHandler, contains((Object) HttpExchangeEndedEvent.INSTANCE));
    }

    @Test
    public void shouldNotTellTheClientLegOfARawBytesResponseWhichTheRelayReadsAsAResponse() {
        acceptedLoopback.pipeline().fireUserEventTriggered(HttpExchangeEndedEvent.RAW_RESPONSE_WRITTEN);
        proxyClient.runPendingTasks();

        assertThat(seenOnClientLegAfterCodec, is(empty()));
        assertThat(seenOnLoopbackAfterHandler, contains((Object) HttpExchangeEndedEvent.RAW_RESPONSE_WRITTEN));
    }

    @Test
    public void shouldPassOnTheEventOfAConnectionThatIsNoRelaysLoopback() {
        EmbeddedChannel direct = acceptedFrom(new LocalAddress("client-" + UUID.randomUUID()));

        direct.pipeline().fireUserEventTriggered(HttpExchangeEndedEvent.INSTANCE);
        proxyClient.runPendingTasks();

        assertThat(seenOnClientLegAfterCodec, is(empty()));
        assertThat(seenOnLoopbackAfterHandler, contains((Object) HttpExchangeEndedEvent.INSTANCE));
        direct.finishAndReleaseAll();
    }

    @Test
    public void shouldForgetTheTunnelWhenItsLoopbackCloses() {
        assertThat(RelayLoopbackAddresses.isRelayLoopback(acceptedLoopback), is(true));

        relayLoopback.close();

        assertThat(RelayLoopbackAddresses.isRelayLoopback(acceptedLoopback), is(false));
        acceptedLoopback.pipeline().fireUserEventTriggered(HttpExchangeEndedEvent.INSTANCE);
        proxyClient.runPendingTasks();
        assertThat(seenOnClientLegAfterCodec, is(empty()));
    }

    private EmbeddedChannel acceptedFrom(SocketAddress remoteAddress) {
        return new EmbeddedChannel(LoopbackExchangeEndedHandler.INSTANCE, recorder(seenOnLoopbackAfterHandler)) {
            @Override
            protected SocketAddress remoteAddress0() {
                return remoteAddress;
            }
        };
    }

    private static ChannelInboundHandlerAdapter recorder(List<Object> seen) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                seen.add(evt);
            }
        };
    }
}
