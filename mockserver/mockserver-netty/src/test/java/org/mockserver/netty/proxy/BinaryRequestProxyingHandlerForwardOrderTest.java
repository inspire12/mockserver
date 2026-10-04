package org.mockserver.netty.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.BinaryMessage;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.model.BinaryMessage.bytes;

/**
 * With {@code forwardBinaryRequestsWithoutWaitingForResponse} a client can send its next message before the
 * previous one has been forwarded. Each message goes upstream on a connection of its own, so the next forward
 * must not start until the previous one has been written, or the upstream could accept them in either order.
 */
public class BinaryRequestProxyingHandlerForwardOrderTest {

    // the socket timeout bounds how long the inline scheduler waits for a response that is never relayed
    private final Configuration configuration = configuration().forwardBinaryRequestsWithoutWaitingForResponse(true).maxSocketTimeoutInMillis(2_000L);
    private final NettyHttpClient httpClient = mock(NettyHttpClient.class);
    private final MockServerLogger mockServerLogger = mock(MockServerLogger.class);
    private final List<String> forwardsStarted = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> requestSent = new CopyOnWriteArrayList<>();
    private final List<CompletableFuture<BinaryMessage>> upstreamResponses = new CopyOnWriteArrayList<>();
    private final List<Thread> forwardStartedOn = new CopyOnWriteArrayList<>();

    /**
     * @param upstream what the forward client does with each message, by its position (0 for the first)
     */
    private void upstream(Function<Integer, CompletableFuture<BinaryMessage>> upstream) {
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        when(httpClient.sendRequest(any(BinaryMessage.class), anyBoolean(), any(InetSocketAddress.class), any(), any()))
            .thenAnswer(invocation -> {
                forwardStartedOn.add(Thread.currentThread());
                requestSent.add(invocation.getArgument(4));
                forwardsStarted.add(new String(invocation.<BinaryMessage>getArgument(0).getBytes(), StandardCharsets.UTF_8));
                CompletableFuture<BinaryMessage> response = upstream.apply(forwardsStarted.size() - 1);
                upstreamResponses.add(response);
                return response;
            });
    }

    private static CompletableFuture<BinaryMessage> noResponseYet(int position) {
        return new CompletableFuture<>();
    }

