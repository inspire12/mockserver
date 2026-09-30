package org.mockserver.netty.integration.mock;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A request body sent over HTTP/2 as thousands of one-byte DATA frames passes the stream's component limit
 * (1,024 at the default 10 MiB) several times, so the aggregator consolidates it more than once. The body must
 * still reach the matchers unchanged. A raw in-JVM Netty h2c client puts each frame on the wire as written.
 */
public class Http2TinyDataFramesIntegrationTest {

    private static final int FRAMES = 20_000;

    private MockServer mockServer;
    private MockServerClient mockServerClient;

    @Before
    public void startServer() {
        mockServer = new MockServer();
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
    }

    @After
    public void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Test(timeout = 60000)
    public void shouldReceiveABodySentAsOneByteDataFramesUnchanged() throws Exception {
        StringBuilder bodyBuilder = new StringBuilder(FRAMES);
        for (int i = 0; i < FRAMES; i++) {
            bodyBuilder.append((char) ('a' + i % 26));
        }
        String body = bodyBuilder.toString();
        mockServerClient
            .when(request().withMethod("POST").withPath("/tiny_frames").withBody(body))
            .respond(response().withStatusCode(200).withBody("matched"));

        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            Channel parent = connectMultiplexParent(group);
            CompletableFuture<Response> responseFuture = new CompletableFuture<>();
            Http2StreamChannel streamChannel = new Http2StreamChannelBootstrap(parent)
                .handler(new ResponseCollector(responseFuture))
                .open()
                .sync()
                .getNow();

            Http2Headers headers = new DefaultHttp2Headers()
                .method(HttpMethod.POST.asciiName())
                .scheme(HttpScheme.HTTP.name())
                .authority("localhost:" + mockServer.getLocalPort())
                .path("/tiny_frames");
            streamChannel.write(new DefaultHttp2HeadersFrame(headers, false));
            byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < FRAMES; i++) {
                streamChannel.write(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(bytes, i, 1), i == FRAMES - 1));
            }
            streamChannel.flush();

            Response response = responseFuture.get(30, TimeUnit.SECONDS);

            assertThat("status (404 => the body did not arrive unchanged) - body <" + response.body + ">", response.status, is("200"));
            assertThat(response.body, is("matched"));
            assertThat(mockServerClient.retrieveRecordedRequests(request().withPath("/tiny_frames").withBody(body)), arrayWithSize(1));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    private Channel connectMultiplexParent(NioEventLoopGroup group) throws Exception {
        Bootstrap bootstrap = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                    ch.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                        }
                    }));
                }
            });
        return bootstrap.connect("localhost", mockServer.getLocalPort()).sync().channel();
    }

    private static final class Response {
        final String status;
        final String body;

        Response(String status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class ResponseCollector extends ChannelInboundHandlerAdapter {
        private final CompletableFuture<Response> done;
        private final StringBuilder body = new StringBuilder();
        private volatile String status;

        ResponseCollector(CompletableFuture<Response> done) {
            this.done = done;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof Http2HeadersFrame) {
                    Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
                    CharSequence s = headersFrame.headers().status();
                    if (s != null) {
                        status = s.toString();
                    }
                    if (headersFrame.isEndStream()) {
                        done.complete(new Response(status, body.toString()));
                    }
                } else if (msg instanceof Http2DataFrame) {
                    Http2DataFrame dataFrame = (Http2DataFrame) msg;
                    body.append(dataFrame.content().toString(StandardCharsets.UTF_8));
                    if (dataFrame.isEndStream()) {
                        done.complete(new Response(status, body.toString()));
                    }
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            done.completeExceptionally(cause);
        }
    }
}
