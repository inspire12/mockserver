package org.mockserver.netty.unification;

import io.netty.buffer.PooledByteBufAllocator;
import org.junit.Assume;
import org.junit.Test;
import org.mockserver.codec.HttpObjectAggregators;
import org.mockserver.netty.unification.Http2ConnectionMemoryHarness.Frame;
import org.mockserver.netty.unification.Http2ConnectionMemoryHarness.Result;
import org.mockserver.netty.unification.Http2ConnectionMemoryHarness.SampledPooledAllocator;
import org.mockserver.netty.unification.Http2ConnectionMemoryHarness.Variant;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.fair;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.tracked;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.mix;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.eachInItsOwnRead;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.oneStream;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.pinning;

/**
 * On demand: what each stream aggregator variant holds and copies for uploads through the real HTTP/2 connection
 * codec, printed as a table. Its name keeps it out of surefire's includes; run it with
 * {@code -Dtest=Http2StreamPeakHeldMeasurement -Dmockserver.h2PeakMeasurement=true} and Netty's leak detector off
 * ({@code -Dmockserver.testArgLine=-Dio.netty.leakDetection.level=disabled}), which otherwise records every retain of
 * every frame.
 * {@code -Dmockserver.h2PeakMeasurement.only=<text>} runs the scenarios whose names contain it, and
 * {@code -Dmockserver.h2PeakMeasurement.pooled=true} adds a sampled run on the pooled allocator. Not a verification:
 * {@link Http2StreamPeakHeldTest} pins the bound.
 */
public class Http2StreamPeakHeldMeasurement {

    private static final int KIB = 1024;
    private static final int MIB = 1024 * 1024;