    private EmbeddedChannel clientConnection(Scheduler scheduler) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(REMOTE_SOCKET).set(new InetSocketAddress("127.0.0.1", 1234));
        channel.pipeline().addLast(new BinaryRequestProxyingHandler(configuration, mockServerLogger, scheduler, httpClient, mock(HttpState.class)));
        return channel;
    }

    private EmbeddedChannel clientConnection() {
        return clientConnection(mock(Scheduler.class));
    }

    /** The real scheduler in its synchronous mode: handles a response on the calling thread, waiting for it if need be. */
    private Scheduler inlineScheduler() {
        return new Scheduler(configuration, mockServerLogger, true);
    }

    /** Handles each response when it completes, without waiting for it, as the real scheduler does on its own threads. */
    @SuppressWarnings("unchecked")
    private static Scheduler schedulerRunningOnCompletion() {
        Scheduler scheduler = mock(Scheduler.class);
        doAnswer(invocation -> {
            Runnable handleResponse = invocation.getArgument(1);
            invocation.<CompletableFuture<BinaryMessage>>getArgument(0).whenComplete((response, throwable) -> handleResponse.run());
            return null;
        }).when(scheduler).submit(any(CompletableFuture.class), any(Runnable.class), anyBoolean());
        return scheduler;
    }

    private static void clientSends(EmbeddedChannel channel, String message) {
        channel.writeInbound(Unpooled.copiedBuffer(message, StandardCharsets.UTF_8));
    }

    private List<LogEntry> logged(Level level) {
        ArgumentCaptor<LogEntry> entries = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, atLeast(0)).logEvent(entries.capture());
        return entries.getAllValues().stream().filter(entry -> entry.getLogLevel() == level).collect(Collectors.toList());
    }

    @Test
    public void shouldStartTheNextForwardOnlyOnceThePreviousHasBeenSent() {
        upstream(BinaryRequestProxyingHandlerForwardOrderTest::noResponseYet);
        EmbeddedChannel channel = clientConnection();

        clientSends(channel, "first");
        clientSends(channel, "second");
        clientSends(channel, "third");
        assertThat("only the first is forwarded while it has not been sent", forwardsStarted, contains("first"));

        requestSent.get(0).accept(null);
        assertThat("the second follows once the first has been sent", forwardsStarted, contains("first", "second"));

        requestSent.get(1).accept(null);
        assertThat("and the third once the second has", forwardsStarted, contains("first", "second", "third"));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldForwardEveryMessageOfAClientThatHasSentAndClosed() {
        upstream(BinaryRequestProxyingHandlerForwardOrderTest::noResponseYet);
        EmbeddedChannel channel = clientConnection();

        clientSends(channel, "first");
        clientSends(channel, "second");
        channel.close();
        requestSent.get(0).accept(null);

        assertThat(forwardsStarted, contains("first", "second"));
    }

    @Test
    public void shouldNotHoldBackAnotherConnectionsForward() {
        upstream(BinaryRequestProxyingHandlerForwardOrderTest::noResponseYet);
        EmbeddedChannel oneClient = clientConnection();
        EmbeddedChannel anotherClient = clientConnection();

        clientSends(oneClient, "one client's first");
        clientSends(oneClient, "one client's second");
        clientSends(anotherClient, "another client's first");

        assertThat(forwardsStarted, contains("one client's first", "another client's first"));

        oneClient.finishAndReleaseAll();
        anotherClient.finishAndReleaseAll();
    }

    @Test
    public void shouldNotAttemptTheMessagesQueuedBehindAForwardThatWasNotSent() {
        upstream(BinaryRequestProxyingHandlerForwardOrderTest::noResponseYet);
        EmbeddedChannel channel = clientConnection(schedulerRunningOnCompletion());

        clientSends(channel, "first");
        clientSends(channel, "second");
        clientSends(channel, "third");
        requestSent.get(0).accept(new IOException("connection refused"));

        assertThat("the upstream could not be reached, so the queued messages are not tried one by one", forwardsStarted, contains("first"));
        List<LogEntry> warnings = logged(Level.WARN);
        assertThat("one for the forward that failed, one for the two not attempted", warnings, hasSize(2));
        assertThat(warnings.get(0).getArguments()[0], is((Object) "java.io.IOException: connection refused"));
        assertThat(warnings.get(1).getMessageFormat(), containsString("not forwarding"));
        assertThat(warnings.get(1).getArguments()[0], is((Object) 2));
        assertThat(channel.isOpen(), is(false));
    }

    @Test
    public void shouldCloseTheConnectionWhenTheRequestIsNotWritten() {
        // the upstream's response future is left to complete empty, as it does when its connection then closes
        upstream(BinaryRequestProxyingHandlerForwardOrderTest::noResponseYet);
        EmbeddedChannel channel = clientConnection(schedulerRunningOnCompletion());

        clientSends(channel, "the only message");
        requestSent.get(0).accept(new IOException("broken pipe"));

        assertThat("with nothing queued behind it, the client is still told by its connection closing", channel.isOpen(), is(false));
        List<LogEntry> warnings = logged(Level.WARN);
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0).getMessageFormat(), containsString("closing connection"));
        assertThat("the cause is logged", warnings.get(0).getArguments()[0], is((Object) "java.io.IOException: broken pipe"));
    }

    @Test
    public void shouldNotAttemptTheMessagesQueuedBehindAForwardWhoseResponseFailsLater() {
        upstream(BinaryRequestProxyingHandlerForwardOrderTest::noResponseYet);
        EmbeddedChannel channel = clientConnection();

        clientSends(channel, "first");
        clientSends(channel, "second");
        clientSends(channel, "third");
        requestSent.get(0).accept(null);
        upstreamResponses.get(0).completeExceptionally(new IOException("connection reset"));
        requestSent.get(1).accept(null);

        assertThat("the first failed after it was sent, while the second was in flight", forwardsStarted, contains("first", "second"));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldStartTheNextForwardOnTheConnectionsEventLoopWhenTheClientReportsFromAnotherThread() throws Exception {
        upstream(BinaryRequestProxyingHandlerForwardOrderTest::noResponseYet);
        EventLoopGroup group = new DefaultEventLoopGroup(1);
        LocalAddress address = new LocalAddress("binary-forward-order-" + System.nanoTime());
        BinaryRequestProxyingHandler handler = new BinaryRequestProxyingHandler(configuration, mockServerLogger, mock(Scheduler.class), httpClient, mock(HttpState.class));
        try {
            Channel server = new ServerBootstrap()
                .group(group)
                .channel(LocalServerChannel.class)
                .childAttr(REMOTE_SOCKET, new InetSocketAddress("127.0.0.1", 1234))
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    protected void initChannel(LocalChannel channel) {
                        channel.pipeline().addLast(handler);
                    }
                })
                .bind(address).sync().channel();
            Channel client = new Bootstrap()
                .group(group)
                .channel(LocalChannel.class)
                .handler(new ChannelInboundHandlerAdapter())
                .connect(address).sync().channel();
            client.writeAndFlush(Unpooled.copiedBuffer("first", StandardCharsets.UTF_8)).sync();
            client.writeAndFlush(Unpooled.copiedBuffer("second", StandardCharsets.UTF_8)).sync();
            awaitForwardsStarted(1);
            // the second message has been read and is queued once the event loop has run a task queued after it
            group.next().submit(() -> { }).sync();
            group.next().submit(() -> { }).sync();

            // the forward client reports from its own thread, here the test's
            requestSent.get(0).accept(null);
            awaitForwardsStarted(2);

            assertThat(forwardsStarted, contains("first", "second"));
            assertThat("the queue is only ever used on the connection's event loop", forwardStartedOn.get(1), is(forwardStartedOn.get(0)));
            assertThat(forwardStartedOn.get(1) == Thread.currentThread(), is(false));

            client.close().sync();
            server.close().sync();
        } finally {
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    private void awaitForwardsStarted(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (forwardsStarted.size() < count && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertThat("forwards started within 10 seconds", forwardsStarted.size() >= count, is(true));
    }

    @Test
    public void shouldNotAttemptTheMessagesQueuedBehindAForwardThatCouldNotBeStarted() {
        upstream(position -> {
            if (position == 1) {
                throw new IllegalStateException("the forward cannot be started");
            }
            return new CompletableFuture<>();
        });
        EmbeddedChannel channel = clientConnection(schedulerRunningOnCompletion());

        clientSends(channel, "first");
        clientSends(channel, "second");
        clientSends(channel, "third");
        clientSends(channel, "fourth");
        requestSent.get(0).accept(null);

        assertThat(forwardsStarted, contains("first", "second"));
        List<LogEntry> warnings = logged(Level.WARN);
        assertThat("one for the forward that failed, one for the two not attempted", warnings, hasSize(2));
        assertThat(warnings.get(0).getArguments()[0], is((Object) "java.lang.IllegalStateException: the forward cannot be started"));
        assertThat(warnings.get(1).getMessageFormat(), containsString("not forwarding"));
        assertThat(warnings.get(1).getArguments()[0], is((Object) 2));
        assertThat(channel.isOpen(), is(false));
    }

    @Test
    public void shouldStartALongQueueWithoutRecursing() {
        int queued = 20_000;
        upstream(BinaryRequestProxyingHandlerForwardOrderTest::noResponseYet);
        EmbeddedChannel channel = clientConnection();
        clientSends(channel, "first");
        for (int i = 0; i < queued; i++) {
            clientSends(channel, "queued");
        }
        // every later forward is reported sent from inside sendRequest, as a client completing at once would
        when(httpClient.sendRequest(any(BinaryMessage.class), anyBoolean(), any(InetSocketAddress.class), any(), any()))
            .thenAnswer(invocation -> {
                forwardsStarted.add("queued");
                invocation.<Consumer<Throwable>>getArgument(4).accept(null);
                return new CompletableFuture<BinaryMessage>();
            });

        requestSent.get(0).accept(null);

        assertThat(forwardsStarted, hasSize(queued + 1));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldWriteTheUpstreamsResponseToTheClient() {
        upstream(position -> CompletableFuture.completedFuture(bytes("the response".getBytes(StandardCharsets.UTF_8))));
        EmbeddedChannel channel = clientConnection(inlineScheduler());

        clientSends(channel, "a request");

        ByteBuf written = channel.readOutbound();
        assertThat(written.toString(StandardCharsets.UTF_8), is("the response"));
        written.release();
        assertThat(channel.isOpen(), is(true));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldWriteNothingWhenTheUpstreamClosesWithoutAResponse() {
        upstream(position -> CompletableFuture.completedFuture(null));
        EmbeddedChannel channel = clientConnection(inlineScheduler());

        clientSends(channel, "a request");

        assertThat(channel.<ByteBuf>readOutbound(), is(nullValue()));
        assertThat(channel.isOpen(), is(true));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldCloseTheConnectionWhenTheForwardFails() {
        CompletableFuture<BinaryMessage> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IOException("connection refused"));
        upstream(position -> failed);
        EmbeddedChannel channel = clientConnection(inlineScheduler());

        clientSends(channel, "a request");

        assertThat("the client is told by its connection closing", channel.isOpen(), is(false));
        List<LogEntry> warnings = logged(Level.WARN);
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0).getMessageFormat(), containsString("closing connection"));
        assertThat("the upstream's own failure is what is reported", warnings.get(0).getArguments()[0], is((Object) "java.io.IOException: connection refused"));
    }

    @Test
    public void shouldCloseTheConnectionWhenTheForwardCannotBeStarted() {
        upstream(position -> {
            throw new IllegalStateException("the forward cannot be started");
        });
        EmbeddedChannel channel = clientConnection(inlineScheduler());

        clientSends(channel, "a request");

        assertThat("the client is told by its connection closing", channel.isOpen(), is(false));
        List<LogEntry> warnings = logged(Level.WARN);
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0).getMessageFormat(), containsString("closing connection"));
        assertThat("the reason it could not be started is what is reported", warnings.get(0).getArguments()[0], is((Object) "java.lang.IllegalStateException: the forward cannot be started"));
    }
}
