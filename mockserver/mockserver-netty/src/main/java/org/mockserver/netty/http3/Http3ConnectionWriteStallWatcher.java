package org.mockserver.netty.http3;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
import org.mockserver.netty.connection.WriteStallTimeoutHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongSupplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Times the request streams of one QUIC connection that have writes waiting, and resets those whose client has taken
 * none of their data for {@code responseWriteStallTimeoutMillis}.
 * <p>
 * Streams share the connection's credit ({@code MAX_DATA}), so a stream the client does not read can hold all of it
 * and leave a stream the client would read with nothing to send. A stream sent under {@code holderBytes} cannot be
 * the one holding it, so it gets a fresh period when a stalled stream sent that much is reset, and when it stalls
 * waits one period, once, for such a stream that is still moving. That holds only while the waiting streams sent
 * under {@code holderBytes} have together been sent under it: otherwise they could hold the credit between them, and
 * every stream is reset on its own clock, as a stream sent {@code holderBytes} always is.
 */
final class Http3ConnectionWriteStallWatcher implements Runnable {

    /**
     * One request stream's writes. Not thread-safe: used only from the connection's event loop.
     */
    static final class Stream {
        private final Runnable reset;
        // sizes of the writes QUIC has not taken all of, oldest first
        private final ArrayDeque<Integer> waiting = new ArrayDeque<>();
        private long takenBytes;
        private long movedNanos;
        private long sparedNanos;
        private boolean spared;
        // has had its one period of waiting for a moving stream since its own data last moved or a holder was reset
        private boolean waited;
        private boolean watched;

        private Stream(Runnable reset) {
            this.reset = reset;
        }

        boolean isWaiting() {
            return !waiting.isEmpty();
        }

        private boolean mayHoldCredit(long holderBytes) {
            return takenBytes >= holderBytes;
        }

        private boolean movedWithin(long nowNanos, long timeoutNanos) {
            return nowNanos - movedNanos < timeoutNanos;
        }

        private boolean stalled(long nowNanos, long timeoutNanos) {
            return !movedWithin(nowNanos, timeoutNanos) && !(spared && nowNanos - sparedNanos < timeoutNanos);
        }

        private void spare(long nowNanos, boolean waiting) {
            spared = true;
            sparedNanos = nowNanos;
            waited = waiting;
        }
    }

    private final long timeoutMillis;
    private final long timeoutNanos;
    private final long checkIntervalMillis;
    private final EventExecutor executor;
    private final LongSupplier holderBytes;
    private final LongSupplier nanoTime;
    private final List<Stream> watched = new ArrayList<>();
    private ScheduledFuture<?> check;

    /**
     * @param holderBytes the bytes a stream must have been sent before it could be what holds the connection's credit,
     *                    read only when a stream has stalled; 0 resets every stalled stream on its own clock
     */
    Http3ConnectionWriteStallWatcher(long timeoutMillis, EventExecutor executor, LongSupplier holderBytes) {
        this(timeoutMillis, executor, holderBytes, System::nanoTime);
    }

    Http3ConnectionWriteStallWatcher(long timeoutMillis, EventExecutor executor, LongSupplier holderBytes, LongSupplier nanoTime) {
        this.timeoutMillis = timeoutMillis;
        this.timeoutNanos = MILLISECONDS.toNanos(timeoutMillis);
        this.checkIntervalMillis = WriteStallTimeoutHandler.checkIntervalMillis(timeoutMillis);
        this.executor = executor;
        this.holderBytes = holderBytes;
        this.nanoTime = nanoTime;
    }

    /**
     * The timeout every stream of the connection is timed by: the one its first stream was given.
     */
    long timeoutMillis() {
        return timeoutMillis;
    }

    /**
     * @param reset resets the stream; run at most once per stall, from the connection's event loop
     */
    Stream stream(Runnable reset) {
        return new Stream(reset);
    }

    /**
     * Call before passing a write on: QUIC may take it, and so complete it, at once.
     */
    void writing(Stream stream, int bytes) {
        if (stream.waiting.isEmpty()) {
            stream.movedNanos = nanoTime.getAsLong();
        }
        stream.waiting.addLast(bytes);
    }

    void written(Stream stream, boolean taken) {
        Integer bytes = stream.waiting.pollFirst();
        if (taken && bytes != null) {
            stream.takenBytes += bytes;
            stream.movedNanos = nanoTime.getAsLong();
            stream.waited = false;
        }
    }

    /**
     * Call after passing a write on: a stream is timed only while QUIC has not taken all of its writes.
     */
    void watch(Stream stream) {
        if (stream.isWaiting() && !stream.watched) {
            stream.watched = true;
            watched.add(stream);
            if (check == null) {
                check = executor.schedule(this, checkIntervalMillis, MILLISECONDS);
            }
        }
    }

    void closed(Stream stream) {
        stream.waiting.clear();
    }

    @Override
    public void run() {
        check = null;
        check();
        if (!watched.isEmpty()) {
            check = executor.schedule(this, checkIntervalMillis, MILLISECONDS);
        }
    }

    void check() {
        long nowNanos = nanoTime.getAsLong();
        boolean anyOverdue = false;
        for (Iterator<Stream> streams = watched.iterator(); streams.hasNext(); ) {
            Stream stream = streams.next();
            if (!stream.isWaiting()) {
                stream.watched = false;
                streams.remove();
            } else if (!stream.movedWithin(nowNanos, timeoutNanos)) {
                anyOverdue = true;
            }
        }
        if (!anyOverdue) {
            return;
        }
        long holder = holderBytes.getAsLong();
        boolean holderWaiting = false;
        boolean holderStalled = false;
        long sentToTheRest = 0;
        for (Stream stream : watched) {
            if (stream.mayHoldCredit(holder)) {
                holderWaiting = true;
                holderStalled |= !stream.movedWithin(nowNanos, timeoutNanos);
            } else {
                sentToTheRest += stream.takenBytes;
            }
        }
        // sent that much between them, the rest could hold the credit themselves: every stream on its own clock
        boolean restMayHoldCredit = sentToTheRest >= holder;
        List<Stream> resets = new ArrayList<>();
        for (Iterator<Stream> streams = watched.iterator(); streams.hasNext(); ) {
            Stream stream = streams.next();
            boolean ownClock = restMayHoldCredit || stream.mayHoldCredit(holder);
            if (!ownClock && holderStalled) {
                // a stream that may hold the credit is reset in this check: time for what it returns to arrive
                stream.spare(nowNanos, false);
            } else if (ownClock ? !stream.movedWithin(nowNanos, timeoutNanos) : stream.stalled(nowNanos, timeoutNanos)) {
                if (!ownClock && holderWaiting && !stream.waited) {
                    stream.spare(nowNanos, true);
                } else {
                    stream.watched = false;
                    streams.remove();
                    resets.add(stream);
                }
            }
        }
        for (Stream stream : resets) {
            stream.reset.run();
        }
    }
}
