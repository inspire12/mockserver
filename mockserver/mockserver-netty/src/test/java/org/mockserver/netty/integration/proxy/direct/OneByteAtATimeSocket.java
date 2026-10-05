package org.mockserver.netty.integration.proxy.direct;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

/**
 * Sends the first bytes written to it one at a time, each in a packet of its own, as a very slow client would,
 * and records how far apart they were written. MockServer gives up on bytes it cannot yet place one second after
 * the last of them, so a test whose client was kept from writing for that long has shown nothing: it asks
 * {@link #longestGapMillis()} and makes the attempt again.
 */
class OneByteAtATimeSocket extends Socket {

    private static final int BYTES_SENT_ONE_AT_A_TIME = 8;

    private OutputStream output;
    private int sentOneAtATime;
    private boolean restFollowed;
    private long previousWriteStarted = -1;
    private long longestGapNanos;

    OneByteAtATimeSocket(String host, int port) throws IOException {
        this(host, port, true);
    }

    /** @param fromTheFirstByte false to send everything whole until {@link #oneAtATimeFromNow()} */
    OneByteAtATimeSocket(String host, int port, boolean fromTheFirstByte) throws IOException {
        super(host, port);
        setTcpNoDelay(true);
        if (!fromTheFirstByte) {
            sentOneAtATime = BYTES_SENT_ONE_AT_A_TIME;
            restFollowed = true;
        }
    }

    /** The longest time from starting to write one of the bytes sent one at a time to having written the next. */
    synchronized long longestGapMillis() {
        return TimeUnit.NANOSECONDS.toMillis(longestGapNanos);
    }

    /** What is written from now on starts again with bytes sent one at a time. */
    synchronized void oneAtATimeFromNow() {
        sentOneAtATime = 0;
        restFollowed = false;
        previousWriteStarted = -1;
    }

    private synchronized void wroteSince(long started) {
        if (previousWriteStarted >= 0) {
            longestGapNanos = Math.max(longestGapNanos, System.nanoTime() - previousWriteStarted);
        }
        previousWriteStarted = started;
    }

    private synchronized boolean nextGoesAlone() {
        return sentOneAtATime < BYTES_SENT_ONE_AT_A_TIME;
    }

    private synchronized void wentAlone() {
        sentOneAtATime++;
    }

    private synchronized boolean firstOfTheRest() {
        boolean first = !restFollowed;
        restFollowed = true;
        return first;
    }

    @Override
    public synchronized OutputStream getOutputStream() throws IOException {
        if (output == null) {
            output = new FilterOutputStream(super.getOutputStream()) {
                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    int position = offset;
                    while (position < offset + length && nextGoesAlone()) {
                        long started = System.nanoTime();
                        out.write(bytes[position++]);
                        out.flush();
                        wroteSince(started);
                        wentAlone();
                        try {
                            TimeUnit.MILLISECONDS.sleep(30);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                        }
                    }
                    if (position < offset + length) {
                        long started = System.nanoTime();
                        out.write(bytes, position, offset + length - position);
                        out.flush();
                        if (firstOfTheRest()) {
                            wroteSince(started);
                        }
                    }
                }

                @Override
                public void write(int b) throws IOException {
                    write(new byte[]{(byte) b}, 0, 1);
                }
            };
        }
        return output;
    }
}
