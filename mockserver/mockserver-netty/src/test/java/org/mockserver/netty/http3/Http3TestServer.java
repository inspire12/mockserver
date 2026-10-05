package org.mockserver.netty.http3;

import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;
import org.mockserver.testing.socket.TestPortFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Starts the server a test needs with HTTP/3 on a UDP port it really holds.
 *
 * <p>MockServer binds the HTTP/3 port itself and {@code http3Port} cannot ask for an ephemeral one, so a
 * candidate port can be taken between being found and being bound. A start that did not get its candidate is
 * repeated on the next candidate only when that port is seen to be held by another socket; HTTP/3 failing to
 * start on a port that is free fails the test at once, as does any other failure to start.
 */
public final class Http3TestServer {

    private static final int UDP_PORT_CANDIDATES = 5;
    private static final int NO_HTTP3 = -1;

    private Http3TestServer() {
    }

    /**
     * @param configuration its {@code http3Port} is set to the UDP port the returned server holds
     * @return a MockServer on an ephemeral TCP port with HTTP/3 started
     */
    public static MockServer startWithHttp3(Configuration configuration) {
        return startWithHttp3(udpPort -> new MockServer(configuration.http3Port(udpPort), 0));
    }

    /**
     * @param start starts a MockServer configured with the {@code http3Port} it is given
     * @return the MockServer {@code start} returned, with HTTP/3 started on the port it was given
     */
    public static MockServer startWithHttp3(IntFunction<MockServer> start) {
        return startWithHttp3(start, MockServer::getHttp3Port, MockServer::stop);
    }

    /**
     * For a server that is not an in-process MockServer.
     *
     * @param start          starts a server configured with the {@code http3Port} it is given
     * @param boundHttp3Port the UDP port the started server serves HTTP/3 on, or -1 if it serves none (and so holds
     *                       no UDP socket)
     * @param stop           stops a server that did not get its port, releasing whatever it bound
     */
    public static <T> T startWithHttp3(IntFunction<T> start, ToIntFunction<T> boundHttp3Port, Consumer<T> stop) {
        return startWithHttp3(TestPortFactory::findFreeUdpPort, start, boundHttp3Port, stop);
    }

    static <T> T startWithHttp3(IntSupplier candidateUdpPorts, IntFunction<T> start, ToIntFunction<T> boundHttp3Port, Consumer<T> stop) {
        List<Integer> held = new ArrayList<>();
        for (int attempt = 0; attempt < UDP_PORT_CANDIDATES; attempt++) {
            int udpPort = candidateUdpPorts.getAsInt();
            T candidate = start.apply(udpPort);
            int bound = boundHttp3Port.applyAsInt(candidate);
            if (bound == udpPort) {
                return candidate;
            }
            // without HTTP/3 the server holds no UDP socket, so the port is probed while the socket that took it is still there
            if (bound == NO_HTTP3) {
                boolean heldByAnotherSocket = isHeldByAnotherSocket(udpPort);
                stop.accept(candidate);
                assertThat("HTTP/3 must have started: UDP port " + udpPort + " is free", heldByAnotherSocket, is(true));
            } else {
                stop.accept(candidate);
                assertThat("HTTP/3 must have started on UDP port " + udpPort + ", not " + bound + ": the port is free now (HTTP/3 did not start, or the socket that held the port has gone)",
                    isHeldByAnotherSocket(udpPort), is(true));
            }
            held.add(udpPort);
        }
        throw new AssertionError("HTTP/3 must have started, but another socket held each of the UDP ports " + held);
    }

    /**
     * @return the UDP port {@code server} was started on, which is the port it was asked to bind
     */
    public static int startWithHttp3(Http3Server server) throws Exception {
        return startWithHttp3(TestPortFactory::findFreeUdpPort, server);
    }

    static int startWithHttp3(IntSupplier candidateUdpPorts, Http3Server server) throws Exception {
        List<Integer> held = new ArrayList<>();
        for (int attempt = 0; attempt < UDP_PORT_CANDIDATES; attempt++) {
            int udpPort = candidateUdpPorts.getAsInt();
            try {
                assertThat("the HTTP/3 server must bind the UDP port it was given", server.start(udpPort), is(udpPort));
                return udpPort;
            } catch (BindException refused) {
                if (!isHeldByAnotherSocket(udpPort)) {
                    throw new AssertionError("HTTP/3 must have started: UDP port " + udpPort + " is free", refused);
                }
                held.add(udpPort);
            }
        }
        throw new AssertionError("HTTP/3 must have started, but another socket held each of the UDP ports " + held);
    }

    // held on IPv4, where MockServer refuses the port, or not bindable at all
    private static boolean isHeldByAnotherSocket(int udpPort) {
        try (DatagramChannel ipv4 = DatagramChannel.open(StandardProtocolFamily.INET); DatagramChannel dualStack = DatagramChannel.open()) {
            ipv4.bind(new InetSocketAddress(udpPort));
            ipv4.close();
            dualStack.bind(new InetSocketAddress(udpPort));
            return false;
        } catch (BindException inUse) {
            return true;
        } catch (IOException e) {
            throw new AssertionError("could not probe UDP port " + udpPort, e);
        }
    }
}
