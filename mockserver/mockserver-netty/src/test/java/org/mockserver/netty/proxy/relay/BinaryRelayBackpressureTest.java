package org.mockserver.netty.proxy.relay;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.socket.ChannelReadPause;
import org.slf4j.event.Level;

import java.net.ConnectException;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockserver.netty.proxy.relay.BinaryRelay.MAX_PENDING_LISTENER_CALLS;
import static org.mockserver.netty.proxy.relay.BinaryRelayHarness.STALL_TIMEOUT_MILLIS;
import static org.mockserver.netty.proxy.relay.BinaryRelayHarness.isReading;
import static org.mockserver.netty.proxy.relay.BinaryRelayHarness.setWritable;

/**
 * A binary connection relayed on one upstream connection is not read while what is read from it has nowhere to go,
 * and neither is its upstream. Each reason is one hold on the connection's reads, given up when the reason ends and
 * when either connection closes.
 */
public class BinaryRelayBackpressureTest {

    private BinaryRelayHarness relay;

    @After
    public void releaseBuffers() {
        if (relay != null) {
            relay.finish();
        }
    }

    private EmbeddedChannel connectedRelay() {
        relay = new BinaryRelayHarness(true);
        EmbeddedChannel client = relay.clientConnection();
        relay.clientSends("first");
        assertThat(relay.receivedByUpstream(), is("first"));
        return client;
    }

    private EmbeddedChannel connectingRelay() {
        relay = new BinaryRelayHarness(false);
        EmbeddedChannel client = relay.clientConnection();
        relay.clientSends("first");
        return client;
    }

    @Test
    public void shouldNotReadTheClientUntilTheUpstreamIsConnectedThenSendWhatItHeld() {
        EmbeddedChannel client = connectingRelay();

        assertThat("nothing is read while the first message waits for the connect", isReading(client), is(false));
        // what a read already under way still delivers
        relay.clientSends("second");
        assertThat("the hold is taken once", ChannelReadPause.holds(client), is(1));
        assertThat(relay.receivedByUpstream(), is(""));

        relay.connect.setSuccess();

        assertThat("in the order the client sent it", relay.receivedByUpstream(), is("firstsecond"));
        assertThat(isReading(client), is(true));
        assertThat(ChannelReadPause.holds(client), is(0));
    }

    @Test
    public void shouldNotReadTheClientWhileTheUpstreamCannotTakeMore() {
        EmbeddedChannel client = connectedRelay();

        setWritable(relay.upstream, false);
        assertThat(isReading(client), is(false));
        // what a read already under way still delivers is passed on, and takes no second hold
        relay.clientSends("second");
        assertThat(ChannelReadPause.holds(client), is(1));
        assertThat(relay.receivedByUpstream(), is("second"));

        setWritable(relay.upstream, true);
        assertThat(isReading(client), is(true));
        assertThat(ChannelReadPause.holds(client), is(0));

        setWritable(relay.upstream, false);
        assertThat("and is held back again the next time", ChannelReadPause.holds(client), is(1));
    }

    @Test
    public void shouldNotReadTheUpstreamWhileTheClientCannotTakeMore() {
        EmbeddedChannel client = connectedRelay();

        setWritable(client, false);
        assertThat("nothing is held back until there is something the client cannot take", isReading(relay.upstream), is(true));
        relay.upstreamSends("one");
        assertThat(isReading(relay.upstream), is(false));
        relay.upstreamSends("two");
        assertThat("the hold is taken once", ChannelReadPause.holds(relay.upstream), is(1));

        setWritable(client, true);
        assertThat(isReading(relay.upstream), is(true));
        assertThat(ChannelReadPause.holds(relay.upstream), is(0));
        assertThat("nothing was dropped while it was held back", relay.receivedByClient(), is("onetwo"));
    }

    @Test
    public void shouldLeaveAnotherHoldersHoldOnTheClientAlone() {
        relay = new BinaryRelayHarness(false);
        EmbeddedChannel client = relay.clientConnection();
        ChannelReadPause.pause(client);
        relay.clientSends("first");
        assertThat(ChannelReadPause.holds(client), is(2));

        relay.connect.setSuccess();
        assertThat(ChannelReadPause.holds(client), is(1));
        setWritable(relay.upstream, false);
        assertThat(ChannelReadPause.holds(client), is(2));
        relay.upstream.close();

        assertThat("only the relay's own holds are given up", ChannelReadPause.holds(client), is(1));
    }

