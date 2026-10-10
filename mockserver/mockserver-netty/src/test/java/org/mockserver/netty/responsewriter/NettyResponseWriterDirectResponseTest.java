package org.mockserver.netty.responsewriter;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.http.HttpSseResponseActionHandler;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;
import org.mockserver.netty.InFlightRequest;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;
import org.mockserver.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * An SSE response and a pipelined request after it on one HTTP/1.1 connection: the SSE exchange stays in the
 * graceful-shutdown drain until its own stream has been written, whatever happens to the exchange behind it.
 */
public class NettyResponseWriterDirectResponseTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger(NettyResponseWriterDirectResponseTest.class);

    @Test
    public void directResponseStaysInFlightUntilItsOwnStreamEndsWhateverAPipelinedExchangeDoes() {
        // given - two pipelined requests on one connection, the first answered with a stream held open
        HeldChunks heldChunks = new HeldChunks();
        ChannelInboundHandlerAdapter requestHandler = new ChannelInboundHandlerAdapter();
        EmbeddedChannel connection = new EmbeddedChannel(new HttpServerCodec(), heldChunks, requestHandler);
        connection.writeInbound(Unpooled.copiedBuffer(
            "GET /sse HTTP/1.1\r\nHost: localhost\r\n\r\nGET /plain HTTP/1.1\r\nHost: localhost\r\n\r\n", StandardCharsets.US_ASCII));
        ChannelHandlerContext ctx = connection.pipeline().context(requestHandler);
        AtomicInteger inFlight = new AtomicInteger();
        LifeCycle server = counterBackedServer(inFlight);
        NettyResponseWriter sseWriter = new NettyResponseWriter(configuration(), mockServerLogger, ctx, mock(Scheduler.class), tracked(server, connection));
        NettyResponseWriter plainWriter = new NettyResponseWriter(configuration(), mockServerLogger, ctx, mock(Scheduler.class), tracked(server, connection));
        assertThat(inFlight.get(), is(2));

        new HttpSseResponseActionHandler(mockServerLogger, mock(Scheduler.class), configuration()).handle(
            HttpSseResponse.sseResponse().withEvents(SseEvent.sseEvent().withData("first"), SseEvent.sseEvent().withData("last")),
            ctx, request("/sse").withKeepAlive(true), StreamingFormat.SSE, sseWriter.respondingDirectly());

        // when - the exchange behind it is answered, and the connection's oldest exchange is ended with no answer
        plainWriter.writeResponse(request("/plain").withKeepAlive(true), response("plain"), false);
        HttpExchangeEndedEvent.fire(ctx);

        // then - only the answered exchange has left
        assertThat("the stream is still being written", inFlight.get(), is(1));

        // when
        heldChunks.release();

        // then - written whole, then released
        StringBuilder written = new StringBuilder();
        Object outbound;
        while ((outbound = connection.readOutbound()) != null) {
            // the other exchange's response is a model object here: no MockServer codec turns it into HTTP
            if (outbound instanceof ByteBuf) {
                written.append(((ByteBuf) outbound).toString(StandardCharsets.US_ASCII));
            }
            ReferenceCountUtil.release(outbound);
        }
        assertThat(written.toString(), containsString("data: last"));
        assertThat(inFlight.get(), is(0));
        assertThat(connection.isOpen(), is(true));
        connection.finishAndReleaseAll();
    }

    private static InFlightRequest tracked(LifeCycle server, EmbeddedChannel connection) {
        InFlightRequest inFlightRequest = InFlightRequest.started(server);
        inFlightRequest.trackConnectionClose(connection);
        return inFlightRequest;
    }

    private static LifeCycle counterBackedServer(AtomicInteger inFlight) {
        LifeCycle server = mock(LifeCycle.class);
        doAnswer(invocation -> inFlight.incrementAndGet()).when(server).requestProcessingStarted();
        doAnswer(invocation -> inFlight.decrementAndGet()).when(server).requestProcessingComplete();
        return server;
    }

    /**
     * Holds the stream's content chunks, leaving their writes incomplete so the stream stays open, until released.
     */
    private static final class HeldChunks extends ChannelOutboundHandlerAdapter {

        private final List<Object> messages = new ArrayList<>();
        private final List<ChannelPromise> promises = new ArrayList<>();
        private ChannelHandlerContext ctx;
        private boolean holding = true;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (holding && msg instanceof HttpContent && !(msg instanceof LastHttpContent) && !(msg instanceof HttpMessage)) {
                messages.add(msg);
                promises.add(promise);
            } else {
                ctx.write(msg, promise);
            }
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
