package org.mockserver.codec;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.AdaptiveRecvByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * An HTTP/1.1 chunked body through bare codecs with unbounded line limits, one chunk per socket read, so each read
 * can be padded with a chunk extension and the one-byte chunk in it is a slice that would keep the whole read
 * allocated. This isolates the aggregator: in production the server's chunk-size lines are limited to 8,192 bytes by
 * {@link HttpChunkLineLimiter} and the forward client's {@code new HttpClientCodec()} to 4,096. The server's aggregator
 * and the forward client's merge the chunks of a mostly unused read, so the body holds about its own size plus the
 * read in hand, as for one-byte chunks with no extension.
 */
public class Http1ChunkPinningTest {

    private static final int KIB = 1024;
    private static final int MAX = Integer.MAX_VALUE;

    @Test(timeout = 120_000)
    public void shouldNotPinAReadPerChunkOnTheServerCodec() {
        for (int extensionBytes : new int[]{0, 16 * KIB, 60 * KIB}) {
            int chunks = 2_000;
            Run production = run(() -> new HttpServerCodec(MAX, MAX, MAX), () -> HttpObjectAggregators.httpObjectAggregator(10 * 1024 * 1024), true, extensionBytes, chunks);
            Run netty = run(() -> new HttpServerCodec(MAX, MAX, MAX), () -> HttpObjectAggregators.limitComponents(new HttpObjectAggregator(10 * 1024 * 1024)), true, extensionBytes, chunks);
            assertBounded("server, extension " + extensionBytes, production, netty, chunks);
        }
    }

    @Test(timeout = 120_000)
    public void shouldNotPinAReadPerChunkOnTheForwardClientCodec() {
        // an upstream's chunked response through the forward client's codec and aggregator
        for (int extensionBytes : new int[]{0, 60 * KIB}) {
            int chunks = 2_000;
            Run production = run(() -> new HttpClientCodec(MAX, MAX, MAX), () -> new StreamingAwareHttpObjectAggregator(10 * 1024 * 1024), false, extensionBytes, chunks);
            Run netty = run(() -> new HttpClientCodec(MAX, MAX, MAX), () -> HttpObjectAggregators.limitComponents(new HttpObjectAggregator(10 * 1024 * 1024)), false, extensionBytes, chunks);
            assertBounded("forward client, extension " + extensionBytes, production, netty, chunks);
        }
    }

    private static void assertBounded(String description, Run production, Run netty, int body) {
        String detail = description + ": production held " + production.peakBytes + ", Netty's aggregator " + netty.peakBytes;
        assertThat(detail, production.received, is((long) body));
        assertThat(detail, production.unreleased, is(0L));
        // twice the body, the read in hand and the next, and the decoder's own buffer for a line as long as a read
        assertThat(detail, production.peakBytes, lessThanOrEqualTo(2L * body + 4 * 64 * KIB));
        if (description.endsWith(" " + 60 * KIB)) {
            assertThat(detail, 10 * production.peakBytes, lessThanOrEqualTo(netty.peakBytes));
        }
    }

    private static Run run(Supplier<ChannelHandler> codec, Supplier<HttpObjectAggregator> aggregator, boolean request, int extensionBytes, int chunks) {
        TrackingAllocator allocator = new TrackingAllocator();
        Run run = new Run();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAllocator(allocator);
        channel.pipeline().addLast(codec.get(), aggregator.get(), new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (msg instanceof FullHttpMessage) {
                    run.received = ((FullHttpMessage) msg).content().readableBytes();
                }
                ReferenceCountUtil.release(msg);
            }
        });
        if (!request) {
            // the client codec decodes a response only after it has sent a request
            channel.writeOutbound(new io.netty.handler.codec.http.DefaultFullHttpRequest(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.GET, "/"));
            Object written;
            while ((written = channel.readOutbound()) != null) {
                ReferenceCountUtil.release(written);
            }
        }
        RecvByteBufAllocator.Handle handle = new AdaptiveRecvByteBufAllocator().maxMessagesPerRead(16).newHandle();
        String head = request
            ? "POST /x HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n"
            : "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n";
        try {
            deliver(channel, handle, allocator, head.getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = ("1" + (extensionBytes > 0 ? ";e=" + "a".repeat(extensionBytes) : "") + "\r\nX\r\n").getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < chunks; i++) {
                deliver(channel, handle, allocator, chunk);
            }
            deliver(channel, handle, allocator, "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        } finally {
            channel.finishAndReleaseAll();
        }
        run.peakBytes = allocator.peakLiveBytes;
        run.unreleased = allocator.unreleased();
        return run;
    }

    /**
     * One socket read event, its bytes in buffers sized by Netty's adaptive read allocator as a socket channel's are.
     */
    private static void deliver(EmbeddedChannel channel, RecvByteBufAllocator.Handle handle, TrackingAllocator allocator, byte[] bytes) {
        int offset = 0;
        handle.reset(channel.config());
        while (offset < bytes.length) {
            ByteBuf read = handle.allocate(allocator);
            int length = Math.min(read.writableBytes(), bytes.length - offset);
            handle.attemptedBytesRead(read.writableBytes());
            read.writeBytes(bytes, offset, length);
            handle.lastBytesRead(length);
            handle.incMessagesRead(1);
            offset += length;
            channel.pipeline().fireChannelRead(read);
            if (!handle.continueReading()) {
                handle.readComplete();
                channel.pipeline().fireChannelReadComplete();
                handle.reset(channel.config());
            }
        }
        handle.readComplete();
        channel.pipeline().fireChannelReadComplete();
        channel.runPendingTasks();
    }

    private static final class Run {
        private long received = -1;
        private long peakBytes;
        private long unreleased;
    }

    /**
     * Counts every buffer's bytes while it is allocated, including capacity growth, and the most at once.
     */
    private static final class TrackingAllocator extends AbstractByteBufAllocator {
        private final List<ByteBuf> buffers = new ArrayList<>();
        private long liveBytes;
        private long peakLiveBytes;

        TrackingAllocator() {
            super(false);
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            allocated(initialCapacity);
            ByteBuf buffer = new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity) {
                @Override
                public ByteBuf capacity(int newCapacity) {
                    int old = capacity();
                    ByteBuf resized = super.capacity(newCapacity);
                    allocated(newCapacity);
                    liveBytes -= old;
                    return resized;
                }

                @Override
                protected void deallocate() {
                    liveBytes -= capacity();
                    super.deallocate();
                }
            };
            buffers.add(buffer);
            return buffer;
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            return newHeapBuffer(initialCapacity, maxCapacity);
        }

        private void allocated(int bytes) {
            liveBytes += bytes;
            peakLiveBytes = Math.max(peakLiveBytes, liveBytes);
        }

        long unreleased() {
            return buffers.stream().filter(buffer -> buffer.refCnt() != 0).count();
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }
    }
}
