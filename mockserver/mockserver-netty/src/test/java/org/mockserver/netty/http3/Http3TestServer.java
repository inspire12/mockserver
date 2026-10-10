package org.mockserver.netty.http3;

import org.apache.commons.lang3.exception.ExceptionUtils;
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
 * candidate port can be taken between being found and being bound, and the server then refuses to start. A
 * start that fails with a {@link BindException} in its cause chain is repeated on the next candidate only when
 * that port is seen to be held by another socket. A start refused on a port that is free, any other failure to
 * start, and a server that started without HTTP/3 on its candidate each fail the test at once.
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
     * @param start          starts a server configured with the {@code http3Port} it is given; a server that
     *                       refuses to start because it could not bind that port throws an exception with a
     *                       {@link BindException} in its cause chain, having released whatever it bound
     * @param boundHttp3Port the UDP port the started server serves HTTP/3 on, or -1 if it serves none
     * @param stop           stops a server that started without HTTP/3 on the port it was given
     */
    public static <T> T startWithHttp3(IntFunction<T> start, ToIntFunction<T> boundHttp3Port, Consumer<T> stop) {
        return startWithHttp3(TestPortFactory::findFreeUdpPort, start, boundHttp3Port, stop);
    }

    static <T> T startWithHttp3(IntSupplier candidateUdpPorts, IntFunction<T> start, ToIntFunction<T> boundHttp3Port, Consumer<T> stop) {
        List<Integer> held = new ArrayList<>();
        for (int attempt = 0; attempt < UDP_PORT_CANDIDATES; attempt++) {
            int udpPort = candidateUdpPorts.getAsInt();
            T candidate;
            try {
                candidate = start.apply(udpPort);
            } catch (RuntimeException refused) {
                if (ExceptionUtils.indexOfType(refused, BindException.class) < 0) {
                    throw refused;
                }
                requireHeldByAnotherSocket(udpPort, refused);
                held.add(udpPort);
                continue;
            }
            int bound = boundHttp3Port.applyAsInt(candidate);
            if (bound != udpPort) {
                stop.accept(candidate);
                throw new AssertionError("HTTP/3 must have started on UDP port " + udpPort + ", but the server started "
                    + (bound == NO_HTTP3 ? "without HTTP/3" : "with HTTP/3 on UDP port " + bound));
            }
            return candidate;
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
                requireHeldByAnotherSocket(udpPort, refused);
                held.add(udpPort);
            }
        }
        throw new AssertionError("HTTP/3 must have started, but another socket held each of the UDP ports " + held);
    }

    // the server has already released what it bound, so a port that is free now was refused while free or has just been released
    private static void requireHeldByAnotherSocket(int udpPort, Exception refused) {
        if (!isHeldByAnotherSocket(udpPort)) {
            throw new AssertionError("HTTP/3 must have started: UDP port " + udpPort + " is free", refused);
        }
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
