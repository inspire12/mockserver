package org.mockserver.netty.connection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersEncoder;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;
import org.mockserver.socket.tls.SniHandler;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;

/**
 * Drives {@link InboundConnectionIdleHandler} and {@link HttpExchangeTracker} on an {@link EmbeddedChannel}
 * with a frozen clock, so "idle for the timeout" is exact rather than a sleep.
 */
public class InboundConnectionIdleHandlerTest {

    private static final long IDLE_MILLIS = 1_000;

    private EmbeddedChannel channel;

    @After
    public void closeChannel() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel httpConnection() {
        channel = new EmbeddedChannel();
        channel.freezeTime();
        channel.pipeline().addLast(new InboundConnectionIdleHandler(IDLE_MILLIS, new MockServerLogger()));
        channel.pipeline().addLast(HttpExchangeTracker.INSTANCE);
        return channel;
    }

    private void idleFor(long millis) {
        channel.advanceTimeBy(millis, MILLISECONDS);
        channel.runScheduledPendingTasks();
    }

    private void receiveRequest() {
        channel.writeInbound(new DefaultHttpRequest(HTTP_1_1, HttpMethod.GET, "/"));
        releaseInbound();
    }

    private void releaseInbound() {
        Object inbound;
        while ((inbound = channel.readInbound()) != null) {
            ReferenceCountUtil.release(inbound);
        }
    }

    private void sendResponse(HttpResponseStatus status) {
        channel.writeOutbound(new DefaultFullHttpResponse(HTTP_1_1, status));
        releaseOutbound();
    }

