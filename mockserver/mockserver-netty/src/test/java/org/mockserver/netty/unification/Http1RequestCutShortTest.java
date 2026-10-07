package org.mockserver.netty.unification;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.PrematureChannelClosureException;
import org.junit.Test;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Only the aggregator's report of a request it held when its connection closed is an upload cut short; anything else
 * is left to the handler that asked, which logs it as before.
 */
public class Http1RequestCutShortTest {

    @Test
    public void shouldTakeTheAggregatorsReportOnAClosedConnection() {
        assertThat(Http1RequestCutShort.isRequestCutShort(null, closedContext(), new PrematureChannelClosureException("Channel closed while still aggregating message")), is(true));
    }

    @Test
    public void shouldLeaveAnyOtherExceptionOnAClosedConnection() {
        assertThat(Http1RequestCutShort.isRequestCutShort(null, closedContext(), new IOException("Connection reset by peer")), is(false));
        assertThat(Http1RequestCutShort.isRequestCutShort(null, closedContext(), new IllegalStateException("unexpected")), is(false));
    }

    @Test
    public void shouldLeaveTheExceptionOnAConnectionStillOpen() {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        try {
            assertThat(Http1RequestCutShort.isRequestCutShort(null, channel.pipeline().firstContext(), new PrematureChannelClosureException("closed")), is(false));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static ChannelHandlerContext closedContext() {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ChannelHandlerContext ctx = channel.pipeline().firstContext();
        channel.finishAndReleaseAll();
        return ctx;
    }
}