    @Test
    public void shouldNotReadTheClientWhileMoreMessagesThanTheLimitWaitForTheListener() {
        relay = new BinaryRelayHarness(true);
        relay.configuration.binaryProxyListener((binaryRequest, binaryResponse, serverAddress, clientAddress) -> {
        });
        EmbeddedChannel client = relay.clientConnection();

        for (int message = 0; message < MAX_PENDING_LISTENER_CALLS; message++) {
            relay.clientSends("m");
        }
        assertThat("at the limit the client is still read", isReading(client), is(true));
        relay.clientSends("m");
        assertThat("one more than the limit and it is not", isReading(client), is(false));
        relay.clientSends("m");
        assertThat("the hold is taken once", ChannelReadPause.holds(client), is(1));
        assertThat("every message was still relayed at once", relay.receivedByUpstream().length(), is(MAX_PENDING_LISTENER_CALLS + 2));
        assertThat("one call at a time is with the listener", relay.listenerCalls, hasSize(1));

        for (int waiting = MAX_PENDING_LISTENER_CALLS + 2; waiting > MAX_PENDING_LISTENER_CALLS / 2 + 1; waiting--) {
            relay.runNextListenerCall();
            assertThat("still not read with " + (waiting - 1) + " waiting", isReading(client), is(false));
        }
        relay.runNextListenerCall();
        assertThat("read again with half the limit waiting", isReading(client), is(true));
        assertThat(ChannelReadPause.holds(client), is(0));
    }

    @Test
    public void shouldGiveUpEveryHoldWhenTheClientClosesWhileTheUpstreamIsConnecting() {
        EmbeddedChannel client = connectingRelay();

        client.close();
        assertThat(ChannelReadPause.holds(client), is(0));
        relay.connect.setSuccess();

        assertThat("what the client sent before it closed is still delivered", relay.receivedByUpstream(), is("first"));
        assertThat("and then the upstream connection is closed", relay.upstream.isOpen(), is(false));
        assertThat("no hold is taken, or given up, a second time", ChannelReadPause.holds(client), is(0));
    }

    @Test
    public void shouldGiveUpEveryHoldWhenTheConnectFails() {
        EmbeddedChannel client = connectingRelay();

        relay.connect.setFailure(new ConnectException("Connection refused"));

        assertThat(ChannelReadPause.holds(client), is(0));
        assertThat(client.isOpen(), is(false));
        assertThat(relay.upstream.isOpen(), is(false));
    }

    @Test
    public void shouldGiveUpEveryHoldWhenTheClientClosesWhileTheUpstreamCannotTakeMore() {
        EmbeddedChannel client = connectedRelay();
        setWritable(relay.upstream, false);
        assertThat(ChannelReadPause.holds(client), is(1));

        client.close();

        assertThat(ChannelReadPause.holds(client), is(0));
        assertThat(relay.upstream.isOpen(), is(false));
    }

    @Test
    public void shouldGiveUpEveryHoldWhenTheUpstreamClosesWhileItCannotTakeMore() {
        EmbeddedChannel client = connectedRelay();
        setWritable(relay.upstream, false);
        assertThat(ChannelReadPause.holds(client), is(1));

        relay.upstream.close();

        assertThat(ChannelReadPause.holds(client), is(0));
        assertThat(client.isOpen(), is(false));
    }

    @Test
    public void shouldReadTheClientAgainOnceTheUpstreamHasClosedThoughTheClientIsStillBeingSentItsLastBytes() {
        EmbeddedChannel client = connectedRelay();
        setWritable(relay.upstream, false);
        relay.clientFlushGate.blocked = true;
        relay.upstreamSends("last words");

        relay.upstream.close();

        assertThat("the client is kept until it has taken what the upstream sent", client.isOpen(), is(true));
        assertThat("and is read meanwhile, so that its own close is seen", ChannelReadPause.holds(client), is(0));
        relay.clientFlushGate.blocked = false;
        client.flush();
        assertThat(relay.receivedByClient(), is("last words"));
        assertThat(client.isOpen(), is(false));
        assertThat(ChannelReadPause.holds(client), is(0));
    }

    @Test
    public void shouldGiveUpEveryHoldWhenTheClientClosesWhileItCannotTakeMore() {
        EmbeddedChannel client = connectedRelay();
        setWritable(client, false);
        relay.upstreamSends("one");
        assertThat(ChannelReadPause.holds(relay.upstream), is(1));

        client.close();

        assertThat("the upstream is read again, so that its close is seen", ChannelReadPause.holds(relay.upstream), is(0));
        assertThat(relay.upstream.isOpen(), is(false));
    }

