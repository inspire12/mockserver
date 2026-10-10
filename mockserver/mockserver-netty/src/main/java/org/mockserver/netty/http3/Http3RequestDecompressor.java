package org.mockserver.netty.http3;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.codec.MockServerHttpContentDecompressor;

import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.DefaultHttpHeadersFactory.headersFactory;

/**
 * Decompresses one HTTP/3 request body that carries a {@code Content-Encoding}, exactly as the HTTP/1.1 and HTTP/2
 * pipelines do: the body is run through the same {@link MockServerHttpContentDecompressor} they install, so the set of
 * supported encodings, the decoders and their failures are identical by construction.
 * <p>
 * The decompressed size is bounded by {@code maxRequestBodySize} as HTTP/1.1's aggregator bounds it, and compressed
 * input is fed in slices of at most {@link #SLICE_BYTES} (Netty's default HTTP chunk size), so a decompression bomb
 * inflates at most one slice past the limit before it is rejected. Decompressed output is kept up to the limit and
 * discarded, piece by piece, only once the limit is exceeded. A corrupt body throws {@link DecoderException}.
 * <p>
 * Not thread-safe: used only from its request stream's event loop. {@link #release()} must be called on every path.
 */
final class Http3RequestDecompressor {

    static final int SLICE_BYTES = 8192;

    private final EmbeddedChannel channel;
    private final DecompressedBodySink sink;

    private Http3RequestDecompressor(EmbeddedChannel channel, DecompressedBodySink sink) {
        this.channel = channel;
        this.sink = sink;
    }

    /**
     * A decompressor for a request with these headers, or {@code null} when the request is not decompressed: it has
     * no {@code Content-Encoding}, or one {@link MockServerHttpContentDecompressor} does not decode (such as {@code identity},
     * a list of codings, or {@code br} without Brotli on the classpath).
     *
     * @param maxBodySize the largest decompressed body accepted, normally {@code maxRequestBodySize}
     */
    static Http3RequestDecompressor forHeaders(List<Map.Entry<String, String>> headers, ByteBufAllocator alloc, int maxBodySize, int componentLimit) {
        DefaultHttpRequest nettyRequest = null;
        if (headers != null) {
            for (Map.Entry<String, String> header : headers) {
                if (HttpHeaderNames.CONTENT_ENCODING.contentEqualsIgnoreCase(header.getKey())) {
                    if (nettyRequest == null) {
                        nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", headersFactory().withValidation(false));
                    }
                    nettyRequest.headers().add(HttpHeaderNames.CONTENT_ENCODING, header.getValue() != null ? header.getValue() : "");
                }
            }
        }
        if (nettyRequest == null) {
            return null;
        }
        DecompressedBodySink sink = new DecompressedBodySink(alloc, maxBodySize, componentLimit);
        EmbeddedChannel channel = new EmbeddedChannel();
        // the decoders allocate from this channel's config, so give them the request stream's allocator
        channel.config().setAllocator(alloc);
        channel.pipeline().addLast(new MockServerHttpContentDecompressor(maxBodySize), sink);
        Http3RequestDecompressor decompressor = new Http3RequestDecompressor(channel, sink);
        try {
            channel.writeInbound(nettyRequest);
        } catch (RuntimeException | Error e) {
            decompressor.release();
            throw e;
        }
        // HttpContentDecompressor removes Content-Encoding only when it installs a decoder for it
        if (!sink.decoding) {
            decompressor.release();
            return null;
        }
        return decompressor;
    }

    /**
     * Decompresses the readable bytes of {@code content} without changing its indices or reference count.
     *
     * @return false once the decompressed body exceeds the maximum size, after which nothing more is decompressed
     * @throws DecoderException when the compressed body is corrupt
     */
    boolean decompress(ByteBuf content) {
        int index = content.readerIndex();
        int end = content.writerIndex();
        while (index < end && !sink.exceeded) {
            int length = Math.min(SLICE_BYTES, end - index);
            channel.writeInbound(new DefaultHttpContent(content.retainedSlice(index, length)));
            index += length;
        }
        return !sink.exceeded;
    }

    /**
     * Ends the body, decompressing whatever the decoder still holds.
     *
     * @return false when the decompressed body exceeds the maximum size
     * @throws DecoderException when the compressed body is corrupt
     */
    boolean finish() {
        if (!sink.exceeded) {
            channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
        }
        return !sink.exceeded;
    }

    /**
     * How many bytes the decoder has produced, including any it produced past the maximum size before stopping.
     */
    long decompressedSize() {
        return sink.size;
    }

    /**
     * The decompressed body, still owned by this decompressor: it is released by {@link #release()}.
     */
    CompositeByteBuf body() {
        return sink.body;
    }

    void release() {
        sink.releaseBody();
        try {
            channel.finishAndReleaseAll();
        } catch (RuntimeException ignored) {
            // a decoder abandoned mid-body may fail as it closes; the body has already been released
        }
    }

    private static final class DecompressedBodySink extends ChannelInboundHandlerAdapter {

        private final int maxBodySize;
        private final int componentLimit;
        private CompositeByteBuf body;
        private int mergedComponents;
        private long size;
        private boolean decoding;
        private boolean exceeded;

        DecompressedBodySink(ByteBufAllocator alloc, int maxBodySize, int componentLimit) {
            this.maxBodySize = maxBodySize;
            this.componentLimit = componentLimit;
            // the component limit is enforced by Http3RequestBridge.limitComponents, as for the compressed body
            this.body = alloc.compositeBuffer(Integer.MAX_VALUE);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof HttpRequest) {
                    decoding = !((HttpRequest) msg).headers().contains(HttpHeaderNames.CONTENT_ENCODING);
                } else if (msg instanceof HttpContent) {
                    ByteBuf content = ((HttpContent) msg).content();
                    size += content.readableBytes();
                    if (size > maxBodySize) {
                        exceeded = true;
                        releaseBody();
                    } else if (body != null) {
                        Http3RequestBridge.accumulateBody(body, content);
                        mergedComponents = Http3RequestBridge.limitComponents(body, componentLimit, mergedComponents);
                    }
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        void releaseBody() {
            if (body != null) {
                body.release();
                body = null;
            }
        }
    }
}
