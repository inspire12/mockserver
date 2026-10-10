package org.mockserver.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import org.junit.Test;
import org.mockserver.codec.StreamAddressedHttpContent;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Coverage for {@link InFlightRequest} — the WS7.2 graceful-shutdown drain token and its channel
 * {@code closeFuture} safety net.
 *
 * <p>The load-bearing test is {@link #completedTokensAreNotRetainedOnAKeepAliveConnection()}: it
 * reproduces the per-request heap leak on the HTTP/1.1 connection-channel shape (a single channel
 * whose {@code closeFuture} never completes while many requests flow over it) and proves the fix
 * frees each completed token. On the pre-fix code — where the close-future listener was added but
 * never removed — the {@link WeakReference}s stay reachable through the channel's {@code closeFuture}
 * and the assertion fails.</p>
 */
public class InFlightRequestTest {

    /**
     * A {@link LifeCycle} whose in-flight counter is backed by a real {@link AtomicInteger}, so the
     * started/complete increments and decrements are observable. Mockito is used only to obtain a
     * concrete {@link LifeCycle}; the counter itself is real, so double-completion or a leaked
     * decrement would show up in {@link #inFlight}.
     */
    private LifeCycle counterBackedServer(AtomicInteger inFlight) {
        LifeCycle server = mock(LifeCycle.class);
        doAnswer(invocation -> {
            inFlight.incrementAndGet();
            return null;
        }).when(server).requestProcessingStarted();
        doAnswer(invocation -> {
            inFlight.updateAndGet(current -> current > 0 ? current - 1 : 0);
            return null;
        }).when(server).requestProcessingComplete();
        return server;
    }

    @Test
    public void completedTokensAreNotRetainedOnAKeepAliveConnection() {
        // given - one long-lived channel standing in for an HTTP/1.1 keep-alive connection, whose
        // closeFuture does NOT complete while requests flow over it
        EmbeddedChannel keepAliveConnection = new EmbeddedChannel();
        AtomicInteger inFlight = new AtomicInteger(0);
        LifeCycle server = counterBackedServer(inFlight);
        List<WeakReference<InFlightRequest>> tokens = new ArrayList<>();

        // when - 100 requests are processed over that single connection, each completing normally
        // (as NettyResponseWriter.sendResponse would) without the connection closing
        for (int i = 0; i < 100; i++) {
            InFlightRequest token = InFlightRequest.started(server);
            token.trackConnectionClose(keepAliveConnection);
            token.complete();
            tokens.add(new WeakReference<>(token));
            // token goes out of scope here - the only thing that could keep it alive is the
            // closeFuture listener, which complete() must have removed
        }

        // then - the drain counter has returned to baseline ...
        assertThat("every completed token decrements the drain counter", inFlight.get(), is(0));
        // ... and no completed token is still reachable (i.e. no listener is pinned to the
        // still-open connection's closeFuture). On the pre-fix code these references survive GC.
        assertThat("completed tokens are freed, not pinned to the keep-alive closeFuture",
            allCleared(tokens), is(true));

        // keep the channel strongly reachable to the end so the pre-fix leak has somewhere to hang
        assertThat(keepAliveConnection.isOpen(), is(true));
    }

    @Test
    public void closeFutureSafetyNetStillDecrementsWhenNoResponseIsProduced() {
        // given - a request whose channel closes before any response is written
        EmbeddedChannel connection = new EmbeddedChannel();
        AtomicInteger inFlight = new AtomicInteger(0);
        LifeCycle server = counterBackedServer(inFlight);

        InFlightRequest token = InFlightRequest.started(server);
        token.trackConnectionClose(connection);
        assertThat("started increments the drain counter", inFlight.get(), is(1));

        // when - the connection drops without the response funnel ever completing the token
        connection.close();

        // then - the close-future safety net still fires complete(), so the drain counter decrements
        assertThat("dropped connection still decrements the drain counter", inFlight.get(), is(0));
    }

    @Test
    public void completeIsIdempotentAcrossTheResponsePathAndTheCloseFuture() {
        // given
        EmbeddedChannel connection = new EmbeddedChannel();
        AtomicInteger inFlight = new AtomicInteger(0);
        LifeCycle server = counterBackedServer(inFlight);

        InFlightRequest token = InFlightRequest.started(server);
        token.trackConnectionClose(connection);

        // when - the response path completes the token first, then the connection later closes
        token.complete();
        connection.close();

        // then - the counter is decremented exactly once (never below zero, never double-counted)
        assertThat(inFlight.get(), is(0));
    }

    @Test
    public void completeIsANoOpAndSafeWithoutAnyTrackedConnection() {
        // given - started() with no trackConnectionClose (mirrors the null-channel / no-safety-net
        // branch); complete() must still decrement exactly once and not throw
        AtomicInteger inFlight = new AtomicInteger(0);
        LifeCycle server = counterBackedServer(inFlight);

        InFlightRequest token = InFlightRequest.started(server);
        assertThat(inFlight.get(), is(1));

        // when
        token.complete();
        token.complete();

        // then
        assertThat(inFlight.get(), is(0));
    }

    /**
     * A token whose response is written straight to the channel, with {@code handler} standing in for
     * the request handler that writes it, on a connection kept open throughout.
     */
    private InFlightRequest directResponse(EmbeddedChannel connection, AtomicInteger inFlight) {
        InFlightRequest token = InFlightRequest.started(counterBackedServer(inFlight));
        token.trackConnectionClose(connection);
        token.completeWhenResponseEnds(connection.pipeline().context("handler"));
        return token;
    }

    private static EmbeddedChannel keepAliveConnection() {
        EmbeddedChannel connection = new EmbeddedChannel();
        connection.pipeline().addLast("handler", new ChannelInboundHandlerAdapter());
        return connection;
    }

    private static boolean watching(EmbeddedChannel connection) {
        return connection.pipeline().get(InFlightRequest.ResponseEndWatcher.class) != null;
    }

    @Test
    public void directResponseIsReleasedWhenItsLastContentIsWrittenNotBefore() {
        EmbeddedChannel connection = keepAliveConnection();
        AtomicInteger inFlight = new AtomicInteger(0);
        directResponse(connection, inFlight);
        ChannelHandlerContext handler = connection.pipeline().context("handler");

        handler.writeAndFlush(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        handler.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer("data: first\n\n", StandardCharsets.UTF_8)));
        assertThat("the head and an event do not end the response", inFlight.get(), is(1));

        handler.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        assertThat("the last content ends it", inFlight.get(), is(0));
        assertThat("the watcher leaves once the response has ended", watching(connection), is(false));
        assertThat(connection.isOpen(), is(true));
        connection.finishAndReleaseAll();
    }

    @Test
    public void directResponseIsReleasedOnlyOnceTheLastContentWriteHasCompleted() {
        EmbeddedChannel connection = keepAliveConnection();
        AtomicInteger inFlight = new AtomicInteger(0);
        directResponse(connection, inFlight);
        ChannelHandlerContext handler = connection.pipeline().context("handler");

        handler.write(LastHttpContent.EMPTY_LAST_CONTENT);
        assertThat("queued but not yet written", inFlight.get(), is(1));

        connection.flush();
        assertThat(inFlight.get(), is(0));
        connection.finishAndReleaseAll();
    }

    @Test
    public void interimResponseDoesNotEndTheExchangeButSwitchingProtocolsDoes() {
        EmbeddedChannel connection = keepAliveConnection();
        AtomicInteger inFlight = new AtomicInteger(0);
        directResponse(connection, inFlight);
        ChannelHandlerContext handler = connection.pipeline().context("handler");

        handler.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
        assertThat("100 Continue precedes the response", inFlight.get(), is(1));

        handler.channel().writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS));
        assertThat("101 hands the connection to WebSocket", inFlight.get(), is(0));
        assertThat(watching(connection), is(false));
        connection.finishAndReleaseAll();
    }

    @Test
    public void directResponseIsReleasedByItsEndOfStreamFrame() {
        EmbeddedChannel connection = keepAliveConnection();
        AtomicInteger inFlight = new AtomicInteger(0);
        directResponse(connection, inFlight);
        ChannelHandlerContext handler = connection.pipeline().context("handler");

        handler.writeAndFlush(new StreamAddressedHttpContent(Unpooled.copiedBuffer("event", StandardCharsets.UTF_8), 3, false));
        assertThat(inFlight.get(), is(1));

        handler.writeAndFlush(new StreamAddressedHttpContent(Unpooled.EMPTY_BUFFER, 3, true));
        assertThat(inFlight.get(), is(0));
        connection.finishAndReleaseAll();
    }

    @Test
    public void directResponseIsReleasedWhenTheExchangeEndsWithoutOne() {
        for (HttpExchangeEndedEvent ended : new HttpExchangeEndedEvent[]{HttpExchangeEndedEvent.INSTANCE, HttpExchangeEndedEvent.RAW_RESPONSE_WRITTEN}) {
            EmbeddedChannel connection = keepAliveConnection();
            AtomicInteger inFlight = new AtomicInteger(0);
            directResponse(connection, inFlight);

            connection.pipeline().fireUserEventTriggered(ended);

            assertThat(ended + " ends the exchange", inFlight.get(), is(0));
            assertThat(watching(connection), is(false));
            connection.finishAndReleaseAll();
        }
    }

    @Test
    public void directResponseIsReleasedOnceWhenTheConnectionAlsoCloses() {
        EmbeddedChannel connection = keepAliveConnection();
        AtomicInteger inFlight = new AtomicInteger(0);
        directResponse(connection, inFlight);
        // a second exchange on the same connection, still in flight, shows a double release
        InFlightRequest.started(counterBackedServer(inFlight));

        connection.pipeline().context("handler").writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        connection.close();

        assertThat(inFlight.get(), is(1));
        connection.finishAndReleaseAll();
    }

    @Test
    public void directResponseOnAClosedConnectionIsReleasedByTheCloseFuture() {
        EmbeddedChannel connection = keepAliveConnection();
        AtomicInteger inFlight = new AtomicInteger(0);
        directResponse(connection, inFlight);

        connection.close();

        assertThat(inFlight.get(), is(0));
    }

    /**
     * Force garbage collection and report whether every referent has been cleared. Retries to
     * tolerate the non-determinism of a single {@link System#gc()} call.
     */
    private static boolean allCleared(List<WeakReference<InFlightRequest>> references) {
        for (int attempt = 0; attempt < 50; attempt++) {
            System.gc();
            if (references.stream().allMatch(reference -> reference.get() == null)) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return references.stream().allMatch(reference -> reference.get() == null);
    }
}
