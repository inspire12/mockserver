package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Test;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.codec.HttpChunkLineLimiter.MAX_CHUNK_LINE_BYTES;

/**
 * Wire bytes are written to an unbounded {@link HttpServerCodec} (as MockServer configures it by default) with the
 * limiter's two handlers around it; the decoded objects are read after them and the encoded responses before them.
 */
public class HttpChunkLineLimiterTest {

    private static final String CHUNKED_HEAD = "POST /upload HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n";
    private static final int READ_BYTES = 1024;

    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @After
    public void closeChannels() {
        for (EmbeddedChannel channel : channels) {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldDecodeAChunkedBodyWithChunkExtensionsAndTrailers() {
        EmbeddedChannel channel = limited();
        String request = CHUNKED_HEAD
            + "5;chunk-signature=" + "a".repeat(64) + "\r\nhello\r\n"
            + "6 ; name=\"quoted value\"\r\n world\r\n"
            + "0\r\nx-checksum: abc\r\n\r\n";

        for (int readBytes : new int[]{1, 7, READ_BYTES, request.length()}) {
            writeInReads(channel, ascii(request), readBytes);

            assertThat(body(channel), is("hello world"));
            assertThat(channel.isOpen(), is(true));
            assertThat(channel.outboundMessages(), is(empty()));
            respondOk(channel);
        }
    }

    @Test
    public void shouldAcceptAChunkSizeLineOfExactlyTheLimitHoweverItIsRead() {
        String line = chunkSizeLine("5", MAX_CHUNK_LINE_BYTES);
        assertThat(line.length(), is(MAX_CHUNK_LINE_BYTES));
        // a chunk before it, so the CRLF ending that chunk is read together with the line
        String request = CHUNKED_HEAD + "3\r\nabc\r\n" + line + "hello\r\n0\r\n\r\n";

        for (int readBytes : new int[]{1, 2, 3, READ_BYTES, MAX_CHUNK_LINE_BYTES - 1, MAX_CHUNK_LINE_BYTES}) {
            EmbeddedChannel channel = limited();
            writeInReads(channel, ascii(request), readBytes);

            assertThat("in reads of " + readBytes, body(channel), is("abchello"));
            assertThat(channel.isOpen(), is(true));
        }
    }

    @Test
    public void shouldAcceptATrailerSectionUpToTheLimit() {
        StringBuilder trailers = new StringBuilder();
        while (trailers.length() < MAX_CHUNK_LINE_BYTES - 200) {
            trailers.append("x-trailer-").append(trailers.length()).append(": ").append("v".repeat(80)).append("\r\n");
        }
        String request = CHUNKED_HEAD + "5\r\nhello\r\n0\r\n" + trailers + "\r\n";

        for (int readBytes : new int[]{1, 100, READ_BYTES}) {
            EmbeddedChannel channel = limited();
            writeInReads(channel, ascii(request), readBytes);

            assertThat(body(channel), is("hello"));
            assertThat(channel.isOpen(), is(true));
        }
    }

    @Test
    public void shouldRejectAnEndlessChunkExtensionWithoutBufferingPastTheLimit() {
        EmbeddedChannel channel = limited();
        channel.writeInbound(buffer(CHUNKED_HEAD + "5;name="));
        byte[] read = ascii("a".repeat(READ_BYTES));

        int reads = 0;
        List<ByteBuf> afterRejection = new ArrayList<>();
        while (reads < 100) {
            ByteBuf buffer = Unpooled.wrappedBuffer(read);
            channel.writeInbound(buffer);
            reads++;
            if (channel.outboundMessages().isEmpty()) {
                continue;
            }
            // later reads are dropped rather than handed to the codec
            for (int i = 0; i < 3; i++) {
                ByteBuf dropped = Unpooled.wrappedBuffer(read);
                channel.writeInbound(dropped);
                afterRejection.add(dropped);
            }
            break;
        }

        // rejected on the first read to take what the codec holds past the limit
        assertThat(reads, is(MAX_CHUNK_LINE_BYTES / READ_BYTES + 1));
        assertThat(response(channel), allOf(startsWith("HTTP/1.1 400 Bad Request\r\n"), containsString("connection: close\r\n"), containsString("content-length: 0\r\n")));
        for (ByteBuf dropped : afterRejection) {
            assertThat(dropped.refCnt(), is(0));
        }
        // the connection stays open, dropping what it reads, so the client can read the response, and is then closed
        assertThat(channel.isOpen(), is(true));
        channel.advanceTimeBy(HttpChunkLineLimiter.CLOSE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat(channel.isOpen(), is(false));
        // only the request head was decoded: the body never completes
        assertThat(decoded(channel), contains(instanceOf(HttpRequest.class)));
    }

    @Test
    public void shouldRejectAChunkSizeLineOneByteOverTheLimitOnceItIsAllWaitingToBeDecoded() {
        String firstChunk = CHUNKED_HEAD + "3\r\nabc";
        String line = chunkSizeLine("5", MAX_CHUNK_LINE_BYTES + 1);
        EmbeddedChannel channel = limited();

        channel.writeInbound(buffer(firstChunk));
        channel.writeInbound(buffer("\r\n"));
        channel.writeInbound(buffer(line.substring(0, line.length() - 1)));
        assertThat(channel.outboundMessages(), is(empty()));
        channel.writeInbound(buffer("\n"));

        assertThat(response(channel), startsWith("HTTP/1.1 400 Bad Request\r\n"));
    }

    @Test
    public void shouldRejectAnEndlessTrailerSection() {
        EmbeddedChannel channel = limited();
        channel.writeInbound(buffer(CHUNKED_HEAD + "5\r\nhello\r\n0\r\n"));

        int reads = 0;
        while (reads < 100 && channel.outboundMessages().isEmpty()) {
            channel.writeInbound(buffer("x-trailer-" + reads + ": " + "v".repeat(READ_BYTES) + "\r\n"));
            reads++;
        }

        assertThat(reads, lessThanOrEqualTo(MAX_CHUNK_LINE_BYTES / READ_BYTES + 1));
        assertThat(response(channel), startsWith("HTTP/1.1 400 Bad Request\r\n"));
    }

    @Test
    public void shouldNotLimitTheRequestLineHeadersOrBody() {
        EmbeddedChannel channel = limited();
        String longPath = "/" + "p".repeat(4 * MAX_CHUNK_LINE_BYTES);
        String longHeader = "h".repeat(4 * MAX_CHUNK_LINE_BYTES);
        String body = "b".repeat(8 * MAX_CHUNK_LINE_BYTES);
        String contentLengthRequest = "POST " + longPath + " HTTP/1.1\r\nHost: localhost\r\nX-Long: " + longHeader + "\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
        String chunkedRequest = "POST " + longPath + " HTTP/1.1\r\nHost: localhost\r\nX-Long: " + longHeader + "\r\nTransfer-Encoding: chunked\r\n\r\n"
            + Integer.toHexString(body.length()) + "\r\n" + body + "\r\n0\r\n\r\n";

        for (String request : new String[]{contentLengthRequest, chunkedRequest}) {
            for (int readBytes : new int[]{1, READ_BYTES}) {
                writeInReads(channel, ascii(request), readBytes);

                List<HttpObject> decoded = decoded(channel);
                assertThat(((HttpRequest) decoded.get(0)).uri(), is(longPath));
                assertThat(((HttpRequest) decoded.get(0)).headers().get("X-Long"), is(longHeader));
                assertThat(content(decoded), is(body));
                assertThat(channel.isOpen(), is(true));
                respondOk(channel);
            }
        }
    }

    @Test
    public void shouldCloseWithoutRespondingWhenAResponseIsAlreadyUnderWay() {
        EmbeddedChannel channel = limited();
        channel.writeInbound(buffer(CHUNKED_HEAD + "5;name="));
        // a response sent before the request body has been read, still streaming
        DefaultHttpResponse early = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        early.headers().set("transfer-encoding", "chunked");
        channel.writeOutbound(early);
        String head = response(channel);

        writeInReads(channel, ascii("a".repeat(2 * MAX_CHUNK_LINE_BYTES)), READ_BYTES);

        assertThat(head, startsWith("HTTP/1.1 200 OK\r\n"));
        assertThat(channel.outboundMessages(), is(empty()));
        assertThat(channel.isOpen(), is(false));
    }

    @Test
    public void shouldCloseWithoutRespondingWhenAnEarlierPipelinedRequestIsUnanswered() {
        EmbeddedChannel channel = limited();
        channel.writeInbound(buffer("GET /first HTTP/1.1\r\nHost: localhost\r\n\r\n" + CHUNKED_HEAD + "5;name="));

        writeInReads(channel, ascii("a".repeat(2 * MAX_CHUNK_LINE_BYTES)), READ_BYTES);

        assertThat(channel.outboundMessages(), is(empty()));
        assertThat(channel.isOpen(), is(false));
    }

    @Test
    public void shouldRespondWhenEarlierExchangesOnTheConnectionHaveEnded() {
        EmbeddedChannel channel = limited();
        // one answered through the codec, one answered with raw bytes (announced by the event), then 100 Continue
        channel.writeInbound(buffer("GET /first HTTP/1.1\r\nHost: localhost\r\n\r\n"));
        respondOk(channel);
        channel.writeInbound(buffer("GET /second HTTP/1.1\r\nHost: localhost\r\n\r\n"));
        HttpExchangeEndedEvent.fire(channel.pipeline().lastContext());
        channel.writeInbound(buffer(CHUNKED_HEAD + "5;name="));
        channel.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
        assertThat(response(channel), startsWith("HTTP/1.1 100 Continue\r\n"));

        writeInReads(channel, ascii("a".repeat(2 * MAX_CHUNK_LINE_BYTES)), READ_BYTES);

        assertThat(response(channel), startsWith("HTTP/1.1 400 Bad Request\r\n"));
    }

    @Test
    public void shouldForgetAChunkedBodyAbandonedByARefusedExpectation() {
        HttpChunkLineLimiter limiter = new HttpChunkLineLimiter(null);
        EmbeddedChannel channel = channel(limiter.beforeCodec(), new HttpServerCodec(Integer.MAX_VALUE, Integer.MAX_VALUE, 8192), limiter.afterCodec(), new HttpObjectAggregator(1 << 20));
        // the aggregator answers 417 and resets the decoder, so the chunked body ends without a LastHttpContent
        channel.writeInbound(buffer("POST /refused HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nExpect: something-else\r\n\r\n"));
        assertThat(response(channel), startsWith("HTTP/1.1 417 Expectation Failed\r\n"));

        String longHeader = "h".repeat(2 * MAX_CHUNK_LINE_BYTES);
        writeInReads(channel, ascii("GET /next HTTP/1.1\r\nHost: localhost\r\nX-Long: " + longHeader + "\r\n\r\n"), READ_BYTES);

        assertThat(channel.isOpen(), is(true));
        assertThat(channel.outboundMessages(), is(empty()));
        FullHttpRequest next = channel.readInbound();
        try {
            assertThat(next.uri(), is("/next"));
            assertThat(next.headers().get("X-Long"), is(longHeader));
        } finally {
            next.release();
        }
    }

    @Test
    public void shouldDecodeExactlyWhatTheCodecAloneDecodes() {
        Random random = new Random(74);
        for (int iteration = 0; iteration < 300; iteration++) {
            byte[] requests = randomRequests(random);
            EmbeddedChannel limited = limited();
            EmbeddedChannel plain = channel(new HttpServerCodec(Integer.MAX_VALUE, Integer.MAX_VALUE, 8192));

            int offset = 0;
            while (offset < requests.length) {
                int length = Math.min(requests.length - offset, 1 + random.nextInt(random.nextBoolean() ? 16 : 3000));
                limited.writeInbound(Unpooled.copiedBuffer(requests, offset, length));
                plain.writeInbound(Unpooled.copiedBuffer(requests, offset, length));
                offset += length;
            }

            assertThat("iteration " + iteration, describe(decoded(limited)), is(describe(decoded(plain))));
            assertThat("iteration " + iteration, limited.isOpen(), is(true));
            assertThat("iteration " + iteration, limited.outboundMessages(), is(empty()));
        }
    }

    private static byte[] randomRequests(Random random) {
        StringBuilder requests = new StringBuilder();
        int count = 1 + random.nextInt(3);
        for (int request = 0; request < count; request++) {
            if (random.nextInt(4) == 0) {
                int length = random.nextInt(20_000);
                requests.append("POST /fixed HTTP/1.1\r\nHost: localhost\r\nContent-Length: ").append(length).append("\r\n\r\n").append(text(random, length));
                continue;
            }
            requests.append(CHUNKED_HEAD);
            int chunks = random.nextInt(8);
            for (int chunk = 0; chunk < chunks; chunk++) {
                int length = 1 + random.nextInt(random.nextBoolean() ? 20 : 20_000);
                String size = Integer.toHexString(length);
                switch (random.nextInt(4)) {
                    case 0:
                        requests.append(size).append("\r\n");
                        break;
                    case 1:
                        requests.append(size).append(";sig=").append("e".repeat(random.nextInt(200))).append("\r\n");
                        break;
                    case 2:
                        requests.append(chunkSizeLine(size, MAX_CHUNK_LINE_BYTES - random.nextInt(3)));
                        break;
                    default:
                        requests.append(chunkSizeLine(size, 10 + size.length() + random.nextInt(MAX_CHUNK_LINE_BYTES - 20)));
                        break;
                }
                requests.append(text(random, length)).append("\r\n");
            }
            requests.append("0\r\n");
            int trailers = random.nextInt(3);
            for (int trailer = 0; trailer < trailers; trailer++) {
                requests.append("x-trailer-").append(trailer).append(": ").append("t".repeat(random.nextInt(2000))).append("\r\n");
            }
            requests.append("\r\n");
        }
        return ascii(requests.toString());
    }

    private static String text(Random random, int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            // line feeds and semicolons in the data must not be taken for chunk framing
            chars[i] = "abc\r\n;0".charAt(random.nextInt(7));
        }
        return new String(chars);
    }

    /**
     * A chunk-size line of {@code lineBytes} bytes including its CRLF: the size, then one chunk extension.
     */
    private static String chunkSizeLine(String size, int lineBytes) {
        String prefix = size + ";x=";
        return prefix + "e".repeat(lineBytes - prefix.length() - 2) + "\r\n";
    }

    private EmbeddedChannel limited() {
        HttpChunkLineLimiter limiter = new HttpChunkLineLimiter(null);
        return channel(limiter.beforeCodec(), new HttpServerCodec(Integer.MAX_VALUE, Integer.MAX_VALUE, 8192), limiter.afterCodec());
    }

    private EmbeddedChannel channel(io.netty.channel.ChannelHandler... handlers) {
        EmbeddedChannel channel = new EmbeddedChannel(handlers);
        channels.add(channel);
        return channel;
    }

    private static void writeInReads(EmbeddedChannel channel, byte[] bytes, int readBytes) {
        for (int offset = 0; offset < bytes.length && channel.isOpen(); offset += readBytes) {
            channel.writeInbound(Unpooled.copiedBuffer(bytes, offset, Math.min(readBytes, bytes.length - offset)));
        }
    }

    private static void respondOk(EmbeddedChannel channel) {
        DefaultFullHttpResponse ok = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        ok.headers().set("content-length", 0);
        channel.writeOutbound(ok);
        assertThat(response(channel), startsWith("HTTP/1.1 200 OK\r\n"));
    }

    private static List<HttpObject> decoded(EmbeddedChannel channel) {
        List<HttpObject> decoded = new ArrayList<>();
        for (Object message = channel.readInbound(); message != null; message = channel.readInbound()) {
            decoded.add((HttpObject) message);
        }
        return decoded;
    }

    private static String body(EmbeddedChannel channel) {
        List<HttpObject> decoded = decoded(channel);
        assertThat(decoded.get(decoded.size() - 1), instanceOf(LastHttpContent.class));
        assertThat(decoded.get(decoded.size() - 1).decoderResult().isSuccess(), is(true));
        return content(decoded);
    }

    private static String content(List<HttpObject> decoded) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (HttpObject object : decoded) {
            if (object instanceof HttpContent) {
                byte[] bytes = ByteBufUtil.getBytes(((HttpContent) object).content());
                content.write(bytes, 0, bytes.length);
            }
            ReferenceCountUtil.release(object);
        }
        return new String(content.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static List<String> describe(List<HttpObject> decoded) {
        List<String> described = new ArrayList<>();
        for (HttpObject object : decoded) {
            StringBuilder description = new StringBuilder(object.decoderResult().toString());
            if (object instanceof HttpRequest) {
                description.append(' ').append(((HttpRequest) object).uri()).append(' ').append(((HttpRequest) object).headers().entries());
            }
            if (object instanceof HttpContent) {
                description.append(' ').append(Arrays.hashCode(ByteBufUtil.getBytes(((HttpContent) object).content()))).append(' ').append(((HttpContent) object).content().readableBytes());
            }
            if (object instanceof LastHttpContent) {
                description.append(" last ").append(((LastHttpContent) object).trailingHeaders().entries());
            }
            described.add(description.toString());
            ReferenceCountUtil.release(object);
        }
        return described;
    }

    private static String response(EmbeddedChannel channel) {
        StringBuilder response = new StringBuilder();
        for (Object message = channel.readOutbound(); message != null; message = channel.readOutbound()) {
            response.append(((ByteBuf) message).toString(StandardCharsets.US_ASCII));
            ReferenceCountUtil.release(message);
        }
        return response.toString();
    }

    private static ByteBuf buffer(String text) {
        return Unpooled.wrappedBuffer(ascii(text));
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