    private void releaseOutbound() {
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(outbound);
        }
    }

    @Test
    public void shouldCloseConnectionThatSendsNothing() {
        httpConnection();

        idleFor(IDLE_MILLIS - 1);
        assertThat("closed before the timeout", channel.isOpen(), is(true));

        idleFor(1);
        assertThat(channel.isOpen(), is(false));
    }

    @Test
    public void shouldCloseKeepAliveConnectionOnlyAfterTimeoutFollowingItsLastResponse() {
        httpConnection();
        receiveRequest();
        idleFor(IDLE_MILLIS / 2);
        sendResponse(HttpResponseStatus.OK);

        idleFor(IDLE_MILLIS - 1);
        assertThat("an exchange that just finished restarts the idle clock", channel.isOpen(), is(true));

        idleFor(1);
        assertThat(channel.isOpen(), is(false));
    }

    @Test
    public void shouldNotCloseConnectionWhileResponseIsOutstanding() {
        httpConnection();
        receiveRequest();

        idleFor(IDLE_MILLIS * 10);

        assertThat("a delayed or paused response keeps the connection open", channel.isOpen(), is(true));
    }

    @Test
    public void shouldNotCloseConnectionWhileStreamingResponseIsIncomplete() {
        httpConnection();
        receiveRequest();
        channel.writeOutbound(new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK));
        channel.writeOutbound(new DefaultHttpContent(Unpooled.copiedBuffer(new byte[]{'a'})));
        releaseOutbound();

        idleFor(IDLE_MILLIS * 10);
        assertThat("a stream with long gaps between chunks stays open", channel.isOpen(), is(true));

        channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT);
        releaseOutbound();
        idleFor(IDLE_MILLIS);
        assertThat(channel.isOpen(), is(false));
    }

    @Test
    public void shouldNotTreatContinueAsTheEndOfTheExchange() {
        httpConnection();
        receiveRequest();
        sendResponse(HttpResponseStatus.CONTINUE);

        idleFor(IDLE_MILLIS * 10);

        assertThat("100 Continue precedes the real response", channel.isOpen(), is(true));
    }

    @Test
    public void shouldNeverCloseConnectionUpgradedToWebSocket() {
        httpConnection();
        receiveRequest();
        sendResponse(HttpResponseStatus.SWITCHING_PROTOCOLS);

        idleFor(IDLE_MILLIS * 10);

        assertThat(channel.isOpen(), is(true));
    }

    @Test
    public void shouldNeverCloseConnectionMarkedLongLived() {
        httpConnection();

        InboundConnectionActivity.markLongLived(channel);
        channel.runPendingTasks();
        idleFor(IDLE_MILLIS * 10);

        assertThat("WebSockets, binary relays and a tunnel's loopback leg are exempt", channel.isOpen(), is(true));
        assertThat("their traffic no longer pays for idle tracking", channel.pipeline().get(InboundConnectionIdleHandler.class), is(nullValue()));
        assertThat(channel.pipeline().get(HttpExchangeTracker.class), is(nullValue()));
    }

    @Test
    public void shouldNotCloseConnectionWhileMockServerHasStoppedReading() {
        httpConnection();
        channel.config().setAutoRead(false);

        idleFor(IDLE_MILLIS * 10);
        assertThat("silence caused by MockServer (connection delay, back-pressure) is not the client's", channel.isOpen(), is(true));

        channel.config().setAutoRead(true);
        idleFor(IDLE_MILLIS);
        assertThat(channel.isOpen(), is(false));
    }

    @Test
    public void shouldNotCloseConnectionWhileItsCertificateIsBeingGenerated() {
        httpConnection();
        channel.attr(SniHandler.SSL_CONTEXT_PENDING).set(Boolean.TRUE);

        idleFor(IDLE_MILLIS * 10);
        assertThat(channel.isOpen(), is(true));

        channel.attr(SniHandler.SSL_CONTEXT_PENDING).set(null);
        idleFor(IDLE_MILLIS);
        assertThat(channel.isOpen(), is(false));
    }

    @Test
    public void shouldNotCloseConnectionWhileTlsHandshakeIsInProgress() throws Exception {
        httpConnection();
        SslHandler sslHandler = SslContextBuilder.forClient().build().newHandler(channel.alloc());
        // the handshake's own timeout is what bounds this state; switched off so only the idle handler acts
        sslHandler.setHandshakeTimeoutMillis(0);
        channel.pipeline().addFirst(sslHandler);
        releaseOutbound();

        idleFor(IDLE_MILLIS * 10);

        assertThat(channel.isOpen(), is(true));
    }

    @Test
    public void shouldCountPipelinedExchangesIndependently() {
        httpConnection();
        receiveRequest();
        receiveRequest();
        sendResponse(HttpResponseStatus.OK);

        idleFor(IDLE_MILLIS * 10);
        assertThat("the second pipelined request is still outstanding", channel.isOpen(), is(true));

        sendResponse(HttpResponseStatus.OK);
        idleFor(IDLE_MILLIS);
        assertThat(channel.isOpen(), is(false));
    }

    @Test
    public void shouldCloseKeepAliveConnectionOnceIdleAfterAnExchangeEndsOutsideTheCodec() {
        httpConnection();
        receiveRequest();
        channel.pipeline().fireUserEventTriggered(HttpExchangeEndedEvent.INSTANCE);

        idleFor(IDLE_MILLIS - 1);
        assertThat(channel.isOpen(), is(true));

        idleFor(1);
        assertThat("an exchange answered with raw bytes no longer keeps the connection busy forever", channel.isOpen(), is(false));
    }

    @Test
    public void shouldEndOnlyOneExchangeOnAnExchangeEndedEvent() {
        httpConnection();
        receiveRequest();
        receiveRequest();
        channel.pipeline().fireUserEventTriggered(HttpExchangeEndedEvent.INSTANCE);

        idleFor(IDLE_MILLIS * 10);
        assertThat("the second pipelined request is still outstanding", channel.isOpen(), is(true));

        sendResponse(HttpResponseStatus.OK);
        idleFor(IDLE_MILLIS);
        assertThat(channel.isOpen(), is(false));
    }

    @Test
    public void shouldIgnoreExchangeEndedEventWithNoExchangeRatherThanGoNegative() {
        httpConnection();
        channel.pipeline().fireUserEventTriggered(HttpExchangeEndedEvent.INSTANCE);
        receiveRequest();

        idleFor(IDLE_MILLIS * 10);

        assertThat("the unanswered request must still count", channel.isOpen(), is(true));
    }

    @Test
    public void shouldIgnoreResponseWithoutRequestRatherThanGoNegative() {
        httpConnection();
        sendResponse(HttpResponseStatus.UPGRADE_REQUIRED);
        receiveRequest();

        idleFor(IDLE_MILLIS * 10);

        assertThat("the unanswered request must still count", channel.isOpen(), is(true));
    }

    @Test
    public void shouldNotCloseHttp2ConnectionWithActiveStream() throws Exception {
        channel = new EmbeddedChannel();
        channel.freezeTime();
        channel.pipeline().addLast(new InboundConnectionIdleHandler(IDLE_MILLIS, new MockServerLogger()));
        channel.pipeline().addLast(Http2FrameCodecBuilder.forServer().build());

        ByteBuf openStream = Unpooled.buffer();
        openStream.writeBytes(Http2CodecUtil.connectionPrefaceBuf());
        writeFrameHeader(openStream, 0, 0x4, 0, 0);
        ByteBuf headerBlock = Unpooled.buffer();
        Http2Headers headers = new DefaultHttp2Headers().method("GET").path("/").scheme("http").authority("localhost");
        new DefaultHttp2HeadersEncoder().encodeHeaders(1, headers, headerBlock);
        writeFrameHeader(openStream, headerBlock.readableBytes(), 0x1, 0x4, 1);
        openStream.writeBytes(headerBlock);
        headerBlock.release();
        channel.writeInbound(openStream);
        releaseInbound();
        releaseOutbound();

        idleFor(IDLE_MILLIS * 10);
        assertThat("an open HTTP/2 stream (gRPC stream, SSE, paused exchange) keeps the connection open", channel.isOpen(), is(true));

        ByteBuf resetStream = Unpooled.buffer();
        writeFrameHeader(resetStream, 4, 0x3, 0, 1);
        resetStream.writeInt(0x8);
        channel.writeInbound(resetStream);
        releaseInbound();
        releaseOutbound();

        idleFor(IDLE_MILLIS);
        assertThat("with no stream left the connection is idle", channel.isOpen(), is(false));
    }

    private static void writeFrameHeader(ByteBuf out, int length, int type, int flags, int streamId) {
        out.writeMedium(length);
        out.writeByte(type);
        out.writeByte(flags);
        out.writeInt(streamId);
    }
}
