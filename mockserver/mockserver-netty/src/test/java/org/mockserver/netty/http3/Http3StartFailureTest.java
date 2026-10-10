package org.mockserver.netty.http3;

import io.netty.buffer.ByteBufAllocator;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.lifecycle.Ipv4UdpPortProbe;
import org.mockserver.lifecycle.LeftBehind;
import org.mockserver.lifecycle.RefusedStart.RecordingMockServer;
import org.mockserver.lifecycle.RefusedStart.Started;
import org.mockserver.netty.MockServer;
import org.mockserver.test.FailOnLeakResourceLeakDetector;
import org.mockserver.testing.socket.TestPortFactory;

import java.io.File;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.lifecycle.RefusedStart.assertStopped;
import static org.mockserver.lifecycle.RefusedStart.refusedStart;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;

/**
 * A server that cannot serve HTTP/3 on the {@code http3Port} it was given must refuse to start, as it does
 * for a TCP port it cannot bind, and leave nothing running: the caller gets no reference it could stop.
 */
public class Http3StartFailureTest {

    // well short of Netty's default quiet period of 2 s
    private static final long AT_THE_THROW_MILLIS = 500;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void onlyWhereTheQuicNativeLoads() {
        Assume.assumeTrue("native QUIC not available on this platform", Http3Server.isQuicAvailable());
    }

