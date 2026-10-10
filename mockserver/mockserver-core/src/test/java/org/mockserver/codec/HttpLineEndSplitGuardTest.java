package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectDecoder;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.TooLongHttpHeaderException;
import io.netty.handler.codec.http.TooLongHttpLineException;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Wire bytes are written in reads split at every position to an HTTP/1.1 codec with the guard's two handlers around
 * it, and the decoded objects are read after them. Netty's own decoder refuses a section of exactly its limit when a
 * read ends between the last line's CR and LF; with the guard the limit is the same however the bytes are split.
 */
public class HttpLineEndSplitGuardTest {

    private static final int LIMIT = 64;
    private static final int LINE_LIMIT = 40;
    private static final String CHUNKED = "transfer-encoding: chunked";

    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<Object> read = new ArrayList<>();

    @After
    public void closeChannels() {
        for (EmbeddedChannel channel : channels) {
            channel.finishAndReleaseAll();
        }
        releaseAll(read);
    }

    @Test
    public void shouldShowNettyRefusesAHeaderSectionOfExactlyTheLimitWhenAReadEndsBetweenItsLastCrAndLf() {
        String request = "GET / HTTP/1.1\r\n" + section(LIMIT) + "\r\n";
        int afterLastCr = request.length() - 3;
        HttpServerCodec codec = serverCodec();
        EmbeddedChannel channel = track(new EmbeddedChannel(codec));

        channel.writeInbound(buffer(request.substring(0, afterLastCr)), buffer(request.substring(afterLastCr)));

        assertThat(failure(decoded(channel)), instanceOf(TooLongHttpHeaderException.class));
    }

    @Test
    public void shouldAcceptARequestHeaderSectionOfExactlyTheLimitHoweverItIsSplit() {
        String request = "GET / HTTP/1.1\r\n" + section(LIMIT) + "\r\n";

        for (List<String> reads : splits(request)) {
            List<HttpObject> decoded = decoded(writeInReads(server(), reads));

            assertThat(reads.toString(), failure(decoded), nullValue());
            assertThat(reads.toString(), ((HttpRequest) decoded.get(0)).headers().get("x-f").length(), is(LIMIT - 5));
            assertThat(reads.toString(), last(decoded), instanceOf(LastHttpContent.class));
        }
    }

    @Test
    public void shouldRefuseARequestHeaderSectionOneByteOverTheLimitHoweverItIsSplit() {
        String request = "GET / HTTP/1.1\r\n" + section(LIMIT + 1) + "\r\n";

        for (List<String> reads : splits(request)) {
            assertThat(reads.toString(), failure(decoded(writeInReads(server(), reads))), instanceOf(TooLongHttpHeaderException.class));
        }
    }

    @Test
    public void shouldLimitTrailersAsExactlyHoweverTheyAreSplit() {
        String head = "POST / HTTP/1.1\r\n" + CHUNKED + "\r\n\r\n5\r\nhello\r\n0\r\n";
        int trailerBudget = LIMIT - CHUNKED.length();

        for (List<String> reads : splits(head + section(trailerBudget) + "\r\n")) {
            List<HttpObject> decoded = decoded(writeInReads(server(), reads));

            assertThat(reads.toString(), failure(decoded), nullValue());
            assertThat(reads.toString(), ((LastHttpContent) last(decoded)).trailingHeaders().get("x-f").length(), is(trailerBudget - 5));
            assertThat(reads.toString(), content(decoded), is("hello"));
        }
        for (List<String> reads : splits(head + section(trailerBudget + 1) + "\r\n")) {
            assertThat(reads.toString(), failure(decoded(writeInReads(server(), reads))), instanceOf(TooLongHttpHeaderException.class));
        }
    }

    @Test
    public void shouldLimitARequestLineAsExactlyHoweverItIsSplit() {
        String exact = "GET /" + "a".repeat(LINE_LIMIT - 14) + " HTTP/1.1";
        assertThat(exact.length(), is(LINE_LIMIT));

        for (List<String> reads : splits(exact + "\r\n\r\n")) {
            assertThat(reads.toString(), failure(decoded(writeInReads(server(), reads))), nullValue());
        }
        for (List<String> reads : splits("GET /a" + exact.substring(5) + "\r\n\r\n")) {
            assertThat(reads.toString(), failure(decoded(writeInReads(server(), reads))), instanceOf(TooLongHttpLineException.class));
        }
    }

