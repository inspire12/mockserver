package org.mockserver.cli;

import io.netty.util.internal.PlatformDependent;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The CLI entry point must bound Netty's direct memory before anything initialises Netty, because Netty
 * reads the limit only once. Runs in a fresh JVM, since this one initialised Netty long ago.
 */
public class MainDirectMemoryLimitTest {

    @Test
    public void shouldBoundNettyDirectMemoryBeforeNettyInitialises() throws Exception {
        List<String> command = Arrays.asList(
            System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
            "-Xmx512m",
            "-cp", System.getProperty("java.class.path"),
            Probe.class.getName()
        );
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        processBuilder.environment().remove("JAVA_TOOL_OPTIONS");
        Process process = processBuilder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            in.transferTo(output);
        }
        assertThat(process.waitFor(60, TimeUnit.SECONDS), is(true));
        String[] lines = output.toString(StandardCharsets.UTF_8).trim().split("\\R");

        String[] fields = lines[lines.length - 1].split(" ");
        long maxHeap = Long.parseLong(fields[1]);
        // compared with the child's own maximum heap, which some collectors report below -Xmx
        assertThat(Long.parseLong(fields[0]), is(Math.min(maxHeap, Math.max(64L * 1024 * 1024, maxHeap / 4))));
    }

    public static class Probe {
        public static void main(String[] arguments) throws Exception {
            Class.forName("org.mockserver.cli.Main");
            System.out.println(PlatformDependent.maxDirectMemory() + " " + Runtime.getRuntime().maxMemory());
        }
    }
}
