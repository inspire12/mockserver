package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.StreamingBody;

import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class StreamIdleTimeoutHandlerTest {

    @Test
    public void shouldIgnoreAnIdleEventWhileReadsAreWithheldForTheClient() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new StreamIdleTimeoutHandler(new MockServerLogger(), 1, () -> true), recorder(failure));

        channel.pipeline().fireUserEventTriggered(IdleStateEvent.ALL_IDLE_STATE_EVENT);

        assertThat(channel.isOpen(), is(true));
        assertThat(failure.get(), is(nullValue()));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldAbortTheStreamWhenIdleWhileReading() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new StreamIdleTimeoutHandler(new MockServerLogger(), 1, () -> false), recorder(failure));

        channel.pipeline().fireUserEventTriggered(IdleStateEvent.ALL_IDLE_STATE_EVENT);

        assertThat("the stream fails as aborted, so the client's response ends incomplete", failure.get(), instanceOf(StreamingBody.IdleTimeoutException.class));
        assertThat(failure.get(), instanceOf(StreamingBody.StreamAbortedException.class));
        assertThat(channel.isOpen(), is(false));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldReportAwaitingTheClientOnlyAboveTheWatermark() {
        StreamingBody body = new StreamingBody(0, false, 4096);
        body.subscribe(chunk -> {
        }, () -> {
        }, error -> {
        });
        assertThat(body.isAwaitingClient(), is(false));
        add(body, 1024);
        assertThat("at the watermark (a quarter of the bound) a read is requested", body.isAwaitingClient(), is(false));
        add(body, 1);
        assertThat(body.isAwaitingClient(), is(true));
        body.chunkWritten(1);
        assertThat(body.isAwaitingClient(), is(false));
        assertThat("without a bound reads are never withheld", new StreamingBody(0, false).isAwaitingClient(), is(false));
    }

    private static void add(StreamingBody body, int size) {
        ByteBuf chunk = Unpooled.buffer(size).writeZero(size);
        body.addChunk(chunk);
        chunk.release();
    }

    private static ChannelInboundHandlerAdapter recorder(AtomicReference<Throwable> failure) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                failure.set(cause);
            }
        };
    }
}