    @Test(timeout = 3_600_000)
    public void measure() {
        Assume.assumeTrue("on demand", Boolean.getBoolean("mockserver.h2PeakMeasurement"));
        String only = System.getProperty("mockserver.h2PeakMeasurement.only", "");
        int limit = HttpObjectAggregators.streamComponentLimit(10 * MIB);
        Map<String, Supplier<List<Frame>>> scenarios = new LinkedHashMap<>();
        Map<String, Integer> streams = new LinkedHashMap<>();
        // no copying expected: what the codec alone holds
        add(scenarios, streams, "1 stream, 16 KiB frames, 4 MiB", () -> oneStream(4 * MIB, 16 * KIB), 1);
        add(scenarios, streams, "1 stream, 16 KiB frames cut by the window, 4 MiB", () -> oneStream(4 * MIB, 16 * KIB, 16 * KIB, 16 * KIB, 16 * KIB - 1), 1);
        add(scenarios, streams, "1 stream, 8 KiB frames, 4 MiB", () -> oneStream(4 * MIB, 8 * KIB), 1);
        // one stream of tiny frames
        add(scenarios, streams, "1 stream, 1 B frames, 1 MiB", () -> oneStream(MIB, 1), 1);
        add(scenarios, streams, "1 stream, 100 B frames, 1 MiB", () -> oneStream(MIB, 100), 1);
        add(scenarios, streams, "1 stream, 1,023 B frames, 1 MiB", () -> oneStream(MIB, 1023), 1);
        // interleaved streams of tiny frames, sharing reads
        add(scenarios, streams, "4 streams, 1 B frames, 256 KiB each", () -> fair(new int[]{256 * KIB, 256 * KIB, 256 * KIB, 256 * KIB}, new int[][]{{1}, {1}, {1}, {1}}), 4);
        add(scenarios, streams, "4 streams, 100 B frames, 1 MiB each", () -> fair(new int[]{MIB, MIB, MIB, MIB}, new int[][]{{100}, {100}, {100}, {100}}), 4);
        add(scenarios, streams, "4 streams, 1,023 B frames, 1 MiB each", () -> fair(new int[]{MIB, MIB, MIB, MIB}, new int[][]{{1023}, {1023}, {1023}, {1023}}), 4);
        // mixed sizes on one stream (#53's HTTP/1.1 counter-examples)
        add(scenarios, streams, "1 stream, 64x1,023+1 KiB then 4x(15x1+1 KiB), 1 MiB",
            () -> oneStream(MIB, mix(64, 1023, 1, 1024, 15, 1, 1, 1024, 15, 1, 1, 1024, 15, 1, 1, 1024, 15, 1, 1, 1024)), 1);
        add(scenarios, streams, "1 stream, 16x1,023+1 KiB+15x1+1 KiB, 1 MiB", () -> oneStream(MIB, mix(16, 1023, 1, 1024, 15, 1, 1, 1024)), 1);
        add(scenarios, streams, "1 stream, 31x100+1 KiB, 1 MiB", () -> oneStream(MIB, mix(31, 100, 1, 1024)), 1);
        add(scenarios, streams, "1 stream, 31x1+16 KiB, 1 MiB", () -> oneStream(MIB, mix(31, 1, 1, 16 * KIB)), 1);
        add(scenarios, streams, "1 stream, random 1 B-16 KiB mix, 1 MiB", () -> oneStream(MIB, randomMix(new Random(62), 4_000)), 1);
        // a large-frame stream interleaved with tiny-frame streams
        add(scenarios, streams, "16 KiB-frame stream + 1 B, 100 B, 1,023 B streams, 1 MiB each",
            () -> fair(new int[]{MIB, MIB, MIB, MIB}, new int[][]{{16 * KIB}, {1}, {100}, {1023}}), 4);
        add(scenarios, streams, "16 KiB-frame stream + 3 streams of 16x1,023+1 KiB, 1 MiB each",
            () -> fair(new int[]{MIB, MIB, MIB, MIB}, new int[][]{{16 * KIB}, mix(16, 1023, 1, 1024), mix(16, 1023, 1, 1024), mix(16, 1023, 1, 1024)}), 4);
        // one stream's held frames pinning reads that other, completed, requests filled
        for (int piece : new int[]{1, 100, 1023, 1024, 16 * KIB}) {
            // past the stream limit, but within the 10 MiB body limit
            int cycles = Math.min(1_100, 10 * MIB / piece - 1);
            add(scenarios, streams, "pinning: " + piece + " B frame per 32 KiB read, " + cycles + " reads", () -> pinning(cycles, piece), cycles + 1);
        }
        // a 1 KiB frame after every 15 tiny ones breaks the blocks' runs
        add(scenarios, streams, "pinning: 15x1 B+1 KiB frames per 32 KiB read, 1100 reads", () -> pinning(1_100, mix(15, 1, 1, 1024)), 1_101);
        add(scenarios, streams, "pinning: 20 streams x 60 1 B frames, each in its own read",
            () -> eachInItsOwnRead(fair(filled(20, 60), filledPatterns(20, 1))), 20);
        // the stream component limit at the 10 MiB default
        add(scenarios, streams, "1 stream, 1 B frames, 10 MiB", () -> oneStream(10 * MIB, 1), 1);
        add(scenarios, streams, "1 stream, 2L+1 x 1 B then 1,000 B, 10 MiB",
            () -> oneStream(10 * MIB, concat(mix(2 * limit + 1, 1), repeat(1000, 20_000))), 1);
        add(scenarios, streams, "16 KiB-frame stream + 1 B stream, 10 MiB each",
            () -> fair(new int[]{10 * MIB, 10 * MIB}, new int[][]{{16 * KIB}, {1}}), 2);

        int[] readSizes = {64, KIB, 16 * KIB, 64 * KIB};
        StringBuilder table = new StringBuilder();
        table.append(String.format("%-62s %6s %-10s %10s %10s %11s %8s %9s %9s %10s%n",
            "scenario", "read", "variant", "body", "wire", "peak held", "peak/body", "peak/wire", "copied/body", "conn alloc"));
        for (Map.Entry<String, Supplier<List<Frame>>> scenario : scenarios.entrySet()) {
            if (!only.isEmpty() && !scenario.getKey().contains(only)) {
                continue;
            }
            boolean large = scenario.getKey().contains("10 MiB") || scenario.getKey().startsWith("pinning");
            List<Frame> frames = scenario.getValue().get();
            for (int readSize : large ? new int[]{16 * KIB, 64 * KIB} : readSizes) {
                for (Variant variant : new Variant[]{Variant.BLOCKS, Variant.MERGE_ONLY, Variant.NETTY}) {
                    Result result = tracked(variant, readSize, frames, streams.get(scenario.getKey()));
                    String row = String.format("%-62s %6d %-10s %10d %10d %11d %8.2f %9.2f %9.2f %10d%n",
                        scenario.getKey(), readSize, variant, result.bodyBytes, result.wireBytes, result.peakHeldBytes,
                        result.peakTimesBody(), (double) result.peakHeldBytes / result.wireBytes, result.copiedTimesBody(), result.connectionAllocatedBytes);
                    table.append(row);
                    System.out.print(row);
                }
            }
        }
        if (Boolean.getBoolean("mockserver.h2PeakMeasurement.pooled")) {
            table.append(String.format("%npooled allocator, sampled after every allocation and read%n"));
            table.append(String.format("%-62s %6s %-10s %10s %10s %11s %8s %9s%n",
                "scenario", "read", "variant", "body", "wire", "peak held", "peak/body", "peak/wire"));
            for (Map.Entry<String, Supplier<List<Frame>>> scenario : scenarios.entrySet()) {
                if (scenario.getKey().contains("10 MiB") || (!only.isEmpty() && !scenario.getKey().contains(only))) {
                    continue;
                }
                List<Frame> frames = scenario.getValue().get();
                for (Variant variant : new Variant[]{Variant.BLOCKS, Variant.MERGE_ONLY, Variant.NETTY}) {
                    Result result = pooled(variant, 64 * KIB, frames, streams.get(scenario.getKey()));
                    String row = String.format("%-62s %6d %-10s %10d %10d %11d %8.2f %9.2f%n",
                        scenario.getKey(), 64 * KIB, variant, result.bodyBytes, result.wireBytes, result.peakHeldBytes,
                        result.peakTimesBody(), (double) result.peakHeldBytes / result.wireBytes);
                    table.append(row);
                    System.out.print(row);
                }
            }
        }
        System.out.println();
        System.out.println(table);
    }

