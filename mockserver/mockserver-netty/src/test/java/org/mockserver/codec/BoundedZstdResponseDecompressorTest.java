package org.mockserver.codec;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DelegatingDecompressorFrameListener;
import io.netty.handler.codec.http2.Http2EventAdapter;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * An upstream {@code zstd} response cannot make the forward client's or the CONNECT relay's decompressors allocate
 * the content size its frame header declares, and every other encoding is decoded by exactly the decoder Netty uses.
 * Lives in this module because zstd-jni is on its classpath and not on {@code mockserver-core}'s.
 */
public class BoundedZstdResponseDecompressorTest {

    private static final int MAX_BODY_SIZE = 1024 * 1024;

    // magic, frame header (8-byte content size, not single-segment), 1 KiB window, content size 1.5 GiB, one empty last raw block
    private static final byte[] DECLARES_ONE_AND_A_HALF_GIB = {
        0x28, (byte) 0xB5, 0x2F, (byte) 0xFD,
        (byte) 0xC0,
        0x00,
        0x00, 0x00, 0x00, 0x60, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x00
    };

    private RecordingAllocator allocator;

    @Before
    public void requireZstd() {
        Assume.assumeTrue("zstd-jni is not on the classpath", Zstd.isAvailable());
        allocator = new RecordingAllocator();
    }

    @Test
    public void shouldNotAllocateTheContentSizeAResponseFrameDeclares() {
        assertThat(DECLARES_ONE_AND_A_HALF_GIB.length, is(17));
        EmbeddedChannel channel = decompressingChannel();
        try {
            try {
                channel.writeInbound(zstdResponse(DECLARES_ONE_AND_A_HALF_GIB));
            } catch (RuntimeException rejected) {
                // a frame whose content does not match its declared size may be rejected; the allocation check below
                // still fails if the declared size was requested
            }

            assertThat(allocator.largestRequest, lessThanOrEqualTo(BoundedZstdHttpContentDecompressor.ZSTD_MAX_ALLOCATION));
        } finally {
            releaseAll(channel);
        }
    }

    @Test
    public void shouldBoundAResponseDecompressionBombByTheBodySizeLimit() {
        byte[] bomb = com.github.luben.zstd.Zstd.compress(new byte[64 * 1024 * 1024], 3);
        assertThat(com.github.luben.zstd.Zstd.getFrameContentSize(bomb), is(64L * 1024 * 1024));
        EmbeddedChannel channel = decompressingChannel();
        try {
            try {
                channel.writeInbound(zstdResponse(bomb));
            } catch (TooLongFrameException expected) {
                // HttpObjectAggregator refuses a response over its limit by throwing
            }

            assertThat(channel.inboundMessages(), is(empty()));
            assertThat(allocator.largestRequest, lessThanOrEqualTo(BoundedZstdHttpContentDecompressor.ZSTD_MAX_ALLOCATION));
        } finally {
            releaseAll(channel);
        }
    }

    @Test
    public void shouldStillDecompressAnOrdinaryResponseLargerThanOneAllocation() {
        byte[] plain = plain(300 * 1024);
        EmbeddedChannel channel = decompressingChannel();
        try {
            channel.writeInbound(zstdResponse(com.github.luben.zstd.Zstd.compress(plain, 3)));

            FullHttpResponse decoded = channel.readInbound();
            try {
                byte[] body = new byte[decoded.content().readableBytes()];
                decoded.content().getBytes(decoded.content().readerIndex(), body);
                assertThat(Arrays.equals(body, plain), is(true));
                assertThat(decoded.headers().contains(HttpHeaderNames.CONTENT_ENCODING), is(false));
            } finally {
                decoded.release();
            }
            assertThat(allocator.largestRequest, lessThanOrEqualTo(BoundedZstdHttpContentDecompressor.ZSTD_MAX_ALLOCATION));
        } finally {
            releaseAll(channel);
        }
    }

