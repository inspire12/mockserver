package org.mockserver.netty.http3;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.BrotliEncoder;
import io.netty.handler.codec.compression.SnappyFrameEncoder;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.compression.ZstdEncoder;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;
import org.mockserver.codec.HttpObjectAggregators;
import org.mockserver.codec.MockServerHttpContentDecompressor;
import org.mockserver.codec.NettyHttpToMockServerHttpRequestDecoder;
import org.mockserver.codec.PreserveHeadersNettyRemoves;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.remotewrite.SnappyBlock;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Checks that an HTTP/3 request with a {@code Content-Encoding} body reaches the matchers as the same
 * {@link HttpRequest} the HTTP/1.1 pipeline builds from the same bytes: the HTTP/1.1 side is the real
 * {@code HttpServerCodec -> PreserveHeadersNettyRemoves -> MockServerHttpContentDecompressor -> aggregator -> request decoder}
 * chain {@code PortUnificationHandler} installs, the HTTP/3 side is {@link Http3MockServerHandler} fed QUIC-packet-sized
 * DATA frames. Headers (with their order), body, raw bytes and original body must agree, for well-formed, truncated
 * and corrupt bodies.
 */
@RunWith(Parameterized.class)
public class Http3RequestDecompressionParityTest {

    private static final int MAX_BODY = 1024 * 1024;
    private static final Configuration CONFIGURATION = configuration().maxRequestBodySize(MAX_BODY);
    private static final MockServerLogger LOGGER = new MockServerLogger(Http3RequestDecompressionParityTest.class);
    private static final byte[] PLAIN = plainJson();

    @Parameterized.Parameter
    public String label;

    @Parameterized.Parameter(1)
    public String contentEncoding;

    @Parameterized.Parameter(2)
    public byte[] wire;

    @Parameterized.Parameter(3)
    public boolean decodedByHttp1;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> encodings() {
        List<Object[]> encodings = new ArrayList<>();
        encodings.add(new Object[]{"gzip", "gzip", gzip(PLAIN), true});
        encodings.add(new Object[]{"x-gzip", "x-gzip", gzip(PLAIN), true});
        encodings.add(new Object[]{"GZIP", "GZIP", gzip(PLAIN), true});
        encodings.add(new Object[]{"deflate", "deflate", zlib(PLAIN), true});
        encodings.add(new Object[]{"x-deflate", "x-deflate", zlib(PLAIN), true});
        // HTTP/1.1's decompressor is not strict, so 'deflate' also accepts a raw (headerless) stream
        encodings.add(new Object[]{"deflate (raw)", "deflate", rawDeflate(PLAIN), true});
        encodings.add(new Object[]{"snappy", "snappy", encode(new SnappyFrameEncoder(), PLAIN), true});
        // the raw block format Prometheus remote-write sends
        encodings.add(new Object[]{"snappy (block)", "snappy", SnappyBlock.compress(PLAIN), true});
        encodings.add(new Object[]{"zstd", "zstd", Zstd.isAvailable() ? encode(new ZstdEncoder(), PLAIN) : gzip(PLAIN), Zstd.isAvailable()});
        encodings.add(new Object[]{"br", "br", Brotli.isAvailable() ? encode(new BrotliEncoder(), PLAIN) : gzip(PLAIN), Brotli.isAvailable()});
        // not decoded by HTTP/1.1, so not by HTTP/3 either
        encodings.add(new Object[]{"identity", "identity", PLAIN, false});
        encodings.add(new Object[]{"compress", "compress", gzip(PLAIN), false});
        encodings.add(new Object[]{"gzip, br", "gzip, br", gzip(PLAIN), false});
        return encodings;
    }

    @Test
    public void shouldBuildTheSameRequestAsHttp1WithContentLength() {
        assertSameOutcome(wire, false);
    }

    @Test
    public void shouldBuildTheSameRequestAsHttp1WithoutContentLength() {
        assertSameOutcome(wire, true);
    }

    @Test
    public void shouldBehaveAsHttp1ForATruncatedBody() {
        assertSameOutcome(Arrays.copyOf(wire, wire.length / 2), false);
    }

    @Test
    public void shouldBehaveAsHttp1ForACorruptBody() {
        byte[] corrupt = wire.clone();
        for (int i = corrupt.length / 3; i < corrupt.length * 2 / 3; i++) {
            corrupt[i] = (byte) 0xA5;
        }
        Outcome viaHttp3 = assertSameOutcome(corrupt, false);
        if (decodedByHttp1 && !label.endsWith("(raw)")) {
            // these decoders all fail on this input, so the stream is closed without a response, as HTTP/1.1 closes the connection
            assertThat(viaHttp3.rejected, is(true));
            assertThat(viaHttp3.status, nullValue());
        }
    }

