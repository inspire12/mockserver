package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import org.junit.After;
import org.junit.Test;
import org.mockserver.codec.HttpObjectAggregators;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * What the HTTP/2 server codecs do with a request's trailers over {@code maxHeaderSize}: the refusal is logged once, as
 * the trailers'. A direct connection resets the stream with {@code PROTOCOL_ERROR}, and the handlers past the stream's
 * codec are told only that the request they held was cut short. A tunnel answers {@code 431}.
 */
public class Http2RequestTrailerLimitTest {

    private static final int LIMIT = 16 * 1024;
    private static final int STREAM = 3;
    private static final String TRAILERS_OVER_THE_LIMIT = "because the request's trailers are larger than maxHeaderSize";
    private static final String HEADERS_OVER_THE_LIMIT = "because its header list is larger than maxHeaderSize";

    private final Configuration configuration = configuration().maxHeaderSize(LIMIT);
    private final List<String> logged = new ArrayList<>();
    private final MockServerLogger mockServerLogger = new MockServerLogger(Http2RequestTrailerLimitTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return true;
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry.getMessageFormat());
        }
    };
    private final List<String> responseStatuses = new ArrayList<>();
    private final List<Long> resetErrorCodes = new ArrayList<>();
    private final Http2ConnectionHandler clientCodec = new Http2ConnectionHandlerBuilder()
        .server(false)
        // or the client would refuse to send what the server limits
        .encoderIgnoreMaxHeaderListSize(true)
        .frameListener(new Http2FrameAdapter() {
            @Override
            public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endStream) {
                responseStatuses.add(headers.status().toString());
            }

            @Override
            public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
                resetErrorCodes.add(errorCode);
            }
        })
        .build();
    private final EmbeddedChannel client = new EmbeddedChannel(clientCodec);
    private EmbeddedChannel server;

    @After
    public void closeChannels() {
        client.finishAndReleaseAll();
        if (server != null) {
            server.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldResetAStreamForItsTrailersOnADirectConnectionAndStopTheErrorAtItsCodec() {
        List<Throwable> pastTheCodec = new ArrayList<>();
        List<Boolean> knownAsRefused = new ArrayList<>();
        connect(directCodec(), new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel stream) {
                stream.pipeline().addLast(new LenientInboundHttp2StreamFrameCodec());
                stream.pipeline().addLast(HttpObjectAggregators.streamHttpObjectAggregator(1024 * 1024));
                stream.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        pastTheCodec.add(cause);
                        knownAsRefused.add(Http2RequestHeaderLimit.isRefusedRequestCutShort(ctx.channel(), cause));
                    }
                });
            }
        }));

        sendRequestWithTrailersOfSize(LIMIT + 1);

        assertThat("no 431: Netty's stream channels are given the error instead", responseStatuses, is(empty()));
        assertThat(resetErrorCodes, contains(Http2Error.PROTOCOL_ERROR.code()));
        assertThat(logged, contains(containsString(TRAILERS_OVER_THE_LIMIT)));
        assertThat("only the aggregator's report of the request it held", pastTheCodec, contains(instanceOf(PrematureChannelClosureException.class)));
        assertThat("which is known as the refusal already logged", knownAsRefused, contains(true));
        assertThat("the connection stays open", server.isOpen(), is(true));
    }

    @Test
    public void shouldAnswerTrailersOverTheLimitInATunnelWith431() {
        connect(Http2RequestHeaderLimit.tunnelServerHandler(configuration, mockServerLogger, new DefaultHttp2Connection(true), new Http2FrameAdapter(), null));

        sendRequestWithTrailersOfSize(LIMIT + 1);

        // the 431 ends a stream the trailers ended too, so the reset Netty sends after it is for a closed stream
        assertThat(responseStatuses, contains("431"));
        assertThat(logged, contains(containsString(TRAILERS_OVER_THE_LIMIT)));
        assertThat("the tunnel stays open", server.isOpen(), is(true));
    }

    @Test
    public void shouldLogARequestsHeadersOverTheLimitAsItsHeadersOnADirectConnection() {
        connect(directCodec());

        sendRequestHeadersOverTheLimit();

        assertThat(responseStatuses, contains("431"));
        assertThat(logged, contains(containsString(HEADERS_OVER_THE_LIMIT)));
    }

    @Test
    public void shouldLogARequestsHeadersOverTheLimitAsItsHeadersInATunnel() {
        connect(Http2RequestHeaderLimit.tunnelServerHandler(configuration, mockServerLogger, new DefaultHttp2Connection(true), new Http2FrameAdapter(), null));

        sendRequestHeadersOverTheLimit();

        assertThat(responseStatuses, contains("431"));
        assertThat(logged, contains(containsString(HEADERS_OVER_THE_LIMIT)));
    }

    @Test
    public void shouldPassOnAnyOtherErrorOfAStream() {
        List<Throwable> pastTheCodec = new ArrayList<>();
        EmbeddedChannel stream = new EmbeddedChannel(new LenientInboundHttp2StreamFrameCodec(), new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                pastTheCodec.add(cause);
            }
        });
        try {
            Http2Exception trailersOverTheLimit = Http2Exception.headerListSizeError(STREAM, Http2Error.PROTOCOL_ERROR, true, "Header size exceeded max allowed size (%d)", LIMIT);
            Http2Exception responseOverTheClientsLimit = Http2Exception.headerListSizeError(STREAM, Http2Error.PROTOCOL_ERROR, false, "Header size exceeded max allowed size (%d)", LIMIT);
            Http2Exception anotherStreamError = Http2Exception.streamError(STREAM, Http2Error.PROTOCOL_ERROR, "Stream %d received trailers without END_STREAM", STREAM);
            PrematureChannelClosureException cutShort = new PrematureChannelClosureException("Channel closed while still aggregating message");

            stream.pipeline().fireExceptionCaught(cutShort);
            assertThat("a request cut short for another reason", Http2RequestHeaderLimit.isRefusedRequestCutShort(stream, cutShort), is(false));

            stream.pipeline().fireExceptionCaught(responseOverTheClientsLimit);
            stream.pipeline().fireExceptionCaught(anotherStreamError);
            stream.pipeline().fireExceptionCaught(trailersOverTheLimit);

            assertThat(pastTheCodec, contains(cutShort, responseOverTheClientsLimit, anotherStreamError));
            assertThat(Http2RequestHeaderLimit.isRefusedRequestCutShort(stream, cutShort), is(true));
            assertThat("only the aggregator's report", Http2RequestHeaderLimit.isRefusedRequestCutShort(stream, anotherStreamError), is(false));
        } finally {
            stream.finishAndReleaseAll();
        }
    }

    private ChannelHandler directCodec() {
        return Http2RequestHeaderLimit.frameCodecBuilder(mockServerLogger)
            .initialSettings(Http2RequestHeaderLimit.serverSettings(configuration))
            .build();
    }

    private void connect(ChannelHandler... serverHandlers) {
        server = new EmbeddedChannel(serverHandlers);
        deliver(client, server);
        deliver(server, client);
    }

    /**
     * A request's headers, part of its body, then trailers of exactly {@code size} bytes as RFC 9113 counts them.
     */
    private void sendRequestWithTrailersOfSize(int size) {
        ChannelHandlerContext ctx = client.pipeline().context(clientCodec);
        clientCodec.encoder().writeHeaders(ctx, STREAM, requestHeaders(), 0, false, ctx.newPromise());
        clientCodec.encoder().writeData(ctx, STREAM, Unpooled.copiedBuffer("request body", StandardCharsets.UTF_8), 0, false, ctx.newPromise());
        Http2Headers trailers = new DefaultHttp2Headers().add("x-trailer", "a".repeat(size - "x-trailer".length() - 32));
        clientCodec.encoder().writeHeaders(ctx, STREAM, trailers, 0, true, ctx.newPromise());
        client.flush();
        deliver(client, server);
        deliver(server, client);
    }

    private void sendRequestHeadersOverTheLimit() {
        ChannelHandlerContext ctx = client.pipeline().context(clientCodec);
        clientCodec.encoder().writeHeaders(ctx, STREAM, requestHeaders().add("x-filler", "a".repeat(LIMIT)), 0, false, ctx.newPromise());
        client.flush();
        deliver(client, server);
        deliver(server, client);
    }

    private static Http2Headers requestHeaders() {
        return new DefaultHttp2Headers().method("POST").scheme("http").authority("localhost").path("/upload");
    }

    private static void deliver(EmbeddedChannel from, EmbeddedChannel to) {
        from.runPendingTasks();
        for (ByteBuf written = from.readOutbound(); written != null; written = from.readOutbound()) {
            to.writeInbound(written);
        }
        to.runPendingTasks();
    }
}