    @Test
    public void shouldGiveUpEveryHoldWhenTheUpstreamClosesWhileTheClientCannotTakeMore() {
        EmbeddedChannel client = connectedRelay();
        setWritable(client, false);
        relay.upstreamSends("one");
        assertThat(ChannelReadPause.holds(relay.upstream), is(1));

        relay.upstream.close();

        assertThat(ChannelReadPause.holds(relay.upstream), is(0));
        assertThat(client.isOpen(), is(false));
        // the client becoming writable as it closes must not give the hold up a second time
        assertThat(ChannelReadPause.holds(client), is(0));
    }

    @Test
    public void shouldGiveUpEveryHoldWhenAConnectionClosesWhileTheListenerIsBehind() {
        relay = new BinaryRelayHarness(true);
        relay.configuration.binaryProxyListener((binaryRequest, binaryResponse, serverAddress, clientAddress) -> {
        });
        EmbeddedChannel client = relay.clientConnection();
        for (int message = 0; message < MAX_PENDING_LISTENER_CALLS + 1; message++) {
            relay.clientSends("m");
        }
        assertThat(ChannelReadPause.holds(client), is(1));

        relay.upstream.close();
        assertThat(ChannelReadPause.holds(client), is(0));
        while (!relay.listenerCalls.isEmpty()) {
            relay.runNextListenerCall();
        }

        assertThat("and not a second time as the listener catches up", ChannelReadPause.holds(client), is(0));
        assertThat(client.isOpen(), is(false));
    }

    @Test
    public void shouldCloseBothConnectionsWhenTheUpstreamTakesNothingForTheWriteStallTimeout() {
        EmbeddedChannel client = connectedRelay();
        setWritable(relay.upstream, false);

        relay.stallTimeoutPasses();

        assertThat(relay.upstream.isOpen(), is(false));
        assertThat(client.isOpen(), is(false));
        assertThat(ChannelReadPause.holds(client), is(0));
        List<LogEntry> warnings = relay.logged(Level.WARN);
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0).getMessageFormat(), containsString("took none of the bytes waiting for it"));
    }

    @Test
    public void shouldNotCloseAnUpstreamThatTakesMoreBeforeTheWriteStallTimeout() {
        EmbeddedChannel client = connectedRelay();
        setWritable(relay.upstream, false);
        setWritable(relay.upstream, true);

        relay.stallTimeoutPasses();

        assertThat(relay.upstream.isOpen(), is(true));
        assertThat(client.isOpen(), is(true));
        assertThat(relay.logged(Level.WARN), is(empty()));
    }

    @Test
    public void shouldCountTheWriteStallTimeoutFromWhenTheUpstreamLastStoppedTakingBytes() {
        EmbeddedChannel client = connectedRelay();
        setWritable(relay.upstream, false);
        relay.timePasses(STALL_TIMEOUT_MILLIS / 2);
        setWritable(relay.upstream, true);
        setWritable(relay.upstream, false);

        relay.timePasses(STALL_TIMEOUT_MILLIS / 2 + 1);
        assertThat("half a timeout into this stall", relay.upstream.isOpen(), is(true));

        relay.timePasses(STALL_TIMEOUT_MILLIS / 2);
        assertThat(relay.upstream.isOpen(), is(false));
        assertThat(client.isOpen(), is(false));
    }

    @Test
    public void shouldNotCloseAnUpstreamThatIsSlowButStillTakingBytes() {
        EmbeddedChannel client = connectedRelay();
        setWritable(relay.upstream, false);
        relay.upstreamFlushGate.blocked = true;
        relay.clientSends("waiting for the upstream");
        relay.upstreamFlushGate.blocked = false;
        relay.upstream.flush();

        relay.stallTimeoutPasses();
        assertThat("it took what was waiting, so it is not stalled", relay.upstream.isOpen(), is(true));
        assertThat(client.isOpen(), is(true));

        relay.stallTimeoutPasses();
        assertThat("a whole timeout with nothing taken", relay.upstream.isOpen(), is(false));
        assertThat(client.isOpen(), is(false));
    }

    @Test
    public void shouldNotWatchForAStalledUpstreamWhenTheWriteStallTimeoutIsOff() {
        relay = new BinaryRelayHarness(true);
        relay.configuration.responseWriteStallTimeoutMillis(0L);
        EmbeddedChannel client = relay.clientConnection();
        relay.clientSends("first");
        setWritable(relay.upstream, false);

        relay.stallTimeoutPasses();

        assertThat(relay.upstream.isOpen(), is(true));
        assertThat("the client is still held back", isReading(client), is(false));
    }
}
