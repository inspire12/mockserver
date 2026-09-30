package org.mockserver.httpclient;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import org.junit.Test;
import org.mockserver.codec.StreamingAwareHttpObjectAggregator;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The forward client's own request stream keeps the connection-level component limit, but a stream the upstream
 * opens gets the per-stream limit: an upstream could open many at once, each with its own response aggregator.
 * A real client and server frame codec are joined back to back.
 */
public class Http2ForwardStreamComponentLimitTest {

    private static final int TEN_MIB = 10 * 1024 * 1024;
    private static final Configuration CONFIGURATION = configuration().maxResponseBodySize(TEN_MIB);

    @Test
    public void shouldLimitEachStreamTheUpstreamOpensToTheStreamComponentLimit() {
        int streams = 3;
        int frames = 3_000;
        Probe probe = new Probe();
        // default settings, so the upstream may open several streams; the forward client's settings allow one
        Link link = new Link(Http2Settings.defaultSettings().pushEnabled(false), probe);
        try {
            List<Http2StreamChannel> upstreamStreams = new ArrayList<>();
            for (int i = 0; i < streams; i++) {
                Http2StreamChannel stream = link.openUpstreamStream();
                stream.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200").set("content-type", "text/plain"), false));
                upstreamStreams.add(stream);
            }
            link.pump();
            for (int f = 0; f < frames; f++) {
                for (Http2StreamChannel stream : upstreamStreams) {
                    stream.write(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(new byte[]{(byte) ('a' + f % 26)}), f == frames - 1));
                }
                if (f % 500 == 0) {
                    upstreamStreams.forEach(Http2StreamChannel::flush);
                    link.pump();
                }
            }
            upstreamStreams.forEach(Http2StreamChannel::flush);
            link.pump();

            assertThat(probe.limits.keySet(), containsInAnyOrder(2, 4, 6));
            for (int streamId : probe.limits.keySet()) {
                assertThat("stream " + streamId, probe.limits.get(streamId), is(1024));
                assertThat("stream " + streamId, probe.components.get(streamId), lessThanOrEqualTo(1024));
                assertThat("stream " + streamId, probe.bodies.get(streamId).length(), is(frames));
            }
        } finally {
            link.close();
        }
    }

    @Test
    public void shouldKeepTheConnectionLimitOnTheClientsOwnStream() {
        Probe probe = new Probe();
        Link link = new Link(HttpClientInitializer.forwardClientSettings(TEN_MIB), probe);
        try {
            Future<Http2StreamChannel> open = new Http2StreamChannelBootstrap(link.client).handler(link.childInitializer).open();
            link.pump();
            Http2StreamChannel stream = open.getNow();
            StreamingAwareHttpObjectAggregator aggregator = stream.pipeline().get(StreamingAwareHttpObjectAggregator.class);
            assertThat(aggregator.maxCumulationBufferComponents(), is(10240));
            stream.close();
        } finally {
            link.close();
        }
    }

    @Test
    public void shouldAllowTheUpstreamOneStreamOfItsOwnAtATime() {
        assertThat(HttpClientInitializer.forwardClientSettings(TEN_MIB).maxConcurrentStreams(), is(1L));
        assertThat(HttpClientInitializer.forwardClientSettings(TEN_MIB).pushEnabled(), is(false));

        Link link = new Link(HttpClientInitializer.forwardClientSettings(TEN_MIB), new Probe());
        try {
            Http2StreamChannel first = link.openUpstreamStream();
            first.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), false));
            link.pump();
            Http2StreamChannel second = link.openUpstreamStream();
            ChannelFuture refused = second.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), false));
            link.pump();

            assertThat(refused.isDone() && !refused.isSuccess(), is(true));
        } finally {
            link.close();
        }
    }

    /**
     * A forward-client connection (the channel {@link HttpClientInitializer} builds, with {@link Http2ForwardStreamChildInitializer})
     * joined to a plain HTTP/2 server connection standing in for the upstream.
     */
    private static final class Link {
        private final EmbeddedChannel client = new EmbeddedChannel();
        private final EmbeddedChannel upstream = new EmbeddedChannel();
        private final Http2ForwardStreamChildInitializer childInitializer;

        Link(Http2Settings clientSettings, ChannelHandler lastHandler) {
            childInitializer = new Http2ForwardStreamChildInitializer(CONFIGURATION, new MockServerLogger(), Collections.emptyMap(), lastHandler, null);
            client.pipeline().addLast(Http2FrameCodecBuilder.forClient().initialSettings(clientSettings).build());
            client.pipeline().addLast(new Http2MultiplexHandler(childInitializer));
            upstream.pipeline().addLast(Http2FrameCodecBuilder.forServer().build());
            upstream.pipeline().addLast(new Http2MultiplexHandler(new ChannelInboundHandlerAdapter()));
            pump();
        }

        Http2StreamChannel openUpstreamStream() {
            Future<Http2StreamChannel> open = new Http2StreamChannelBootstrap(upstream).handler(new ChannelInboundHandlerAdapter()).open();
            pump();
            return open.getNow();
        }

        void pump() {
            boolean moved = true;
            while (moved) {
                client.runPendingTasks();
                upstream.runPendingTasks();
                moved = move(client, upstream) | move(upstream, client);
            }
        }

        private static boolean move(EmbeddedChannel from, EmbeddedChannel to) {
            boolean moved = false;
            for (Object message = from.readOutbound(); message != null; message = from.readOutbound()) {
                to.writeInbound(message);
                moved = true;
            }
            return moved;
        }

        void close() {
            client.finishAndReleaseAll();
            upstream.finishAndReleaseAll();
        }
    }

    /**
     * Stands in for HttpClientHandler; on each stream it inserts a handler straight after the aggregator that records
     * the aggregator's component limit and the aggregated body's component count.
     */
    @ChannelHandler.Sharable
    private static final class Probe extends ChannelInboundHandlerAdapter {
        private final Map<Integer, Integer> limits = new ConcurrentHashMap<>();
        private final Map<Integer, Integer> components = new ConcurrentHashMap<>();
        private final Map<Integer, String> bodies = new ConcurrentHashMap<>();

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            String aggregator = ctx.pipeline().context(StreamingAwareHttpObjectAggregator.class).name();
            ctx.pipeline().addAfter(aggregator, "probe", new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext probeCtx, Object msg) {
                    int streamId = ((Http2StreamChannel) probeCtx.channel()).stream().id();
                    limits.put(streamId, probeCtx.pipeline().get(StreamingAwareHttpObjectAggregator.class).maxCumulationBufferComponents());
                    ByteBuf content = ((FullHttpResponse) msg).content();
                    components.put(streamId, content instanceof CompositeByteBuf ? ((CompositeByteBuf) content).numComponents() : 1);
                    bodies.put(streamId, content.toString(java.nio.charset.StandardCharsets.US_ASCII));
                    ReferenceCountUtil.release(msg);
                }
            });
        }
    }
}
