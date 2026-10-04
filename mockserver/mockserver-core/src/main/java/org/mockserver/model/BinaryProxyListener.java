package org.mockserver.model;

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;

/**
 * Told of each binary (non-HTTP) message MockServer forwards.
 * <p>
 * With {@code forwardBinaryRequestsWithoutWaitingForResponse} the listener is called once per message read from
 * a client connection, on a thread of its own rather than the thread serving that connection, so it may block,
 * for example on {@code binaryResponse}. The messages of one connection are reported one at a time, in the order
 * they arrived; messages of different connections are reported concurrently, so the listener must be
 * thread-safe. A call can come after the response has already been written to the client, or after the client
 * has closed its connection. A listener that throws closes the client's connection.
 * <p>
 * Without that setting the listener is called once the upstream's response has arrived, before it is written to
 * the client.
 */
public interface BinaryProxyListener {

    /**
     * @param binaryRequest  the bytes read from the client
     * @param binaryResponse completes with the upstream's response, with {@code null} if the upstream closed
     *                       its connection without one, or exceptionally if the message could not be forwarded
     * @param serverAddress  the upstream the message is forwarded to
     * @param clientAddress  the client that sent it
     */
    public void onProxy(BinaryMessage binaryRequest, CompletableFuture<BinaryMessage> binaryResponse, SocketAddress serverAddress, SocketAddress clientAddress);

}
