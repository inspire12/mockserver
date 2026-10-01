package org.mockserver.mappers;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.SnappyFrameDecoder;
import io.netty.handler.codec.compression.SnappyFrameEncoder;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import org.junit.Test;
import org.mockserver.codec.BodyContentEncodingEncoder;
import org.mockserver.codec.HttpObjectAggregators;
import org.mockserver.codec.MockServerHttpContentDecompressor;
import org.mockserver.codec.NettyHttpToMockServerHttpRequestDecoder;
import org.mockserver.codec.PreserveHeadersNettyRemoves;
import org.mockserver.codec.SnappyBlockOrFrameDecoder;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.remotewrite.SnappyBlock;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.xerial.snappy.Snappy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * A request received with a {@code Content-Encoding} and forwarded: the request is built by the real HTTP/1.1 inbound
 * chain from wire bytes, then mapped to the outbound request by {@link MockServerHttpRequestToFullHttpRequest}.
 * Unchanged, the upstream gets the bytes the client sent, whatever the coding; changed, the body is encoded in its
 * coding again; under a coding MockServer does not decode, the body is never encoded a second time.
 */
public class ForwardedRequestContentEncodingTest {

    private static final int MAX_BODY = 1024 * 1024;
    private static final Configuration CONFIGURATION = configuration().maxRequestBodySize(MAX_BODY);
    private static final MockServerLogger LOGGER = new MockServerLogger(ForwardedRequestContentEncodingTest.class);
    private static final byte[] PLAIN = plain();

    private final MockServerHttpRequestToFullHttpRequest mapper = new MockServerHttpRequestToFullHttpRequest(LOGGER, null);

    @Test
    public void shouldForwardTheReceivedBytesForEveryDecodedCoding() throws IOException {
        Object[][] codings = {
            {"gzip", gzip(PLAIN, Deflater.BEST_COMPRESSION)},
            {"x-gzip", gzip(PLAIN, Deflater.BEST_SPEED)},
            {"deflate", zlib(PLAIN)},
            {"deflate", rawDeflate(PLAIN)},
            {"snappy", SnappyBlock.compress(PLAIN)},
            {"snappy", snappyFramed(PLAIN)},
        };
        for (Object[] coding : codings) {
            String contentEncoding = (String) coding[0];
            byte[] wire = (byte[]) coding[1];

            HttpRequest received = receive(wire, "content-encoding: " + contentEncoding);
            Forwarded forwarded = forward(received);

            assertThat(contentEncoding + " was decoded on the way in", received.getBodyAsRawBytes(), is(PLAIN));
            assertThat(contentEncoding + " forwarded byte-identical", forwarded.body, is(wire));
            assertThat(forwarded.contentEncodings, contains(contentEncoding));
            assertThat(forwarded.contentLength, is(String.valueOf(wire.length)));
        }
    }

    @Test
    public void shouldForwardTheReceivedBytesFromACloneOrAnOverrideThatKeepsTheBody() throws IOException {
        byte[] wire = gzip(PLAIN, Deflater.BEST_COMPRESSION);
        assertThat("differs from MockServer's own gzip, so re-encoding would show", wire, not(BodyContentEncodingEncoder.encodeBody(PLAIN, "gzip")));
        HttpRequest received = receive(wire, "content-encoding: gzip");

        assertThat(forward(received.clone()).body, is(wire));
        assertThat(forward(received.shallowClone()).body, is(wire));
        assertThat(forward(received.clone().update(request().withPath("/elsewhere").withHeader("x-extra", "1"), null)).body, is(wire));
    }

    @Test
    public void shouldForwardAnUndecodedCodingListAsReceived() throws IOException {
        byte[] wire = gzip(PLAIN);

        HttpRequest received = receive(wire, "content-encoding: gzip, br");
        Forwarded forwarded = forward(received);

        assertThat("a coding list is not decoded", received.getBodyAsRawBytes(), is(wire));
        assertThat("and is not gzipped a second time", forwarded.body, is(wire));
        assertThat(forwarded.contentEncodings, contains("gzip, br"));
    }