    @Test
    public void shouldDecodeEveryOtherResponseEncodingWithNettysOwnDecoder() throws Exception {
        ExposedBounded bounded = new ExposedBounded();
        ExposedNetty netty = new ExposedNetty();
        EmbeddedChannel channel = new EmbeddedChannel(bounded, netty);
        try {
            for (String encoding : new String[]{"gzip", "x-gzip", "deflate", "x-deflate", "br", "snappy", "identity", "compress"}) {
                assertThat(encoding, firstHandlerClass(bounded.decoder(encoding)), is(firstHandlerClass(netty.decoder(encoding))));
            }
            assertThat(firstHandlerClass(bounded.decoder("zstd")), is((Object) io.netty.handler.codec.compression.ZstdDecoder.class));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldNotAllocateTheContentSizeAnHttp2ResponseFrameDeclares() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAllocator(allocator);
        ContextCapture context = new ContextCapture();
        channel.pipeline().addLast(context);
        ExposedBoundedListener listener = new ExposedBoundedListener();
        EmbeddedChannel decoder = listener.decoder(context.ctx, "zstd");
        try {
            try {
                decoder.writeInbound(Unpooled.wrappedBuffer(DECLARES_ONE_AND_A_HALF_GIB));
                decoder.finish();
            } catch (RuntimeException rejected) {
                // as for HTTP/1.1, a frame whose content does not match its declared size may be rejected
            }

            assertThat(allocator.largestRequest, lessThanOrEqualTo(BoundedZstdHttpContentDecompressor.ZSTD_MAX_ALLOCATION));
        } finally {
            releaseAll(decoder);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldStillDecompressAnOrdinaryHttp2ResponseLargerThanOneAllocation() throws Exception {
        byte[] plain = plain(300 * 1024);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAllocator(allocator);
        ContextCapture context = new ContextCapture();
        channel.pipeline().addLast(context);
        EmbeddedChannel decoder = new ExposedBoundedListener().decoder(context.ctx, "zstd");
        try {
            decoder.writeInbound(Unpooled.wrappedBuffer(com.github.luben.zstd.Zstd.compress(plain, 3)));
            decoder.finish();

            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            for (ByteBuf buf = decoder.readInbound(); buf != null; buf = decoder.readInbound()) {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                decoded.write(bytes);
                buf.release();
            }
            assertThat(Arrays.equals(decoded.toByteArray(), plain), is(true));
            assertThat(allocator.largestRequest, lessThanOrEqualTo(BoundedZstdHttpContentDecompressor.ZSTD_MAX_ALLOCATION));
        } finally {
            releaseAll(decoder);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldDecodeEveryOtherHttp2ResponseEncodingWithNettysOwnDecoder() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        ContextCapture context = new ContextCapture();
        channel.pipeline().addLast(context);
        ExposedBoundedListener bounded = new ExposedBoundedListener();
        ExposedNettyListener netty = new ExposedNettyListener();
        try {
            for (String encoding : new String[]{"gzip", "x-gzip", "deflate", "x-deflate", "br", "snappy", "identity", "compress"}) {
                assertThat(encoding, firstHandlerClass(bounded.decoder(context.ctx, encoding)), is(firstHandlerClass(netty.decoder(context.ctx, encoding))));
            }
            assertThat(firstHandlerClass(bounded.decoder(context.ctx, "zstd")), is((Object) io.netty.handler.codec.compression.ZstdDecoder.class));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldKeepNettysLenientDeflateOnHttp1AndStrictDeflateOnHttp2() throws Exception {
        byte[] plain = plain(4 * 1024);
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(plain);
        deflater.finish();
        byte[] buffer = new byte[plain.length];
        byte[] rawDeflate = Arrays.copyOf(buffer, deflater.deflate(buffer));
        deflater.end();

        EmbeddedChannel http1 = new EmbeddedChannel(new BoundedZstdHttpContentDecompressor(), new HttpObjectAggregator(MAX_BODY_SIZE));
        try {
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(rawDeflate));
            response.headers().set(HttpHeaderNames.CONTENT_ENCODING, "deflate");
            http1.writeInbound(response);
            FullHttpResponse decoded = http1.readInbound();
            try {
                byte[] body = new byte[decoded.content().readableBytes()];
                decoded.content().getBytes(decoded.content().readerIndex(), body);
                assertThat("deflate without a zlib header is accepted on HTTP/1.1", Arrays.equals(body, plain), is(true));
            } finally {
                decoded.release();
            }
        } finally {
            releaseAll(http1);
        }

        EmbeddedChannel channel = new EmbeddedChannel();
        ContextCapture context = new ContextCapture();
        channel.pipeline().addLast(context);
        EmbeddedChannel http2 = new ExposedBoundedListener().decoder(context.ctx, "deflate");
        try {
            boolean rejected = false;
            try {
                http2.writeInbound(Unpooled.wrappedBuffer(rawDeflate));
                http2.finish();
            } catch (RuntimeException expected) {
                rejected = true;
            }
            assertThat("deflate without a zlib header is rejected on the strict HTTP/2 listener", rejected, is(true));
        } finally {
            releaseAll(http2);
            channel.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel decompressingChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        // the zstd decoder allocates from this channel's config
        channel.config().setAllocator(allocator);
        channel.pipeline().addLast(new BoundedZstdHttpContentDecompressor(), new HttpObjectAggregator(MAX_BODY_SIZE));
        return channel;
    }

    private static FullHttpResponse zstdResponse(byte[] body) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_ENCODING, "zstd");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        return response;
    }

    private static byte[] plain(int length) {
        byte[] plain = new byte[length];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) ('a' + i % 26);
        }
        return plain;
    }

    private static Object firstHandlerClass(EmbeddedChannel decoder) {
        if (decoder == null) {
            return "no decoder";
        }
        try {
            return decoder.pipeline().first().getClass();
        } finally {
            decoder.finishAndReleaseAll();
        }
    }

    private static void releaseAll(EmbeddedChannel channel) {
        try {
            channel.finishAndReleaseAll();
        } catch (RuntimeException ignore) {
            // a rejected frame may rethrow on close; every buffer is still released
        }
    }

    private static final class ExposedBounded extends BoundedZstdHttpContentDecompressor {
        EmbeddedChannel decoder(String contentEncoding) throws Exception {
            return newContentDecoder(contentEncoding);
        }
    }

    private static final class ExposedNetty extends HttpContentDecompressor {
        ExposedNetty() {
            super(0);
        }

        EmbeddedChannel decoder(String contentEncoding) throws Exception {
            return newContentDecoder(contentEncoding);
        }
    }

    private static final class ExposedBoundedListener extends BoundedZstdDecompressorFrameListener {
        ExposedBoundedListener() {
            super(new DefaultHttp2Connection(false), new Http2EventAdapter());
        }

        EmbeddedChannel decoder(ChannelHandlerContext ctx, String contentEncoding) throws Http2Exception {
            return newContentDecompressor(ctx, contentEncoding);
        }
    }

    private static final class ExposedNettyListener extends DelegatingDecompressorFrameListener {
        @SuppressWarnings("deprecation")
        ExposedNettyListener() {
            // the constructor the CONNECT relay used before
            super(new DefaultHttp2Connection(false), new Http2EventAdapter());
        }

        EmbeddedChannel decoder(ChannelHandlerContext ctx, String contentEncoding) throws Http2Exception {
            return newContentDecompressor(ctx, contentEncoding);
        }
    }

    private static final class ContextCapture extends ChannelInboundHandlerAdapter {
        private ChannelHandlerContext ctx;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }
    }

    /**
     * Records the largest buffer requested and refuses anything over 16 MiB, so an unbounded allocation fails this
     * test deterministically instead of depending on the test JVM's heap size.
     */
    private static final class RecordingAllocator extends AbstractByteBufAllocator {

        private static final int REFUSE_ABOVE = 16 * 1024 * 1024;

        private int largestRequest;

        RecordingAllocator() {
            super(false);
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            record(initialCapacity);
            return new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            record(initialCapacity);
            return new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }

        private void record(int initialCapacity) {
            largestRequest = Math.max(largestRequest, initialCapacity);
            if (initialCapacity > REFUSE_ABOVE) {
                throw new RefusedAllocation(initialCapacity);
            }
        }
    }

    private static final class RefusedAllocation extends RuntimeException {
        RefusedAllocation(int size) {
            super("refused an allocation of " + size + " bytes");
        }
    }
}
