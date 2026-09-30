package org.mockserver.codec;

import io.netty.handler.codec.MessageAggregator;
import io.netty.handler.codec.http.HttpObjectAggregator;

/**
 * Creates the HTTP aggregators MockServer puts in its pipelines.
 */
public final class HttpObjectAggregators {

    static final int MIN_COMPONENTS = 1024;
    static final int AVERAGE_CHUNK_BYTES = 1024;

    private HttpObjectAggregators() {
        // utility class
    }

    /**
     * An {@link HttpObjectAggregator} with a component limit sized to its maximum content length.
     *
     * @see #limitComponents(MessageAggregator)
     */
    public static HttpObjectAggregator httpObjectAggregator(int maxContentLength) {
        return limitComponents(new HttpObjectAggregator(maxContentLength));
    }

    /**
     * Past its component limit an aggregator copies its body so far into one new direct buffer, briefly
     * needing twice the body's size; Netty's default limit (1,024) makes that happen to any large body. Each
     * component also costs about 110 bytes of heap, so the limit cannot be unbounded either: a client could
     * send one-byte chunks. This sizes it to hold a full body of chunks averaging 1 KiB without copying,
     * never below Netty's default. Must be called before the aggregator is added to a pipeline.
     */
    public static <T extends MessageAggregator<?, ?, ?, ?>> T limitComponents(T aggregator) {
        aggregator.setMaxCumulationBufferComponents(componentLimit(aggregator.maxContentLength()));
        return aggregator;
    }

    public static int componentLimit(int maxContentLength) {
        return Math.max(MIN_COMPONENTS, maxContentLength / AVERAGE_CHUNK_BYTES);
    }
}
