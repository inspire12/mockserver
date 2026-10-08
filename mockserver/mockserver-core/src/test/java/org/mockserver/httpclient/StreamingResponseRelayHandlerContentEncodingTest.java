package org.mockserver.httpclient;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;
import org.mockserver.codec.BoundedZstdHttpContentDecompressor;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Message;
import org.mockserver.model.StreamingBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

/**
 * A streamed upstream response reaches the client with the {@code Content-Encoding} its body is in: none when the
 * forward client's decompressor decoded it, and the upstream's when it did not, with the bytes as the upstream sent them.
 */
public class StreamingResponseRelayHandlerContentEncodingTest {

    private static final byte[] EVENTS = "data: one\n\ndata: two\n\n".getBytes(StandardCharsets.US_ASCII);

    @Test
    public void shouldKeepAContentEncodingTheDecompressorDoesNotDecode() throws Exception {
        byte[] encoded = {0x1f, (byte) 0x8b, 0x08, 0x00, 0x7f, 0x00, 0x01, 0x02};

        Relayed relayed = relay("x-unknown", encoded);

        assertThat(relayed.response.getHeader("Content-Encoding"), contains("x-unknown"));
        assertThat("the bytes are the upstream's", relayed.body, is(encoded));
    }

    @Test
    public void shouldKeepAListOfContentCodingsTheDecompressorDoesNotDecode() throws Exception {
        byte[] encoded = gzip(EVENTS);

        Relayed relayed = relay("gzip, x-unknown", encoded);

        assertThat(relayed.response.getHeader("Content-Encoding"), contains("gzip, x-unknown"));
        assertThat(relayed.body, is(encoded));
    }

    @Test
    public void shouldDropTheContentEncodingOfABodyTheDecompressorDecoded() throws Exception {
        Relayed relayed = relay("gzip", gzip(EVENTS));

        assertThat(relayed.response.getHeader("Content-Encoding"), is(empty()));
        assertThat("the bytes are decoded", relayed.body, is(EVENTS));
    }

    @Test
    public void shouldDropAnUpstreamContentLengthWhateverTheCoding() throws Exception {
        byte[] encoded = {1, 2, 3};

        Relayed relayed = relay("x-unknown", encoded);

        assertThat(relayed.response.getHeader("Content-Length"), is(empty()));
    }

    private static Relayed relay(String contentEncoding, byte[] body) throws Exception {
        EmbeddedChannel upstream = new EmbeddedChannel(
            new BoundedZstdHttpContentDecompressor(),
            new StreamingResponseRelayHandler(Configuration.configuration(), new MockServerLogger(), 0));
        try {
            CompletableFuture<Message> future = new CompletableFuture<>();
            upstream.attr(RESPONSE_FUTURE).set(future);
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream")
                .set(HttpHeaderNames.CONTENT_ENCODING, contentEncoding)
                .set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            upstream.writeInbound(head);
            HttpResponse response = (HttpResponse) future.get(10, TimeUnit.SECONDS);
            StreamingBody streamingBody = response.getStreamingBody();
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            CompletableFuture<Void> complete = new CompletableFuture<>();
            streamingBody.subscribe(chunk -> {
                received.writeBytes(ByteBufUtil.getBytes(chunk));
                streamingBody.chunkWritten(chunk.readableBytes());
            }, () -> complete.complete(null), complete::completeExceptionally);
            upstream.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(body)));
            upstream.runPendingTasks();
            complete.get(10, TimeUnit.SECONDS);
            return new Relayed(response, received.toByteArray());
        } finally {
            upstream.finishAndReleaseAll();
        }
    }

    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(plain);
        }
        return compressed.toByteArray();
    }

    private static final class Relayed {
        private final HttpResponse response;
        private final byte[] body;

        private Relayed(HttpResponse response, byte[] body) {
            this.response = response;
            this.body = body;
        }
    }
}
