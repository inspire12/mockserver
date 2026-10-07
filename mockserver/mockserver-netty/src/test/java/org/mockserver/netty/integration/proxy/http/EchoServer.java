package org.mockserver.netty.integration.proxy.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A server on this machine that sends back every byte it is sent.
 */
final class EchoServer implements AutoCloseable {
    private final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();

    EchoServer() throws IOException {
        RecordingUpstreamProxy.daemon(() -> {
            while (!listener.isClosed()) {
                Socket client = listener.accept();
                sockets.add(client);
                RecordingUpstreamProxy.daemon(() -> RecordingUpstreamProxy.copy(client, client));
            }
        });
    }

    int port() {
        return listener.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        listener.close();
        for (Socket socket : sockets) {
            socket.close();
        }
    }
}
