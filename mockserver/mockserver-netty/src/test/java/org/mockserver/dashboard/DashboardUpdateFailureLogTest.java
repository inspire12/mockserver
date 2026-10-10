package org.mockserver.dashboard;

import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;

/**
 * An update that fails to build or send used to vanish: the send runs as a task on an executor whose
 * FutureTask swallows what it throws, and a failed walk was logged only as a generic query failure. It
 * must be logged with what to do about it, and no more than once a minute however often it fails.
 */
public class DashboardUpdateFailureLogTest {

    private final List<Scheduler> schedulers = new ArrayList<>();
    private final List<HttpState> httpStates = new ArrayList<>();
    private final List<DashboardWebSocketHandler> handlers = new ArrayList<>();
    private final List<FrameCapturingChannel> channels = new ArrayList<>();

    public static class ExplodingArgument {
        public String getValue() {
            throw new OutOfMemoryError("simulated: frame too large");
        }
    }

    // Fails the walk on demand: the dashboard's row mapping reads this setting for every row it builds.
    private static final class FailingConfiguration extends Configuration {
        private volatile boolean failWalk;

        @Override
        public Boolean redactSecretsInLog() {
            if (failWalk) {
                throw new IllegalStateException("simulated: walk failed");
            }
            return super.redactSecretsInLog();
        }
    }

    private static final class FrameCapturingChannel extends EmbeddedChannel {
        private final List<TextWebSocketFrame> frames = new CopyOnWriteArrayList<>();

        @Override
        public ChannelFuture writeAndFlush(Object msg) {
            if (msg instanceof TextWebSocketFrame) {
                frames.add((TextWebSocketFrame) msg);
            }
            return newSucceededFuture();
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
    public void shouldLogAnOutOfMemoryErrorBuildingTheFrameOnceWithARemedy() throws Exception {
        FailingConfiguration configuration = new FailingConfiguration();
        HttpState httpState = newHttpState(configuration);
        seed(httpState, new LogEntry()
            .setLogLevel(Level.INFO)
            .setMessageFormat("argument that cannot be serialised:{}")
            .setArguments(new ExplodingArgument()));
        DashboardWebSocketHandler handler = newHandler(httpState);
        FrameCapturingChannel channel = newChannel();

        // three failing updates, one per write permit
        for (int i = 0; i < 3; i++) {
            handler.sendUpdate(channel, request());
            Thread.sleep(1100);
        }

        List<String> logged = awaitFailureLogs(httpState, 1);
        assertThat("no frame was sent", channel.frames.size(), is(0));
        assertThat("logged once however often it failed", logged.size(), is(1));
        assertThat(logged.get(0), containsString("ran out of memory"));
        assertThat(logged.get(0), containsString("PUT /mockserver/clear?type=log"));
        assertThat(logged.get(0), containsString("simulated: frame too large"));

        // the failure does not stop a later update that can be built
        httpState.getMockServerLog().reset();
        long deadline = System.currentTimeMillis() + 10_000;
        while (channel.frames.isEmpty() && System.currentTimeMillis() < deadline) {
            handler.sendUpdate(channel, request());
            Thread.sleep(250);
        }
        assertThat("a later update is still sent", channel.frames.isEmpty(), is(false));
    }

    @Test
    public void shouldLogAFailedWalk() throws Exception {
        FailingConfiguration configuration = new FailingConfiguration();
        HttpState httpState = newHttpState(configuration);
        seed(httpState, new LogEntry()
            .setLogLevel(Level.INFO)
            .setHttpRequest(request("/walk"))
            .setMessageFormat("received request:{}")
            .setArguments(request("/walk")));
        DashboardWebSocketHandler handler = newHandler(httpState);
        FrameCapturingChannel channel = newChannel();

        configuration.failWalk = true;
        handler.sendUpdate(channel, request());
        List<String> logged = awaitFailureLogs(httpState, 1);
        configuration.failWalk = false;

        assertThat("no frame was sent", channel.frames.size(), is(0));
        assertThat(logged.size(), is(1));
        assertThat(logged.get(0), containsString("building or sending it failed"));
        assertThat(logged.get(0), containsString("simulated: walk failed"));
    }

    @Test
    public void shouldLogRepeatedFailuresAtMostOnceAMinute() throws Exception {
        HttpState httpState = newHttpState(new FailingConfiguration());
        DashboardWebSocketHandler handler = newHandler(httpState);

        handler.reportUpdateFailure(new IllegalStateException("first"));
        handler.reportUpdateFailure(new IllegalStateException("second"));
        handler.reportUpdateFailure(new OutOfMemoryError("third"));

        List<String> logged = awaitFailureLogs(httpState, 1);
        Thread.sleep(500);
        logged = failureLogs(httpState);
        assertThat(logged.size(), is(1));
        assertThat(logged.get(0), containsString("first"));
    }

    private static List<String> awaitFailureLogs(HttpState httpState, int atLeast) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        List<String> logged = failureLogs(httpState);
        while (logged.size() < atLeast && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            logged = failureLogs(httpState);
        }
        return logged;
    }

    private static List<String> failureLogs(HttpState httpState) throws Exception {
        CompletableFuture<List<LogEntry>> entries = new CompletableFuture<>();
        httpState.getMockServerLog().retrieveMessageLogEntries(null, entries::complete);
        return entries.get(10, SECONDS)
            .stream()
            .filter(entry -> entry.getLogLevel() == Level.ERROR)
            .filter(entry -> entry.getMessageFormat() != null && entry.getMessageFormat().startsWith("dashboard update was not sent"))
            .map(LogEntry::getMessage)
            .collect(Collectors.toList());
    }

    private static void seed(HttpState httpState, LogEntry entry) throws Exception {
        httpState.getMockServerLog().add(entry);
        CompletableFuture<Integer> recorded = new CompletableFuture<>();
        httpState.getMockServerLog().retrieveMessageLogEntries(null, logEntries -> recorded.complete(logEntries.size()));
        assertThat("the seeded entry is recorded", recorded.get(10, SECONDS) >= 1, is(true));
    }

    private HttpState newHttpState(Configuration configuration) {
        configuration.disableSystemOut(true);
        MockServerLogger mockServerLogger = new MockServerLogger(DashboardUpdateFailureLogTest.class);
        Scheduler scheduler = new Scheduler(configuration, mockServerLogger, true);
        schedulers.add(scheduler);
        HttpState httpState = new HttpState(configuration, mockServerLogger, scheduler);
        httpStates.add(httpState);
        return httpState;
    }

    private DashboardWebSocketHandler newHandler(HttpState httpState) {
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
