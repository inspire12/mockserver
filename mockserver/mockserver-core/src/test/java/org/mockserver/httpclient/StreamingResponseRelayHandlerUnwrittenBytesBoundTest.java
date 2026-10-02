package org.mockserver.httpclient;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Message;
import org.mockserver.model.StreamingBody;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

/**
 * A streamed upstream response whose relayed bytes, not yet written to the client, pass the bound fails its
 * {@link StreamingBody} and closes the upstream; the rest of the read that passed it is released, not relayed.
 */
public class StreamingResponseRelayHandlerUnwrittenBytesBoundTest {

    private static final int BOUND = 4 * 1024;

    @Test
    public void shouldCloseTheUpstreamAndFailTheBodyWhenUnwrittenBytesPassTheBound() throws Exception {
        EmbeddedChannel upstream = new EmbeddedChannel(new StreamingResponseRelayHandler(Configuration.configuration(), new MockServerLogger(), BOUND));
        CompletableFuture<Message> future = new CompletableFuture<>();
        upstream.attr(RESPONSE_FUTURE).set(future);
        upstream.writeInbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        StreamingBody body = ((HttpResponse) future.get(10, TimeUnit.SECONDS)).getStreamingBody();
        AtomicInteger delivered = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        // a client that never finishes a write
        body.subscribe(chunk -> delivered.addAndGet(chunk.readableBytes()), () -> {
        }, error::set);

        HttpContent[] oneRead = new HttpContent[8];
        for (int i = 0; i < oneRead.length; i++) {
            oneRead[i] = new DefaultHttpContent(Unpooled.buffer(1024).writeZero(1024));
        }
        upstream.writeInbound((Object[]) oneRead);

        assertThat(upstream.isOpen(), is(false));
        assertThat(error.get(), instanceOf(StreamingBody.UnwrittenBytesLimitExceededException.class));
        assertThat("only the pieces within the bound reach the client", delivered.get(), is(BOUND));
        for (HttpContent piece : oneRead) {
            assertThat(piece.refCnt(), is(0));
        }
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldRelayManyTimesTheBoundWhileEachChunkIsWritten() throws Exception {
        EmbeddedChannel upstream = new EmbeddedChannel(new StreamingResponseRelayHandler(Configuration.configuration(), new MockServerLogger(), BOUND));
        CompletableFuture<Message> future = new CompletableFuture<>();
        upstream.attr(RESPONSE_FUTURE).set(future);
        upstream.writeInbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        StreamingBody body = ((HttpResponse) future.get(10, TimeUnit.SECONDS)).getStreamingBody();
        AtomicInteger delivered = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        body.subscribe(chunk -> {
            delivered.addAndGet(chunk.readableBytes());
            body.chunkWritten(chunk.readableBytes());
        }, () -> {
        }, error::set);

        for (int i = 0; i < 100; i++) {
            upstream.writeInbound(new DefaultHttpContent(Unpooled.buffer(1024).writeZero(1024)));
        }

        assertThat(upstream.isOpen(), is(true));
        assertThat(delivered.get(), is(100 * 1024));
        assertThat(error.get() == null, is(true));
        upstream.finishAndReleaseAll();
    }
}