    @Test
    public void shouldPassOnABodyEndingInACrAtOnce() {
        String head = "POST / HTTP/1.1\r\ncontent-length: 2\r\n\r\n";

        EmbeddedChannel oneRead = server();
        oneRead.writeInbound(buffer(head + "a\r"));
        List<HttpObject> decoded = decoded(oneRead);
        assertThat(content(decoded), is("a\r"));
        assertThat(last(decoded), instanceOf(LastHttpContent.class));

        EmbeddedChannel twoReads = server();
        twoReads.writeInbound(buffer(head));
        twoReads.writeInbound(buffer("a\r"));
        decoded = decoded(twoReads);
        assertThat(content(decoded), is("a\r"));
        assertThat(last(decoded), instanceOf(LastHttpContent.class));
    }

    @Test
    public void shouldAcceptAResponseHeaderSectionOfExactlyTheLimitHoweverItIsSplitAndRefuseOneByteMore() {
        for (List<String> reads : splits("HTTP/1.1 200 OK\r\n" + section(LIMIT, "content-length: 0") + "\r\n")) {
            List<HttpObject> decoded = decoded(writeInReads(client(HttpMethod.GET), reads));

            assertThat(reads.toString(), failure(decoded), nullValue());
            assertThat(reads.toString(), decoded.get(0), instanceOf(HttpResponse.class));
            assertThat(reads.toString(), last(decoded), instanceOf(LastHttpContent.class));
        }
        for (List<String> reads : splits("HTTP/1.1 200 OK\r\n" + section(LIMIT + 1, "content-length: 0") + "\r\n")) {
            assertThat(reads.toString(), failure(decoded(writeInReads(client(HttpMethod.GET), reads))), instanceOf(TooLongHttpHeaderException.class));
        }
    }

    @Test
    public void shouldPassOnTheBytesAfterASwitchingProtocolsResponseAtOnce() {
        EmbeddedChannel channel = client(HttpMethod.GET);

        channel.writeInbound(buffer("HTTP/1.1 101 Switching Protocols\r\nupgrade: websocket\r\nconnection: upgrade\r\nsec-websocket-accept: x\r\n\r\n\r"));
        channel.writeInbound(buffer("\r"));

        assertThat(describe(decoded(channel)), contains("101 Switching Protocols", "last", "bytes:\r", "bytes:\r"));
    }

    @Test
    public void shouldPassOnTheBytesAfterAConnectIsAnsweredAtOnce() {
        EmbeddedChannel channel = client(HttpMethod.CONNECT);

        channel.writeInbound(buffer("HTTP/1.1 200 Connection established\r\n\r\n"));
        channel.writeInbound(buffer("\r"));

        assertThat(describe(decoded(channel)), contains("200 Connection established", "last", "bytes:\r"));
    }

    @Test
    public void shouldHoldACrThatEndsAReadOnlyUntilTheNextRead() {
        Spied spied = spied();

        spied.channel.writeInbound(buffer("GET / HTTP/1.1\r"));
        assertThat(spied.seen(), is("GET / HTTP/1.1"));
        spied.channel.writeInbound(buffer("\n\r\n"));

        assertThat(spied.seen(), is("GET / HTTP/1.1\r\n\r\n"));
        assertThat(failure(decoded(spied.channel)), nullValue());
    }

