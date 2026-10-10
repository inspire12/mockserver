package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapterBuilder;
import io.netty.channel.ChannelFuture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.SCHEME;
import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_DEPENDENCY_ID;
import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_ID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * The relay's HTTP/2 loopback against a real HTTP/2 peer, frames shuttled between two embedded channels: requests
 * written in any client-stream order open increasing loopback streams, and each response goes back to the client
 * stream that asked for it.
 */
public class LoopbackHttp2StreamIdRemapperTest {

    private EmbeddedChannel loopback;
    private EmbeddedChannel server;
    private EmbeddedChannel proxyClient;
    private Http2Connection proxyClientConnection;
    private LoopbackHttp2StreamIdRemapper remapper;
    private final List<ReceivedRequest> requestsAtServer = new ArrayList<>();
    private final Map<Integer, String> responsesByClientStream = new LinkedHashMap<>();
    private boolean serverAnswers = true;

    @Before
    public void connect() {
        proxyClientConnection = new DefaultHttp2Connection(true);
        proxyClient = new EmbeddedChannel(new HttpToHttp2ConnectionHandlerBuilder()
            .frameListener(new Http2FrameAdapter())
            .connection(proxyClientConnection)
            .build());
        Http2Connection loopbackConnection = new DefaultHttp2Connection(false);
        remapper = new LoopbackHttp2StreamIdRemapper(new MockServerLogger(), loopbackConnection, proxyClient);
        loopback = new EmbeddedChannel(
            new HttpToHttp2ConnectionHandlerBuilder()
                .frameListener(new InboundHttp2ToHttpAdapterBuilder(loopbackConnection).maxContentLength(1024 * 1024).build())
                .connection(loopbackConnection)
                .build(),
            remapper,
            new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    FullHttpResponse response = (FullHttpResponse) msg;
                    try {
                        responsesByClientStream.put(response.headers().getInt(STREAM_ID.text()), response.content().toString(StandardCharsets.UTF_8));
                    } finally {
                        response.release();
                    }
                }
            }
        );
        Http2Connection serverConnection = new DefaultHttp2Connection(true);
        server = new EmbeddedChannel(
            new HttpToHttp2ConnectionHandlerBuilder()
                .frameListener(new InboundHttp2ToHttpAdapterBuilder(serverConnection).maxContentLength(1024 * 1024).build())
                .connection(serverConnection)
                .build(),
            new SimpleChannelInboundHandler<FullHttpRequest>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                    int streamId = request.headers().getInt(STREAM_ID.text());
                    requestsAtServer.add(new ReceivedRequest(streamId, request.uri(), request.headers().getInt(STREAM_DEPENDENCY_ID.text())));
                    if (serverAnswers) {
                        answer(ctx, streamId, request.uri());
                    }
                }
            }
        );
        pump();
    }

    @After
    public void close() {
        loopback.finishAndReleaseAll();
        server.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    @Test
    public void shouldOpenIncreasingLoopbackStreamsForRequestsWrittenOutOfClientStreamOrder() throws Exception {
        openClientStreams(1, 3, 5, 7);
        for (int clientStreamId : new int[]{3, 1, 7, 5}) {
            loopback.writeAndFlush(request(clientStreamId, "/stream-" + clientStreamId));
            pump();
        }

        assertThat(loopback.isActive(), is(true));
        assertThat(streamIdsAtServer(), is(Arrays.asList(1, 3, 5, 7)));
        assertThat(urisAtServer(), is(Arrays.asList("/stream-3", "/stream-1", "/stream-7", "/stream-5")));
        for (int clientStreamId : new int[]{1, 3, 5, 7}) {
            assertThat(responsesByClientStream.get(clientStreamId), is("answer for /stream-" + clientStreamId));
        }
        assertThat(remapper.mappedStreams(), is(0));
    }

    @Test
    public void shouldForgetEachPairWhenItsStreamCloses() throws Exception {
        // 10,000 streams in pairs written in reverse order, the second of each pair finishing first
        for (int pair = 0; pair < 5_000; pair++) {
            int first = 4 * pair + 1;
            int second = first + 2;
            openClientStreams(first, second);
            loopback.writeAndFlush(request(second, "/stream-" + second));
            loopback.writeAndFlush(request(first, "/stream-" + first));
            assertThat(remapper.mappedStreams(), lessThanOrEqualTo(2));
            pump();
            assertThat(responsesByClientStream.remove(first), is("answer for /stream-" + first));
            assertThat(responsesByClientStream.remove(second), is("answer for /stream-" + second));
            assertThat(remapper.mappedStreams(), is(0));
            // answered, so the client's streams end too: its connection allows only so many at once
            proxyClientConnection.stream(first).close();
            proxyClientConnection.stream(second).close();
        }

        assertThat(loopback.isActive(), is(true));
        assertThat(requestsAtServer.size(), is(10_000));
        assertThat(requestsAtServer.get(requestsAtServer.size() - 1).streamId, is(19_999));
    }

    @Test
    public void shouldMapBothDirectionsWhileTheStreamIsOpen() throws Exception {
        serverAnswers = false;
        openClientStreams(5);
        loopback.writeAndFlush(request(5, "/held"));
        pump();

        assertThat(remapper.loopbackStreamId(5), is(1));
        assertThat(remapper.clientStreamId(1), is(5));
        assertThat(remapper.loopbackStreamId(3), nullValue());

        answer(server.pipeline().lastContext(), 1, "/held");
        pump();
        assertThat(responsesByClientStream.get(5), is("answer for /held"));
        assertThat(remapper.loopbackStreamId(5), nullValue());
        assertThat(remapper.clientStreamId(1), nullValue());
    }

    @Test
    public void shouldForgetAPairItIsToldToUnpairWhileItsStreamIsStillOpen() {
        serverAnswers = false;
        loopback.writeAndFlush(request(5, "/held"));
        loopback.writeAndFlush(request(7, "/other"));
        pump();

        remapper.unpair(5);
        remapper.unpair(9);

        assertThat(remapper.loopbackStreamId(5), nullValue());
        assertThat(remapper.clientStreamId(1), nullValue());
        assertThat(remapper.loopbackStreamId(7), is(3));
        assertThat(remapper.mappedStreams(), is(1));
    }

    @Test
    public void shouldReuseThePairForAMessageThatLeavesItsStreamOpen() {
        // the relay itself writes each request whole, which closes the loopback stream's local side; only a message
        // that leaves it open, such as these headers-only heads, can be followed by another on the same stream
        serverAnswers = false;
        List<Integer> loopbackIdsWritten = new ArrayList<>();
        loopback.pipeline().addBefore(loopback.pipeline().context(remapper).name(), "loopback-ids", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                loopbackIdsWritten.add(((HttpMessage) msg).headers().getInt(STREAM_ID.text()));
                ctx.write(msg, promise);
            }
        });

        loopback.writeAndFlush(headersOnly(5, "/first"));
        assertThat(remapper.loopbackStreamId(5), is(1));
        loopback.writeAndFlush(headersOnly(5, "/second"));

        assertThat(loopbackIdsWritten, is(Arrays.asList(1, 1)));
    }

    @Test
    public void shouldDropASecondRequestWhileTheFirstStillAwaitsItsResponse() throws Exception {
        serverAnswers = false;
        openClientStreams(5);
        // as Netty's own InboundHttp2ToHttpAdapter hands on a request sent with Expect whose body follows at once (the
        // relay's adapter does not): an empty request, then the whole request again, both on client stream 5
        FullHttpRequest headers = request(5, "/expect", "");
        headers.headers().set("expect", "100-continue");
        loopback.writeAndFlush(headers);
        pump();
        FullHttpRequest body = request(5, "/expect");
        ChannelFuture written = loopback.writeAndFlush(body);
        pump();

        assertThat(written.isSuccess(), is(true));
        assertThat(body.refCnt(), is(0));
        assertThat(loopback.isActive(), is(true));
        assertThat(urisAtServer(), is(List.of("/expect")));
        answer(server.pipeline().lastContext(), 1, "/expect");
        pump();
        assertThat(responsesByClientStream.get(5), is("answer for /expect"));
        assertThat(remapper.mappedStreams(), is(0));
    }

    @Test
    public void shouldDropASecondRequestOnAStreamWhoseLoopbackStreamHasClosed() throws Exception {
        // the client's stream stays open while it uploads the body
        openClientStreams(5, 7);
        loopback.writeAndFlush(request(5, "/headers"));
        pump();
        assertThat(responsesByClientStream.get(5), is("answer for /headers"));

        // as Netty's own InboundHttp2ToHttpAdapter hands on a request sent with Expect: its headers, then later its body
        FullHttpRequest body = request(5, "/body");
        ChannelFuture written = loopback.writeAndFlush(body);
        pump();

        assertThat(written.isSuccess(), is(true));
        assertThat(body.refCnt(), is(0));
        assertThat(urisAtServer(), is(List.of("/headers")));
        assertThat(remapper.mappedStreams(), is(0));
        loopback.writeAndFlush(request(7, "/next"));
        pump();
        assertThat(responsesByClientStream.get(7), is("answer for /next"));
        assertThat(loopback.isActive(), is(true));
    }

    @Test
    public void shouldTranslateAPriorityDependencyOrDropItWhenItNamesNoOpenStream() {
        serverAnswers = false;
        loopback.writeAndFlush(request(9, "/parent"));
        FullHttpRequest dependent = request(3, "/dependent");
        dependent.headers().setInt(STREAM_DEPENDENCY_ID.text(), 9);
        loopback.writeAndFlush(dependent);
        FullHttpRequest orphan = request(11, "/orphan");
        // client stream 1 is not open on the loopback; left as is it would name loopback stream 1, the parent
        orphan.headers().setInt(STREAM_DEPENDENCY_ID.text(), 1);
        loopback.writeAndFlush(orphan);
        FullHttpRequest selfDependent = request(13, "/self-dependent");
        selfDependent.headers().setInt(STREAM_DEPENDENCY_ID.text(), 13);
        loopback.writeAndFlush(selfDependent);
        pump();

        assertThat(requestsAtServer.get(1).dependency, is(1));
        assertThat(requestsAtServer.get(2).dependency, nullValue());
        assertThat(requestsAtServer.get(3).uri, is("/self-dependent"));
        assertThat(requestsAtServer.get(3).dependency, nullValue());
    }

    @Test
    public void shouldForgetARequestWhoseStreamWasNeverOpened() throws Exception {
        serverAnswers = false;
        proxyClientConnection.remote().createStream(1, false);
        proxyClientConnection.remote().createStream(3, false);
        loopback.writeAndFlush(request(1, "/before-goaway"));
        pump();
        HttpToHttp2ConnectionHandler serverHandler = server.pipeline().get(HttpToHttp2ConnectionHandler.class);
        ChannelHandlerContext serverCtx = server.pipeline().context(serverHandler);
        serverHandler.goAway(serverCtx, 1, Http2Error.NO_ERROR.code(), Unpooled.EMPTY_BUFFER, serverCtx.newPromise());
        serverCtx.flush();
        pump();

        // above the GOAWAY's last stream, so the loopback refuses to open it
        loopback.writeAndFlush(request(3, "/after-goaway"));
        pump();

        assertThat(urisAtServer(), is(List.of("/before-goaway")));
        assertThat(remapper.loopbackStreamId(3), nullValue());
        assertThat(remapper.mappedStreams(), is(1));
        assertThat(remapper.relayed(1), is(true));
        // so a loopback close refuses it, which tells the client a retry is safe
        assertThat(remapper.relayed(3), is(false));
    }

    @Test
    public void shouldMarkAClientStreamAnsweredOnlyByAWholeFinalResponse() throws Exception {
        serverAnswers = false;
        proxyClientConnection.remote().createStream(5, false);
        loopback.writeAndFlush(request(5, "/answered"));
        pump();
        assertThat(remapper.relayed(5), is(true));
        assertThat(remapper.answered(5), is(false));

        // a 1xx is handed on as soon as it arrives, ahead of the response
        FullHttpResponse informational = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE);
        informational.headers().setInt(STREAM_ID.text(), 1);
        loopback.writeInbound(informational);
        assertThat(remapper.answered(5), is(false));

        answer(server.pipeline().lastContext(), 1, "/answered");
        pump();
        assertThat(responsesByClientStream.get(5), is("answer for /answered"));
        assertThat(remapper.answered(5), is(true));
    }

    @Test
    public void shouldDropAndReleaseAResponseForAClientStreamThatHasEnded() throws Exception {
        serverAnswers = false;
        openClientStreams(5, 7);
        loopback.writeAndFlush(request(5, "/ended"));
        loopback.writeAndFlush(request(7, "/open"));
        pump();
        proxyClientConnection.stream(5).close();

        // a write to the ended stream would fail, which closes the whole tunnel
        FullHttpResponse informational = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE, Unpooled.copiedBuffer("1xx", StandardCharsets.UTF_8));
        informational.headers().setInt(STREAM_ID.text(), 1);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer("late", StandardCharsets.UTF_8));
        response.headers().setInt(STREAM_ID.text(), 1);
        loopback.writeInbound(informational, response);
        answer(server.pipeline().lastContext(), 3, "/open");
        pump();

        assertThat(informational.refCnt(), is(0));
        assertThat(response.refCnt(), is(0));
        assertThat(responsesByClientStream, is(Map.of(7, "answer for /open")));
    }

    @Test
    public void shouldDropAResponseThatAnswersNoRelayedRequest() {
        EmbeddedChannel channel = new EmbeddedChannel(new LoopbackHttp2StreamIdRemapper(new MockServerLogger(), new DefaultHttp2Connection(false), null));
        FullHttpResponse pushed = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer("pushed", StandardCharsets.UTF_8));
        pushed.headers().setInt(STREAM_ID.text(), 2);

        channel.writeInbound(pushed);

        assertThat(channel.readInbound(), nullValue());
        assertThat(pushed.refCnt(), is(0));
        channel.finishAndReleaseAll();
    }

    private static FullHttpRequest request(int clientStreamId, String uri) {
        return request(clientStreamId, uri, "upload for " + uri);
    }

    private static FullHttpRequest request(int clientStreamId, String uri, String body) {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers().setInt(STREAM_ID.text(), clientStreamId);
        request.headers().set("host", "localhost");
        request.headers().set(SCHEME.text(), "https");
        return request;
    }

    private static HttpMessage headersOnly(int clientStreamId, String uri) {
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri);
        request.headers().setInt(STREAM_ID.text(), clientStreamId);
        request.headers().set("host", "localhost");
        request.headers().set(SCHEME.text(), "https");
        return request;
    }

    private void openClientStreams(int... clientStreamIds) throws Http2Exception {
        for (int clientStreamId : clientStreamIds) {
            proxyClientConnection.remote().createStream(clientStreamId, false);
        }
    }

    private static void answer(ChannelHandlerContext ctx, int streamId, String uri) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer("answer for " + uri, StandardCharsets.UTF_8));
        response.headers().setInt(STREAM_ID.text(), streamId);
        ctx.writeAndFlush(response);
    }

    private void pump() {
        boolean moved;
        do {
            moved = false;
            ByteBuf bytes;
            while ((bytes = loopback.readOutbound()) != null) {
                server.writeInbound(bytes);
                moved = true;
            }
            while ((bytes = server.readOutbound()) != null) {
                loopback.writeInbound(bytes);
                moved = true;
            }
        } while (moved);
    }

    private List<Integer> streamIdsAtServer() {
        List<Integer> streamIds = new ArrayList<>();
        requestsAtServer.forEach(request -> streamIds.add(request.streamId));
        return streamIds;
    }

    private List<String> urisAtServer() {
        List<String> uris = new ArrayList<>();
        requestsAtServer.forEach(request -> uris.add(request.uri));
        return uris;
    }

    private static final class ReceivedRequest {
        private final int streamId;
        private final String uri;
        private final Integer dependency;

        private ReceivedRequest(int streamId, String uri, Integer dependency) {
            this.streamId = streamId;
            this.uri = uri;
            this.dependency = dependency;
        }
    }
}
