package org.mockserver.netty.connection;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.metrics.Metrics;

import java.nio.charset.StandardCharsets;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.connection.RecordedDurations.TRANSPORT;
import static org.mockserver.netty.connection.RecordedDurations.count;
import static org.mockserver.netty.connection.RecordedDurations.countOver;

public class HttpTransportTimerTest {

    private static final long GAP_MILLIS = 60;
    private static final double UNDER_GAP_SECONDS = 0.05;

    private EmbeddedChannel channel;

    @Before
    public void enableMetrics() {
        Metrics.resetAdditionalMetricsForTesting();
        new Metrics(configuration().metricsEnabled(true));
        channel = new EmbeddedChannel(new HttpTransportTimer());
    }

    @After
    public void resetMetrics() {
        channel.finishAndReleaseAll();
        Metrics.resetAdditionalMetricsForTesting();
    }

    @Test
    public void shouldRecordOnceWhenTheResponseWriteCompletesNotWhenItIsWritten() {
        readRequest();

        channel.writeOneOutbound(response(HttpResponseStatus.OK));
        assertThat("not recorded before the bytes are flushed", count(TRANSPORT), is(0L));

        channel.flushOutbound();
        channel.flushOutbound();
        assertThat(count(TRANSPORT), is(1L));
    }

    @Test
    public void shouldTimeKeepAliveRequestsSeparately() {
        readRequest();
        writeAndFlush(response(HttpResponseStatus.OK));
        readRequest();
        writeAndFlush(response(HttpResponseStatus.OK));

        assertThat(count(TRANSPORT), is(2L));
    }

    @Test
    public void shouldTimePipelinedRequestsFromTheirOwnArrival() throws InterruptedException {
        readRequest();
        readRequest();
        Thread.sleep(GAP_MILLIS);
        writeAndFlush(response(HttpResponseStatus.OK));
        // arrive while the second response is still owed, so the queue wraps and then grows
        readRequest();
        readRequest();
        writeAndFlush(response(HttpResponseStatus.OK));
        writeAndFlush(response(HttpResponseStatus.OK));
        writeAndFlush(response(HttpResponseStatus.OK));

        assertThat(count(TRANSPORT), is(4L));
        assertThat("only the two requests that waited out the gap took longer than it", countOver(TRANSPORT, UNDER_GAP_SECONDS), is(2L));
    }

    @Test
    public void shouldTimeAStreamedResponseToItsLastContent() {
        readRequest();

        writeAndFlush(new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK));
        writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer("chunk", StandardCharsets.UTF_8)));
        assertThat(count(TRANSPORT), is(0L));

        writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        assertThat(count(TRANSPORT), is(1L));
    }

    @Test
    public void shouldNotEndTheExchangeOnAContinueResponse() throws InterruptedException {
        readRequest();

        writeAndFlush(response(HttpResponseStatus.CONTINUE));
        assertThat(count(TRANSPORT), is(0L));

        Thread.sleep(GAP_MILLIS);
        writeAndFlush(response(HttpResponseStatus.OK));
        assertThat(count(TRANSPORT), is(1L));
        assertThat("timed from the request, not from the 100 Continue", countOver(TRANSPORT, UNDER_GAP_SECONDS), is(1L));
    }

    @Test
    public void shouldNotRecordAWriteThatFails() {
        readRequest();

        ChannelFuture write = channel.writeOneOutbound(response(HttpResponseStatus.OK));
        channel.close();

        assertThat(write.isSuccess(), is(false));
        assertThat(count(TRANSPORT), is(0L));
    }

    @Test
    public void shouldNotRecordAResponseWithNoRequest() {
        writeAndFlush(response(HttpResponseStatus.UPGRADE_REQUIRED));

        assertThat(count(TRANSPORT), is(0L));
    }

    @Test
    public void shouldStopTimingOnceTheConnectionSwitchesProtocols() {
        readRequest();

        writeAndFlush(response(HttpResponseStatus.SWITCHING_PROTOCOLS));

        assertThat(count(TRANSPORT), is(0L));
        assertThat(channel.pipeline().get(HttpTransportTimer.class), nullValue());
    }

    private void readRequest() {
        channel.writeInbound(new DefaultFullHttpRequest(HTTP_1_1, HttpMethod.GET, "/"));
        channel.releaseInbound();
    }

    private void writeAndFlush(Object msg) {
        channel.writeOneOutbound(msg);
        channel.flushOutbound();
        channel.releaseOutbound();
    }

    private static DefaultFullHttpResponse response(HttpResponseStatus status) {
        return new DefaultFullHttpResponse(HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
    }
}
