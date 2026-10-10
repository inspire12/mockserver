package org.mockserver.codec;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The HTTP/1.1 server aggregator ({@link HttpObjectAggregators#httpObjectAggregator}) and the forward client's
 * {@link StreamingAwareHttpObjectAggregator}, past their component limit, merge only the pieces added since their last
 * merge. Each case is wire bytes decoded by the real Netty codec, with the decompressor production puts before the
 * aggregator, and is run through the production aggregator and a plain {@link HttpObjectAggregator} with the same
 * limits: three messages on one keep-alive connection (pipelined into one read, or split into reads), compared
 * message by message, with every buffer released.
 */
public class Http1CoalescingAggregationTest {

    private static final int BLOCK = CoalescingHttpObjectAggregator.BLOCK_BYTES;
    private static final int SMALL = CoalescingHttpObjectAggregator.SMALL_PIECE_BYTES;
    private static final int RUN = CoalescingHttpObjectAggregator.RUN_PIECES;
    private static final int MAX = 40_000;
    private static final int SMALL_LIMIT = 128;
    private static final int[][] PATTERNS = {
        {1}, {100}, {SMALL - 1}, {SMALL}, {8192}, {BLOCK + 1}, {1, BLOCK},
        runThen(100, RUN, SMALL), runThen(1, 2 * RUN - 1, BLOCK), runThen(64, RUN, SMALL, 10, 10, 10, 10, 10, SMALL),
        randomSizes(1, 64, 1), randomSizes(1, 40_000, 2)
    };

    private enum Side {
        SERVER, CLIENT
    }

    private enum Framing {
        /**
         * every message in one read, so the decoder emits each chunk whole and the messages are pipelined
         */
        CHUNKED_ONE_READ,
        /**
         * reads of 1 to 64 bytes, so chunks arrive in pieces and reads cross chunk and message boundaries
         */
        CHUNKED_SMALL_READS,
        CHUNKED_TRAILERS,
        /**
         * a Content-Length body, its pieces being the reads
         */
        CONTENT_LENGTH,
        /**
         * a gzip body, chunked, so the pieces are what the decompressor emits
         */
        GZIP_CHUNKED
    }

    @Test
    public void shouldAggregateTheSameAsAPlainAggregatorOnTheServerAndForwardClientCodecs() {
        int[] cases = new int[1];
        int[] mergedMidBody = new int[1];
        for (Side side : Side.values()) {
            for (int componentLimit : new int[]{HttpObjectAggregators.componentLimit(MAX), SMALL_LIMIT}) {
                for (int[] pattern : PATTERNS) {
                    for (int length : lengths(pattern, componentLimit)) {
                        for (Framing framing : Framing.values()) {
                            if (assertSameAsPlainAggregator(side, componentLimit, pattern, length, framing)) {
                                mergedMidBody[0]++;
                            }
                            cases[0]++;
                        }
                    }
                }
            }
        }
        assertThat(cases[0], is(2 * 2 * PATTERNS.length * 10 * Framing.values().length));
        // the corpus is deterministic: 511 cases deliver a message in more pieces than the limit
        assertThat("cases whose body passed the component limit", mergedMidBody[0], is(511));
    }

    private static List<Integer> lengths(int[] pattern, int componentLimit) {
        List<Integer> lengths = new ArrayList<>(List.of(0, 1, BLOCK + 1, MAX - 1, MAX, MAX + 1));
        for (int pieces : new int[]{64, 65, 80, componentLimit + 1}) {
            long bytes = 0;
            for (int i = 0; i < pieces; i++) {
                bytes += pattern[i % pattern.length];
            }
            lengths.add((int) Math.min(bytes, MAX + 1));
        }
        return lengths;
    }

    /**
     * @return whether a delivered message arrived in more pieces than the component limit, so the plain aggregator
     * consolidated it and the production one merged it on the way
     */
    private static boolean assertSameAsPlainAggregator(Side side, int componentLimit, int[] pattern, int length, Framing framing) {
        String description = side + " " + framing + " limit " + componentLimit + " length " + length
            + " pieces " + Arrays.toString(pattern.length > 8 ? Arrays.copyOf(pattern, 8) : pattern);
        int[] messageLengths = {length, 5_000, length};
        Result merging = run(side, true, componentLimit, pattern, messageLengths, framing);
        Result plain = run(side, false, componentLimit, pattern, messageLengths, framing);
        assertThat(description, merging.events, is(plain.events));
        assertThat(description, merging.outbound.toString(), is(plain.outbound.toString()));
        assertThat(description, merging.open, is(plain.open));
        assertThat(description, merging.bodies.size(), is(plain.bodies.size()));
        for (int i = 0; i < merging.bodies.size(); i++) {
            assertThat(description + " message " + i, merging.bodies.get(i), is(plain.bodies.get(i)));
        }
        // the first message is delivered whole unless it is too large, and then the response says so
        if (length <= MAX) {
            assertThat(description, merging.bodies.get(0), is(body(0, length)));
        } else if (side == Side.SERVER) {
            assertThat(description, merging.outbound.toString(), containsString("413 Request Entity Too Large"));
        } else {
            assertThat(description, merging.events.get(0), containsString("TooLongHttpContentException"));
        }
        // merged components count as one towards the limit, and the whole body is merged again once half the limit is
        // merged components, so up to half the limit less one more than Netty's composite holds
        for (int components : merging.components) {
            assertThat(description, components, lessThanOrEqualTo(componentLimit + componentLimit / 2 - 1));
        }
        return merging.mergedMidBody;
    }

    private static Result run(Side side, boolean production, int componentLimit, int[] pattern, int[] messageLengths, Framing framing) {
        TrackingAllocator allocator = new TrackingAllocator();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAllocator(allocator);
        Configuration configuration = configuration();
        HttpObjectAggregator aggregator;
        if (side == Side.SERVER) {
            channel.pipeline().addLast(new HttpServerCodec(configuration.maxInitialLineLength(), configuration.maxHeaderSize(), configuration.maxChunkSize()));
            aggregator = production ? HttpObjectAggregators.httpObjectAggregator(MAX) : new HttpObjectAggregator(MAX);
        } else {
            channel.pipeline().addLast(new HttpClientCodec());
            aggregator = production ? new StreamingAwareHttpObjectAggregator(MAX, configuration, new MockServerLogger()) : new HttpObjectAggregator(MAX);
        }
        aggregator.setMaxCumulationBufferComponents(componentLimit);
        channel.pipeline().addLast(side == Side.SERVER ? new MockServerHttpContentDecompressor(MAX) : new HttpContentDecompressor());
        PieceCounter pieces = new PieceCounter();
        channel.pipeline().addLast(pieces);
        channel.pipeline().addLast(aggregator);
        Recorder recorder = new Recorder(componentLimit, pieces);
        channel.pipeline().addLast(recorder);
        List<ByteBuf> inputs = new ArrayList<>();
        Result result = new Result();
        try {
            if (side == Side.CLIENT) {
                for (int i = 0; i < messageLengths.length; i++) {
                    channel.writeOutbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/forwarded/" + i));
                }
                drainOutbound(channel);
            }
            for (byte[] read : reads(side, pattern, messageLengths, framing)) {
                ByteBuf input = Unpooled.wrappedBuffer(read);
                inputs.add(input);
                if (channel.isOpen()) {
                    channel.writeInbound(input);
                } else {
                    input.release();
                }
                result.outbound.append(drainOutbound(channel));
                result.open.add(channel.isOpen());
            }
        } finally {
            channel.finishAndReleaseAll();
        }
        result.events = recorder.events;
        result.bodies = recorder.bodies;
        result.components = recorder.components;
        result.mergedMidBody = recorder.mergedMidBody;
        allocator.assertAllReleased();
        for (ByteBuf input : inputs) {
            assertThat("input released", input.refCnt(), is(0));
        }
        return result;
    }

    private static String drainOutbound(EmbeddedChannel channel) {
        StringBuilder outbound = new StringBuilder();
        Object message;
        while ((message = channel.readOutbound()) != null) {
            if (message instanceof ByteBuf) {
                outbound.append(((ByteBuf) message).toString(StandardCharsets.US_ASCII));
            }
            ReferenceCountUtil.release(message);
        }
        return outbound.toString();
    }

    /**
     * The wire bytes of every message, split into the reads the channel receives.
     */
    private static List<byte[]> reads(Side side, int[] pattern, int[] messageLengths, Framing framing) {
        List<byte[]> reads = new ArrayList<>();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        for (int message = 0; message < messageLengths.length; message++) {
            byte[] body = body(message, messageLengths[message]);
            StringBuilder head = new StringBuilder(side == Side.SERVER
                ? "POST /upload/" + message + " HTTP/1.1\r\nHost: localhost\r\n"
                : "HTTP/1.1 200 OK\r\nx-message: " + message + "\r\n");
            if (framing == Framing.CONTENT_LENGTH) {
                head.append("Content-Length: ").append(body.length).append("\r\n\r\n");
                reads.add(head.toString().getBytes(StandardCharsets.US_ASCII));
                int offset = 0;
                for (int piece = 0; offset < body.length; piece++) {
                    int pieceLength = Math.min(pattern[piece % pattern.length], body.length - offset);
                    reads.add(Arrays.copyOfRange(body, offset, offset + pieceLength));
                    offset += pieceLength;
                }
                continue;
            }
            byte[] encoded = body;
            if (framing == Framing.GZIP_CHUNKED) {
                head.append("Content-Encoding: gzip\r\n");
                encoded = gzip(body);
            }
            head.append("Transfer-Encoding: chunked\r\n\r\n");
            write(wire, head.toString().getBytes(StandardCharsets.US_ASCII));
            int offset = 0;
            for (int piece = 0; offset < encoded.length; piece++) {
                int pieceLength = Math.min(pattern[piece % pattern.length], encoded.length - offset);
                write(wire, (Integer.toHexString(pieceLength) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                wire.write(encoded, offset, pieceLength);
                write(wire, "\r\n".getBytes(StandardCharsets.US_ASCII));
                offset += pieceLength;
            }
            write(wire, (framing == Framing.CHUNKED_TRAILERS ? "0\r\nx-checksum: " + message + "\r\nx-empty:\r\n\r\n" : "0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        }
        byte[] all = wire.toByteArray();
        if (framing == Framing.CHUNKED_SMALL_READS) {
            Random random = new Random(all.length);
            for (int offset = 0; offset < all.length; ) {
                int readLength = Math.min(1 + random.nextInt(64), all.length - offset);
                reads.add(Arrays.copyOfRange(all, offset, offset + readLength));
                offset += readLength;
            }
        } else if (all.length > 0) {
            reads.add(all);
        }
        return reads;
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    private static byte[] gzip(byte[] body) {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return compressed.toByteArray();
    }

    /**
     * Every byte value, including the non-ASCII ones, at every offset modulo 251, and different for each message.
     */
    static byte[] body(int message, int length) {
        byte[] body = new byte[length];
        for (int i = 0; i < length; i++) {
            body[i] = (byte) ((i * 31 + i / 251 + message * 17) % 256);
        }
        return body;
    }

    /**
     * {@code count} pieces of {@code small} bytes, then the pieces in {@code then}.
     */
    private static int[] runThen(int small, int count, int... then) {
        int[] pattern = new int[count + then.length];
        Arrays.fill(pattern, 0, count, small);
        System.arraycopy(then, 0, pattern, count, then.length);
        return pattern;
    }

    private static int[] randomSizes(int min, int max, long seed) {
        Random random = new Random(seed);
        int[] sizes = new int[500];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = min + random.nextInt(max - min + 1);
        }
        return sizes;
    }

    private static final class Result {
        private List<String> events;
        private List<byte[]> bodies;
        private List<Integer> components;
        private boolean mergedMidBody;
        private final StringBuilder outbound = new StringBuilder();
        private final List<Boolean> open = new ArrayList<>();
    }

    /**
     * Records each aggregated message (status or request line, headers, trailers, decoder result) and body, and each
     * exception, the way the handlers after the aggregator would see them.
     */
    private static final class Recorder extends ChannelInboundHandlerAdapter {
        private final int componentLimit;
        private final PieceCounter pieces;
        private final List<String> events = new ArrayList<>();
        private final List<byte[]> bodies = new ArrayList<>();
        private final List<Integer> components = new ArrayList<>();
        private boolean mergedMidBody;

        Recorder(int componentLimit, PieceCounter pieces) {
            this.componentLimit = componentLimit;
            this.pieces = pieces;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                FullHttpMessage message = (FullHttpMessage) msg;
                String start = message instanceof FullHttpRequest
                    ? ((FullHttpRequest) message).method() + " " + ((FullHttpRequest) message).uri()
                    : String.valueOf(((FullHttpResponse) message).status());
                events.add(start + " " + message.headers().entries() + " " + message.trailingHeaders().entries() + " " + message.decoderResult());
                bodies.add(ByteBufUtil.getBytes(message.content()));
                if (message.content() instanceof CompositeByteBuf) {
                    components.add(((CompositeByteBuf) message.content()).numComponents());
                }
                mergedMidBody |= pieces.pieces > componentLimit;
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            StringBuilder chain = new StringBuilder("exception");
            for (Throwable t = cause; t != null; t = t.getCause()) {
                chain.append(' ').append(t.getClass().getSimpleName());
            }
            events.add(chain.toString());
        }
    }

    /**
     * Counts the non-empty content pieces of the current message as they reach the aggregator.
     */
    private static final class PieceCounter extends ChannelInboundHandlerAdapter {
        private int pieces;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof HttpMessage) {
                pieces = 0;
            }
            if (msg instanceof HttpContent && ((HttpContent) msg).content().isReadable()) {
                pieces++;
            }
            ctx.fireChannelRead(msg);
        }
    }

    private static final class TrackingAllocator extends AbstractByteBufAllocator {
        private final List<ByteBuf> buffers = new ArrayList<>();

        TrackingAllocator() {
            super(false);
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            ByteBuf buffer = new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
            buffers.add(buffer);
            return buffer;
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            ByteBuf buffer = new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
            buffers.add(buffer);
            return buffer;
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }

        void assertAllReleased() {
            for (ByteBuf buffer : buffers) {
                assertThat("allocated buffer released", buffer.refCnt(), is(0));
            }
        }
    }
}
