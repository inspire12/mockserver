package org.mockserver.mock.action.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslClosedEngineException;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.GrpcStreamResponse;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.HttpWebSocketResponse;
import org.mockserver.model.SseEvent;
import org.mockserver.model.WebSocketMessage;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * The write of the second part of a mocked streaming response (a server-sent event, a gRPC stream message, a WebSocket
 * message) fails. A client that has gone is an ordinary end of the response: at most DEBUG, with no stack trace. Any
 * other failure is still a WARN with its cause. Parts carry no delay, so each handler runs on this thread.
 */
public class StreamedResponseWriteFailureLoggingTest {

    private static final String SECOND = "second-part";

    /**
     * A cause of a client that has gone, and how the DEBUG entry names it.
     */
    private record Gone(Supplier<Throwable> cause, String described) {
    }

    private static List<Gone> clientGone() {
        return Arrays.asList(
            new Gone(ClosedChannelException::new, "ClosedChannelException"),
            new Gone(() -> new SslClosedEngineException("SSLEngine closed already"), "SslClosedEngineException: SSLEngine closed already"),
            new Gone(() -> new IOException("Broken pipe"), "IOException: Broken pipe"),
            new Gone(() -> new IOException("Connection reset by peer"), "IOException: Connection reset by peer")
        );
    }

    private static List<Supplier<Throwable>> genuineFailures() {
        return Arrays.asList(
            () -> new EncoderException("unexpected message type"),
            () -> new IllegalStateException("write refused"),
            () -> new EncoderException(new SSLException("bad record mac"))
        );
    }

    @Test
    public void shouldLogAClientThatHasGoneMidSseResponseAtDebugWithoutAStackTrace() {
        for (Gone gone : clientGone()) {
            assertClientGoneLoggedAtDebug(sse(gone.cause().get()), "streaming chunk", gone);
        }
    }

    @Test
    public void shouldWarnForAnyOtherWriteFailureMidSseResponse() {
        for (Supplier<Throwable> cause : genuineFailures()) {
            assertWarned(sse(cause.get()), "async write failure for streaming chunk");
        }
    }

    @Test
    public void shouldLogAClientThatHasGoneMidGrpcStreamAtDebugWithoutAStackTrace() {
        for (Gone gone : clientGone()) {
            assertClientGoneLoggedAtDebug(grpc(gone.cause().get()), "gRPC stream message", gone);
        }
    }

    @Test
    public void shouldWarnForAnyOtherWriteFailureMidGrpcStream() {
        for (Supplier<Throwable> cause : genuineFailures()) {
            assertWarned(grpc(cause.get()), "async write failure for gRPC stream message");
        }
    }

    @Test
    public void shouldLogAClientThatHasGoneMidWebSocketResponseAtDebugWithoutAStackTrace() {
        for (Gone gone : clientGone()) {
            assertClientGoneLoggedAtDebug(webSocket(gone.cause().get()), "WebSocket message", gone);
        }
    }

    @Test
    public void shouldWarnForAnyOtherWriteFailureMidWebSocketResponse() {
        for (Supplier<Throwable> cause : genuineFailures()) {
            assertWarned(webSocket(cause.get()), "async write failure for WebSocket message");
        }
    }

    private static void assertClientGoneLoggedAtDebug(CapturingLogger logger, String part, Gone gone) {
        String described = gone.described();
        assertThat(described + ": nothing at WARN or above", logger.atOrAbove(Level.WARN), is(empty()));
        List<LogEntry> left = logger.entries.stream().filter(entry -> entry.getLogLevel() == Level.DEBUG && entry.getMessage().startsWith("client left before")).collect(Collectors.toList());
        assertThat(described + ": logged at DEBUG", left.size(), is(1));
        assertThat(described + ": names the part and the cause", left.get(0).getMessage(),
            matchesPattern(Pattern.compile("client left before " + part + "\\s+2\\s+was sent:\\s+" + Pattern.quote(described) + "\\s+for request:.*", Pattern.DOTALL)));
        for (LogEntry entry : logger.entries) {
            assertThat(described + ": no stack trace", entry.getThrowable(), nullValue());
        }
    }

    private static void assertWarned(CapturingLogger logger, String message) {
        List<LogEntry> warnings = logger.atOrAbove(Level.WARN);
        assertThat(warnings.size(), is(1));
        assertThat(warnings.get(0).getMessage(), startsWith(message));
        assertThat("with its cause", warnings.get(0).getThrowable() != null, is(true));
        assertThat(logger.at(Level.DEBUG, "client left before"), is(0L));
    }