    @Test
    public void shouldPassOnACrOnceTheConnectionMayStopCarryingHttp() {
        Spied afterConnect = spied();
        afterConnect.channel.writeInbound(buffer("CONNECT example.com:443 HTTP/1.1\r\n\r\n\r"));
        assertThat(afterConnect.seen(), is("CONNECT example.com:443 HTTP/1.1\r\n\r\n\r"));

        Spied afterSwitchingProtocols = spied();
        afterSwitchingProtocols.channel.writeInbound(buffer("GET / HTTP/1.1\r\nupgrade: websocket\r\n\r\n"));
        afterSwitchingProtocols.channel.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS));
        afterSwitchingProtocols.channel.writeInbound(buffer("\r"));
        assertThat(afterSwitchingProtocols.seen(), is("GET / HTTP/1.1\r\nupgrade: websocket\r\n\r\n\r"));

        Spied afterUndecodableTrailers = spied();
        afterUndecodableTrailers.channel.writeInbound(buffer("POST / HTTP/1.1\r\n" + CHUNKED + "\r\n\r\n0\r\n" + section(LIMIT) + "\r\n"));
        afterUndecodableTrailers.channel.writeInbound(buffer("\r"));
        assertThat(afterUndecodableTrailers.seen().endsWith("\r\n\r\n\r"), is(true));
    }

    @Test
    public void shouldNotHoldACrWhenAnotherHandlerIsBetweenTheGuardAndTheCodec() {
        HttpServerCodec codec = serverCodec();
        HttpLineEndSplitGuard guard = new HttpLineEndSplitGuard(codec);
        Spy between = new Spy();
        EmbeddedChannel channel = track(new EmbeddedChannel(guard.beforeCodec(), between, codec, guard.afterCodec()));

        channel.writeInbound(buffer("GET / HTTP/1.1\r"));

        assertThat(between.seen(), is("GET / HTTP/1.1\r"));
    }

    @Test
    public void shouldHandOnAHeldCrBeforeTheConnectionCloses() {
        Spied spied = spied();
        spied.channel.writeInbound(buffer("GET / HTTP/1.1\r"));

        spied.channel.pipeline().fireChannelInactive();

        assertThat(spied.spy.events, contains("GET / HTTP/1.1", "\r", "inactive"));
    }

    @Test
    public void shouldHandOnAHeldCrWhenRemoved() {
        Spied spied = spied();
        spied.channel.writeInbound(buffer("GET / HTTP/1.1\r"));

        HttpLineEndSplitGuard.removeFrom(spied.channel.pipeline());

        assertThat(spied.seen(), is("GET / HTTP/1.1\r"));
        assertThat(spied.channel.pipeline().last(), instanceOf(HttpServerCodec.class));
    }

    @Test
    public void shouldAskForTheNextReadWhenItHoldsAWholeReadWithoutAutoRead() {
        ReadCounter reads = new ReadCounter();
        HttpServerCodec codec = serverCodec();
        HttpLineEndSplitGuard guard = new HttpLineEndSplitGuard(codec);
        EmbeddedChannel channel = track(new EmbeddedChannel(reads, guard.beforeCodec(), codec, guard.afterCodec()));
        channel.config().setAutoRead(false);
        int before = reads.count;

        channel.writeInbound(buffer("\r"));

        assertThat(reads.count, is(before + 1));
    }

    @Test
    public void shouldLeaveAskingForTheNextReadToTheCodecWhenALaterReadInTheSameCycleReachesIt() {
        ReadCounter reads = new ReadCounter();
        HttpServerCodec codec = serverCodec();
        HttpLineEndSplitGuard guard = new HttpLineEndSplitGuard(codec);
        EmbeddedChannel channel = track(new EmbeddedChannel(reads, guard.beforeCodec(), codec, guard.afterCodec()));
        channel.config().setAutoRead(false);
        int before = reads.count;

        // one cycle: a read held whole, then one the codec reads and decodes nothing from
        channel.writeInbound(buffer("\r"), buffer("GET / HTTP/1.1\r\n"));

        assertThat(reads.count, is(before + 1));
    }

    /**
     * A header section of {@code size} bytes as Netty counts it (its lines without their CRLF), each line ending CRLF.
     */
    private static String section(int size, String... fixedLines) {
        StringBuilder section = new StringBuilder();
        int fixed = 0;
        for (String line : fixedLines) {
            section.append(line).append("\r\n");
            fixed += line.length();
        }
        return section.append("x-f: ").append("f".repeat(size - fixed - 5)).append("\r\n").toString();
    }

    /**
     * The message whole, in two reads split at every position, and a byte a read.
     */
    private static List<List<String>> splits(String message) {
        List<List<String>> splits = new ArrayList<>();
        splits.add(List.of(message));
        for (int at = 1; at < message.length(); at++) {
            splits.add(List.of(message.substring(0, at), message.substring(at)));
        }
        List<String> bytes = new ArrayList<>();
        for (char c : message.toCharArray()) {
            bytes.add(String.valueOf(c));
        }
        splits.add(bytes);
        return splits;
    }

    private static HttpServerCodec serverCodec() {
        return new HttpServerCodec(new HttpDecoderConfig().setMaxHeaderSize(LIMIT).setMaxInitialLineLength(LINE_LIMIT));
    }

    private EmbeddedChannel server() {
        HttpServerCodec codec = serverCodec();
        HttpLineEndSplitGuard guard = new HttpLineEndSplitGuard(codec);
        return track(new EmbeddedChannel(guard.beforeCodec(), codec, guard.afterCodec()));
    }

    private EmbeddedChannel client(HttpMethod method) {
        HttpClientCodec codec = new HttpClientCodec(HttpObjectDecoder.DEFAULT_MAX_INITIAL_LINE_LENGTH, LIMIT, HttpObjectDecoder.DEFAULT_MAX_CHUNK_SIZE);
        HttpLineEndSplitGuard guard = new HttpLineEndSplitGuard(codec);
        EmbeddedChannel channel = track(new EmbeddedChannel(guard.beforeCodec(), codec, guard.afterCodec()));
        channel.writeOutbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, method == HttpMethod.CONNECT ? "example.com:443" : "/"));
        Object encoded;
        while ((encoded = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(encoded);
        }
        return channel;
    }

    private Spied spied() {
        HttpServerCodec codec = serverCodec();
        Spy spy = new Spy();
        HttpLineEndSplitGuard guard = new HttpLineEndSplitGuard(spy);
        return new Spied(track(new EmbeddedChannel(guard.beforeCodec(), spy, codec, guard.afterCodec())), spy);
    }

    private EmbeddedChannel track(EmbeddedChannel channel) {
        channels.add(channel);
        return channel;
    }

    private static EmbeddedChannel writeInReads(EmbeddedChannel channel, List<String> reads) {
        for (String read : reads) {
            channel.writeInbound(buffer(read));
        }
        return channel;
    }

    private List<HttpObject> decoded(EmbeddedChannel channel) {
        List<HttpObject> decoded = new ArrayList<>();
        Object message;
        while ((message = channel.readInbound()) != null) {
            read.add(message);
            decoded.add(message instanceof ByteBuf ? new RawBytes(((ByteBuf) message).toString(StandardCharsets.ISO_8859_1)) : (HttpObject) message);
        }
        return decoded;
    }

    private static Throwable failure(List<HttpObject> decoded) {
        for (HttpObject object : decoded) {
            if (object.decoderResult().isFailure()) {
                return object.decoderResult().cause();
            }
        }
        return null;
    }

    private static HttpObject last(List<HttpObject> decoded) {
        return decoded.get(decoded.size() - 1);
    }

    private static String content(List<HttpObject> decoded) {
        StringBuilder content = new StringBuilder();
        for (HttpObject object : decoded) {
            if (object instanceof HttpContent) {
                content.append(((HttpContent) object).content().toString(StandardCharsets.ISO_8859_1));
            }
        }
        return content.toString();
    }

    private static List<String> describe(List<HttpObject> decoded) {
        List<String> described = new ArrayList<>();
        for (HttpObject object : decoded) {
            if (object instanceof RawBytes) {
                described.add("bytes:" + ((RawBytes) object).text);
            } else if (object instanceof HttpResponse) {
                described.add(((HttpResponse) object).status().toString());
            } else if (object instanceof LastHttpContent) {
                described.add("last");
            } else if (object instanceof HttpMessage) {
                described.add("message");
            } else {
                described.add("content");
            }
        }
        return described;
    }

    private static void releaseAll(List<Object> messages) {
        for (Object message : messages) {
            ReferenceCountUtil.release(message);
        }
    }

    private static ByteBuf buffer(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.ISO_8859_1);
    }

    private static final class RawBytes extends io.netty.handler.codec.http.DefaultHttpObject {
        private final String text;

        private RawBytes(String text) {
            this.text = text;
        }
    }

    private static final class Spied {
        private final EmbeddedChannel channel;
        private final Spy spy;

        private Spied(EmbeddedChannel channel, Spy spy) {
            this.channel = channel;
            this.spy = spy;
        }

        private String seen() {
            return spy.seen();
        }
    }

    /**
     * Records what reaches it and passes it on.
     */
    private static final class Spy extends ChannelInboundHandlerAdapter {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final List<String> events = new ArrayList<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf) {
                String text = ((ByteBuf) msg).toString(StandardCharsets.ISO_8859_1);
                bytes.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
                events.add(text);
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            events.add("inactive");
            ctx.fireChannelInactive();
        }

        private String seen() {
            return bytes.toString(StandardCharsets.ISO_8859_1);
        }
    }

    private static final class ReadCounter extends ChannelOutboundHandlerAdapter {
        private int count;

        @Override
        public void read(ChannelHandlerContext ctx) {
            count++;
            ctx.read();
        }
    }
}
