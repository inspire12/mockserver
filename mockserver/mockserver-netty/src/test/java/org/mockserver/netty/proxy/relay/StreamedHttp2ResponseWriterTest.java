package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * A part is written to the stream it names when that stream can take it. When it cannot, the part is released and its
 * write fails as that one stream's, with nothing sent: handed to the encoder it would be an error of the whole
 * connection, answered with a GOAWAY that ends every other stream on the tunnel.
 */
public class StreamedHttp2ResponseWriterTest {

    private EmbeddedChannel proxyClient;
    private Http2Connection connection;

    @Before
    public void connect() {
        connection = new DefaultHttp2Connection(true);
        Http2ConnectionHandler handler = new Http2ConnectionHandlerBuilder().connection(connection).frameListener(new Http2FrameAdapter()).build();
        proxyClient = new EmbeddedChannel(handler, new StreamedHttp2ResponseWriter());
        proxyClient.releaseOutbound();
    }

    @After
    public void close() {
        proxyClient.unsafe().close(proxyClient.voidPromise());
        proxyClient.finishAndReleaseAll();
    }

    @Test
    public void shouldWriteEachPartToTheStreamItNames() throws Exception {
        connection.remote().createStream(3, true);
        connection.remote().createStream(5, true);

        assertThat(proxyClient.writeAndFlush(StreamedHttp2ResponsePart.headers(3, status("200"), false)).isSuccess(), is(true));
        assertThat(proxyClient.writeAndFlush(StreamedHttp2ResponsePart.headers(5, status("200"), false)).isSuccess(), is(true));
        // after the other stream's headers: content written as HTTP content would go to that stream
        assertThat(proxyClient.writeAndFlush(data(3, "for three", true, new AtomicInteger())).isSuccess(), is(true));

        assertThat("ended by its own data", connection.stream(3), is((Http2Stream) null));
        assertThat("the other stream is still open", connection.stream(5).state(), is(Http2Stream.State.HALF_CLOSED_REMOTE));
        assertStillConnected();
    }

    @Test
    public void shouldReportWhenThePartHasBeenWritten() throws Exception {
        connection.remote().createStream(3, true);
        proxyClient.writeAndFlush(StreamedHttp2ResponsePart.headers(3, status("200"), false));
        AtomicInteger written = new AtomicInteger();

        proxyClient.writeAndFlush(data(3, "taken", false, written));

        assertThat(written.get(), is(1));
    }

    @Test
    public void shouldReportOnlyOnceTheClientHasTakenThePart() throws Exception {
        connection.remote().flowController().initialWindowSize(0);
        connection.remote().createStream(3, true);
        proxyClient.writeAndFlush(StreamedHttp2ResponsePart.headers(3, status("200"), false));
        AtomicInteger written = new AtomicInteger();

        ChannelFuture write = proxyClient.writeAndFlush(data(3, "waiting", false, written));
        assertThat("waiting for the client's window", write.isDone(), is(false));
        assertThat(written.get(), is(0));

        connection.remote().flowController().incrementWindowSize(connection.stream(3), 100);
        connection.remote().flowController().writePendingBytes();
        proxyClient.flush();

        assertThat(write.isSuccess(), is(true));
        assertThat(written.get(), is(1));
    }

    @Test
    public void shouldFailOnlyTheStreamOfAPartWhoseStreamHasGone() throws Exception {
        connection.remote().createStream(5, true);
        AtomicInteger written = new AtomicInteger();
        StreamedHttp2ResponsePart part = data(3, "too late", false, written);

        ChannelFuture write = proxyClient.writeAndFlush(part);

        assertFailedAsItsStreamAlone(write, part);
        assertThat("nothing was written, so nothing is returned for it", written.get(), is(0));
    }

    @Test
    public void shouldFailOnlyTheStreamOfAPartWhoseStreamHasBeenReset() throws Exception {
        Http2Stream stream = connection.remote().createStream(3, true);
        proxyClient.writeAndFlush(StreamedHttp2ResponsePart.headers(3, status("200"), false));
        // as the write-stall watcher resets a stream: the stream stays until its RST_STREAM has been written
        stream.resetSent();
        StreamedHttp2ResponsePart part = data(3, "after the reset", false, new AtomicInteger());

        assertFailedAsItsStreamAlone(proxyClient.writeAndFlush(part), part);
    }

    @Test
    public void shouldFailOnlyTheStreamOfAPartWhoseStreamHasAlreadyBeenEnded() throws Exception {
        // the client's request is still being uploaded, so the stream outlives the response's end
        connection.remote().createStream(3, false);
        proxyClient.writeAndFlush(StreamedHttp2ResponsePart.headers(3, status("431"), true));
        assertThat(connection.stream(3).state(), is(Http2Stream.State.HALF_CLOSED_LOCAL));
        StreamedHttp2ResponsePart part = data(3, "after the end", false, new AtomicInteger());

        assertFailedAsItsStreamAlone(proxyClient.writeAndFlush(part), part);
    }

