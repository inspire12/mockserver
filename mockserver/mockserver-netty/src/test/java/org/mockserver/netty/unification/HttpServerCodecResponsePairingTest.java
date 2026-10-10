package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Test;
import org.mockserver.mock.action.http.HttpErrorActionHandler;
import org.mockserver.model.HttpError;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;
import org.mockserver.responsewriter.ResponseWrittenBeneathCodecEvent;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

/**
 * A response that does not pass {@link HttpServerCodec}'s encoder must not leave its request's method for the codec to
 * encode the next response for.
 */
public class HttpServerCodecResponsePairingTest {

    private static final byte[] RAW = "raw".getBytes(StandardCharsets.US_ASCII);

    private final HttpServerCodecResponsePairing pairing = new HttpServerCodecResponsePairing();
    private final EmbeddedChannel channel = new EmbeddedChannel(pairing.beforeCodec(), new HttpServerCodec(), pairing.afterCodec());

    @After
    public void close() {
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldEncodeAGetsBodyAfterAHeadAnsweredWithRawBytes() {
        request("HEAD");
        new HttpErrorActionHandler().handle(HttpError.error().withResponseBytes(RAW), codec());
        request("GET");
        channel.writeOutbound(response("simple"));

        assertThat(written(), is("raw" + head(6) + "simple"));
    }

    @Test
    public void shouldEncodeAHeadWithoutABodyAfterAGetAnsweredWithRawBytes() {
        request("GET");
        new HttpErrorActionHandler().handle(HttpError.error().withResponseBytes(RAW), codec());
        request("HEAD");
        channel.writeOutbound(response("simple"));

        assertThat(written(), is("raw" + head(6)));
    }

    @Test
    public void shouldEncodeAGetsBodyAfterAHeadAnsweredWithNothing() {
        request("HEAD");
        new HttpErrorActionHandler().handle(HttpError.error().withDropConnection(false), codec());
        request("GET");
        channel.writeOutbound(response("simple"));

        assertThat(written(), is(head(6) + "simple"));
        assertThat(channel.isOpen(), is(true));
    }

    @Test
    public void shouldEncodeAGetsBodyAfterAHeadAnsweredWithAFinal1xx() {
        request("HEAD");
        channel.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(103), Unpooled.EMPTY_BUFFER));
        HttpExchangeEndedEvent.fire(codec());
        request("GET");
        channel.writeOutbound(response("simple"));

