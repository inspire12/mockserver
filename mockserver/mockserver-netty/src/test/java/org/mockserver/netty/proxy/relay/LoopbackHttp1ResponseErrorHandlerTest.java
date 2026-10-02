package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * What {@link LoopbackHttp1ResponseErrorHandler} writes to the proxy client when the HTTP/1.1 loopback fails to
 * decode a response, and what it lets through to {@link DownstreamProxyRelayHandler} afterwards.
 */
public class LoopbackHttp1ResponseErrorHandlerTest {

    private EmbeddedChannel proxyClientChannel;
    private EmbeddedChannel loopbackChannel;
    private final List<Throwable> exceptionsPassedOn = new ArrayList<>();

    @Before
    public void setUp() {
        proxyClientChannel = new EmbeddedChannel();
        loopbackChannel = new EmbeddedChannel(new LoopbackHttp1ResponseErrorHandler(proxyClientChannel), new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                exceptionsPassedOn.add(cause);
            }
        });
    }

    @After
    public void tearDown() {
        loopbackChannel.finishAndReleaseAll();
        proxyClientChannel.finishAndReleaseAll();
    }

    @Test
    public void shouldAnswerA502WhenTheResponseFailsToDecodeBeforeItsHead() {
        DecoderException corrupt = new DecoderException("corrupt");

        loopbackChannel.pipeline().fireExceptionCaught(corrupt);

        FullHttpResponse written = proxyClientChannel.readOutbound();
        assertThat(written.status(), is(HttpResponseStatus.BAD_GATEWAY));
        assertThat(written.headers().get(HttpHeaderNames.CONNECTION), is("close"));
        assertThat(written.headers().getInt(HttpHeaderNames.CONTENT_LENGTH), is(0));
        written.release();
        assertThat("passed on, so the relay still logs and closes", exceptionsPassedOn, contains(corrupt));
    }

    @Test
    public void shouldWriteNothingToTheClientWhenAStreamedResponseFailsAfterItsHead() {
        HttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        loopbackChannel.writeInbound(head);
        assertThat(loopbackChannel.readInbound(), is((Object) head));
        DecoderException corrupt = new DecoderException("corrupt");

        loopbackChannel.pipeline().fireExceptionCaught(corrupt);

        assertThat("the head has gone, so a 502 would corrupt the response", proxyClientChannel.readOutbound(), nullValue());
        assertThat(exceptionsPassedOn, contains(corrupt));
    }

    @Test
    public void shouldReleaseAndNotForwardWhatArrivesAfterTheFault() {
        loopbackChannel.writeInbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        loopbackChannel.<HttpResponse>readInbound();
        loopbackChannel.pipeline().fireExceptionCaught(new DecoderException("corrupt"));
        HttpContent afterTheFault = new DefaultHttpContent(Unpooled.copiedBuffer(new byte[]{1, 2, 3}));

        loopbackChannel.writeInbound(afterTheFault);

        assertThat(loopbackChannel.readInbound(), nullValue());
        assertThat(afterTheFault.refCnt(), is(0));
    }

    @Test
    public void shouldAnswerA502ForTheNextResponseOnceTheLastOneHasEnded() {
        FullHttpResponse previous = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        loopbackChannel.writeInbound(previous);
        loopbackChannel.<FullHttpResponse>readInbound().release();

        loopbackChannel.pipeline().fireExceptionCaught(new DecoderException("corrupt"));

        FullHttpResponse written = proxyClientChannel.readOutbound();
        assertThat(written.status(), is(HttpResponseStatus.BAD_GATEWAY));
        written.release();
    }

    @Test
    public void shouldNotAnswerA502ForAFailureThatIsNotTheRelayDecoding() {
        IOException reset = new IOException("Connection reset by peer");

        loopbackChannel.pipeline().fireExceptionCaught(reset);

        assertThat("a dropped loopback stays a dropped connection", proxyClientChannel.readOutbound(), nullValue());
        assertThat(exceptionsPassedOn, contains(reset));
    }
}