    static Result pooled(Variant variant, int readSize, List<Frame> frames, int streams) {
        SampledPooledAllocator allocator = new SampledPooledAllocator(PooledByteBufAllocator.DEFAULT);
        return new Http2ConnectionMemoryHarness(configuration(), variant, readSize, allocator, allocator, allocator, allocator::held)
            .upload(frames, streams);
    }

    private static void add(Map<String, Supplier<List<Frame>>> scenarios, Map<String, Integer> streams, String name, Supplier<List<Frame>> frames, int count) {
        scenarios.put(name, frames);
        streams.put(name, count);
    }

    private static int[] randomMix(Random random, int pieces) {
        int[] sizes = new int[pieces];
        for (int i = 0; i < pieces; i++) {
            int kind = random.nextInt(4);
            sizes[i] = kind == 0 ? 1 + random.nextInt(4) : kind == 1 ? 5 + random.nextInt(200) : kind == 2 ? 500 + random.nextInt(1_500) : 16 * KIB;
        }
        return sizes;
    }

    private static int[] filled(int count, int value) {
        int[] values = new int[count];
        Arrays.fill(values, value);
        return values;
    }

    private static int[][] filledPatterns(int count, int pieceBytes) {
        int[][] patterns = new int[count][];
        Arrays.fill(patterns, new int[]{pieceBytes});
        return patterns;
    }

    private static int[] repeat(int size, int count) {
        return mix(count, size);
    }

    private static int[] concat(int[] first, int[] second) {
        int[] all = new int[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
