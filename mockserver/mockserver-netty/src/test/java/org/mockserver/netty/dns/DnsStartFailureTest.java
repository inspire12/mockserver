package org.mockserver.netty.dns;

import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.DnsRecord;
import org.mockserver.model.DnsResponse;
import org.mockserver.netty.MockServer;
import org.mockserver.stop.Stoppable;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.DnsRequestDefinition.dnsRequest;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A server that cannot serve DNS on the {@code dnsPort} it was given must refuse to start, as it does for a TCP
 * port it cannot bind, and leave nothing running: the caller gets no reference it could stop.
 */
public class DnsStartFailureTest {

    private static final long DEADLINE_SECONDS = 30;
    private static final int QUERY_TIMEOUT_MILLIS = 5000;
    private static final String SERVED_NAME = "served.dns-start.example.";
    private static final String SERVED_ADDRESS = "10.9.8.7";

    // every MockServer builds a JDK HttpClient for cluster fan-in, whose daemon thread outlives stop() too
    private static final Pattern JDK_HTTP_CLIENT_SELECTOR = Pattern.compile("HttpClient-\\d+-SelectorManager");

    // a socket on 127.0.0.1 fails the server's bind of that port on every address, on every platform
    private static DatagramChannel heldUdpPort() throws Exception {
        DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET);
        otherApplication.bind(new InetSocketAddress("127.0.0.1", 0));
        return otherApplication;
    }

    private static int port(DatagramChannel channel) throws Exception {
        return ((InetSocketAddress) channel.getLocalAddress()).getPort();
    }

    private static Configuration dnsOn(int dnsPort) {
        return configuration().dnsEnabled(true).dnsPort(dnsPort);
    }

    @Test
    public void shouldRefuseToStartWhenTheDnsPortIsHeldNamingThePortAndTheCauseOnOneLine() throws Exception {
        try (DatagramChannel otherApplication = heldUdpPort()) {
            int udpPort = port(otherApplication);

            Throwable refused = refusedStart(() -> new MockServer(dnsOn(udpPort), 0));

            assertThat(refused, instanceOf(DnsStartupException.class));
            assertThat(refused.getMessage(), startsWith(portCouldNotBeOpenedOrBound(udpPort)));
            // the operating system's own words for the port being in use, under the exception of the transport in use
            assertThat(refused.getMessage(), endsWith("Address already in use)"));
            assertThat("one line", refused.getMessage(), not(containsString("\n")));
            assertThat(refused.getCause(), instanceOf(IOException.class));
            assertThat(refused.getCause().getMessage(), containsString("Address already in use"));
            assertThat(((DnsStartupException) refused).isPortUnavailable(), is(true));
        }
    }

    @Test
    public void shouldHaveClosedItsTcpListenerAndHoldNoUdpSocketWhenTheConstructorThrows() throws Exception {
        Started started = new Started();
        try (DatagramChannel otherApplication = heldUdpPort()) {
            int udpPort = port(otherApplication);

            refusedStart(() -> new RecordingMockServer(started, dnsOn(udpPort)), () -> {
                assertStopped(started);
                // only the other application held the port, so with it gone a socket left there is the server's
                otherApplication.close();
                try (DatagramChannel sameBindAgain = DatagramChannel.open(StandardProtocolFamily.INET)) {
                    sameBindAgain.bind(new InetSocketAddress("127.0.0.1", udpPort));
                }
            });
        }
    }

    // stop() does not wait on an interrupted thread, and a JUnit timeout interrupts the thread that is starting a server
    @Test
    public void shouldStopAndKeepTheInterruptWhenTheStartingThreadIsInterrupted() throws Exception {
        Started started = new Started();
        started.interruptOnceTcpIsBound = true;
        AtomicBoolean interruptedAtTheThrow = new AtomicBoolean();
        try (DatagramChannel otherApplication = heldUdpPort()) {
            int udpPort = port(otherApplication);

            Throwable refused = refusedStart(() -> new RecordingMockServer(started, dnsOn(udpPort)), () -> {
                interruptedAtTheThrow.set(Thread.interrupted());
                assertStopped(started);
            });

            assertThat(refused, instanceOf(DnsStartupException.class));
            assertThat("the caller must still see the interrupt", interruptedAtTheThrow.get(), is(true));
        }
    }

    @Test
    public void shouldRefuseASecondMockServerGivenTheDnsPortOfTheFirst() throws Exception {
        MockServer first = new MockServer(dnsOn(0), 0);
        try {
            int udpPort = first.getDnsPort();

            Throwable refused = refusedStart(() -> new MockServer(dnsOn(udpPort), 0));

            assertThat(refused, instanceOf(DnsStartupException.class));
            assertThat(refused.getMessage(), startsWith(portCouldNotBeOpenedOrBound(udpPort)));
            assertThat("the first server keeps the port", first.getDnsPort(), is(udpPort));
            assertThat(first.isRunning(), is(true));
        } finally {
            first.stop();
        }
    }

    // its cause is an IllegalArgumentException, which the run command takes for a usage error and exits 0
    @Test
    public void shouldRefuseToStartWhenTheDnsPortIsNotAPort() throws Exception {
        Throwable refused = refusedStart(() -> new MockServer(dnsOn(70000), 0));

        assertThat(refused, instanceOf(DnsStartupException.class));
        assertThat(refused.getMessage(), is("DNS mocking is enabled (dnsEnabled=true, dnsPort=70000) but its server could not start on UDP port 70000, so MockServer cannot start:"
            + " fix the underlying error or set dnsEnabled=false to run without DNS mocking (underlying error: IllegalArgumentException: port out of range:70000)"));
        assertThat(refused.getCause(), instanceOf(IllegalArgumentException.class));
        assertThat(((DnsStartupException) refused).isPortUnavailable(), is(false));
    }

    @Test
    public void shouldPropagateTheRefusalFromStartClientAndServer() throws Exception {
        try (DatagramChannel otherApplication = heldUdpPort()) {
            int udpPort = port(otherApplication);

            Throwable refused = refusedStart(() -> ClientAndServer.startClientAndServer(dnsOn(udpPort), 0));

            assertThat(refused, instanceOf(DnsStartupException.class));
            assertThat(refused.getMessage(), containsString("UDP port " + udpPort + " could not be opened or bound"));
        }
    }

    @Test
    public void shouldServeDnsOnAPortTheOperatingSystemChoosesAndReportIt() throws Exception {
        // on macOS the operating system can choose a port another process holds on IPv4, which then gets the query
        List<String> unanswered = new ArrayList<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            MockServer server = new MockServer(dnsOn(0), 0);
            MockServerClient client = new MockServerClient("127.0.0.1", server.getLocalPort());
            try {
                assertThat("the port the operating system chose", server.getDnsPort(), greaterThan(0));
                client.when(dnsRequest(SERVED_NAME)).respondWithDns(new DnsResponse().withAnswerRecords(DnsRecord.aRecord(SERVED_NAME, SERVED_ADDRESS)));

                String answer = addressAnsweredFor(SERVED_NAME, server.getDnsPort());

                if (SERVED_ADDRESS.equals(answer)) {
                    return;
                }
                unanswered.add("UDP port " + server.getDnsPort() + " answered " + answer);
            } finally {
                stopQuietly(client);
                server.stop();
            }
        }
        throw new AssertionError("no server answered a DNS query on the port it reported: " + unanswered);
    }

    @Test
    public void shouldStartWithoutDnsAndLeaveTheDnsPortAloneWhenDnsIsDisabled() throws Exception {
        try (DatagramChannel otherApplication = heldUdpPort()) {
            MockServer server = new MockServer(configuration().dnsEnabled(false).dnsPort(port(otherApplication)), 0);
            try {
                assertThat(server.isRunning(), is(true));
                assertThat("no DNS port is bound", server.getDnsPort(), is(-1));
            } finally {
                server.stop();
            }
        }
    }

    // what a process that may not bind the port is told; a real "Permission denied" needs a host that withholds the port
    @Test
    public void shouldNameAPortTheProcessMayNotBind() {
        DnsStartupException refused = DnsStartupException.portCouldNotBeOpenedOrBound(53, new java.net.BindException("Permission denied"));

        assertThat(refused.getMessage(), is(portCouldNotBeOpenedOrBound(53) + "BindException: Permission denied)"));
        assertThat(refused.isPortUnavailable(), is(true));
    }

    // the epoll transport, the default on Linux, reports a failed bind with an IOException of its own
    @Test
    public void shouldNameAPortWhoseBindFailedWithAnyException() {
        DnsStartupException refused = DnsStartupException.portCouldNotBeOpenedOrBound(5353, new IOException("bind(..) failed with error(-98): Address already in use"));

        assertThat(refused.getMessage(), is(portCouldNotBeOpenedOrBound(5353) + "IOException: bind(..) failed with error(-98): Address already in use)"));
        assertThat(refused.isPortUnavailable(), is(true));
    }

    private static String portCouldNotBeOpenedOrBound(int udpPort) {
        return "DNS mocking is enabled (dnsEnabled=true, dnsPort=" + udpPort + ") but UDP port " + udpPort + " could not be opened or bound, so MockServer cannot start:"
            + " free the port if another application holds it, choose a different dnsPort (0 picks a free port, and a port below 1024 can need extra privileges),"
            + " or set dnsEnabled=false to run without DNS mocking (underlying error: ";
    }

    /**
     * @return the address in the A record answered for {@code name}, or why there was none
     */
    private static String addressAnsweredFor(String name, int dnsPort) throws Exception {
        byte[] query = Message.newQuery(org.xbill.DNS.Record.newRecord(Name.fromString(name), Type.A, DClass.IN)).toWire();
        try (DatagramSocket resolver = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            resolver.setSoTimeout(QUERY_TIMEOUT_MILLIS);
            resolver.connect(new InetSocketAddress("127.0.0.1", dnsPort));
            resolver.send(new DatagramPacket(query, query.length));
            DatagramPacket reply = new DatagramPacket(new byte[512], 512);
            resolver.receive(reply);
            List<org.xbill.DNS.Record> answers = new Message(Arrays.copyOf(reply.getData(), reply.getLength())).getSection(Section.ANSWER);
            return answers.size() == 1 && answers.get(0) instanceof ARecord ? ((ARecord) answers.get(0)).getAddress().getHostAddress() : "answers " + answers;
        } catch (SocketTimeoutException | java.net.PortUnreachableException | org.xbill.DNS.WireParseException notThisServer) {
            return "nothing (" + notThisServer + ")";
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
        ThreadGroup serverThreads = new ThreadGroup("refused-dns-start");
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
        }, "refused-dns-start");
        starter.start();
        starter.join(TimeUnit.SECONDS.toMillis(DEADLINE_SECONDS));

        assertThat("the start neither returned nor threw within " + DEADLINE_SECONDS + "s", starter.isAlive(), is(false));
        assertThat("the server started without DNS on its dnsPort", thrown.get() != null, is(true));
        if (wrongAtTheThrow.get() != null) {
            throw new AssertionError("when the start threw " + thrown.get() + ": " + wrongAtTheThrow.get().getMessage(), wrongAtTheThrow.get());
        }
        assertThat("threads the refused server left running", threadsStillAlive(serverThreads), is(empty()));
        return thrown.get();
    }

    @FunctionalInterface
    private interface AtTheThrow {
        void check() throws Exception;
    }

    private static void assertStopped(Started started) {
        assertThat("the TCP port was bound before the DNS port", started.tcpPorts, contains(greaterThan(0)));
        assertThat("the stop must be complete when the constructor throws, not merely begun", started.server.stopAsync().isDone(), is(true));
        assertThrows("the TCP listener on port " + started.tcpPorts.get(0) + " must be closed", ConnectException.class, () -> connect(started.tcpPorts.get(0)));
    }

    private static List<String> threadsStillAlive(ThreadGroup group) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        while (true) {
            Thread[] threads = new Thread[group.activeCount() + 16];
            int count = group.enumerate(threads, true);
            List<String> alive = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                if (threads[i].isAlive() && !JDK_HTTP_CLIENT_SELECTOR.matcher(threads[i].getName()).matches()) {
                    alive.add(threads[i].getName());
                }
            }
            if (alive.isEmpty() || System.nanoTime() >= deadline) {
                return alive;
            }
            Thread.sleep(50);
        }
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
        private volatile boolean interruptOnceTcpIsBound;
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
            if (started.interruptOnceTcpIsBound) {
                Thread.currentThread().interrupt();
            }
            return bound;
        }
    }
}
