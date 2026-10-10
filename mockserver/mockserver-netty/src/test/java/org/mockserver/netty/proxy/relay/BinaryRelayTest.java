package org.mockserver.netty.proxy.relay;

import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.After;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.model.BinaryMessage;
import org.mockserver.model.BinaryProxyListener;
import org.mockserver.socket.ChannelReadPause;
import org.mockserver.socket.LingeringClose;
import org.slf4j.event.Level;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.netty.proxy.relay.BinaryRelayHarness.TARGET;

/**
 * With {@code forwardBinaryRequestsUseSingleConnection} a binary connection's messages all go to one upstream connection,
 * what the upstream sends comes back as it arrives, and closing either connection closes the other.
 */
public class BinaryRelayTest {

    private BinaryRelayHarness relay;
    private final List<String> reported = new ArrayList<>();
    private final List<CompletableFuture<BinaryMessage>> responses = new ArrayList<>();

    @After
    public void releaseBuffers() {
        if (relay != null) {
            relay.finish();
        }
    }

    private EmbeddedChannel relay(boolean connectAtOnce) {
        relay = new BinaryRelayHarness(connectAtOnce);
        return relay.clientConnection();
    }

    private EmbeddedChannel relayWithListener(boolean connectAtOnce) {
        relay = new BinaryRelayHarness(connectAtOnce);
        relay.configuration.binaryProxyListener((binaryRequest, binaryResponse, serverAddress, clientAddress) -> {
            reported.add(text(binaryRequest) + " for " + serverAddress);
            responses.add(binaryResponse);
        });
        return relay.clientConnection();
    }

    private void runListenerCalls() {
        while (!relay.listenerCalls.isEmpty()) {
            relay.runNextListenerCall();
        }
    }

    private static String text(BinaryMessage message) {
        return new String(message.getBytes(), StandardCharsets.UTF_8);
    }

    private static String textOf(CompletableFuture<BinaryMessage> response) throws Exception {
        assertThat(response.isDone(), is(true));
        BinaryMessage message = response.get();
        return message == null ? null : text(message);
    }

    @Test
    public void shouldSendEveryMessageOfAConnectionOnOneUpstreamConnection() {
        relay(true);

        relay.clientSends("one");
        relay.clientSends("two");
        relay.upstreamSends("answer");
        relay.clientSends("three");

        assertThat(relay.upstreamConnections, is(1));
        assertThat(relay.receivedByUpstream(), is("onetwothree"));
        assertThat("nothing is forwarded on a connection of its own", relay.forwardedPerMessage, is(empty()));
    }

