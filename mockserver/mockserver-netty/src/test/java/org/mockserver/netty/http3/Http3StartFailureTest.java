package org.mockserver.netty.http3;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.lifecycle.LeftBehind;
import org.mockserver.netty.MockServer;
import org.mockserver.stop.Stoppable;
import org.mockserver.testing.socket.TestPortFactory;

import java.io.File;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;

/**
 * A server that cannot serve HTTP/3 on the {@code http3Port} it was given must refuse to start, as it does
 * for a TCP port it cannot bind, and leave nothing running: the caller gets no reference it could stop.
 */
public class Http3StartFailureTest {

    private static final long DEADLINE_SECONDS = 30;

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

            Throwable refused = refusedStart(() -> new MockServer(configuration().http3Port(udpPort), 0));

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
    public void shouldStopAndThenRestoreTheInterruptWhenTheHttp3StartIsInterrupted() throws Exception {
        Started started = new Started();
        Configuration http3StartThrows = new Http3StartThrows(new InterruptedException("interrupted while binding"));
        AtomicBoolean interruptedAtTheThrow = new AtomicBoolean();

        Throwable refused = refusedStart(
            () -> startWithHttp3(udpPort -> new RecordingMockServer(started, http3StartThrows.http3Port(udpPort))),
            () -> {
                interruptedAtTheThrow.set(Thread.interrupted());
                assertStopped(started);
            });

        assertThat(refused, instanceOf(Http3StartupException.class));
        assertThat(refused.getCause(), instanceOf(InterruptedException.class));
        assertThat("the caller must still see the interrupt", interruptedAtTheThrow.get(), is(true));
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

    private static Throwable refusedStart(Supplier<? extends Stoppable> start) throws InterruptedException {
        return refusedStart(start, () -> {
        });
    }

    /**
     * Runs {@code start} in a thread group of its own, so the threads that server started can be told from
     * those of every other test in the JVM, and stops the server if it wrongly started.
     *
     * @param atTheThrow checks made on the starting thread as soon as {@code start} has thrown, which is when
     *                   the caller of a refused start may rely on everything having been stopped
     * @return what {@code start} threw, once no thread it started is left alive
     */
    private static Throwable refusedStart(Supplier<? extends Stoppable> start, AtTheThrow atTheThrow) throws InterruptedException {
        ThreadGroup serverThreads = new ThreadGroup("refused-start");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Throwable> wrongAtTheThrow = new AtomicReference<>();
        Thread starter = new Thread(serverThreads, () -> {
            try {
                start.get().stop();
            } catch (Throwable throwable) {
                thrown.set(throwable);
                try {
                    atTheThrow.check();
                } catch (Throwable wrong) {
                    wrongAtTheThrow.set(wrong);
                }
            }
        }, "refused-start");
        starter.start();
        starter.join(TimeUnit.SECONDS.toMillis(DEADLINE_SECONDS));

        assertThat("the start neither returned nor threw within " + DEADLINE_SECONDS + "s", starter.isAlive(), is(false));
        assertThat("the server started without HTTP/3 on its http3Port", thrown.get() != null, is(true));
        if (wrongAtTheThrow.get() != null) {
            throw new AssertionError("when the start threw " + thrown.get() + ": " + wrongAtTheThrow.get().getMessage(), wrongAtTheThrow.get());
        }
        assertThat("threads the refused server left running", LeftBehind.threadsStillAlive(serverThreads), is(empty()));
        return thrown.get();
    }

    @FunctionalInterface
    private interface AtTheThrow {
        void check() throws Exception;
    }

    private static void assertStopped(Started started) {
        assertThat("the TCP port was bound before HTTP/3 was started", started.tcpPorts, contains(greaterThan(0)));
        assertThat("the stop must be complete when the constructor throws, not merely begun", started.server.stopAsync().isDone(), is(true));
        assertThrows("the TCP listener on port " + started.tcpPorts.get(0) + " must be closed", ConnectException.class, () -> connect(started.tcpPorts.get(0)));
    }

    private static void connect(int tcpPort) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", tcpPort), 5000);
        }
    }

    // what a refused server had started: its constructor throws, so this can only be seen from inside it
    private static final class Started {
        private final List<Integer> tcpPorts = new CopyOnWriteArrayList<>();
        private volatile MockServer server;
    }

    private static final class RecordingMockServer extends MockServer {

        private static final ThreadLocal<Started> RECORD_INTO = new ThreadLocal<>();

        private RecordingMockServer(Started started, Configuration configuration) {
            super(recordInto(started, configuration), 0);
        }

        private static Configuration recordInto(Started started, Configuration configuration) {
            RECORD_INTO.set(started);
            return configuration;
        }

        @Override
        public List<Integer> bindServerPorts(List<Integer> requestedPortBindings) {
            List<Integer> bound = super.bindServerPorts(requestedPortBindings);
            Started started = RECORD_INTO.get();
            started.server = this;
            started.tcpPorts.addAll(bound);
            return bound;
        }
    }

    // the HTTP/3 server reads this while it starts, after the TCP ports are bound
    private static final class Http3StartThrows extends Configuration {

        private final Throwable failure;

        private Http3StartThrows(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public Long http3MaxIdleTimeout() {
            return Http3StartFailureTest.<RuntimeException, Long>throwUnchecked(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R throwUnchecked(Throwable throwable) throws T {
        throw (T) throwable;
    }
}
