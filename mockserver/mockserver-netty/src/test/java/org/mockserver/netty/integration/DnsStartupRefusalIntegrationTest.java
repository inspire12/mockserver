package org.mockserver.netty.integration;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.version.Version;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Boots the real assembly jar in a forked JVM with DNS mocking on a UDP port another socket holds: the process
 * must exit with a failure status and one line naming the port and the cause, on the transport the platform
 * defaults to (epoll on Linux, NIO elsewhere).
 */
public class DnsStartupRefusalIntegrationTest {

    private static final long TIMEOUT_SECONDS = 60;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldExitWithAFailureStatusAndNameThePortWhenTheDnsPortCannotBeBound() throws Exception {
        File output = temporaryFolder.newFile("dns-port-held.log");
        try (DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET)) {
            otherApplication.bind(new InetSocketAddress("127.0.0.1", 0));
            int udpPort = ((InetSocketAddress) otherApplication.getLocalAddress()).getPort();
            Process process = new ProcessBuilder(Arrays.asList(
                new File(System.getProperty("java.home"), "bin" + File.separator + "java").getAbsolutePath(),
                "-Dfile.encoding=UTF-8",
                "-Dmockserver.dnsEnabled=true",
                "-Dmockserver.dnsPort=" + udpPort,
                "-jar", assemblyJar().getAbsolutePath(), "-serverPort", "0"
            )).redirectErrorStream(true).redirectOutput(output).start();
            try {
                assertThat("the server must exit rather than start without the DNS port it was given",
                    process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
                String log = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
                assertThat(log, process.exitValue(), is(1));
                assertThat(log, containsString("DNS mocking is enabled (dnsEnabled=true, dnsPort=" + udpPort + ") but UDP port " + udpPort + " could not be opened or bound, so MockServer cannot start:"
                    + " free the port if another application holds it, choose a different dnsPort (0 picks a free port, and a port below 1024 can need extra privileges),"
                    + " or set dnsEnabled=false to run without DNS mocking (underlying error: "));
                assertThat(log, containsString("Address already in use)"));
                assertThat(log, not(containsString("DNS mock server started")));
                assertThat("the fix must not be buried in a stack trace", log, not(containsString("\tat ")));
                assertThat("one cause line", log.split("underlying error: ", -1).length, is(2));
            } finally {
                process.destroyForcibly();
            }
        }
    }

    private static File assemblyJar() {
        File target = new File(System.getProperty("project.basedir", System.getProperty("user.dir", ".")), "target");
        File jar = new File(target, "mockserver-netty-" + Version.getVersion() + "-jar-with-dependencies.jar");
        if (!jar.isFile()) {
            throw new IllegalStateException("could not find " + jar.getAbsolutePath() + " - was the assembly built (package phase)?");
        }
        return jar;
    }
}