    @Test
    public void shouldFailOnlyTheStreamOfASecondResponseOnAStream() throws Exception {
        connection.remote().createStream(3, true);
        proxyClient.writeAndFlush(StreamedHttp2ResponsePart.headers(3, status("200"), false));
        StreamedHttp2ResponsePart second = StreamedHttp2ResponsePart.headers(3, status("200"), false);

        assertFailedAsItsStreamAlone(proxyClient.writeAndFlush(second), second);
    }

    @Test
    public void shouldFailOnlyTheStreamOfAPartAfterTheTrailers() throws Exception {
        connection.remote().createStream(3, false);
        proxyClient.writeAndFlush(StreamedHttp2ResponsePart.headers(3, status("200"), false));
        assertThat(proxyClient.writeAndFlush(StreamedHttp2ResponsePart.headers(3, new DefaultHttp2Headers().set("grpc-status", "0"), true)).isSuccess(), is(true));
        StreamedHttp2ResponsePart again = StreamedHttp2ResponsePart.headers(3, new DefaultHttp2Headers().set("grpc-status", "0"), true);

        assertFailedAsItsStreamAlone(proxyClient.writeAndFlush(again), again);
    }

    @Test
    public void shouldWriteTrailersAfterTheHeaders() throws Exception {
        connection.remote().createStream(3, true);

        assertThat(proxyClient.writeAndFlush(StreamedHttp2ResponsePart.headers(3, status("200"), false)).isSuccess(), is(true));
        assertThat(proxyClient.writeAndFlush(StreamedHttp2ResponsePart.headers(3, new DefaultHttp2Headers().set("grpc-status", "0"), true)).isSuccess(), is(true));

        assertThat("ended by its trailers", connection.stream(3), is((Http2Stream) null));
        assertStillConnected();
    }

    /**
     * The part waits for the client's window and its stream closes first, so its write fails. Its bytes are reported
     * all the same: the loopback must not go on waiting for them.
     */
    @Test
    public void shouldReportAPartWhoseWriteFails() throws Exception {
        connection.remote().flowController().initialWindowSize(0);
        connection.remote().createStream(3, true);
        proxyClient.writeAndFlush(StreamedHttp2ResponsePart.headers(3, status("200"), false));
        AtomicInteger written = new AtomicInteger();
        ChannelFuture write = proxyClient.writeAndFlush(data(3, "never taken", false, written));
        assertThat(written.get(), is(0));

        connection.stream(3).close();

        assertThat(write.isDone() && !write.isSuccess(), is(true));
        assertThat(written.get(), is(1));
    }

    @Test
    public void shouldPassOnEveryOtherMessage() {
        proxyClient.pipeline().remove(Http2ConnectionHandler.class);
        DefaultHttpContent other = new DefaultHttpContent(Unpooled.copiedBuffer("other", StandardCharsets.UTF_8));

        proxyClient.writeAndFlush(other);

        assertThat(proxyClient.readOutbound(), is(sameInstance((Object) other)));
        other.release();
    }

    private void assertFailedAsItsStreamAlone(ChannelFuture write, StreamedHttp2ResponsePart part) {
        assertThat(write.isDone(), is(true));
        assertThat(Http2CodecUtil.getEmbeddedHttp2Exception(write.cause()), instanceOf(Http2Exception.StreamException.class));
        assertThat(((Http2Exception.StreamException) Http2CodecUtil.getEmbeddedHttp2Exception(write.cause())).streamId(), is(part.streamId()));
        assertThat(Http2CodecUtil.getEmbeddedHttp2Exception(write.cause()).error(), is(Http2Error.STREAM_CLOSED));
        if (part.headers() == null) {
            assertThat("released", part.refCnt(), is(0));
        }
        assertStillConnected();
    }

    // an error of the whole connection sends a GOAWAY and closes it
    private void assertStillConnected() {
        assertThat(proxyClient.isActive(), is(true));
        assertThat(connection.goAwaySent(), is(false));
    }

    private static Http2Headers status(String status) {
        return new DefaultHttp2Headers().status(status);
    }

    private static StreamedHttp2ResponsePart data(int streamId, String data, boolean endOfStream, AtomicInteger written) {
        ByteBuf buffer = Unpooled.copiedBuffer(data, StandardCharsets.UTF_8);
        return StreamedHttp2ResponsePart.data(streamId, buffer, endOfStream, written::incrementAndGet);
    }
}
