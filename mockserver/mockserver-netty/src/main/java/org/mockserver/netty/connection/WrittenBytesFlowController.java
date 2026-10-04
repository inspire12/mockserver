package org.mockserver.netty.connection;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2RemoteFlowController;
import io.netty.handler.codec.http2.Http2Stream;

/**
 * Reports the flow-controlled bytes (DATA payload and padding) its delegate writes for each stream. They are read off
 * the queued frame as it is written, so no frame a client sends can change the count.
 */
final class WrittenBytesFlowController implements Http2RemoteFlowController {

    interface Observer {
        void written(Http2Stream stream, int bytes);
    }

    private final Http2RemoteFlowController delegate;
    private final Observer observer;

    WrittenBytesFlowController(Http2RemoteFlowController delegate, Observer observer) {
        this.delegate = delegate;
        this.observer = observer;
    }

    @Override
    public void addFlowControlled(Http2Stream stream, FlowControlled payload) {
        delegate.addFlowControlled(stream, new Counted(stream, payload));
    }

    private final class Counted implements FlowControlled {
        private final Http2Stream stream;
        private final FlowControlled payload;

        private Counted(Http2Stream stream, FlowControlled payload) {
            this.stream = stream;
            this.payload = payload;
        }

        @Override
        public void write(ChannelHandlerContext ctx, int allowedBytes) {
            int sizeBefore = payload.size();
            try {
                payload.write(ctx, allowedBytes);
            } finally {
                int written = sizeBefore - payload.size();
                if (written > 0) {
                    observer.written(stream, written);
                }
            }
        }

        // the delegate's frames merge only with their own kind, so one is unwrapped for the other
        @Override
        public boolean merge(ChannelHandlerContext ctx, FlowControlled next) {
            return next instanceof Counted && payload.merge(ctx, ((Counted) next).payload);
        }

        @Override
        public int size() {
            return payload.size();
        }

        @Override
        public void error(ChannelHandlerContext ctx, Throwable cause) {
            payload.error(ctx, cause);
        }

        @Override
        public void writeComplete() {
            payload.writeComplete();
        }
    }

    @Override
    public ChannelHandlerContext channelHandlerContext() {
        return delegate.channelHandlerContext();
    }

    @Override
    public void channelHandlerContext(ChannelHandlerContext ctx) throws Http2Exception {
        delegate.channelHandlerContext(ctx);
    }

    @Override
    public void initialWindowSize(int newWindowSize) throws Http2Exception {
        delegate.initialWindowSize(newWindowSize);
    }

    @Override
    public int initialWindowSize() {
        return delegate.initialWindowSize();
    }

    @Override
    public int windowSize(Http2Stream stream) {
        return delegate.windowSize(stream);
    }

    @Override
    public void incrementWindowSize(Http2Stream stream, int delta) throws Http2Exception {
        delegate.incrementWindowSize(stream, delta);
    }

    @Override
    public boolean hasFlowControlled(Http2Stream stream) {
        return delegate.hasFlowControlled(stream);
    }

    @Override
    public void writePendingBytes() throws Http2Exception {
        delegate.writePendingBytes();
    }

    @Override
    public void listener(Listener listener) {
        delegate.listener(listener);
    }

    @Override
    public boolean isWritable(Http2Stream stream) {
        return delegate.isWritable(stream);
    }

    @Override
    public void channelWritabilityChanged() throws Http2Exception {
        delegate.channelWritabilityChanged();
    }

    @Override
    public void updateDependencyTree(int childStreamId, int parentStreamId, short weight, boolean exclusive) {
        delegate.updateDependencyTree(childStreamId, parentStreamId, weight, exclusive);
    }
}