        String written = written();
        assertThat(written, startsWith("HTTP/1.1 103"));
        assertThat(written, endsWith(head(6) + "simple"));
    }

    @Test
    public void shouldEncodeAGetsBodyAfterRawBytesForAHeadPipelinedAheadOfIt() {
        // both requests decoded before either is answered
        channel.writeInbound(Unpooled.copiedBuffer("HEAD / HTTP/1.1\r\nHost: localhost\r\n\r\nGET / HTTP/1.1\r\nHost: localhost\r\n\r\n", StandardCharsets.US_ASCII));
        releaseInbound();

        new HttpErrorActionHandler().handle(HttpError.error().withResponseBytes(RAW), codec());
        channel.writeOutbound(response("simple"));

        assertThat(written(), is("raw" + head(6) + "simple"));
    }

    @Test
    public void shouldNotReplaceAWriteIssuedAsTheStandInsFlushCompletesAnEarlierOne() {
        request("GET");
        request("HEAD");
        // written but not yet flushed; as its write completes, its listener writes more (as response writers do)
        channel.write(response("first")).addListener(future -> codec().writeAndFlush(Unpooled.copiedBuffer("later", StandardCharsets.US_ASCII)));

        HttpExchangeEndedEvent.fire(codec());

        assertThat(written(), is(head(5) + "first" + "later"));
    }

    @Test
    public void shouldEncodeEveryResponseForItsOwnRequestAcrossManyRawResponses() {
        for (int i = 0; i < 100; i++) {
            request(i % 2 == 0 ? "HEAD" : "GET");
            new HttpErrorActionHandler().handle(HttpError.error().withResponseBytes(RAW), codec());
        }
        request("GET");
        channel.writeOutbound(response("simple"));

        assertThat(written(), endsWith("raw" + head(6) + "simple"));
    }

    @Test
    public void shouldWriteNothingOfItsOwnAndPassOnTheEvent() {
        List<Object> seen = new java.util.ArrayList<>();
        channel.pipeline().addLast(new io.netty.channel.ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                seen.add(evt);
            }
        });
        request("GET");

        codec().fireUserEventTriggered(ResponseWrittenBeneathCodecEvent.INSTANCE);

        assertThat(written(), is(""));
        assertThat(seen, contains((Object) ResponseWrittenBeneathCodecEvent.INSTANCE));
    }

    @Test
    public void shouldKeepTheCodecsCloseAfterTheResponseToARequestItMarksToClose() {
        // with RFC 9112 checks off, the codec accepts both headers and closes the connection after the response
        HttpServerCodecResponsePairing lenientPairing = new HttpServerCodecResponsePairing();
        EmbeddedChannel lenient = new EmbeddedChannel(lenientPairing.beforeCodec(), new HttpServerCodec(new HttpDecoderConfig().setUseRfc9112TransferEncoding(false)), lenientPairing.afterCodec());
        lenient.writeInbound(Unpooled.copiedBuffer("GET / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nContent-Length: 5\r\n\r\n0\r\n\r\n", StandardCharsets.US_ASCII));
        for (Object decoded; (decoded = lenient.readInbound()) != null; ) {
            assertThat(((HttpObject) decoded).decoderResult().isSuccess(), is(true));
            ReferenceCountUtil.release(decoded);
        }

        new HttpErrorActionHandler().handle(HttpError.error().withResponseBytes(RAW), lenient.pipeline().context(HttpServerCodec.class));

        ByteBuf first = lenient.readOutbound();
        assertThat("the raw bytes are written before the close", first.toString(StandardCharsets.US_ASCII), is("raw"));
        first.release();
        assertThat(lenient.isOpen(), is(false));
        lenient.finishAndReleaseAll();
    }

    @Test
    public void shouldPutWhatMustSeeRawBytesBeneathTheHandlerBeforeTheCodec() {
        assertThat(HttpServerCodecResponsePairing.beneathCodec(channel.pipeline()).handler(), is(pairing.beforeCodec()));

        EmbeddedChannel codecAlone = new EmbeddedChannel(new HttpServerCodec());
        assertThat(HttpServerCodecResponsePairing.beneathCodec(codecAlone.pipeline()), is(codecAlone.pipeline().context(HttpServerCodec.class)));
        codecAlone.finishAndReleaseAll();
    }

    @Test
    public void shouldRemoveBothHandlers() {
        HttpServerCodecResponsePairing.removeFrom(channel.pipeline());

        assertThat(channel.pipeline().names().size(), is(2));
        assertThat(channel.pipeline().first() instanceof HttpServerCodec, is(true));
    }

    private void request(String method) {
        channel.writeInbound(Unpooled.copiedBuffer(method + " / HTTP/1.1\r\nHost: localhost\r\n\r\n", StandardCharsets.US_ASCII));
        releaseInbound();
    }

    private void releaseInbound() {
        for (Object decoded; (decoded = channel.readInbound()) != null; ) {
            ReferenceCountUtil.release(decoded);
        }
    }

    private ChannelHandlerContext codec() {
        return channel.pipeline().context(HttpServerCodec.class);
    }

    private static FullHttpResponse response(String body) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer(body, StandardCharsets.US_ASCII));
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length());
        return response;
    }

    private static String head(int contentLength) {
        return "HTTP/1.1 200 OK\r\ncontent-length: " + contentLength + "\r\n\r\n";
    }

    private String written() {
        StringBuilder written = new StringBuilder();
        for (Object message; (message = channel.readOutbound()) != null; ReferenceCountUtil.release(message)) {
            if (message instanceof ByteBuf) {
                written.append(((ByteBuf) message).toString(StandardCharsets.ISO_8859_1));
            } else {
                assertThat("only bytes are written beneath the codec, not " + message, message instanceof LastHttpContent, is(false));
            }
        }
        return written.toString();
    }
}
