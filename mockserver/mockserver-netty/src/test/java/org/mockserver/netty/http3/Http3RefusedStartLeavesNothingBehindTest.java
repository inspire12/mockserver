package org.mockserver.netty.http3;

import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.lifecycle.LeftBehind;

import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * A client and server refused its {@code http3Port} must leave nothing behind, as one refused its TCP port
 * must not: the caller gets no reference it could stop.
 */
public class Http3RefusedStartLeavesNothingBehindTest {

    private static final int REFUSED_STARTS = 10;

    @Test
    public void shouldLeaveNothingBehindWhenClientAndServerIsRefusedAHeldHttp3Port() throws Exception {
        try (DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET)) {
            otherApplication.bind(new InetSocketAddress("0.0.0.0", 0));
            int heldPort = ((InetSocketAddress) otherApplication.getLocalAddress()).getPort();
            AtomicInteger refusedStarts = new AtomicInteger();
            refusedEveryStart(heldPort, refusedStarts);
            long baseline = LeftBehind.settledDescriptors();
            ThreadGroup group = new ThreadGroup("refused-http3-port");
            refusedStarts.set(0);

            LeftBehind.in(group, () -> {
                while (refusedStarts.get() < REFUSED_STARTS) {
                    refusedEveryStart(heldPort, refusedStarts);
                }
            });

            assertThat(LeftBehind.threadsStillAlive(group), is(empty()));
            LeftBehind.assertDescriptorsGivenBack(baseline, REFUSED_STARTS);
        }
    }

    // the starter is only ever offered the held port, so every start it makes is refused
    private static void refusedEveryStart(int heldPort, AtomicInteger refusedStarts) {
        Throwable refused = assertThrows(Throwable.class, () -> Http3TestServer.startWithHttp3(
            () -> heldPort,
            udpPort -> {
                refusedStarts.incrementAndGet();
                return ClientAndServer.startClientAndServer(configuration().http3Port(udpPort), 0);
            },
            clientAndServer -> heldPort,
            ClientAndServer::stop).stop());
        if (Http3Server.isQuicAvailable()) {
            assertThat(refused.getMessage(), containsString("another socket held each of the UDP ports"));
        } else {
            assertThat(refused, is(instanceOf(Http3NativeUnavailableException.class)));
        }
    }
}