    @Test
    public void shouldTreatTwoContentEncodingHeadersByTheFirstAsTheDecompressorDoes() throws IOException {
        byte[] wire = gzip(PLAIN, Deflater.BEST_COMPRESSION);
        byte[] changed = "{\"changed\":true}".getBytes(StandardCharsets.UTF_8);

        HttpRequest received = receive(wire, "content-encoding: gzip", "content-encoding: identity");

        assertThat("decoded by the first value", received.getBodyAsRawBytes(), is(PLAIN));
        assertThat(forward(received).body, is(wire));
        assertThat("encoded by the first value", gunzip(forward(received.withBody(changed)).body), is(changed));
    }

    @Test
    public void shouldNotMarkARequestWithoutAContentEncoding() throws IOException {
        HttpRequest received = receive(PLAIN);

        assertThat(received.isBodyAsReceived(), is(false));
        assertThat(request().withBody("body").markBodyAsReceived().isBodyAsReceived(), is(false));
        assertThat(forward(received).body, is(PLAIN));
    }

    @Test
    public void shouldReencodeAChangedBodyInItsCoding() throws IOException {
        byte[] changed = "{\"changed\":true}".getBytes(StandardCharsets.UTF_8);

        HttpRequest gzipped = receive(gzip(PLAIN), "content-encoding: gzip").withBody(changed);
        assertThat(gunzip(forward(gzipped).body), is(changed));

        HttpRequest deflated = receive(zlib(PLAIN), "content-encoding: deflate").withBody(changed);
        assertThat(inflate(forward(deflated).body), is(changed));

        HttpRequest block = receive(SnappyBlock.compress(PLAIN), "content-encoding: snappy").withBody(changed);
        byte[] blockForwarded = forward(block).body;
        assertThat(SnappyBlockOrFrameDecoder.isFramed(blockForwarded), is(false));
        assertThat(Snappy.uncompress(blockForwarded), is(changed));

        HttpRequest framed = receive(snappyFramed(PLAIN), "content-encoding: snappy").withBody(changed);
        byte[] framedForwarded = forward(framed).body;
        assertThat("re-encoded in the format it arrived in", SnappyBlockOrFrameDecoder.isFramed(framedForwarded), is(true));
        assertThat(unsnappyFramed(framedForwarded), is(changed));
    }

    @Test
    public void shouldReencodeWhenTheContentEncodingHeaderChanged() throws IOException {
        HttpRequest received = receive(gzip(PLAIN), "content-encoding: gzip");
        received.replaceHeader(new Header("content-encoding", "deflate"));

        Forwarded forwarded = forward(received);

        assertThat(inflate(forwarded.body), is(PLAIN));
        assertThat(forwarded.contentEncodings, contains("deflate"));
    }

    @Test
    public void shouldForwardDecodedWhenTheContentEncodingHeaderIsRemoved() throws IOException {
        HttpRequest received = receive(gzip(PLAIN), "content-encoding: gzip");
        received.removeHeader("content-encoding");

        Forwarded forwarded = forward(received);

        assertThat(forwarded.body, is(PLAIN));
        assertThat(forwarded.contentEncodings.isEmpty(), is(true));
    }

    @Test
    public void shouldEncodeABodySuppliedDecoded() throws IOException {
        byte[] plain = "{\"supplied\":true}".getBytes(StandardCharsets.UTF_8);

        byte[] forwarded = forward(request().withMethod("POST").withHeader("content-encoding", "gzip").withBody(plain)).body;

        assertThat(gunzip(forwarded), is(plain));
    }

    @Test
    public void shouldNotEncodeABodySuppliedUnderACodingList() throws IOException {
        byte[] body = "supplied already encoded".getBytes(StandardCharsets.UTF_8);

        byte[] forwarded = forward(request().withMethod("POST").withHeader("content-encoding", "gzip, br").withBody(body)).body;

        assertThat(forwarded, is(body));
    }

