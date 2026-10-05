package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.ssl.SslClosedEngineException;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The parts of a response relayed as it is streamed are relayed, dropped and logged by the same rules as whole
 * responses: a write that fails for one stream ends nothing else, one that fails for the connection ends the relay,
 * and once the relay has ended every further part is released without a write.
 */
public class DownstreamProxyRelayHandlerStreamedPartTest {

    private final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private final MockServerLogger logger = new MockServerLogger(DownstreamProxyRelayHandlerStreamedPartTest.class) {
        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry);
        }
    };

    @Test
    public void shouldLeaveTheRelayOpenWhenThePartsStreamHasGone() {
        List<Object> written = new ArrayList<>();
        // as the client leg fails the part of a stream its client has reset
        EmbeddedChannel proxyClient = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (((StreamedHttp2ResponsePart) msg).streamId() == 3) {
                    ReferenceCountUtil.release(msg);
                    promise.setFailure(Http2Exception.streamError(3, Http2Error.STREAM_CLOSED, "Stream closed before write could take place"));
                } else {
                    written.add(msg);
                    promise.setSuccess();
                }
            }
        });
        EmbeddedChannel loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(logger, proxyClient));

        loopback.writeInbound(data(3, "for the stream that has gone"));
        loopback.writeInbound(data(5, "for another"));

        assertThat("nothing logged above DEBUG", logged.stream().filter(entry -> entry.getLogLevel().toInt() > Level.DEBUG.toInt()).count(), is(0L));
        assertThat("the loopback is still open", loopback.isOpen(), is(true));
        assertThat("the proxy client is still open", proxyClient.isOpen(), is(true));
        assertThat("the other stream's part was relayed", written, hasSize(1));
        written.forEach(ReferenceCountUtil::release);
        loopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    @Test
    public void shouldEndTheRelayAndLogThePartWithoutItsHeaderValuesWhenAWriteFailsForTheConnection() {
        EmbeddedChannel proxyClient = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                promise.setFailure(new IOException("Broken pipe"));
            }
        });
        EmbeddedChannel loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(logger, proxyClient));
        StreamedHttp2ResponsePart head = StreamedHttp2ResponsePart.headers(3, new DefaultHttp2Headers().status("200").set("set-cookie", "session=secret-value"), false);

        loopback.writeInbound(head);

        assertThat("the loopback was closed", loopback.isOpen(), is(false));
        assertThat("one failed write logged", logged, hasSize(1));
        String message = logged.get(0).getMessage(configuration().redactSecretsInLog(false));
        assertThat(message, containsString("exception while returning writing"));
        assertThat(message, containsString("StreamedHttp2ResponsePart(stream: 3, headers: 2)"));
        assertThat("a header's value is never in the text logged", message, not(containsString("secret-value")));
        loopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    /**
     * The failed write has released the part's data by the time it is logged, so its text is read from nothing in it.
     */
    @Test
    public void shouldEndTheRelayAndLogADataPartWhoseWriteReleasedIt() {
        EmbeddedChannel proxyClient = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                promise.setFailure(new IOException("Broken pipe"));
            }
        });
        EmbeddedChannel loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(logger, proxyClient));
        StreamedHttp2ResponsePart body = data(3, "body");

        loopback.writeInbound(body);

        assertThat(body.refCnt(), is(0));
        assertThat("the loopback was closed", loopback.isOpen(), is(false));
        assertThat("one failed write logged", logged, hasSize(1));
        assertThat(logged.get(0).getMessage(configuration().redactSecretsInLog(false)), containsString("StreamedHttp2ResponsePart(stream: 3, data: 4 bytes)"));
        loopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    /**
     * A client that closes its TLS session during a streamed response has gone: the relay ends without an error
     * logged, though the client's socket is still open when the write fails.
     */
    @Test
    public void shouldEndTheRelayWithoutLoggingWhenTheClientHasClosedItsTlsSession() {
        EmbeddedChannel proxyClient = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                promise.setFailure(new SslClosedEngineException("SSLEngine closed already"));
            }
        });
        EmbeddedChannel loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(logger, proxyClient));

        loopback.writeInbound(data(3, "body"));

        assertThat("the loopback was closed", loopback.isOpen(), is(false));
        assertThat("nothing logged above DEBUG", logged.stream().filter(entry -> entry.getLogLevel().toInt() > Level.DEBUG.toInt()).count(), is(0L));
        loopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    /**
     * The client has gone with a request still being written, so the loopback is left open and read. The write of
     * the first part to the client that has gone ends the relay, and no later part is written at all.
     */
    @Test
    public void shouldReleaseEachPartUnwrittenOnceTheRelayHasEnded() {
        List<Object> partsWritten = new ArrayList<>();
        EmbeddedChannel proxyClient = new EmbeddedChannel() {
            @Override
            public ChannelFuture writeAndFlush(Object msg) {
                if (msg instanceof StreamedHttp2ResponsePart) {
                    partsWritten.add(msg);
                }
                return super.writeAndFlush(msg);
            }
        };
        SlowSocket loopbackSocket = new SlowSocket();
        EmbeddedChannel loopback = new EmbeddedChannel(loopbackSocket, new DownstreamProxyRelayHandler(logger, proxyClient));
        proxyClient.pipeline().addLast(new UpstreamProxyRelayHandler(logger, proxyClient, loopback, "localhost", 80, 1024));
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", Unpooled.copiedBuffer("upload", StandardCharsets.UTF_8));
        proxyClient.writeInbound(request);
        proxyClient.close();
        StreamedHttp2ResponsePart first = data(3, "data: one");
        StreamedHttp2ResponsePart second = data(3, "data: two");
        StreamedHttp2ResponsePart third = data(5, "data: three");

        loopback.writeInbound(first);
        assertThat("the write to the client that has gone ended the relay", first.refCnt(), is(0));
        loopback.writeInbound(second, third);

        assertThat("only the part that ended the relay was written", partsWritten, contains(sameInstance((Object) first)));
        assertThat(second.refCnt(), is(0));
        assertThat(third.refCnt(), is(0));
        assertThat("left open for the request still being written", loopback.isOpen(), is(true));
        assertThat("nothing logged above DEBUG", logged.stream().filter(entry -> entry.getLogLevel().toInt() > Level.DEBUG.toInt()).count(), is(0L));
        loopbackSocket.drain();
        loopback.runPendingTasks();
        loopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    private static StreamedHttp2ResponsePart data(int streamId, String data) {
        return StreamedHttp2ResponsePart.data(streamId, Unpooled.copiedBuffer(data, StandardCharsets.UTF_8), false, () -> {
        });
    }

    /**
     * A socket that is taking nothing: every write waits, in order, until it drains.
     */
    private static final class SlowSocket extends ChannelOutboundHandlerAdapter {
        private final List<Object> messages = new ArrayList<>();
        private final List<ChannelPromise> promises = new ArrayList<>();
        private ChannelHandlerContext ctx;
        private boolean drained;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (drained) {
                ctx.write(msg, promise);
                return;
            }
            this.ctx = ctx;
            messages.add(msg);
            promises.add(promise);
        }

        void drain() {
            drained = true;
            for (int i = 0; i < messages.size(); i++) {
                ctx.write(messages.get(i), promises.get(i));
            }
            ctx.flush();
        }
    }
}