    @Test
    public void shouldForwardEachMessageOnItsOwnConnectionWhenTheSettingIsOff() {
        relay = new BinaryRelayHarness(true);
        relay.configuration.forwardBinaryRequestsUseSingleConnection(false);
        relay.clientConnection();

        relay.clientSends("one");
        relay.clientSends("two");

        assertThat("no upstream connection is kept", relay.upstreamConnections, is(0));
        assertThat(relay.forwardedPerMessage, contains("one waiting", "two waiting"));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void shouldNotBeChangedByTheSettingThatChoosesWhetherToWaitForEachResponse() {
        for (boolean withoutWaiting : new boolean[]{false, true}) {
            reported.clear();
            EmbeddedChannel client = relayWithListener(true);
            relay.configuration.forwardBinaryRequestsWithoutWaitingForResponse(withoutWaiting);

            relay.clientSends("one");
            relay.clientSends("two");
            runListenerCalls();

            assertThat(relay.upstreamConnections, is(1));
            assertThat(relay.receivedByUpstream(), is("onetwo"));
            assertThat("nothing is forwarded on a connection of its own", relay.forwardedPerMessage, is(empty()));
            assertThat("the listener is told at once, before any response", reported, hasSize(2));
            assertThat(client.isOpen(), is(true));
            relay.finish();
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void shouldStillChooseWhetherToWaitForEachResponseWhenTheSettingIsOff() {
        relay = new BinaryRelayHarness(true);
        relay.configuration.forwardBinaryRequestsUseSingleConnection(false).forwardBinaryRequestsWithoutWaitingForResponse(true);
        relay.clientConnection();

        relay.clientSends("one");

        assertThat(relay.forwardedPerMessage, contains("one not waiting"));
    }

    @Test
    public void shouldForwardEachMessageOnItsOwnConnectionWhenItsOnlyUpstreamProxyIsForwardHttpProxy() {
        relay = new BinaryRelayHarness(true);
        when(relay.httpClient.binaryRelayUnavailableBecause(any(InetSocketAddress.class), anyBoolean())).thenReturn("its upstream proxy is forwardHttpProxy, which does not tunnel a connection");
        EmbeddedChannel client = relay.clientConnection();

        relay.clientSends("one");
        relay.clientSends("two");

        assertThat("nothing is connected around the proxy", relay.upstreamConnections, is(0));
        assertThat("each goes the way it does with the setting off", relay.forwardedPerMessage, contains("one waiting", "two waiting"));
        assertThat(client.isOpen(), is(true));
        List<LogEntry> said = relay.logged(Level.DEBUG);
        assertThat("said once for the connection, not for each message", said, hasSize(1));
        assertThat(said.get(0).getMessageFormat(), containsString("forwardBinaryRequestsUseSingleConnection is false"));
        assertThat(said.get(0).getArguments()[2], is("its upstream proxy is forwardHttpProxy, which does not tunnel a connection"));
        assertThat(relay.logged(Level.WARN), is(empty()));
    }

    @Test
    public void shouldRelayWhatTheUpstreamSendsWhetherOrNotItAnswersAMessage() {
        relay(true);
        relay.clientSends("request");

        relay.upstreamSends("response");
        relay.upstreamSends(" and more");
        relay.upstreamSends(", unprompted");

        assertThat(relay.receivedByClient(), is("response and more, unprompted"));
    }

    @Test
    public void shouldCloseTheClientWhenTheUpstreamClosesAfterDeliveringWhatItSent() {
        EmbeddedChannel client = relay(true);
        relay.clientSends("request");
        relay.upstreamSends("last words");

        relay.upstream.close();

        assertThat(client.isOpen(), is(false));
        assertThat(relay.receivedByClient(), is("last words"));
    }

    @Test
    public void shouldCloseTheUpstreamWhenTheClientClosesAfterDeliveringWhatItSent() {
        EmbeddedChannel client = relay(true);
        relay.clientSends("last message");

        client.close();

        assertThat(relay.upstream.isOpen(), is(false));
        assertThat(relay.receivedByUpstream(), is("last message"));
    }

    @Test
    public void shouldDeliverWhatIsStillQueuedForTheUpstreamBeforeClosingIt() {
        EmbeddedChannel client = relay(true);
        relay.upstreamFlushGate.blocked = true;
        relay.clientSends("not yet taken by the upstream");

        client.close();
        assertThat("the upstream connection is kept until it has taken what is queued", relay.upstream.isOpen(), is(true));
        relay.upstreamFlushGate.blocked = false;
        relay.upstream.flush();

        assertThat(relay.receivedByUpstream(), is("not yet taken by the upstream"));
        assertThat(relay.upstream.isOpen(), is(false));
    }

    @Test
    public void shouldCloseTheUpstreamAtTheLingerLimitWhenItNeverTakesWhatIsQueuedAfterTheClientCloses() {
        EmbeddedChannel client = relay(true);
        relay.upstreamFlushGate.blocked = true;
        relay.clientSends("never taken by the upstream");
        // only advanceTimeBy moves the clock, so the limit cannot pass in real time between the steps
        relay.upstream.freezeTime();

        client.close();
        relay.upstream.advanceTimeBy(LingeringClose.LINGER_MILLIS - 1, TimeUnit.MILLISECONDS);
        relay.upstream.runScheduledPendingTasks();
        assertThat("the upstream connection is kept for the linger limit", relay.upstream.isOpen(), is(true));
        relay.upstream.advanceTimeBy(1, TimeUnit.MILLISECONDS);
        relay.upstream.runScheduledPendingTasks();

        assertThat("and closed then, though the flush never completed", relay.upstream.isOpen(), is(false));
    }

    @Test
    public void shouldCloseBothConnectionsWhenTheUpstreamConnectionFails() {
        EmbeddedChannel client = relay(true);
        relay.clientSends("request");

        relay.upstream.pipeline().fireExceptionCaught(new DecoderException("not what was expected"));

        assertThat(relay.upstream.isOpen(), is(false));
        assertThat(client.isOpen(), is(false));
        assertThat(relay.logged(Level.WARN), hasSize(1));
    }

    @Test
    public void shouldCloseBothConnectionsAndSaySoWhenTheUpstreamResetsItsConnection() {
        EmbeddedChannel client = relay(true);
        relay.clientSends("request");

        relay.upstream.pipeline().fireExceptionCaught(new java.io.IOException("Connection reset by peer"));

        assertThat(relay.upstream.isOpen(), is(false));
        assertThat(client.isOpen(), is(false));
        List<LogEntry> warnings = relay.logged(Level.WARN);
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0).getMessageFormat(), containsString("reset it"));
        assertThat(warnings.get(0).getArguments()[1], is("Connection reset by peer"));
    }

    @Test
    public void shouldCloseTheClientAndSayWhyWhenTheUpstreamCannotBeConnected() throws Exception {
        EmbeddedChannel client = relayWithListener(false);
        relay.clientSends("request");

        relay.connect.setFailure(new ConnectException("Connection refused"));
        runListenerCalls();

        assertThat(client.isOpen(), is(false));
        List<LogEntry> warnings = relay.logged(Level.WARN);
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0).getMessageFormat(), containsString("unable to connect to:{}"));
        assertThat(warnings.get(0).getArguments()[0], is(TARGET));
        assertThat("named, as a connection that could not be made, without a stack trace", warnings.get(0).getArguments()[2], is("Connection refused"));
        assertThat(warnings.get(0).getThrowable(), is(nullValue()));
        assertThat("a message that was not sent has no response to wait for", responses.get(0).isCompletedExceptionally(), is(true));
        assertThat(assertThrows(ExecutionException.class, responses.get(0)::get).getCause(), instanceOf(ConnectException.class));
    }

    @Test
    public void shouldLeaveAFaultBeforeTheConnectHasSucceededToTheRelayToReport() {
        EmbeddedChannel client = relay(false);
        relay.clientSends("request");

        // as a tunnel's handler reports a proxy that would not open the tunnel, which also fails the connect
        relay.upstream.pipeline().fireExceptionCaught(new ConnectException("the proxy refused the tunnel"));
        assertThat("the upstream handler logs nothing", relay.logged(Level.ERROR), is(empty()));
        assertThat(relay.logged(Level.WARN), is(empty()));
        assertThat(relay.upstream.isOpen(), is(false));
        relay.connect.setFailure(new ConnectException("the proxy refused the tunnel"));

        assertThat(client.isOpen(), is(false));
        assertThat(relay.logged(Level.ERROR), is(empty()));
        List<LogEntry> warnings = relay.logged(Level.WARN);
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0).getMessageFormat(), containsString("unable to connect to:"));
    }

    @Test
    public void shouldCloseTheClientWithoutConnectingWhenTheConnectionIsNotPermitted() {
        relay = new BinaryRelayHarness(true);
        when(relay.httpClient.connectBinaryRelay(any(EventLoop.class), any(InetSocketAddress.class), anyBoolean(), any()))
            .thenThrow(new IllegalArgumentException("Forward to loopback address blocked: 127.0.0.1"));
        EmbeddedChannel client = relay.clientConnection();

        relay.clientSends("request");

        assertThat(client.isOpen(), is(false));
        assertThat(relay.upstreamConnections, is(0));
        List<LogEntry> warnings = relay.logged(Level.WARN);
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0).getMessageFormat(), containsString("forwardBinaryRequestsUseSingleConnection"));
        assertThat(warnings.get(0).getArguments()[2], is("Forward to loopback address blocked: 127.0.0.1"));
    }

    @Test
    public void shouldCloseTheClientAndSayWhyWhenTheForwardClientOpensNoConnection() {
        relay = new BinaryRelayHarness(true);
        when(relay.httpClient.connectBinaryRelay(any(EventLoop.class), any(InetSocketAddress.class), anyBoolean(), any())).thenReturn(null);
        EmbeddedChannel client = relay.clientConnection();

        relay.clientSends("request");

        assertThat(client.isOpen(), is(false));
        List<LogEntry> warnings = relay.logged(Level.WARN);
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0).getArguments()[2], is("the forward client opened no upstream connection"));
        client.checkException();
    }

    @Test
    public void shouldKeepRelayingAConnectionWhenTheSettingIsTurnedOffWhileItIsOpen() {
        relay = new BinaryRelayHarness(true);
        EmbeddedChannel client = relay.clientConnection();
        relay.clientSends("one");

        relay.configuration.forwardBinaryRequestsUseSingleConnection(false);
        relay.clientSends("two");

        assertThat("read once, at the connection's first message", relay.upstreamConnections, is(1));
        assertThat(relay.receivedByUpstream(), is("onetwo"));
        assertThat(relay.forwardedPerMessage, is(empty()));
        assertThat(client.isOpen(), is(true));

        relay.clientConnection();
        relay.clientSends("three");
        assertThat("a connection opened since has the new setting", relay.forwardedPerMessage, contains("three waiting"));
        client.finishAndReleaseAll();
    }

    @Test
    public void shouldKeepForwardingAConnectionPerMessageWhenTheSettingIsTurnedOnWhileItIsOpen() {
        relay = new BinaryRelayHarness(true);
        relay.configuration.forwardBinaryRequestsUseSingleConnection(false);
        relay.clientConnection();
        relay.clientSends("one");

        relay.configuration.forwardBinaryRequestsUseSingleConnection(true);
        relay.clientSends("two");

        assertThat(relay.upstreamConnections, is(0));
        assertThat(relay.forwardedPerMessage, contains("one waiting", "two waiting"));
    }

    @Test
    public void shouldDecideOnceWhetherAConnectionCanBeRelayed() {
        relay = new BinaryRelayHarness(true);
        relay.clientConnection();
        relay.clientSends("one");
        // as noProxyHosts changed while the connection is open
        when(relay.httpClient.binaryRelayUnavailableBecause(any(InetSocketAddress.class), anyBoolean())).thenReturn("its upstream proxy is forwardHttpProxy, which does not tunnel a connection");

        relay.clientSends("two");

        assertThat(relay.upstreamConnections, is(1));
        assertThat(relay.receivedByUpstream(), is("onetwo"));
        assertThat(relay.forwardedPerMessage, is(empty()));
        verify(relay.httpClient, times(1)).binaryRelayUnavailableBecause(TARGET, false);
        verify(relay.httpClient).connectBinaryRelay(any(EventLoop.class), eq(TARGET), eq(false), any());
    }

    @Test
    public void shouldTellTheListenerOfWhatTheUpstreamSendsThatIsNoMessagesResponse() throws Exception {
        relay = new BinaryRelayHarness(true);
        relay.configuration.binaryProxyListener(new BinaryProxyListener() {
            @Override
            public void onProxy(BinaryMessage binaryRequest, CompletableFuture<BinaryMessage> binaryResponse, SocketAddress serverAddress, SocketAddress clientAddress) {
                reported.add("message " + text(binaryRequest));
                responses.add(binaryResponse);
            }

            @Override
            public void onUpstreamMessage(BinaryMessage upstreamMessage, SocketAddress serverAddress, SocketAddress clientAddress) {
                reported.add("upstream " + text(upstreamMessage) + " from " + serverAddress);
            }
        });
        relay.clientConnection();

        relay.clientSends("one");
        relay.upstreamSends("answer");
        relay.upstreamSends("more of it");
        relay.upstreamSends("unprompted");
        relay.clientSends("two");
        relay.upstreamSends("answer to two");
        assertThat("one call at a time is handed to the scheduler", relay.listenerCalls, hasSize(1));
        runListenerCalls();

        assertThat(reported, contains("message one", "upstream more of it from " + TARGET, "upstream unprompted from " + TARGET, "message two"));
        assertThat(textOf(responses.get(0)), is("answer"));
        assertThat(textOf(responses.get(1)), is("answer to two"));
        assertThat("every byte still reaches the client", relay.receivedByClient(), is("answermore of itunpromptedanswer to two"));
    }

    @Test
    public void shouldNotCallAListenerForUpstreamReadsWhenItKeepsTheDefault() {
        relayWithListener(true);
        relay.clientSends("one");
        runListenerCalls();

        relay.upstreamSends("answer");
        relay.upstreamSends("more of it");
        relay.upstreamSends("unprompted");

        assertThat("nothing is scheduled for them", relay.listenerCalls, is(empty()));
        assertThat(reported, contains("one for " + TARGET));
    }

    @Test
    public void shouldTellTheListenerOfEachMessageInOrderWithTheUpstreamAsItsServer() {
        relayWithListener(true);

        relay.clientSends("one");
        relay.clientSends("two");
        relay.clientSends("three");
        assertThat("one call at a time is handed to the scheduler", relay.listenerCalls, hasSize(1));
        runListenerCalls();

        assertThat(reported, contains("one for " + TARGET, "two for " + TARGET, "three for " + TARGET));
    }

    @Test
    public void shouldCompleteAResponseWithTheFirstBytesTheUpstreamSendsAfterItsMessage() throws Exception {
        relayWithListener(true);
        relay.clientSends("request");
        runListenerCalls();
        assertThat(responses.get(0).isDone(), is(false));

        relay.upstreamSends("response");
        relay.upstreamSends("more of it");

        assertThat(textOf(responses.get(0)), is("response"));
    }

    @Test
    public void shouldCompleteAResponseWithNothingWhenTheNextMessageArrivesFirstOrTheUpstreamCloses() throws Exception {
        relayWithListener(true);
        relay.clientSends("one");
        relay.clientSends("two");
        runListenerCalls();

        assertThat("the next message arrived before any answer", textOf(responses.get(0)), is(nullValue()));
        assertThat(responses.get(1).isDone(), is(false));

        relay.upstream.close();
        assertThat("the upstream closed without answering", textOf(responses.get(1)), is(nullValue()));
    }

    @Test
    public void shouldNotTakeBytesThatArrivedBeforeAMessageWasWrittenForItsResponse() throws Exception {
        relayWithListener(true);
        relay.clientSends("one");
        relay.upstreamSends("answer to one");
        relay.upstreamFlushGate.blocked = true;
        relay.clientSends("two");
        runListenerCalls();

        relay.upstreamSends("late bytes for one");
        assertThat("two is not yet written, so this is not its response", responses.get(1).isDone(), is(false));

        relay.upstreamFlushGate.blocked = false;
        relay.upstream.flush();
        relay.upstreamSends("answer to two");

        assertThat(textOf(responses.get(0)), is("answer to one"));
        assertThat(textOf(responses.get(1)), is("answer to two"));
    }

    @Test
    public void shouldCloseTheConnectionWhenTheListenerThrows() {
        relay = new BinaryRelayHarness(true);
        relay.configuration.binaryProxyListener((binaryRequest, binaryResponse, serverAddress, clientAddress) -> {
            throw new IllegalStateException("the listener failed");
        });
        EmbeddedChannel client = relay.clientConnection();

        relay.clientSends("request");
        relay.runNextListenerCall();

        assertThat(client.isOpen(), is(false));
        assertThat(relay.upstream.isOpen(), is(false));
        assertThat(relay.logged(Level.ERROR), hasSize(1));
    }

    @Test
    public void shouldLogEachReadFromTheUpstreamAgainstTheLatestMessage() {
        relay(true);

        relay.clientSends("one");
        relay.upstreamSends("answer");
        relay.upstreamSends("unprompted");
        relay.clientSends("two");
        relay.upstreamSends("second answer");

        List<LogEntry> received = relay.logged().stream().filter(entry -> entry.getType() == RECEIVED_REQUEST).collect(Collectors.toList());
        List<LogEntry> returned = relay.logged().stream().filter(entry -> entry.getType() == FORWARDED_REQUEST).collect(Collectors.toList());
        assertThat(received, hasSize(2));
        assertThat(returned, hasSize(3));
        assertThat(returned.get(0).getMessageFormat(), is("returning binary response:{}from:{}for forwarded binary request:{}"));
        assertThat(returned.get(0).getCorrelationId(), is(received.get(0).getCorrelationId()));
        assertThat("bytes that answer no message say so by leaving the request out", returned.get(1).getMessageFormat(), is("returning binary response:{}from:{}"));
        assertThat(returned.get(1).getCorrelationId(), is(received.get(0).getCorrelationId()));
        assertThat(returned.get(1).getArguments()[1], is(TARGET));
        assertThat(returned.get(2).getMessageFormat(), is("returning binary response:{}from:{}for forwarded binary request:{}"));
        assertThat(returned.get(2).getCorrelationId(), is(received.get(1).getCorrelationId()));
        assertThat(received.get(1).getCorrelationId(), is(not(received.get(0).getCorrelationId())));
        assertThat(relay.logged(Level.WARN), is(empty()));
    }

    @Test
    public void shouldCloseTheClientWhenItsUpstreamConnectFailsBeforeAChannelIsRegistered() throws Exception {
        relayWithListener(true);
        relay.connectFailsBeforeRegistration(new ChannelException("too many open files"), false);
        EmbeddedChannel client = relay.client;
        relay.clientSends("one");

        relay.connect.setFailure(new ChannelException("too many open files"));
        client.runPendingTasks();

        assertThat("closed, though no upstream close will ever come", client.isOpen(), is(false));
        assertThat(ChannelReadPause.holds(client), is(0));
        List<LogEntry> warnings = relay.logged(Level.WARN);
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0).getMessageFormat(), containsString("unable to connect to:{}"));
        runListenerCalls();
        assertThat(responses.get(0).isCompletedExceptionally(), is(true));
    }
}
