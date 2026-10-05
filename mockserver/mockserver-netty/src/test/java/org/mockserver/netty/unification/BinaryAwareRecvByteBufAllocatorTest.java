package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.AdaptiveRecvByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOption;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * The buffer each read of a connection is given: exactly what Netty's adaptive allocator would give it, read for
 * read, until the connection is binary, and 64 KiB for every read after that.
 */
public class BinaryAwareRecvByteBufAllocatorTest {

    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @After
    public void closeChannels() {
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
    }

    private EmbeddedChannel channel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channels.add(channel);
        return channel;
    }

    private EmbeddedChannel channelWithTheAllocator() {
        EmbeddedChannel channel = channel();
        BinaryAwareRecvByteBufAllocator.install(channel);
        return channel;
    }

    /** The handle Netty's read loop uses for the channel: made once, by the allocator the channel has at the time. */
    @SuppressWarnings("deprecation")
    static RecvByteBufAllocator.Handle readHandle(Channel channel) {
        return channel.unsafe().recvBufAllocHandle();
    }

    /**
     * Puts the handle through read loops as {@code AbstractNioByteChannel.read()} does, one loop for each number
     * of bytes waiting on the socket, and returns what it did: the buffer it guessed and the buffer it allocated
     * for each read, and where each loop ended.
     */
    @SuppressWarnings("deprecation")
    static List<String> readsOf(RecvByteBufAllocator.Handle handle, ChannelConfig config, int... bytesWaiting) {
        List<String> reads = new ArrayList<>();
        for (int waiting : bytesWaiting) {
            handle.reset(config);
            int left = waiting;
            do {
                int guess = handle.guess();
                ByteBuf buffer = handle.allocate(UnpooledByteBufAllocator.DEFAULT);
                reads.add("guess " + guess + " buffer " + buffer.writableBytes());
                handle.attemptedBytesRead(buffer.writableBytes());
                int read = Math.min(left, buffer.writableBytes());
                buffer.release();
                handle.lastBytesRead(read);
                if (read == 0) {
                    break;
                }
                left -= read;
                handle.incMessagesRead(1);
            } while (handle.continueReading());
            handle.readComplete();
            reads.add("end of loop with " + left + " left");
        }
        return reads;
    }

    /** What Netty's own allocator does over the same read loops, on a channel left as Netty made it. */
    @SuppressWarnings("deprecation")
    private List<String> readsOfNettysAllocator(int... bytesWaiting) {
        EmbeddedChannel untouched = channel();
        assertThat(untouched.config().getRecvByteBufAllocator(), instanceOf(AdaptiveRecvByteBufAllocator.class));
        return readsOf(readHandle(untouched), untouched.config(), bytesWaiting);
    }

    private static int[] repeated(int times, int... bytesWaiting) {
        int[] all = new int[times * bytesWaiting.length];
        for (int i = 0; i < all.length; i++) {
            all[i] = bytesWaiting[i % bytesWaiting.length];
        }
        return all;
    }

    private static int[] sessionOfManySmallMessagesThenLargerOnes() {
        int[] session = Arrays.copyOf(repeated(60, 5), 64);
        session[60] = 100;
        session[61] = 422;
        session[62] = 800;
        session[63] = 3000;
        return session;
    }

    private static int[] seededMixOfSizes() {
        Random random = new Random(187);
        int[] sizes = new int[2000];
        for (int i = 0; i < sizes.length; i++) {
            // most reads small, some of a few kilobytes, a few larger than the largest buffer there is
            int kind = random.nextInt(10);
            sizes[i] = kind < 6 ? 1 + random.nextInt(200) : kind < 9 ? 1 + random.nextInt(8000) : 1 + random.nextInt(400_000);
        }
        return sizes;
    }

    @Test
    public void shouldSizeEveryReadAsNettysAdaptiveAllocatorDoesUntilTheConnectionIsBinary() {
        int[][] readSequences = {
            sessionOfManySmallMessagesThenLargerOnes(),
            repeated(30, 1_000_000),
            repeated(40, 2048, 2047, 1, 64, 65, 65536, 65537, 512, 513),
            seededMixOfSizes(),
        };
        for (int[] bytesWaiting : readSequences) {
            EmbeddedChannel channel = channelWithTheAllocator();

            List<String> reads = readsOf(readHandle(channel), channel.config(), bytesWaiting);

            assertThat(reads, is(readsOfNettysAllocator(bytesWaiting)));
        }
    }

    @Test
    public void shouldFollowSmallReadsDownAsNettyDoesWhileTheConnectionIsNotBinary() {
        EmbeddedChannel channel = channelWithTheAllocator();

        List<String> reads = readsOf(readHandle(channel), channel.config(), repeated(70, 5));

        assertThat("the comparison above is of an allocator that does adapt", reads.get(0), is("guess 2048 buffer 2048"));
        assertThat(reads, hasItem("guess 64 buffer 64"));
    }

    @Test
    public void shouldGiveEveryReadOfABinaryConnection64KiBWhateverWasReadBefore() {
        EmbeddedChannel channel = channelWithTheAllocator();
        RecvByteBufAllocator.Handle handle = readHandle(channel);
        assertThat(readsOf(handle, channel.config(), repeated(70, 5)), hasItem("guess 64 buffer 64"));

        BinaryAwareRecvByteBufAllocator.readWholeMessages(channel);

        List<String> reads = readsOf(handle, channel.config(), sessionOfManySmallMessagesThenLargerOnes());
        reads.removeIf(read -> read.startsWith("end of loop"));
        assertThat(reads, hasSize(64));
        assertThat("one read for each message, of 64 KiB", reads, everyItem(is("guess 65536 buffer 65536")));
    }

    /** The epoll channels read through JNI, into a direct buffer only. */
    @Test
    @SuppressWarnings("deprecation")
    public void shouldGiveABinaryConnectionTheKindOfBufferNettyReadsInto() {
        EmbeddedChannel channel = channelWithTheAllocator();
        BinaryAwareRecvByteBufAllocator.readWholeMessages(channel);

        ByteBuf forABinaryRead = readHandle(channel).allocate(UnpooledByteBufAllocator.DEFAULT);
        ByteBuf nettys = readHandle(channel()).allocate(UnpooledByteBufAllocator.DEFAULT);
        try {
            assertThat(forABinaryRead.isDirect(), is(true));
            assertThat("as Netty's own allocator gives", nettys.isDirect(), is(true));
        } finally {
            forABinaryRead.release();
            nettys.release();
        }
    }

    @Test
    public void shouldReadAMessageLargerThan64KiBIn64KiBPieces() {
        EmbeddedChannel channel = channelWithTheAllocator();
        BinaryAwareRecvByteBufAllocator.readWholeMessages(channel);

        List<String> reads = readsOf(readHandle(channel), channel.config(), 150_000);

        assertThat(reads, is(Arrays.asList("guess 65536 buffer 65536", "guess 65536 buffer 65536", "guess 65536 buffer 65536", "end of loop with 0 left")));
    }

    @Test
    public void shouldKeepTheChannelsLimitOnReadsInOneLoop() {
        EmbeddedChannel channel = channelWithTheAllocator();
        EmbeddedChannel untouched = channel();
        assertThat(channel.config().getOption(ChannelOption.MAX_MESSAGES_PER_READ), is(untouched.config().getOption(ChannelOption.MAX_MESSAGES_PER_READ)));

        channel.config().setOption(ChannelOption.MAX_MESSAGES_PER_READ, 3);
        BinaryAwareRecvByteBufAllocator.readWholeMessages(channel);
        List<String> reads = readsOf(readHandle(channel), channel.config(), 1_000_000);

        assertThat("three reads, then the loop ends with bytes still waiting", reads, hasSize(4));
        assertThat(reads.get(3), startsWith("end of loop with "));
        assertThat(reads.get(3), is(not("end of loop with 0 left")));
    }

    @Test
    public void shouldStopALoopWhenReadingIsTurnedOffAsNettyDoes() {
        EmbeddedChannel channel = channelWithTheAllocator();
        channel.config().setAutoRead(false);
        BinaryAwareRecvByteBufAllocator.readWholeMessages(channel);

        List<String> reads = readsOf(readHandle(channel), channel.config(), 1_000_000);

        assertThat("one read, then the loop ends", reads, hasSize(2));
    }

    /** The epoll and kqueue channels refuse an allocator whose handle is not an ExtendedHandle, and wrap the one they get. */
    @Test
    @SuppressWarnings("deprecation")
    public void shouldBeToldThroughTheChannelSoThatAWrappedHandleStillHears() {
        EmbeddedChannel channel = channelWithTheAllocator();
        RecvByteBufAllocator.Handle handle = channel.config().getRecvByteBufAllocator().newHandle();
        assertThat(handle, instanceOf(RecvByteBufAllocator.ExtendedHandle.class));
        RecvByteBufAllocator.Handle wrappedAsANativeTransportDoes = new RecvByteBufAllocator.DelegatingHandle(handle);
        assertThat(wrappedAsANativeTransportDoes.guess(), is(2048));

        BinaryAwareRecvByteBufAllocator.readWholeMessages(channel);

        assertThat(readsOf(wrappedAsANativeTransportDoes, channel.config(), 5).get(0), is("guess 65536 buffer 65536"));
        assertThat(((RecvByteBufAllocator.ExtendedHandle) handle).continueReading(() -> true), is(true));
        assertThat(((RecvByteBufAllocator.ExtendedHandle) handle).continueReading(() -> false), is(false));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void shouldSayWhetherTheChannelIsInsideAReadLoop() {
        EmbeddedChannel channel = channelWithTheAllocator();
        RecvByteBufAllocator.Handle handle = readHandle(channel);
        assertThat("before the first read", BinaryAwareRecvByteBufAllocator.isReading(channel), is(false));

        handle.reset(channel.config());
        assertThat("from the start of a loop", BinaryAwareRecvByteBufAllocator.isReading(channel), is(true));
        handle.allocate(UnpooledByteBufAllocator.DEFAULT).release();
        handle.lastBytesRead(5);
        handle.incMessagesRead(1);
        assertThat("while it reads", BinaryAwareRecvByteBufAllocator.isReading(channel), is(true));

        handle.readComplete();
        assertThat("to its end", BinaryAwareRecvByteBufAllocator.isReading(channel), is(false));
        assertThat("and never on a channel left as Netty made it", BinaryAwareRecvByteBufAllocator.isReading(channel()), is(false));
    }

    @Test
    public void shouldWrapAChannelsAllocatorOnlyOnce() {
        EmbeddedChannel channel = channelWithTheAllocator();
        RecvByteBufAllocator installed = channel.config().getRecvByteBufAllocator();

        BinaryAwareRecvByteBufAllocator.install(channel);

        assertThat(channel.config().getRecvByteBufAllocator() == installed, is(true));
    }

    @Test
    public void shouldLeaveAChannelWithoutTheAllocatorAsItIs() {
        EmbeddedChannel untouched = channel();

        BinaryAwareRecvByteBufAllocator.readWholeMessages(untouched);

        List<String> reads = readsOf(readHandle(untouched), untouched.config(), repeated(70, 5));
        assertThat(reads.get(0), is("guess 2048 buffer 2048"));
        assertThat(reads, hasItem("guess 64 buffer 64"));
    }
}