    // held on the IPv4 wildcard: MockServer refuses such a port on macOS, and the bind itself fails elsewhere
    private static DatagramChannel heldUdpPort() throws Exception {
        DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET);
        otherApplication.bind(new InetSocketAddress("0.0.0.0", 0));
        return otherApplication;
    }

    // held by a dual-stack socket, which fails the server's own bind on every platform as a held port does on Linux
    private static DatagramChannel heldUdpPortOnBothStacks() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            DatagramChannel otherApplication = DatagramChannel.open();
            try {
                otherApplication.bind(new InetSocketAddress(TestPortFactory.findFreeUdpPort()));
                return otherApplication;
            } catch (BindException heldOnIpv6) {
                otherApplication.close();
            }
        }
        throw new IllegalStateException("no UDP port free for a dual-stack bind in 20 attempts");
    }

    private static int port(DatagramChannel channel) throws Exception {
        return ((InetSocketAddress) channel.getLocalAddress()).getPort();
    }

    @Test
    public void shouldRefuseToStartWhenTheHttp3PortIsHeldNamingThePortAndTheCauseOnOneLine() throws Exception {
        try (DatagramChannel otherApplication = heldUdpPort()) {
            int udpPort = port(otherApplication);

            Throwable refused = refusedStart(() -> new MockServer(configuration().http3Port(udpPort), 0));

            assertThat(refused, instanceOf(Http3StartupException.class));
            assertThat(refused.getMessage(), startsWith("HTTP/3 is enabled (http3Port=" + udpPort + ") but UDP port " + udpPort + " could not be bound, so MockServer cannot start:"
                + " free the port if another application holds it, choose a different http3Port, or remove http3Port to run without HTTP/3 (underlying error: BindException: "));
            assertThat(refused.getMessage(), endsWith(")"));
            assertThat("one line", refused.getMessage(), not(containsString("\n")));
            assertThat(refused.getCause(), instanceOf(BindException.class));
            assertThat(((Http3StartupException) refused).isPortUnavailable(), is(true));
        }
    }

    @Test
    public void shouldRefuseToStartWhenTheBindItselfFails() throws Exception {
        try (DatagramChannel otherApplication = heldUdpPortOnBothStacks()) {
            int udpPort = port(otherApplication);

            Throwable refused = refusedStart(() -> new MockServer(configuration().http3Port(udpPort), 0), Http3StartFailureTest::assertNoOtherNonDaemonThreadLeftRunning);

            assertThat(refused, instanceOf(Http3StartupException.class));
            // the operating system's own words for the port being in use follow
            assertThat(refused.getMessage(), startsWith("HTTP/3 is enabled (http3Port=" + udpPort + ") but UDP port " + udpPort + " could not be bound, so MockServer cannot start:"
                + " free the port if another application holds it, choose a different http3Port, or remove http3Port to run without HTTP/3 (underlying error: BindException: "));
            assertThat(refused.getMessage(), not(containsString("would reach that socket instead of MockServer")));
            assertThat(refused.getCause(), instanceOf(BindException.class));
        }
    }

    @Test
    public void shouldHaveClosedItsTcpListenerAndHoldNoUdpSocketWhenTheConstructorThrows() throws Exception {
        Started started = new Started();
        try (DatagramChannel otherApplication = heldUdpPortOnBothStacks()) {
            int udpPort = port(otherApplication);

            refusedStart(() -> new RecordingMockServer(started, configuration().http3Port(udpPort)), () -> {
                assertStopped(started);
                // only the other application held the port on both stacks, so with it gone a socket left there is the server's
                otherApplication.close();
                assertThat("the refused server must hold no socket on UDP port " + udpPort, Ipv4UdpPortProbe.dualStackBindSucceeds(udpPort), is(true));
            });
        }
    }

    /**
     * Netty's default quiet period would keep the HTTP/3 event loop's thread, which is no daemon and so keeps a JVM
     * from exiting, for about 2 s after the throw: a refusal before the bind starts that thread to shut it down. A
     * failed bind, which has it running, is checked in {@link #shouldRefuseToStartWhenTheBindItselfFails()}.
     */
    @Test
    public void shouldHaveEndedEveryNonDaemonThreadItStartedWhenTheConstructorThrows() throws Exception {
        Configuration http3StartThrows = new Http3StartThrows(new LinkageError("the QUIC codec could not be linked"));

        refusedStart(() -> startWithHttp3(udpPort -> new MockServer(http3StartThrows.http3Port(udpPort), 0)), Http3StartFailureTest::assertNoOtherNonDaemonThreadLeftRunning);
    }

    /**
     * A start interrupted while it binds leaves a channel registered whose QUIC codec holds a direct buffer until
     * the pipeline is torn down, which a loop shut down with no quiet period could skip. Without the tear-down the
     * first such start in a JVM leaked it every time and later ones did not, so the start runs in a fresh JVM with
     * the build's leak detector, which reports the leaks it counted.
     */
    @Test
    public void shouldLeakNoBufferWhenTheFirstHttp3StartInAJvmIsInterruptedWhileItBinds() throws Exception {
        List<String> command = Arrays.asList(
            System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
            "-Dio.netty.leakDetection.level=paranoid",
            "-Dio.netty.customResourceLeakDetector=" + FailOnLeakResourceLeakDetector.CLASS_NAME,
            "-cp", System.getProperty("java.class.path"),
            InterruptedFirstStart.class.getName()
        );
        // to a file, so a child that hangs cannot keep this test reading its output
        File output = temporaryFolder.newFile("interrupted-first-start.log");
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output);
        processBuilder.environment().remove("JAVA_TOOL_OPTIONS");
        Process process = processBuilder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("the child JVM did not exit within 60s:\n" + Files.readString(output.toPath(), StandardCharsets.UTF_8));
        }
        String[] lines = Files.readString(output.toPath(), StandardCharsets.UTF_8).trim().split("\\R");

        assertThat(String.join("\n", lines), lines[lines.length - 1], is("interrupted while binding; buffers leaked: 0"));
    }

    public static class InterruptedFirstStart {
        public static void main(String[] arguments) throws Exception {
            Http3Server server = new Http3Server();
            Thread.currentThread().interrupt();
            String outcome;
            try {
                server.start(0);
                server.stop();
                outcome = "started";
            } catch (InterruptedException expected) {
                outcome = "interrupted while binding";
            }
            Thread.interrupted();
            // a leak is reported once the buffer has been collected and another is allocated
            for (int i = 0; i < 5; i++) {
                System.gc();
                Thread.sleep(50);
                ByteBufAllocator.DEFAULT.buffer(1).release();
            }
            System.out.println(outcome + "; buffers leaked: " + FailOnLeakResourceLeakDetector.leakCount());
            System.exit(0);
        }
    }

    private static void assertNoOtherNonDaemonThreadLeftRunning() throws InterruptedException {
        assertThat("non-daemon threads the refused server left running when it threw", LeftBehind.otherNonDaemonThreadsStillAliveAfter(Thread.currentThread().getThreadGroup(), AT_THE_THROW_MILLIS), is(empty()));
    }

    @Test
    public void shouldRefuseToStartAndStopWhenTheHttp3StartThrowsAnError() throws Exception {
        Started started = new Started();
        LinkageError error = new LinkageError("the QUIC codec could not be linked");
        Configuration http3StartThrows = new Http3StartThrows(error);

        Throwable refused = refusedStart(
            () -> startWithHttp3(udpPort -> new RecordingMockServer(started, http3StartThrows.http3Port(udpPort))),
            () -> assertStopped(started));

        assertThat(refused, instanceOf(Http3StartupException.class));
        assertThat(refused.getCause(), is(sameInstance(error)));
        assertThat(refused.getMessage(), is("HTTP/3 is enabled (http3Port=" + http3StartThrows.http3Port() + ") but its server could not start on UDP port " + http3StartThrows.http3Port()
            + ", so MockServer cannot start: fix the underlying error or remove http3Port to run without HTTP/3 (underlying error: LinkageError: the QUIC codec could not be linked)"));
    }

    @Test
    public void shouldStopAndThenRestoreTheInterruptWhenTheHttp3BindIsInterrupted() throws Exception {
        Started started = new Started();
        started.interruptOnceTcpIsBound = true;
        AtomicBoolean interruptedAtTheThrow = new AtomicBoolean();

        Throwable refused = refusedStart(
            () -> startWithHttp3(udpPort -> new RecordingMockServer(started, configuration().http3Port(udpPort))),
            () -> {
                interruptedAtTheThrow.set(Thread.interrupted());
                assertStopped(started);
            });

        assertThat(refused, instanceOf(Http3StartupException.class));
        assertThat(refused.getCause(), instanceOf(InterruptedException.class));
        assertThat("the caller must still see the interrupt", interruptedAtTheThrow.get(), is(true));
    }

    // an InterruptedException that the starting thread's own wait did not throw says nothing about that thread
    @Test
    public void shouldNotInterruptTheCallerForAnInterruptedExceptionThatWasNotItsOwn() throws Exception {
        Started started = new Started();
        Configuration http3StartThrows = new Http3StartThrows(new InterruptedException("on another thread"));
        AtomicBoolean interruptedAtTheThrow = new AtomicBoolean();

        Throwable refused = refusedStart(
            () -> startWithHttp3(udpPort -> new RecordingMockServer(started, http3StartThrows.http3Port(udpPort))),
            () -> {
                interruptedAtTheThrow.set(Thread.interrupted());
                assertStopped(started);
            });

        assertThat(refused, instanceOf(Http3StartupException.class));
        assertThat(refused.getCause(), instanceOf(InterruptedException.class));
        assertThat("the starting thread was never interrupted", interruptedAtTheThrow.get(), is(false));
    }

    // stop() does not wait on an interrupted thread, and a JUnit timeout interrupts the thread that is starting a server
    @Test
    public void shouldStopAndKeepTheInterruptWhenTheHttp3StartFailsOnAnInterruptedThread() throws Exception {
        Started started = new Started();
        LinkageError error = new LinkageError("the QUIC codec could not be linked");
        Configuration http3StartThrows = new Http3StartThrows(error).interruptingFirst();
        AtomicBoolean interruptedAtTheThrow = new AtomicBoolean();

        Throwable refused = refusedStart(
            () -> startWithHttp3(udpPort -> new RecordingMockServer(started, http3StartThrows.http3Port(udpPort))),
            () -> {
                interruptedAtTheThrow.set(Thread.interrupted());
                assertStopped(started);
            });

        assertThat(refused, instanceOf(Http3StartupException.class));
        assertThat(refused.getCause(), is(sameInstance(error)));
        assertThat("the caller must still see the interrupt", interruptedAtTheThrow.get(), is(true));
    }

    // the starter is only offered the held port, so it starts again on it until it gives up
    @Test
    public void shouldReleaseTheDnsPortItHadBoundWhenHttp3IsRefused() throws Exception {
        try (DatagramChannel otherApplication = heldUdpPort()) {
            int heldPort = port(otherApplication);
            List<Throwable> refusals = new CopyOnWriteArrayList<>();
            List<String> wrongAtTheThrow = new CopyOnWriteArrayList<>();

            Throwable gaveUp = refusedStart(() -> startWithHttp3(() -> heldPort, udpPort -> {
                Started started = new Started();
                try {
                    return new RecordingMockServer(started, new RecordsTheDnsPort(started).dnsEnabled(true).dnsPort(0).http3Port(udpPort));
                } catch (RuntimeException refusal) {
                    refusals.add(refusal);
                    try {
                        assertStopped(started);
                        assertThat("the DNS port was bound before HTTP/3 was started", started.dnsPort, greaterThan(0));
                        assertThat("the refused server must hold no socket on its DNS port " + started.dnsPort, Ipv4UdpPortProbe.dualStackBindSucceeds(started.dnsPort), is(true));
                    } catch (AssertionError wrong) {
                        wrongAtTheThrow.add(wrong.getMessage());
                    }
                    throw refusal;
                }
            }, MockServer::getHttp3Port, MockServer::stop));

            assertThat(gaveUp.getMessage(), containsString("another socket held each of the UDP ports"));
            assertThat(refusals, hasSize(greaterThan(0)));
            assertThat(refusals, everyItem(instanceOf(Http3StartupException.class)));
            assertThat(wrongAtTheThrow, is(empty()));
        }
    }

    @Test
    public void shouldRefuseToStartWhenTheHttp3PortIsNotAPort() throws Exception {
        Throwable refused = refusedStart(() -> new MockServer(configuration().http3Port(70000), 0));

        assertThat(refused, instanceOf(Http3StartupException.class));
        assertThat(refused.getMessage(), is("HTTP/3 is enabled (http3Port=70000) but its server could not start on UDP port 70000, so MockServer cannot start:"
            + " fix the underlying error or remove http3Port to run without HTTP/3 (underlying error: IllegalArgumentException: port out of range:70000)"));
        assertThat(refused.getCause(), instanceOf(IllegalArgumentException.class));
        assertThat(((Http3StartupException) refused).isPortUnavailable(), is(false));
    }

    @Test
    public void shouldRefuseToStartWhenTheTlsContextForQuicCannotBeBuilt() throws Exception {
        File notACertificate = temporaryFolder.newFile("not-a-certificate.pem");
        Files.write(notACertificate.toPath(), "-----BEGIN CERTIFICATE-----\nnot a certificate\n-----END CERTIFICATE-----\n".getBytes(StandardCharsets.UTF_8));
        Configuration unreadableTrustChain = configuration()
            .tlsMutualAuthenticationRequired(true)
            .tlsMutualAuthenticationCertificateChain(notACertificate.getAbsolutePath());

        Throwable refused = refusedStart(() -> startWithHttp3(unreadableTrustChain));

        assertThat(refused, instanceOf(Http3StartupException.class));
        assertThat(refused.getMessage(), startsWith("HTTP/3 is enabled (http3Port=" + unreadableTrustChain.http3Port() + ") but its server could not start on UDP port "
            + unreadableTrustChain.http3Port() + ", so MockServer cannot start: fix the underlying error or remove http3Port to run without HTTP/3 (underlying error: "));
        assertThat(refused.getCause().getMessage(), containsString(notACertificate.getAbsolutePath()));
        assertThat(((Http3StartupException) refused).isPortUnavailable(), is(false));
    }

    @Test
    public void shouldPropagateTheRefusalFromStartClientAndServer() throws Exception {
        try (DatagramChannel otherApplication = heldUdpPort()) {
            int udpPort = port(otherApplication);

            Throwable refused = refusedStart(() -> ClientAndServer.startClientAndServer(configuration().http3Port(udpPort), 0));

            assertThat(refused, instanceOf(Http3StartupException.class));
            assertThat(refused.getMessage(), containsString("UDP port " + udpPort + " could not be bound"));
        }
    }

    // the HTTP/3 server reads this while it starts, after the TCP and DNS ports are bound
    private static final class Http3StartThrows extends Configuration {

        private final Throwable failure;
        private boolean interruptFirst;

        private Http3StartThrows(Throwable failure) {
            this.failure = failure;
        }

        private Http3StartThrows interruptingFirst() {
            interruptFirst = true;
            return this;
        }

        @Override
        public Long http3MaxIdleTimeout() {
            if (interruptFirst) {
                Thread.currentThread().interrupt();
            }
            return Http3StartFailureTest.<RuntimeException, Long>throwUnchecked(failure);
        }
    }

    // records the DNS port from inside the constructor, which a refused start never returns from
    private static final class RecordsTheDnsPort extends Configuration {

        private final Started started;

        private RecordsTheDnsPort(Started started) {
            this.started = started;
        }

        @Override
        public Long http3MaxIdleTimeout() {
            if (started.server != null) {
                started.dnsPort = started.server.getDnsPort();
            }
            return super.http3MaxIdleTimeout();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R throwUnchecked(Throwable throwable) throws T {
        throw (T) throwable;
    }
}
