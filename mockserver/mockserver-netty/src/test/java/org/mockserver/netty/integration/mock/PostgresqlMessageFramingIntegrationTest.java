package org.mockserver.netty.integration.mock;

import io.netty.buffer.ByteBufUtil;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.BinaryMessageFraming;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.socket.PortFactory;
import org.mockserver.socket.tls.KeyStoreFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.BinaryRequestDefinition.binaryRequest;
import static org.mockserver.model.BinaryResponse.binaryResponse;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A PostgreSQL client's messages on real connections, with and without binaryMessageFraming: a message the client
 * writes in two parts with a pause between is matched as one, messages written together are matched one by one,
 * a declared length over maxRequestBodySize closes the connection, and without framing nothing changes.
 */
public class PostgresqlMessageFramingIntegrationTest {

    private static final byte[] SSL_REQUEST = {0, 0, 0, 8, 4, (byte) 0xd2, 0x16, 0x2f};
    private static final byte[] STARTUP = {0, 0, 0, 23, 0, 3, 0, 0, 'u', 's', 'e', 'r', 0, 'p', 'o', 's', 't', 'g', 'r', 'e', 's', 0, 0};
    private static final byte[] AUTHENTICATION_OK_AND_READY = {'R', 0, 0, 0, 8, 0, 0, 0, 0, 'Z', 0, 0, 0, 5, 'I'};
    private static final byte[] SYNC = {'S', 0, 0, 0, 4};
    private static final byte[] READY = {'Z', 0, 0, 0, 5, 'I'};
    private static final long PAUSE_BETWEEN_PARTS_MILLIS = 200;

    private ClientAndServer mockServer;
    private final List<Socket> sockets = new ArrayList<>();

    @After
    public void closeSocketsAndStopMockServer() {
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing what a test may already have closed
            }
        }
        stopQuietly(mockServer);
    }

    private void start(Configuration configuration) {
        mockServer = startClientAndServer(configuration, PortFactory.findFreePort());
        mock(SSL_REQUEST, new byte[]{'S'});
        mock(STARTUP, AUTHENTICATION_OK_AND_READY);
        mock(SYNC, READY);
    }

    private void mock(byte[] request, byte[] response) {
        mockServer.upsert(new Expectation(binaryRequest(request)).thenRespondWithBinary(binaryResponse(response)));
    }

    /** A query message of the length given, its length field included, so no part of it is a whole message. */
    private static byte[] query(int length) {
        ByteBuffer message = ByteBuffer.allocate(length).put((byte) 'Q').putInt(length - 1);
        while (message.hasRemaining()) {
            message.put((byte) 'q');
        }
        return message.array();
    }

    private static byte[] commandComplete(int length) {
        return new byte[]{'C', (byte) (length >> 8), (byte) length};
    }

    private Socket connect(boolean tls) throws Exception {
        Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort());
        sockets.add(socket);
        socket.setSoTimeout(10_000);
        socket.setTcpNoDelay(true);
        if (!tls) {
            return socket;
        }
        exchange(socket, SSL_REQUEST, new byte[]{'S'});
        KeyStore keyStore = new KeyStoreFactory(configuration(), new MockServerLogger()).loadOrCreateKeyStore();
        KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
        trusted.load(null, null);
        trusted.setCertificateEntry("mockserver-ca", keyStore.getCertificate(KeyStoreFactory.KEY_STORE_CA_ALIAS));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        SSLSocket upgraded = (SSLSocket) context.getSocketFactory().createSocket(socket, "127.0.0.1", socket.getPort(), true);
        sockets.add(upgraded);
        upgraded.setUseClientMode(true);
        upgraded.startHandshake();
        return upgraded;
    }

    private static void send(Socket socket, byte[] bytes) throws IOException {
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    private static void sendInTwoPartsWithAPause(Socket socket, byte[] message) throws Exception {
        send(socket, Arrays.copyOfRange(message, 0, message.length / 2));
        Thread.sleep(PAUSE_BETWEEN_PARTS_MILLIS);
        send(socket, Arrays.copyOfRange(message, message.length / 2, message.length));
    }

    private static void receives(Socket socket, byte[] expected) throws IOException {
        assertThat(ByteBufUtil.hexDump(socket.getInputStream().readNBytes(expected.length)), is(ByteBufUtil.hexDump(expected)));
    }

    private static void exchange(Socket socket, byte[] message, byte[] expectedReply) throws IOException {
        send(socket, message);
        receives(socket, expectedReply);
    }

    @Test
    public void shouldMatchAMessageWrittenInTwoPartsWithAPauseAsOneMessage() throws Exception {
        start(configuration().binaryMessageFraming(BinaryMessageFraming.POSTGRESQL));
        mock(query(3000), commandComplete(3000));
        for (boolean tls : new boolean[]{false, true}) {
            Socket session = connect(tls);
            sendInTwoPartsWithAPause(session, STARTUP);
            receives(session, AUTHENTICATION_OK_AND_READY);

            sendInTwoPartsWithAPause(session, query(3000));

            receives(session, commandComplete(3000));
            exchange(session, SYNC, READY);
            session.close();
        }
    }

    @Test
    public void shouldMatchMessagesWrittenTogetherOneByOne() throws Exception {
        start(configuration().binaryMessageFraming(BinaryMessageFraming.POSTGRESQL));
        mock(query(100), commandComplete(100));
        for (boolean tls : new boolean[]{false, true}) {
            Socket session = connect(tls);
            exchange(session, STARTUP, AUTHENTICATION_OK_AND_READY);
            ByteBuffer pipelined = ByteBuffer.allocate(100 + SYNC.length + 100 + SYNC.length);
            pipelined.put(query(100)).put(SYNC).put(query(100)).put(SYNC);

            send(session, pipelined.array());

            ByteBuffer replies = ByteBuffer.allocate(2 * (3 + READY.length));
            replies.put(commandComplete(100)).put(READY).put(commandComplete(100)).put(READY);
            receives(session, replies.array());
            session.close();
        }
    }

    @Test
    public void shouldCloseAConnectionThatDeclaresAMessageOverMaxRequestBodySize() throws Exception {
        start(configuration().binaryMessageFraming(BinaryMessageFraming.POSTGRESQL).maxRequestBodySize(1024));
        for (boolean tls : new boolean[]{false, true}) {
            Socket session = connect(tls);
            exchange(session, STARTUP, AUTHENTICATION_OK_AND_READY);

            send(session, Arrays.copyOf(query(1025), 5));

            assertThat("closed on the declared length alone, with nothing written", session.getInputStream().read(), is(-1));
        }
    }

    @Test
    public void shouldTakeAMessageWrittenInTwoPartsWithAPauseAsTwoMessagesWithoutFraming() throws Exception {
        start(configuration());
        mock(query(3000), commandComplete(3000));
        Socket session = connect(false);
        exchange(session, STARTUP, AUTHENTICATION_OK_AND_READY);

        sendInTwoPartsWithAPause(session, query(3000));

        String reply = new String(session.getInputStream().readNBytes(22), StandardCharsets.UTF_8);
        assertThat("the first part alone matches nothing", reply, startsWith("unknown message format"));
    }
}
