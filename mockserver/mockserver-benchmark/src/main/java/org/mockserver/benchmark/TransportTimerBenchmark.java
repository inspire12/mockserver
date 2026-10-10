package org.mockserver.benchmark;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.mockserver.configuration.Configuration;
import org.mockserver.metrics.Metrics;
import org.mockserver.netty.connection.HttpTransportTimer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Per-exchange cost of {@link HttpTransportTimer}, the handler behind
 * {@code mock_server_request_transport_duration_seconds}: one request read and one response written and
 * flushed through an {@link EmbeddedChannel}, with the timer in the pipeline ({@code timed=true}) or a
 * pass-through handler in its place ({@code timed=false}, which is what a server with metrics disabled
 * pays: no timer is installed at all). The difference between the arms is the timer's cost.
 *
 * <pre>./run.sh -prof gc TransportTimerBenchmark</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TransportTimerBenchmark {

    @Param({"false", "true"})
    public boolean timed;

    private EmbeddedChannel channel;
    private DefaultFullHttpRequest request;
    private DefaultFullHttpResponse response;

    @Setup(Level.Trial)
    public void setup() {
        Metrics.resetAdditionalMetricsForTesting();
        new Metrics(Configuration.configuration().metricsEnabled(true));
        channel = new EmbeddedChannel(timed ? new HttpTransportTimer() : new ChannelDuplexHandler());
        request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/", Unpooled.EMPTY_BUFFER);
        response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        channel.finishAndReleaseAll();
        Metrics.resetAdditionalMetricsForTesting();
    }

    @Benchmark
    public void exchange(Blackhole blackhole) {
        channel.writeInbound(request);
        blackhole.consume(channel.inboundMessages().poll());
        channel.writeOneOutbound(response);
        channel.flushOutbound();
        blackhole.consume(channel.outboundMessages().poll());
    }
}