    private static CapturingLogger sse(Throwable cause) {
        CapturingLogger logger = new CapturingLogger();
        ChannelHandlerContext ctx = context(new EmbeddedChannel(new FailingWrite(cause), new ChannelInboundHandlerAdapter()));
        new HttpSseResponseActionHandler(logger, mock(Scheduler.class), configuration()).handle(
            HttpSseResponse.sseResponse().withEvents(SseEvent.sseEvent().withData("first"), SseEvent.sseEvent().withData(SECOND), SseEvent.sseEvent().withData("third")),
            ctx,
            request("/sse").withKeepAlive(true)
        );
        return logger;
    }

    private static CapturingLogger grpc(Throwable cause) {
        CapturingLogger logger = new CapturingLogger();
        ChannelHandlerContext ctx = context(new EmbeddedChannel(new FailingWrite(cause), new ChannelInboundHandlerAdapter()));
        new GrpcStreamResponseActionHandler(logger, mock(Scheduler.class), mock(GrpcProtoDescriptorStore.class), configuration(), mock(WebSocketClientRegistry.class)).handle(
            GrpcStreamResponse.grpcStreamResponse().withMessages(GrpcStreamMessage.grpcStreamMessage("{\"n\":\"first\"}"), GrpcStreamMessage.grpcStreamMessage("{\"n\":\"" + SECOND + "\"}"), GrpcStreamMessage.grpcStreamMessage("{\"n\":\"third\"}")),
            ctx,
            request("/some.Service/Method")
        );
        return logger;
    }

    private static CapturingLogger webSocket(Throwable cause) {
        CapturingLogger logger = new CapturingLogger();
        ChannelHandlerContext ctx = context(new EmbeddedChannel(new FailingWrite(cause), new HttpServerCodec(), new ChannelInboundHandlerAdapter()));
        new HttpWebSocketResponseActionHandler(logger, mock(Scheduler.class), configuration(), mock(WebSocketClientRegistry.class)).handle(
            HttpWebSocketResponse.webSocketResponse().withMessages(WebSocketMessage.webSocketMessage("first"), WebSocketMessage.webSocketMessage(SECOND), WebSocketMessage.webSocketMessage("third")),
            ctx,
            request("/ws")
                .withMethod("GET")
                .withHeader("Host", "localhost")
                .withHeader("Upgrade", "websocket")
                .withHeader("Connection", "Upgrade")
                .withHeader("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
                .withHeader("Sec-WebSocket-Version", "13")
        );
        assertThat("the handshake and the first message were written", logger.at(Level.WARN, "WebSocket handshake failed"), is(0L));
        return logger;
    }

    private static ChannelHandlerContext context(EmbeddedChannel channel) {
        return channel.pipeline().lastContext();
    }

    /**
     * Fails, with the given cause, every write from the one that carries the second part on, as a write to a client
     * that has gone would.
     */
    private static final class FailingWrite extends ChannelOutboundHandlerAdapter {
        private final Throwable cause;
        private boolean failing;

        FailingWrite(Throwable cause) {
            this.cause = cause;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            failing = failing || carriesSecondPart(msg);
            if (failing) {
                ReferenceCountUtil.release(msg);
                promise.setFailure(cause);
            } else {
                ctx.write(msg, promise);
            }
        }

        private static boolean carriesSecondPart(Object msg) {
            ByteBuf content = msg instanceof ByteBuf ? (ByteBuf) msg : msg instanceof ByteBufHolder ? ((ByteBufHolder) msg).content() : null;
            return content != null && content.toString(StandardCharsets.UTF_8).contains(SECOND);
        }
    }

    private static final class CapturingLogger extends MockServerLogger {
        private final List<LogEntry> entries = new CopyOnWriteArrayList<>();

        CapturingLogger() {
            super(configuration().logLevel("DEBUG"), StreamedResponseWriteFailureLoggingTest.class);
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            if (isEnabledForInstance(logEntry.getLogLevel())) {
                entries.add(logEntry.clone());
            }
        }

        List<LogEntry> atOrAbove(Level level) {
            return entries.stream().filter(entry -> entry.getLogLevel().toInt() >= level.toInt()).collect(Collectors.toList());
        }

        long at(Level level, String messageStart) {
            return entries.stream().filter(entry -> entry.getLogLevel() == level && entry.getMessage().startsWith(messageStart)).count();
        }
    }
}
