package org.mockserver.netty.unification;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * One HTTP/2 connection carries up to 100 concurrent streams, so each stream's aggregator holds a tenth of an
 * HTTP/1.1 connection's components (1,024 at the default 10 MiB). Frames go through the chain every ordinary
 * stream gets ({@link Http2MultiplexChildInitializer#installReAggregatingChain}); the request is captured
 * straight after the aggregator.
 */
public class Http2StreamComponentLimitTest {

    private static final int SIXTEEN_KIB = 16 * 1024;
    private static final int SIXTY_FOUR_MIB = 64 * 1024 * 1024;

    @Test
    public void shouldInstallTheStreamComponentLimit() {
        EmbeddedChannel channel = streamChain(configuration());
        try {
            assertThat(channel.pipeline().get(HttpObjectAggregator.class).maxCumulationBufferComponents(), is(1024));
        } finally {
            channel.finishAndReleaseAll();
        }
        channel = streamChain(configuration().maxRequestBodySize(SIXTY_FOUR_MIB));
        try {
            assertThat(channel.pipeline().get(HttpObjectAggregator.class).maxCumulationBufferComponents(), is(6553));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldBoundComponentsWhenAClientSendsOneByteDataFrames() {
        int frames = 20_000;
        byte[] body = new byte[frames];
        for (int i = 0; i < frames; i++) {
            body[i] = (byte) (i % 251);
        }
        EmbeddedChannel channel = streamChain(configuration());
        CapturingHandler capture = captureAfterAggregator(channel);
        channel.writeInbound(new DefaultHttp2HeadersFrame(postHeaders(), false));
        for (int i = 0; i < frames; i++) {
            channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(new byte[]{body[i]}), i == frames - 1));
        }
        CompositeByteBuf content = capture.content();
        try {
            assertThat(content.numComponents(), lessThanOrEqualTo(1024));
            assertThat(ByteBufUtil.getBytes(content), is(body));
        } finally {
            content.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldNotConsolidateABodyOfSixteenKibDataFrames() {
        // 4,096 frames: past Netty's default of 1,024 components, within the 6,553 allowed at 64 MiB; every frame
        // wraps the same array, so the 64 MiB body costs 16 KiB
        int frames = SIXTY_FOUR_MIB / SIXTEEN_KIB;
        byte[] frame = new byte[SIXTEEN_KIB];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (byte) (i % 251);
        }
        EmbeddedChannel channel = streamChain(configuration().maxRequestBodySize(SIXTY_FOUR_MIB));
        CapturingHandler capture = captureAfterAggregator(channel);
        channel.writeInbound(new DefaultHttp2HeadersFrame(postHeaders(), false));
        for (int i = 0; i < frames; i++) {
            channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(frame), i == frames - 1));
        }
        CompositeByteBuf content = capture.content();
        try {
            assertThat(content.readableBytes(), is(SIXTY_FOUR_MIB));
            assertThat(content.numComponents(), is(frames));
            assertThat(content.getByte(SIXTY_FOUR_MIB - 1), is(frame[SIXTEEN_KIB - 1]));
        } finally {
            content.release();
            channel.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel streamChain(Configuration configuration) {
        EmbeddedChannel channel = new EmbeddedChannel();
        Http2MultiplexChildInitializer.installReAggregatingChain(
            channel.pipeline(),
            configuration,
            new MockServerLogger(Http2StreamComponentLimitTest.class),
            false,
            null,
            channel,
            mock(CallbackWebSocketServerHandler.class),
            mock(DashboardWebSocketHandler.class),
            null,
            mock(TraceContextHandler.class),
            null,
            null,
            null,
            mock(HttpRequestHandler.class)
        );
        return channel;
    }

    private static CapturingHandler captureAfterAggregator(EmbeddedChannel channel) {
        CapturingHandler capture = new CapturingHandler();
        channel.pipeline().addAfter(channel.pipeline().context(HttpObjectAggregator.class).name(), "capture", capture);
        return capture;
    }

    private static DefaultHttp2Headers postHeaders() {
        DefaultHttp2Headers headers = new DefaultHttp2Headers();
        headers.method("POST");
        headers.path("/upload");
        headers.scheme("http");
        return headers;
    }

    private static final class CapturingHandler extends ChannelInboundHandlerAdapter {
        private FullHttpRequest request;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            request = (FullHttpRequest) msg;
        }

        CompositeByteBuf content() {
            assertThat("the aggregator should have produced a request", request, is(notNullValue()));
            assertThat(request.content(), instanceOf(CompositeByteBuf.class));
            return (CompositeByteBuf) request.content();
        }
    }
}
