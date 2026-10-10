package org.mockserver.socket;

import io.netty.util.internal.PlatformDependent;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class NettyDirectMemoryLimitTest {

    private static final long MIB = 1024L * 1024;

    @Test
    public void shouldDefaultToAQuarterOfTheMaximumHeap() {
        assertThat(NettyDirectMemoryLimit.defaultLimitBytes(256 * MIB), is(64 * MIB));
        assertThat(NettyDirectMemoryLimit.defaultLimitBytes(1024 * MIB), is(256 * MIB));
        assertThat(NettyDirectMemoryLimit.defaultLimitBytes(8192 * MIB), is(2048 * MIB));
    }

    @Test
    public void shouldNotGoBelowTheMinimumOrAboveTheHeap() {
        assertThat(NettyDirectMemoryLimit.defaultLimitBytes(128 * MIB), is(64 * MIB));
        assertThat(NettyDirectMemoryLimit.defaultLimitBytes(48 * MIB), is(48 * MIB));
    }

    @Test
    public void shouldReadTheUserPropertyAndTheNameThisNettyReads() {
        assertThat(NettyDirectMemoryLimit.USER_PROPERTY, is("io.netty.maxDirectMemory"));
        // unshaded here; in the shaded jar this is shaded_package.io.netty.maxDirectMemory (see assert-shaded-direct-memory-property.sh)
        assertThat(NettyDirectMemoryLimit.NETTY_PROPERTY, is("io.netty.maxDirectMemory"));
    }

    @Test
    public void shouldLeaveAnUnboundedHeapAlone() {
        assertThat(NettyDirectMemoryLimit.defaultLimitBytes(Long.MAX_VALUE), is(-1L));
        assertThat(NettyDirectMemoryLimit.defaultLimitBytes(0), is(-1L));
    }

    @Test
    public void shouldBoundNettyWhenAppliedBeforeNettyInitialises() throws Exception {
        // compared with the child's own maximum heap, which some collectors report below -Xmx
        for (String heap : new String[]{"-Xmx256m", "-Xmx1g"}) {
            Probe.Result result = probe(List.of(heap), null);
            long expected = NettyDirectMemoryLimit.defaultLimitBytes(result.maxHeap);
            assertThat(result.applied, is(expected));
            assertThat(result.netty, is(expected));
        }
        assertThat(probe(List.of("-Xmx256m", "-XX:+UseG1GC"), null).netty, is(64 * MIB));
    }

    @Test
    public void shouldKeepAnExplicitMaxDirectMemorySize() throws Exception {
        Probe.Result commandLine = probe(List.of("-Xmx256m", "-XX:MaxDirectMemorySize=100m"), null);
        assertThat(commandLine.applied, is(-1L));
        assertThat(commandLine.netty, is(100 * MIB));
        Probe.Result toolOptions = probe(List.of("-Xmx256m"), "-XX:MaxDirectMemorySize=100m");
        assertThat(toolOptions.applied, is(-1L));
        assertThat(toolOptions.netty, is(100 * MIB));
    }

    @Test
    public void shouldKeepAnExplicitNettyLimit() throws Exception {
        Probe.Result explicit = probe(List.of("-Xmx256m", "-Dio.netty.maxDirectMemory=" + 32 * MIB), null);
        assertThat(explicit.applied, is(-1L));
        assertThat(explicit.netty, is(32 * MIB));
        // -1 asks Netty for its own default, which is the JVM's direct-memory limit: the maximum heap
        Probe.Result nettyDefault = probe(List.of("-Xmx256m", "-Dio.netty.maxDirectMemory=-1"), null);
        assertThat(nettyDefault.applied, is(-1L));
        assertThat(nettyDefault.netty, is(nettyDefault.maxHeap));
    }

    private static Probe.Result probe(List<String> jvmArguments, String javaToolOptions) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        command.addAll(jvmArguments);
        command.addAll(Arrays.asList("-cp", System.getProperty("java.class.path"), Probe.class.getName()));
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        processBuilder.environment().remove("JAVA_TOOL_OPTIONS");
        if (javaToolOptions != null) {
            processBuilder.environment().put("JAVA_TOOL_OPTIONS", javaToolOptions);
        }
        Process process = processBuilder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            in.transferTo(output);
        }
        assertThat(process.waitFor(60, TimeUnit.SECONDS), is(true));
        String[] lines = output.toString(StandardCharsets.UTF_8).trim().split("\\R");
        return Probe.Result.parse(lines[lines.length - 1]);
    }

    public static class Probe {
        public static void main(String[] arguments) {
            long applied = NettyDirectMemoryLimit.applyDefault();
            System.out.println(applied + " " + PlatformDependent.maxDirectMemory() + " " + Runtime.getRuntime().maxMemory());
        }

        static final class Result {
            final long applied;
            final long netty;
            final long maxHeap;

            private Result(long applied, long netty, long maxHeap) {
                this.applied = applied;
                this.netty = netty;
                this.maxHeap = maxHeap;
            }

            static Result parse(String line) {
                String[] fields = line.split(" ");
                return new Result(Long.parseLong(fields[0]), Long.parseLong(fields[1]), Long.parseLong(fields[2]));
            }
        }
    }
}
