package org.mockserver.dashboard;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * A dashboard whose client reads slower than updates are produced must not queue update after update
 * on its connection: each queued frame holds its full size in memory until the client takes it. While
 * the connection is not writable the update waits, and the latest state is sent once it drains.
 */
public class DashboardWebSocketBackpressureTest {

    private final List<Scheduler> schedulers = new ArrayList<>();
    private final List<HttpState> httpStates = new ArrayList<>();
    private final List<DashboardWebSocketHandler> handlers = new ArrayList<>();
    private final List<FrameCapturingChannel> channels = new ArrayList<>();

    // Captures the frames the handler writes, and becomes unwritable for real (bytes pending past the
    // high water mark) until flushed, as a connection whose client has stopped reading does.
    private static final class FrameCapturingChannel extends EmbeddedChannel {
        private final List<TextWebSocketFrame> frames = new CopyOnWriteArrayList<>();

        private FrameCapturingChannel() {
            config().setWriteBufferWaterMark(new WriteBufferWaterMark(1, 2));
        }

        @Override
        public ChannelFuture writeAndFlush(Object msg) {
            if (msg instanceof TextWebSocketFrame) {
                frames.add((TextWebSocketFrame) msg);
            }
            return newSucceededFuture();
        }

        private void stopReading() {
            write(Unpooled.buffer(64).writeZero(64));
            assertThat("channel is unwritable while bytes wait for the client", isWritable(), is(false));
        }

        private void clientCatchesUp() {
            flush();
            assertThat("channel is writable once the client has taken the bytes", isWritable(), is(true));
        }
    }

    @After
    public void tearDown() {
        for (DashboardWebSocketHandler handler : handlers) {
            shutdown(handler, "scheduler");
            shutdown(handler, "throttleExecutorService");
        }
        for (FrameCapturingChannel channel : channels) {
            for (TextWebSocketFrame frame : channel.frames) {
                frame.release();
            }
            channel.finishAndReleaseAll();
        }
        for (HttpState httpState : httpStates) {
            httpState.stop();
        }
        for (Scheduler scheduler : schedulers) {
            scheduler.shutdown();
        }
    }

    @Test
    public void shouldNotWriteAnUpdateWhileTheConnectionIsUnwritableAndSendItOnceItDrains() throws Exception {
        DashboardWebSocketHandler handler = newHandler();
        FrameCapturingChannel channel = newChannel();
        // unwritable before it is registered, so the update registration triggers cannot reach it first
        channel.stopReading();
        // registered, as every upgraded dashboard connection is
        handler.registerClient(channel);

        // updates keep arriving for longer than the one-second write permit refill
        long until = System.currentTimeMillis() + 2500;
        while (System.currentTimeMillis() < until) {
            handler.sendUpdate(channel, request());
            Thread.sleep(250);
        }
        assertThat("frames written while the client was not reading", channel.frames.size(), is(0));

        channel.clientCatchesUp();
        long deadline = System.currentTimeMillis() + 10_000;
        while (channel.frames.isEmpty() && System.currentTimeMillis() < deadline) {
            handler.sendUpdate(channel, request());
            Thread.sleep(250);
        }
        assertThat("an update is sent once the connection drains", channel.frames.isEmpty(), is(false));
    }

    @Test
    public void shouldDeliverADeferredUpdateOnceTheConnectionDrainsWithoutAnotherUpdate() throws Exception {
        DashboardWebSocketHandler handler = newHandler();
        FrameCapturingChannel channel = newChannel();
        channel.stopReading();
        handler.registerClient(channel);

        handler.sendUpdate(channel, request());
        // past at least two refill ticks, each of which retries deferred updates
        Thread.sleep(2500);
        assertThat("frames written while the client was not reading", channel.frames.size(), is(0));

        channel.clientCatchesUp();
        long deadline = System.currentTimeMillis() + 10_000;
        while (channel.frames.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat("the deferred update is sent without another update arriving", channel.frames.isEmpty(), is(false));
    }

    @Test
    public void shouldKeepTheWritePermitWhileTheOnlyPendingConnectionIsDraining() throws Exception {
        DashboardWebSocketHandler handler = newHandler();
        FrameCapturingChannel channel = newChannel();
        channel.stopReading();
        handler.registerClient(channel);

        handler.sendUpdate(channel, request());
        // past at least two refill ticks
        Thread.sleep(2500);

        assertThat("the refill tick must not spend the permit on a connection it cannot write to", writePermits(handler), is(1));
        assertThat(channel.frames.size(), is(0));
    }

    @Test
    public void shouldServeAWritablePendingConnectionWithoutWritingToAnUnwritableOne() throws Exception {
        DashboardWebSocketHandler handler = newHandler();
        FrameCapturingChannel reading = newChannel();
        FrameCapturingChannel stalled = newChannel();
        stalled.stopReading();
        handler.registerClient(reading);
        handler.registerClient(stalled);
        // the post-registration update leaves the stalled connection pending through every later tick
        Thread.sleep(1200);
        int before = reading.frames.size();

        // both connections have updates waiting; the ticks serve the writable one
        handler.sendUpdate(stalled, request());
        handler.sendUpdate(reading, request());
        handler.sendUpdate(reading, request());
        long deadline = System.currentTimeMillis() + 10_000;
        while (reading.frames.size() <= before && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        Thread.sleep(1500); // at least one more tick with the stalled connection still pending

        assertThat("the writable connection was served", reading.frames.size() > before, is(true));
        assertThat("nothing was written to the connection that is not reading", stalled.frames.size(), is(0));
    }

    private static int writePermits(DashboardWebSocketHandler handler) throws ReflectiveOperationException {
        Field field = DashboardWebSocketHandler.class.getDeclaredField("semaphore");
        field.setAccessible(true);
        return ((java.util.concurrent.Semaphore) field.get(handler)).availablePermits();
    }

    private DashboardWebSocketHandler newHandler() {
        MockServerLogger mockServerLogger = new MockServerLogger(DashboardWebSocketBackpressureTest.class);
        Configuration configuration = configuration();
        Scheduler scheduler = new Scheduler(configuration, mockServerLogger, true);
        schedulers.add(scheduler);
        HttpState httpState = new HttpState(configuration, mockServerLogger, scheduler);
        httpStates.add(httpState);
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, false, false).registerListeners();
        handlers.add(handler);
        return handler;
    }

    private FrameCapturingChannel newChannel() {
        FrameCapturingChannel channel = new FrameCapturingChannel();
        channels.add(channel);
        return channel;
    }

    private static void shutdown(DashboardWebSocketHandler handler, String fieldName) {
        try {
            Field field = DashboardWebSocketHandler.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object executor = field.get(handler);
            if (executor instanceof ExecutorService) {
                ((ExecutorService) executor).shutdownNow();
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not shut down DashboardWebSocketHandler." + fieldName, e);
        }
    }
}
