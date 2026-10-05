package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.MaxMessagesRecvByteBufAllocator;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.util.UncheckedBooleanSupplier;

/**
 * Sizes the buffer each read of an accepted connection is given: as the channel's own allocator does (adaptive,
 * following recent reads down to 64 bytes) until the connection is found to be binary, then
 * {@link #BINARY_READ_SIZE} for every read, so that a binary message is not cut by a buffer that has followed
 * small messages down. It also knows when the channel is inside a read loop. One instance per channel, installed
 * before its first read: Netty takes the handle once, on that read, and the native transports wrap it, so only
 * this object can be told, or asked.
 */
public final class BinaryAwareRecvByteBufAllocator implements MaxMessagesRecvByteBufAllocator {

    /** The largest buffer Netty's adaptive allocator grows to, so no read is larger than one it could already be. */
    public static final int BINARY_READ_SIZE = 64 * 1024;

    private final MaxMessagesRecvByteBufAllocator original;
    // read and written on the channel's event loop only
    private boolean binary;
    private boolean reading;

    private BinaryAwareRecvByteBufAllocator(MaxMessagesRecvByteBufAllocator original) {
        this.original = original;
    }

    /** Wraps the channel's allocator. Has no effect on the reads of a channel that has already been read. */
    public static void install(Channel channel) {
        RecvByteBufAllocator current = channel.config().getRecvByteBufAllocator();
        if (current instanceof MaxMessagesRecvByteBufAllocator && !(current instanceof BinaryAwareRecvByteBufAllocator)) {
            channel.config().setRecvByteBufAllocator(new BinaryAwareRecvByteBufAllocator((MaxMessagesRecvByteBufAllocator) current));
        }
    }

    /** Every later read of the channel is given {@link #BINARY_READ_SIZE}. Call on the channel's event loop. */
    public static void readWholeMessages(Channel channel) {
        RecvByteBufAllocator current = channel.config().getRecvByteBufAllocator();
        if (current instanceof BinaryAwareRecvByteBufAllocator) {
            ((BinaryAwareRecvByteBufAllocator) current).binary = true;
        }
    }

    /** Whether the channel is inside a read loop: from its first read until just before its channelReadComplete. */
    public static boolean isReading(Channel channel) {
        RecvByteBufAllocator current = channel.config().getRecvByteBufAllocator();
        return current instanceof BinaryAwareRecvByteBufAllocator && ((BinaryAwareRecvByteBufAllocator) current).reading;
    }

    @Override
    public int maxMessagesPerRead() {
        return original.maxMessagesPerRead();
    }

    @Override
    public MaxMessagesRecvByteBufAllocator maxMessagesPerRead(int maxMessagesPerRead) {
        original.maxMessagesPerRead(maxMessagesPerRead);
        return this;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Handle newHandle() {
        return new BinaryAwareHandle(original.newHandle());
    }

    // ExtendedHandle: the epoll and kqueue channels accept no other
    @SuppressWarnings("deprecation")
    private final class BinaryAwareHandle extends DelegatingHandle implements ExtendedHandle {

        private BinaryAwareHandle(Handle original) {
            super(original);
        }

        @Override
        public void reset(ChannelConfig config) {
            reading = true;
            delegate().reset(config);
        }

        @Override
        public void readComplete() {
            reading = false;
            delegate().readComplete();
        }

        @Override
        public int guess() {
            return binary ? BINARY_READ_SIZE : delegate().guess();
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator allocator) {
            return binary ? allocator.ioBuffer(BINARY_READ_SIZE) : delegate().allocate(allocator);
        }

        @Override
        public boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier) {
            Handle original = delegate();
            return original instanceof ExtendedHandle ? ((ExtendedHandle) original).continueReading(maybeMoreDataSupplier) : original.continueReading();
        }
    }
}
