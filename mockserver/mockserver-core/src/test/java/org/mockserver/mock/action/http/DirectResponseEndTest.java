package org.mockserver.mock.action.http;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.GrpcStreamResponse;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.HttpWebSocketResponse;
import org.mockserver.model.SseEvent;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;
import org.mockserver.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;

/**
 * Each handler that writes a response straight to the channel runs the end it was given once its own response has
 * been written, and not before: an HTTP/1.1 connection can carry pipelined exchanges, so the end of another exchange
 * on the same connection must not stand in for it.
 */
public class DirectResponseEndTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger(DirectResponseEndTest.class);

    @Test
    public void sseResponseEndsWhenItsLastPartIsWrittenNotWhenAnotherExchangeOnTheConnectionEnds() {
        // given - the first event's write is held, so the stream stays open
        HeldWrites held = new HeldWrites(msg -> msg instanceof HttpContent && !(msg instanceof LastHttpContent) && !(msg instanceof HttpMessage));
        ChannelInboundHandlerAdapter handler = new ChannelInboundHandlerAdapter();
        EmbeddedChannel connection = new EmbeddedChannel(held, handler);
        ChannelHandlerContext ctx = connection.pipeline().context(handler);
        AtomicInteger ended = new AtomicInteger();
        new HttpSseResponseActionHandler(mockServerLogger, mock(Scheduler.class), configuration()).handle(
            HttpSseResponse.sseResponse().withEvents(SseEvent.sseEvent().withData("first"), SseEvent.sseEvent().withData("last")),
            ctx, request("/sse").withKeepAlive(true), StreamingFormat.SSE, ended::incrementAndGet);
        assertThat(held.size(), is(1));

        // when - a pipelined exchange on the same connection is answered, and another ends without an answer
        ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        connection.pipeline().fireUserEventTriggered(HttpExchangeEndedEvent.INSTANCE);

        // then
        assertThat("the stream is still being written", ended.get(), is(0));

        // when
        held.release();

        // then
        assertThat(ended.get(), is(1));
        assertThat(connection.isOpen(), is(true));
        connection.finishAndReleaseAll();
    }

    @Test
    public void grpcStreamResponseEndsWhenItsTrailersHaveBeenWritten() {
        // given
        HeldWrites held = new HeldWrites(msg -> msg instanceof LastHttpContent);
        ChannelInboundHandlerAdapter handler = new ChannelInboundHandlerAdapter();
        EmbeddedChannel connection = new EmbeddedChannel(held, handler);
        AtomicInteger ended = new AtomicInteger();

        // when
        new GrpcStreamResponseActionHandler(mockServerLogger, mock(Scheduler.class), mock(GrpcProtoDescriptorStore.class), configuration(), mock(WebSocketClientRegistry.class))
            .handle(GrpcStreamResponse.grpcStreamResponse().withStatusName("OK"), connection.pipeline().context(handler), request("/some.Service/Method"), ended::incrementAndGet);

        // then
        assertThat(held.size(), is(1));
        assertThat("the trailers are not yet written", ended.get(), is(0));

        // when
        held.release();

        // then
        assertThat(ended.get(), is(1));
        connection.finishAndReleaseAll();
    }

    @Test
    public void webSocketResponseEndsWhenItsHandshakeResponseHasBeenWritten() {
        // given
        HeldWrites held = new HeldWrites(msg -> msg instanceof FullHttpResponse);
        ChannelInboundHandlerAdapter handler = new ChannelInboundHandlerAdapter();
        EmbeddedChannel connection = new EmbeddedChannel(new HttpServerCodec(), held, handler);
        AtomicInteger ended = new AtomicInteger();

        // when
        new HttpWebSocketResponseActionHandler(mockServerLogger, mock(Scheduler.class), configuration(), null).handle(
            HttpWebSocketResponse.webSocketResponse(),
            connection.pipeline().context(handler),
            request("/ws")
                .withMethod("GET")
                .withHeader("Host", "localhost")
                .withHeader("Upgrade", "websocket")
                .withHeader("Connection", "Upgrade")
                .withHeader("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
                .withHeader("Sec-WebSocket-Version", "13"),
            ended::incrementAndGet);

        // then
        assertThat(held.size(), is(1));
        assertThat("the 101 is not yet written", ended.get(), is(0));

        // when
        held.release();

        // then
        assertThat(ended.get(), is(1));
        connection.finishAndReleaseAll();
    }

    @Test
    public void errorWithResponseBytesEndsWhenTheBytesHaveBeenWritten() {
        // given - beneath the codec, where the raw bytes are written
        HeldWrites held = new HeldWrites(msg -> msg instanceof ByteBuf && ((ByteBuf) msg).isReadable());
        ChannelInboundHandlerAdapter handler = new ChannelInboundHandlerAdapter();
        EmbeddedChannel connection = new EmbeddedChannel(held, new HttpServerCodec(), handler);
        AtomicInteger ended = new AtomicInteger();

        // when
        new HttpErrorActionHandler().handle(error().withResponseBytes("raw".getBytes(StandardCharsets.US_ASCII)), request("/raw"), connection.pipeline().context(handler), ended::incrementAndGet);

        // then
        assertThat(held.size(), is(1));
        assertThat("the bytes are not yet written", ended.get(), is(0));

        // when
        held.release();

        // then
        assertThat(ended.get(), is(1));
        assertThat(connection.isOpen(), is(true));
        connection.finishAndReleaseAll();
    }

    @Test
    public void errorThatWritesNothingEndsAtOnceLeavingTheConnectionOpen() {
        // given
        ChannelInboundHandlerAdapter handler = new ChannelInboundHandlerAdapter();
        EmbeddedChannel connection = new EmbeddedChannel(new HttpServerCodec(), handler);
        AtomicInteger ended = new AtomicInteger();

        // when
        new HttpErrorActionHandler().handle(error(), request("/nothing"), connection.pipeline().context(handler), ended::incrementAndGet);

        // then
        assertThat(ended.get(), is(1));
        assertThat(connection.isOpen(), is(true));
        connection.finishAndReleaseAll();
    }

    @Test
    public void errorThatDropsTheConnectionEndsOnceItIsClosed() {
        // given
        ChannelInboundHandlerAdapter handler = new ChannelInboundHandlerAdapter();
        EmbeddedChannel connection = new EmbeddedChannel(new HttpServerCodec(), handler);
        AtomicInteger ended = new AtomicInteger();
        List<Boolean> openWhenEnded = new ArrayList<>();

        // when
        new HttpErrorActionHandler().handle(error().withDropConnection(true), request("/drop"), connection.pipeline().context(handler), () -> {
            ended.incrementAndGet();
            openWhenEnded.add(connection.isOpen());
        });

        // then
        assertThat(ended.get(), is(1));
        assertThat(openWhenEnded.get(0), is(false));
        connection.finishAndReleaseAll();
    }

    /**
     * Holds the writes it is told to, leaving their promises incomplete, until released; passes the rest on.
     */
    private static final class HeldWrites extends ChannelOutboundHandlerAdapter {

        private final Predicate<Object> toHold;
        private final List<Object> messages = new ArrayList<>();
        private final List<ChannelPromise> promises = new ArrayList<>();
        private ChannelHandlerContext ctx;
        private boolean holding = true;

        private HeldWrites(Predicate<Object> toHold) {
            this.toHold = toHold;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (holding && toHold.test(msg)) {
                messages.add(msg);
                promises.add(promise);
            } else {
                ctx.write(msg, promise);
            }
        }

        int size() {
            return messages.size();
        }

        void release() {
            holding = false;
            // taken out first: completing a write can close the channel, which removes this handler
            List<Object> releasedMessages = new ArrayList<>(messages);
            List<ChannelPromise> releasedPromises = new ArrayList<>(promises);
            messages.clear();
            promises.clear();
            for (int i = 0; i < releasedMessages.size(); i++) {
                ctx.writeAndFlush(releasedMessages.get(i), releasedPromises.get(i));
            }
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            messages.forEach(ReferenceCountUtil::release);
        }
    }
}
