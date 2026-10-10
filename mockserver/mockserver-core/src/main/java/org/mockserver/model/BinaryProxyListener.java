package org.mockserver.model;

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;

/**
 * Told of each binary (non-HTTP) message MockServer forwards.
 * <p>
 * By default ({@code forwardBinaryRequestsUseSingleConnection}) a proxied connection has one upstream connection
 * for its life. The listener is called once per message read from a client connection, as soon as it is read, on
 * a thread of its own rather than the thread serving that connection, so it may block, for example on
 * {@code binaryResponse}. The messages of one connection are reported one at a time, in the order they arrived;
 * messages of different connections are reported concurrently, so the listener must be thread-safe. A call can
 * come after the response has already been written to the client, or after the client has closed its connection.
 * A listener that throws closes the client's connection. What the upstream sends that is not a message's
 * {@code binaryResponse} is reported to {@link #onUpstreamMessage}, in the same order and one at a time with
 * {@link #onProxy}.
 * <p>
 * With that setting false, or for a connection it does not carry (one whose only upstream proxy is
 * {@code forwardHttpProxy}), each message is forwarded on an upstream connection of its own. The deprecated
 * {@code forwardBinaryRequestsWithoutWaitingForResponse} applies only then: with it the listener is called as
 * described above, and without it the listener is called once the upstream's response has arrived, before it is
 * written to the client.
 */
public interface BinaryProxyListener {

    /**
     * @param binaryRequest  the bytes read from the client
     * @param binaryResponse completes with the upstream's response, with {@code null} if the upstream closed
     *                       its connection without one, or exceptionally if the message could not be forwarded.
     *                       On a connection with one upstream connection the response is the first bytes the
     *                       upstream sends after the message was written to it, and it also completes with
     *                       {@code null} when the client's next message arrives first
     * @param serverAddress  the upstream the message is forwarded to
     * @param clientAddress  the client that sent it
     */
    public void onProxy(BinaryMessage binaryRequest, CompletableFuture<BinaryMessage> binaryResponse, SocketAddress serverAddress, SocketAddress clientAddress);

    /**
     * Called, on a connection with one upstream connection, for each read from the upstream that completes no
     * {@code binaryResponse}: bytes it sent unprompted, and each read after the first that followed a message. Not
     * called for a connection whose messages each have an upstream connection of their own. Does nothing unless
     * overridden; a listener that overrides it and returns more slowly than the upstream sends slows the upstream
     * down, except while a message is waiting for its response.
     *
     * @param upstreamMessage the bytes read from the upstream, which are also written to the client
     * @param serverAddress   the upstream that sent them
     * @param clientAddress   the client they are written to
     */
    default void onUpstreamMessage(BinaryMessage upstreamMessage, SocketAddress serverAddress, SocketAddress clientAddress) {
    }

}
