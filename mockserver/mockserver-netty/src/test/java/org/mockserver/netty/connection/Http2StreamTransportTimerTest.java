package org.mockserver.netty.connection;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2Error;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.metrics.Metrics;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.connection.RecordedDurations.TRANSPORT;
import static org.mockserver.netty.connection.RecordedDurations.count;
import static org.mockserver.netty.connection.RecordedDurations.countOver;

public class Http2StreamTransportTimerTest {

    private EmbeddedChannel stream;

    @Before
    public void enableMetrics() {
        Metrics.resetAdditionalMetricsForTesting();
        new Metrics(configuration().metricsEnabled(true));
        stream = new EmbeddedChannel(new Http2StreamTransportTimer());
    }

    @After
    public void resetMetrics() {
        stream.finishAndReleaseAll();
        Metrics.resetAdditionalMetricsForTesting();
    }

    @Test
    public void shouldRecordWhenTheFrameEndingTheResponseIsWritten() throws InterruptedException {
        readHeaders(false);
        read(new DefaultHttp2DataFrame(Unpooled.copiedBuffer("body", StandardCharsets.UTF_8), true));
        Thread.sleep(60);

        writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), false));
        assertThat(count(TRANSPORT), is(0L));

        stream.writeOneOutbound(new DefaultHttp2DataFrame(Unpooled.copiedBuffer("reply", StandardCharsets.UTF_8), true));
        assertThat("not recorded before the frame is flushed", count(TRANSPORT), is(0L));
        stream.flushOutbound();
        stream.releaseOutbound();

        assertThat(count(TRANSPORT), is(1L));
        assertThat(countOver(TRANSPORT, 0.05), is(1L));
    }

    @Test
    public void shouldRecordAHeadersOnlyResponse() {
        readHeaders(true);

        writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("204"), true));

        assertThat(count(TRANSPORT), is(1L));
    }

    @Test
    public void shouldRecordAStreamOnceEvenWhenTrailersFollow() {
        readHeaders(false);
        readHeaders(true);

        writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), false));
        writeAndFlush(new DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, false));
        writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().add("grpc-status", "0"), true));
        writeAndFlush(new DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, true));

        assertThat(count(TRANSPORT), is(1L));
    }

    @Test
    public void shouldNotRecordAResetStream() {
        readHeaders(true);

        writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.CANCEL));

        assertThat(count(TRANSPORT), is(0L));
    }

    @Test
    public void shouldTimeEachStreamOnItsOwnChannel() throws InterruptedException {
        EmbeddedChannel otherStream = new EmbeddedChannel(new Http2StreamTransportTimer());
        try {
            readHeaders(true);
            Thread.sleep(60);
            otherStream.writeInbound(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().method("GET").path("/other"), true));
            otherStream.releaseInbound();

            otherStream.writeOneOutbound(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), true));
            otherStream.flushOutbound();
            writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), true));

            assertThat(count(TRANSPORT), is(2L));
            assertThat("only the stream that waited out the gap took longer than it", countOver(TRANSPORT, 0.05), is(1L));
        } finally {
            otherStream.finishAndReleaseAll();
        }
    }

    private void readHeaders(boolean endStream) {
        read(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().method("POST").path("/"), endStream));
    }

    private void read(Object frame) {
        stream.writeInbound(frame);
        stream.releaseInbound();
    }

    private void writeAndFlush(Object frame) {
        stream.writeOneOutbound(frame);
        stream.flushOutbound();
        stream.releaseOutbound();
    }
}