    @Test
    public void shouldNotTreatADeserialisedRequestAsReceived() throws IOException {
        HttpRequest received = receive(gzip(PLAIN), "content-encoding: gzip");
        HttpRequest roundTripped = new org.mockserver.serialization.HttpRequestSerializer(LOGGER)
            .deserialize(new org.mockserver.serialization.HttpRequestSerializer(LOGGER).serialize(received));

        assertThat(roundTripped.isBodyAsReceived(), is(false));
        // re-encoded from the decoded body rather than trusting a serialised original body
        assertThat(gunzip(forward(roundTripped).body), is(roundTripped.getBodyAsRawBytes()));
    }

    @Test
    public void shouldMarkOnlyARequestWithABody() throws IOException {
        HttpRequest received = receive(new byte[0], "content-encoding: gzip");

        assertThat(received.isBodyAsReceived(), is(false));
        assertThat(forward(received).body, is(new byte[0]));
        assertThat(received.getOriginalBody(), nullValue());
    }

    private static HttpRequest receive(byte[] body, String... headers) {
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpServerCodec(),
            new PreserveHeadersNettyRemoves(),
            new MockServerHttpContentDecompressor(MAX_BODY),
            HttpObjectAggregators.httpObjectAggregator(MAX_BODY),
            new NettyHttpToMockServerHttpRequestDecoder(CONFIGURATION, LOGGER, false, null, 1080)
        );
        try {
            StringBuilder head = new StringBuilder("POST /forward HTTP/1.1\r\nhost: localhost:1080\r\ncontent-type: application/json\r\n");
            for (String header : headers) {
                head.append(header).append("\r\n");
            }
            head.append("content-length: ").append(body.length).append("\r\n\r\n");
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            raw.writeBytes(head.toString().getBytes(StandardCharsets.US_ASCII));
            raw.writeBytes(body);
            channel.writeInbound(Unpooled.wrappedBuffer(raw.toByteArray()));
            HttpRequest request = channel.readInbound();
            assertThat(request, not(nullValue()));
            return request;
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static final class Forwarded {
        byte[] body;
        List<String> contentEncodings;
        String contentLength;
    }

    private Forwarded forward(HttpRequest request) {
        FullHttpRequest outbound = mapper.mapMockServerRequestToNettyRequest(request);
        try {
            Forwarded forwarded = new Forwarded();
            forwarded.body = new byte[outbound.content().readableBytes()];
            outbound.content().getBytes(outbound.content().readerIndex(), forwarded.body);
            forwarded.contentEncodings = outbound.headers().getAll("content-encoding");
            forwarded.contentLength = outbound.headers().get("content-length");
            return forwarded;
        } finally {
            outbound.release();
        }
    }

    private static byte[] plain() {
        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 400; i++) {
            json.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append(",\"name\":\"item ").append(i).append("\"}");
        }
        return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] plain) throws IOException {
        return gzip(plain, Deflater.DEFAULT_COMPRESSION);
    }

    private static byte[] gzip(byte[] plain, int level) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out) {
            {
                def.setLevel(level);
            }
        }) {
            gzip.write(plain);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gzip.readAllBytes();
        }
    }

    private static byte[] zlib(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(out)) {
            deflate.write(plain);
        }
        return out.toByteArray();
    }

    private static byte[] rawDeflate(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(out, new Deflater(Deflater.DEFAULT_COMPRESSION, true))) {
            deflate.write(plain);
        }
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            return inflater.readAllBytes();
        }
    }

    private static byte[] snappyFramed(byte[] plain) {
        EmbeddedChannel channel = new EmbeddedChannel(new SnappyFrameEncoder());
        try {
            channel.writeOutbound(Unpooled.wrappedBuffer(plain));
            return drain(channel, false);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static byte[] unsnappyFramed(byte[] framed) {
        EmbeddedChannel channel = new EmbeddedChannel(new SnappyFrameDecoder());
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(framed));
            return drain(channel, true);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static byte[] drain(EmbeddedChannel channel, boolean inbound) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuf piece;
        while ((piece = inbound ? channel.readInbound() : channel.readOutbound()) != null) {
            byte[] bytes = new byte[piece.readableBytes()];
            piece.readBytes(bytes);
            out.writeBytes(bytes);
            piece.release();
        }
        return out.toByteArray();
    }
}