    @Test
    public void shouldDecompressWhenHttp1Does() {
        HttpRequest viaHttp3 = viaHttp3(wire, false).request;
        assertThat(viaHttp3, notNullValue());
        if (decodedByHttp1) {
            assertThat(new String(viaHttp3.getBodyAsRawBytes(), StandardCharsets.UTF_8), is(new String(PLAIN, StandardCharsets.UTF_8)));
            assertThat(viaHttp3.getOriginalBody(), is(wire));
            assertThat(viaHttp3.getFirstHeader("content-length"), is(String.valueOf(PLAIN.length)));
        } else {
            assertThat(viaHttp3.getBodyAsRawBytes(), is(wire));
            assertThat(viaHttp3.getOriginalBody(), nullValue());
            assertThat(viaHttp3.getFirstHeader("content-length"), is(String.valueOf(wire.length)));
        }
        assertThat(viaHttp3.getFirstHeader("content-encoding"), is(contentEncoding));
    }

    @Test
    public void shouldRejectADecompressedBodyOverTheLimitAsHttp1Does() {
        if (!decodedByHttp1) {
            return;
        }
        byte[] plain = new byte[4 * MAX_BODY];
        byte[] bomb = reencode(plain);

        Outcome viaHttp1 = viaHttp1(bomb, false);
        Outcome viaHttp3 = viaHttp3(bomb, false);

        assertThat("compressed body is under the limit", bomb.length < MAX_BODY, is(true));
        assertThat(viaHttp1.request, nullValue());
        assertThat(viaHttp3.request, nullValue());
        if (label.endsWith("(block)")) {
            // a block declares its decoded size up front, so it is refused as corrupt before anything is decoded
            assertThat(viaHttp1.rejected, is(true));
            assertThat(viaHttp1.status, nullValue());
            assertThat(viaHttp3.rejected, is(true));
            assertThat(viaHttp3.status, nullValue());
        } else {
            assertThat(viaHttp1.status, is(413));
            assertThat(viaHttp3.status, is(413));
        }
    }

    private byte[] reencode(byte[] plain) {
        switch (contentEncoding.trim().toLowerCase()) {
            case "gzip":
            case "x-gzip":
                return gzip(plain);
            case "deflate":
            case "x-deflate":
                return label.endsWith("(raw)") ? rawDeflate(plain) : zlib(plain);
            case "snappy":
                return label.endsWith("(block)") ? SnappyBlock.compress(plain) : encode(new SnappyFrameEncoder(), plain);
            case "zstd":
                return encode(new ZstdEncoder(), plain);
            case "br":
                return encode(new BrotliEncoder(), plain);
            default:
                throw new IllegalArgumentException(contentEncoding);
        }
    }

    private Outcome assertSameOutcome(byte[] body, boolean chunked) {
        Outcome viaHttp1 = viaHttp1(body, chunked);
        Outcome viaHttp3 = viaHttp3(body, chunked);

        assertThat("HTTP/3 rejected the body: " + viaHttp3.rejected + ", HTTP/1.1: " + viaHttp1.rejected, viaHttp3.rejected, is(viaHttp1.rejected));
        if (viaHttp1.request == null) {
            assertThat(viaHttp3.request, nullValue());
            return viaHttp3;
        }
        HttpRequest http1 = viaHttp1.request;
        HttpRequest http3 = viaHttp3.request;
        // HTTP/3 has no Transfer-Encoding; a chunked HTTP/1.1 request is the one that arrived without a Content-Length
        assertThat(headers(http3, UnaryOperator.identity()), is(headers(http1, list -> list.stream().filter(header -> !header.getName().getValue().equalsIgnoreCase("transfer-encoding")).collect(Collectors.toList()))));
        assertThat(http3.getBody(), is(http1.getBody()));
        assertThat(http3.getBodyAsRawBytes(), is(http1.getBodyAsRawBytes()));
        assertThat(http3.getOriginalBody(), is(http1.getOriginalBody()));
        return viaHttp3;
    }

    private static List<String> headers(HttpRequest request, UnaryOperator<List<Header>> filter) {
        return filter.apply(request.getHeaderList()).stream().map(Header::toString).collect(Collectors.toList());
    }

    private static final class Outcome {
        HttpRequest request;
        boolean rejected;
        Integer status;
    }

