package org.mockserver.netty.integration;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.netty.http3.Http3NativeUnavailableException;
import org.mockserver.socket.PortFactory;
import org.mockserver.testing.socket.TestPortFactory;
import org.mockserver.version.Version;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;

/**
 * Boots the real assembly jars in a forked JVM with {@code http3Port} set. The DEFAULT jar carries no
 * QUIC native, which is exactly the state a user who has not opted into HTTP/3 is in, so it must refuse
 * to start and print the fixes without a stack trace; the {@code -http3} classifier must serve HTTP/3.
 */
public class Http3NativeStartupIntegrationTest {

    private static final long TIMEOUT_SECONDS = 60;
    private static final Pattern HTTP3_STARTED = Pattern.compile("HTTP/3 \\(QUIC\\) server started on UDP port:\\s*(\\d+)");
    private static final Pattern HTTP3_OUTCOME = Pattern.compile(HTTP3_STARTED.pattern() + "|HTTP/3 disabled");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void onlyWhereNettyPublishesTheNative() {
        Assume.assumeTrue("no QUIC native is published for " + platformClassifier(), Http3NativeUnavailableException.SUPPORTED_PLATFORMS.contains(platformClassifier()));
    }

    @Test
    public void shouldRefuseToStartAndNameEveryFixWhenTheNativeIsMissing() throws Exception {
        File output = temporaryFolder.newFile("default-jar.log");
        Process process = start(assemblyJar("jar-with-dependencies"), output);
        try {
            String log = awaitExit(process, output);
            assertThat(log, containsString("the QUIC native library for " + platformClassifier() + " is not available, so MockServer cannot start"));
            String nettyVersion = Version.getNettyVersion();
            assertThat(log, containsString("mockserver/mockserver:" + expectedImageTag()));
            assertThat(log, containsString("--set image.variant=http3"));
            assertThat(log, containsString("mockserver-netty-" + Version.getVersion() + "-jar-with-dependencies-http3.jar"));
            assertThat(log, containsString("io.netty:netty-codec-native-quic:" + nettyVersion + ":" + platformClassifier()));
            assertThat(log, containsString("remove http3Port"));
            assertNoStackFramesButOneCauseLine(log);
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    public void shouldNotSendAnHttp3JarUserElsewhereWhenItsNativeFailsToLoad() throws Exception {
        // a work directory Netty cannot create files in stops the bundled native being extracted and loaded
        File notADirectory = temporaryFolder.newFile("not-a-directory");
        File output = temporaryFolder.newFile("http3-jar-unloadable.log");
        Process process = start(assemblyJar("jar-with-dependencies-http3"), output, "-Dio.netty.native.workdir=" + notADirectory.getAbsolutePath());
        try {
            String log = awaitExit(process, output);
            assertThat(log, containsString("the QUIC native library for " + platformClassifier() + " failed to load"));
            assertThat(log, containsString("The native library is present but failed to load, so a different image or jar will not help"));
            assertThat(log, containsString("Found: classpath META-INF/native/libnetty_quiche"));
            assertThat(log, not(containsString("jar-with-dependencies-http3.jar instead")));
            assertNoStackFramesButOneCauseLine(log);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String awaitExit(Process process, File output) throws Exception {
        assertThat("the server must exit rather than start without the HTTP/3 port it was given",
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
        String log = read(output);
        assertThat(log, process.exitValue(), is(not(0)));
        return log;
    }

    private static void assertNoStackFramesButOneCauseLine(String log) {
        assertThat("the fix must not be buried in a stack trace", log, not(containsString("\tat ")));
        assertThat(log.split("underlying error: ", -1).length, is(2));
    }

    @Test
    public void shouldServeHttp3FromTheHttp3Classifier() throws Exception {
        File output = temporaryFolder.newFile("http3-jar.log");
        File jar = assemblyJar("jar-with-dependencies-http3");
        Process process = startWithHttp3(udpPort -> startAndAwaitHttp3Outcome(jar, output, udpPort), started -> http3PortStartedIn(output), Http3NativeStartupIntegrationTest::stop);
        try {
            assertThat(read(output), containsString("HTTP/3 (QUIC) server started on UDP port"));
        } finally {
            stop(process);
        }
    }

    // returns once the server has logged whether HTTP/3 started; a server that says neither fails the test
    private static Process startAndAwaitHttp3Outcome(File jar, File output, int udpPort) {
        Process process = null;
        String log = "";
        try {
            process = start(jar, output, udpPort);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline && process.isAlive() && !HTTP3_OUTCOME.matcher(log = read(output)).find()) {
                Thread.sleep(250);
            }
            if (HTTP3_OUTCOME.matcher(log = read(output)).find()) {
                return process;
            }
        } catch (IOException | InterruptedException e) {
            log = e + "\n" + log;
        }
        if (process != null) {
            stop(process);
        }
        throw new AssertionError("the server did not report whether HTTP/3 started on UDP port " + udpPort + ":\n" + log);
    }

    private static int http3PortStartedIn(File output) {
        try {
            Matcher started = HTTP3_STARTED.matcher(read(output));
            return started.find() ? Integer.parseInt(started.group(1)) : -1;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void stop(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static Process start(File jar, File output, String... jvmOptions) throws IOException {
        return start(jar, output, freeUdpPort(), jvmOptions);
    }

    private static Process start(File jar, File output, int udpPort, String... jvmOptions) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin" + File.separator + "java").getAbsolutePath());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dmockserver.http3Port=" + udpPort);
        command.addAll(Arrays.asList(jvmOptions));
        command.addAll(Arrays.asList("-jar", jar.getAbsolutePath(), "-serverPort", Integer.toString(freeTcpPort())));
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start();
    }

    private static File assemblyJar(String classifier) {
        File target = new File(System.getProperty("project.basedir", System.getProperty("user.dir", ".")), "target");
        File jar = new File(target, "mockserver-netty-" + Version.getVersion() + "-" + classifier + ".jar");
        if (!jar.isFile()) {
            throw new IllegalStateException("could not find " + jar.getAbsolutePath() + " - was the assembly built (package phase)?");
        }
        return jar;
    }

    private static String expectedImageTag() {
        return Version.getVersion().endsWith("-SNAPSHOT") ? "snapshot-http3" : Version.getVersion() + "-http3";
    }

    private static String platformClassifier() {
        return io.netty.util.internal.PlatformDependent.normalizedOs() + "-" + io.netty.util.internal.PlatformDependent.normalizedArch();
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static int freeTcpPort() {
        return PortFactory.findFreePort();
    }

    private static int freeUdpPort() {
        return TestPortFactory.findFreeUdpPort();
    }
}
