package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedInput;
import io.netty.handler.stream.ChunkedWriteHandler;

/**
 * Sends a large encoded HTTP/1.1 message to the socket in slices, writing the next slice only while the
 * connection is writable. A slow reader then holds about one slice plus the connection's write-buffer
 * high-water mark of outbound data, instead of the whole body: the transport copies a heap body into
 * direct memory as it is written, so without this every slow reader pins a direct copy of its response.
 * <p>
 * It sits on the socket side of {@link HttpServerCodec} and sees encoded bytes, so the bytes on the wire
 * are unchanged and anything written after a paced body - including raw bytes written from the codec's
 * context - queues behind it in order. Only buffers written while the codec is in the pipeline are paced:
 * after a WebSocket upgrade removes it, frames pass straight into the outbound buffer, where the WebSocket
 * relay's writability-based backpressure can see them.
 */
public class PacedLargeWriteHandler extends ChunkedWriteHandler {

    static final int PACE_THRESHOLD_BYTES = 64 * 1024;
    static final int SLICE_BYTES = 32 * 1024;

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf && ((ByteBuf) msg).readableBytes() > PACE_THRESHOLD_BYTES && ctx.pipeline().get(HttpServerCodec.class) != null) {
            super.write(ctx, new ByteBufSlices((ByteBuf) msg, SLICE_BYTES), promise);
        } else {
            super.write(ctx, msg, promise);
        }
    }

    /**
     * Owns the buffer it is given and releases it once, on {@link #close()}; each slice it hands out is a
     * retained slice that the transport releases after writing it.
     */
    static final class ByteBufSlices implements ChunkedInput<ByteBuf> {

        private final ByteBuf buffer;
        private final int sliceBytes;
        private final long length;
        private long progress;
        private boolean closed;

        ByteBufSlices(ByteBuf buffer, int sliceBytes) {
            this.buffer = buffer;
            this.sliceBytes = sliceBytes;
            this.length = buffer.readableBytes();
        }

        @Override
        public boolean isEndOfInput() {
            return closed || !buffer.isReadable();
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                buffer.release();
            }
        }

        @Deprecated
        @Override
        public ByteBuf readChunk(ChannelHandlerContext ctx) {
            return readChunk(ctx.alloc());
        }

        @Override
        public ByteBuf readChunk(ByteBufAllocator allocator) {
            if (isEndOfInput()) {
                return null;
            }
            ByteBuf slice = buffer.readRetainedSlice(Math.min(sliceBytes, buffer.readableBytes()));
            progress += slice.readableBytes();
            return slice;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public long progress() {
            return progress;
        }
    }
}