    private Outcome viaHttp1(byte[] body, boolean chunked) {
        Outcome outcome = new Outcome();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpServerCodec(),
            new PreserveHeadersNettyRemoves(),
            new MockServerHttpContentDecompressor(MAX_BODY),
            HttpObjectAggregators.httpObjectAggregator(MAX_BODY),
            new NettyHttpToMockServerHttpRequestDecoder(CONFIGURATION, LOGGER, true, null, 1080)
        );
        try {
            StringBuilder head = new StringBuilder()
                .append("POST /parity?a=b HTTP/1.1\r\n")
                .append("host: localhost:1080\r\n")
                .append("content-type: application/json\r\n")
                .append("content-encoding: ").append(contentEncoding).append("\r\n");
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            if (chunked) {
                head.append("transfer-encoding: chunked\r\n").append("x-after: after\r\n\r\n");
                raw.writeBytes(head.toString().getBytes(StandardCharsets.US_ASCII));
                for (int offset = 0; offset < body.length; offset += 5000) {
                    int length = Math.min(5000, body.length - offset);
                    raw.writeBytes((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    raw.write(body, offset, length);
                    raw.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                raw.writeBytes("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            } else {
                head.append("content-length: ").append(body.length).append("\r\n").append("x-after: after\r\n\r\n");
                raw.writeBytes(head.toString().getBytes(StandardCharsets.US_ASCII));
                raw.writeBytes(body);
            }
            try {
                channel.writeInbound(Unpooled.wrappedBuffer(raw.toByteArray()));
                outcome.request = channel.readInbound();
            } catch (DecoderException decoderException) {
                outcome.rejected = true;
            }
            Object written = channel.readOutbound();
            if (written instanceof FullHttpResponse) {
                outcome.status = ((FullHttpResponse) written).status().code();
                outcome.rejected = true;
                ((FullHttpResponse) written).release();
            } else if (written instanceof ByteBuf) {
                String response = ((ByteBuf) written).toString(StandardCharsets.US_ASCII);
                outcome.status = Integer.parseInt(response.substring(9, 12));
                outcome.rejected = true;
                ((ByteBuf) written).release();
            }
        } finally {
            try {
                channel.finishAndReleaseAll();
            } catch (RuntimeException ignored) {
                // the corrupt-body case leaves a failed decoder behind
            }
        }
        return outcome;
    }

    private Outcome viaHttp3(byte[] body, boolean withoutContentLength) {
        Outcome outcome = new Outcome();
        HttpState httpState = mock(HttpState.class);
        when(httpState.handle(any(), any(), anyBoolean())).thenReturn(true);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, httpState, mock(HttpActionHandler.class), new Metrics(CONFIGURATION)
        );
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
        ChannelFuture future = mock(ChannelFuture.class);
        when(future.addListener(any())).thenReturn(future);
        List<Object> writes = new ArrayList<>();
        org.mockito.stubbing.Answer<ChannelFuture> recordWrite = invocation -> {
            writes.add(invocation.getArgument(0));
            return future;
        };
        when(ctx.write(any())).thenAnswer(recordWrite);
        when(ctx.writeAndFlush(any())).thenAnswer(recordWrite);

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("POST");
        headersFrame.headers().path("/parity?a=b");
        headersFrame.headers().scheme("https");
        headersFrame.headers().authority("localhost:1080");
        headersFrame.headers().add("content-type", "application/json");
        headersFrame.headers().add("content-encoding", contentEncoding);
        if (!withoutContentLength) {
            headersFrame.headers().add("content-length", String.valueOf(body.length));
        }
        headersFrame.headers().add("x-after", "after");
        try {
            handler.channelRead(ctx, headersFrame);
            // HTTP/3 hands a body over in pieces of about one QUIC packet
            for (int offset = 0; offset < body.length; offset += 1200) {
                handler.channelRead(ctx, new DefaultHttp3DataFrame(Unpooled.copiedBuffer(body, offset, Math.min(1200, body.length - offset))));
            }
            handler.channelInputClosed(ctx);

            ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
            try {
                verify(httpState).handle(request.capture(), any(), anyBoolean());
                outcome.request = request.getValue();
            } catch (AssertionError notHandled) {
                verify(httpState, never()).handle(any(), any(), anyBoolean());
                outcome.rejected = true;
            }
            for (Object written : writes) {
                if (written instanceof Http3HeadersFrame) {
                    outcome.status = Integer.parseInt(((Http3HeadersFrame) written).headers().status().toString());
                }
            }
            for (Object written : writes) {
                io.netty.util.ReferenceCountUtil.release(written);
            }
        } finally {
            try {
                handler.handlerRemoved(ctx);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        if (outcome.rejected && outcome.status == null) {
            verify(ctx).close();
        }
        return outcome;
    }

    private static byte[] plainJson() {
        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 400; i++) {
            json.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append(",\"name\":\"item ").append(i).append(" – é\"}");
        }
        return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] plain) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(plain);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }

    private static byte[] zlib(byte[] plain) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(out)) {
            deflate.write(plain);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }

    private static byte[] rawDeflate(byte[] plain) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(out, new Deflater(Deflater.DEFAULT_COMPRESSION, true))) {
            deflate.write(plain);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }

    private static byte[] encode(ChannelHandler encoder, byte[] plain) {
        EmbeddedChannel channel = new EmbeddedChannel(encoder);
        channel.writeOutbound(Unpooled.wrappedBuffer(plain));
        channel.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuf piece;
        while ((piece = channel.readOutbound()) != null) {
            byte[] bytes = new byte[piece.readableBytes()];
            piece.readBytes(bytes);
            out.writeBytes(bytes);
            piece.release();
        }
        return out.toByteArray();
    }
}
