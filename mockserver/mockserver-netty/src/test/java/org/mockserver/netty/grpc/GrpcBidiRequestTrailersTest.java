package org.mockserver.netty.grpc;

import com.google.protobuf.Descriptors;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcServerReflectionHandler;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcBidiRule;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * A HEADERS frame after a bidi request's headers, or a server reflection request's, is its trailers: it ends the
 * request, and is not taken for the request's headers again.
 */
public class GrpcBidiRequestTrailersTest {

    private static final String DESCRIPTOR = "../mockserver-core/src/test/resources/grpc/greeting.dsc";

    private final List<Object> outbound = new ArrayList<>();
    private Descriptors.MethodDescriptor chatMethod;
    private GrpcJsonMessageConverter converter;
    private EmbeddedChannel channel;

    @Before
    public void setUp() {
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get(DESCRIPTOR));
        converter = store.getConverter();
        chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");
        GrpcBidiResponse config = GrpcBidiResponse.grpcBidiResponse()
            .withStatusName("OK")
            .withRule(GrpcBidiRule.grpcBidiRule(".*Alice.*").withResponse("{\"greeting\": \"Hello Alice\"}"));
        channel = new EmbeddedChannel(new FrameCapture(outbound), new GrpcBidiStreamHandler(chatMethod, converter, config, (Runnable) null));
    }

    @After
    public void tearDown() {
        outbound.forEach(ReferenceCountUtil::release);
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldEndTheResponseWithItsStatusWhenTheRequestEndsWithTrailers() {
        DefaultHttp2Headers headers = new DefaultHttp2Headers();
        headers.method("POST").path("/com.example.grpc.GreetingService/Chat").set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        channel.writeInbound(new DefaultHttp2HeadersFrame(headers, false));
        byte[] alice = GrpcFrameCodec.encode(converter.toProtobuf("{\"name\": \"Alice\"}", chatMethod.getInputType()));
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(alice), false));

        channel.writeInbound(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().add("x-trailer", "value"), true));

        assertThat("response headers, one message, then the status", outbound.size(), is(3));
        assertThat(((Http2HeadersFrame) outbound.get(0)).headers().status().toString(), is("200"));
        assertThat(outbound.get(1), instanceOf(Http2DataFrame.class));
        Http2HeadersFrame status = (Http2HeadersFrame) outbound.get(2);
        assertThat("no second :status", status.headers().status(), is(nullValue()));
        assertThat(status.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is("0"));
        assertThat(status.isEndStream(), is(true));
    }

    @Test
    public void shouldEndAServerReflectionResponseWithItsStatusWhenTheRequestEndsWithTrailers() {
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get(DESCRIPTOR));
        List<Object> reflectionOutbound = new ArrayList<>();
        EmbeddedChannel reflection = new EmbeddedChannel(new FrameCapture(reflectionOutbound), new GrpcBidiReflectionHandler(new GrpcServerReflectionHandler(store)));
        try {
            DefaultHttp2Headers headers = new DefaultHttp2Headers();
            headers.method("POST").path("/grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo").set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
            reflection.writeInbound(new DefaultHttp2HeadersFrame(headers, false));

            reflection.writeInbound(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().add("x-trailer", "value"), true));

            assertThat("response headers, then the status", reflectionOutbound.size(), is(2));
            assertThat(((Http2HeadersFrame) reflectionOutbound.get(0)).headers().status().toString(), is("200"));
            Http2HeadersFrame status = (Http2HeadersFrame) reflectionOutbound.get(1);
            assertThat("no second :status", status.headers().status(), is(nullValue()));
            assertThat(status.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is("0"));
            assertThat(status.isEndStream(), is(true));
        } finally {
            reflection.finishAndReleaseAll();
        }
    }

    private static class FrameCapture extends ChannelOutboundHandlerAdapter {
        private final List<Object> captured;

        FrameCapture(List<Object> captured) {
            this.captured = captured;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof Http2StreamFrame) {
                captured.add(msg);
            } else {
                ReferenceCountUtil.release(msg);
            }
            promise.setSuccess();
        }
    }
}
