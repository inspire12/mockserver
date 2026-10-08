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
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.BinaryRequestDefinition.binaryRequest;
import static org.mockserver.model.BinaryResponse.binaryResponse;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * MySQL, Redis and length-prefixed messages on real connections with binaryMessageFraming: a message the client
 * writes in two parts with a pause between is matched as one, messages written together are matched one by one, and
 * a declared length over maxRequestBodySize closes the connection. A MySQL connection turns TLS on after an
 * SSLRequest and is framed the same way inside it.
 */
public class BinaryMessageFramingIntegrationTest {

    private static final long PAUSE_BETWEEN_PARTS_MILLIS = 200;
    private static final byte[] OK = {7, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0};

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
    }

    private void mock(byte[] request, byte[] response) {
        mockServer.upsert(new Expectation(binaryRequest(request)).thenRespondWithBinary(binaryResponse(response)));
    }

    private static byte[] mysqlPacket(int sequence, int payloadLength, char fill) {
        byte[] packet = new byte[4 + payloadLength];
        Arrays.fill(packet, (byte) fill);
        packet[0] = (byte) payloadLength;
        packet[1] = (byte) (payloadLength >> 8);
        packet[2] = (byte) (payloadLength >> 16);
        packet[3] = (byte) sequence;
        return packet;
    }

    private static byte[] mysqlSslRequest() {
        byte[] request = mysqlPacket(1, 32, (char) 0);
        request[4] = (byte) 0x0d;
        request[5] = (byte) 0xaa;
        return request;
    }

    private static byte[] redisCommand(String... arguments) {
        StringBuilder command = new StringBuilder("*" + arguments.length + "\r\n");
        for (String argument : arguments) {
            command.append('$').append(argument.length()).append("\r\n").append(argument).append("\r\n");
        }
        return command.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String letters(char letter, int count) {
        char[] letters = new char[count];
        Arrays.fill(letters, letter);
        return new String(letters);
    }

    /** A 2-byte little-endian length, not counting itself, then the body. */
    private static byte[] lengthPrefixed(int bodyBytes, char fill) {
        ByteBuffer message = ByteBuffer.allocate(2 + bodyBytes).order(ByteOrder.LITTLE_ENDIAN).putShort((short) bodyBytes);
        while (message.hasRemaining()) {
            message.put((byte) fill);
        }
        return message.array();
    }

    private Socket connect() throws Exception {
        Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort());
        sockets.add(socket);
        socket.setSoTimeout(10_000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private Socket upgradeToTls(Socket socket) throws Exception {
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

    private static byte[] joined(byte[]... parts) {
        ByteBuffer all = ByteBuffer.allocate(Arrays.stream(parts).mapToInt(part -> part.length).sum());
        for (byte[] part : parts) {
            all.put(part);
        }
        return all.array();
    }

    @Test
    public void shouldMatchAMysqlPacketWrittenInTwoPartsWithAPauseAsOneMessageInTheClearAndAfterAnSslRequest() throws Exception {
        start(configuration().binaryMessageFraming(BinaryMessageFraming.MYSQL));
        byte[] query = mysqlPacket(0, 3000, 'q');
        mock(query, OK);
        // MySQL answers nothing to an SSLRequest: the client starts its handshake at once
        mock(mysqlSslRequest(), new byte[0]);
        for (boolean tls : new boolean[]{false, true}) {
            Socket session = connect();
            if (tls) {
                send(session, mysqlSslRequest());
                session = upgradeToTls(session);
            }

            sendInTwoPartsWithAPause(session, query);

            receives(session, OK);
            session.close();
        }
    }

    @Test
    public void shouldMatchMysqlPacketsWrittenTogetherOneByOne() throws Exception {
        start(configuration().binaryMessageFraming(BinaryMessageFraming.MYSQL));
        byte[] prepare = mysqlPacket(0, 40, 'p');
        byte[] execute = mysqlPacket(0, 20, 'e');
        mock(prepare, new byte[]{1, 0, 0, 1, 'P'});
        mock(execute, new byte[]{1, 0, 0, 1, 'E'});
        Socket session = connect();

        send(session, joined(prepare, execute));

        receives(session, new byte[]{1, 0, 0, 1, 'P', 1, 0, 0, 1, 'E'});
    }

    @Test
    public void shouldMatchARedisCommandWrittenInTwoPartsWithAPauseAsOneMessage() throws Exception {
        start(configuration().binaryMessageFraming(BinaryMessageFraming.REDIS));
        byte[] set = redisCommand("SET", "key", letters('v', 3000));
        byte[] ok = "+OK\r\n".getBytes(StandardCharsets.US_ASCII);
        mock(set, ok);
        Socket session = connect();

        sendInTwoPartsWithAPause(session, set);

        receives(session, ok);
    }

    @Test
    public void shouldMatchPipelinedRedisCommandsOneByOne() throws Exception {
        start(configuration().binaryMessageFraming(BinaryMessageFraming.REDIS));
        mock(redisCommand("INCR", "counter"), ":1\r\n".getBytes(StandardCharsets.US_ASCII));
        mock(redisCommand("GET", "counter"), "$1\r\n1\r\n".getBytes(StandardCharsets.US_ASCII));
        mock("PING\r\n".getBytes(StandardCharsets.US_ASCII), "+PONG\r\n".getBytes(StandardCharsets.US_ASCII));
        Socket session = connect();

        send(session, joined(redisCommand("INCR", "counter"), redisCommand("GET", "counter"), "PING\r\n".getBytes(StandardCharsets.US_ASCII)));

        receives(session, ":1\r\n$1\r\n1\r\n+PONG\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void shouldMatchALengthPrefixedMessageWrittenInTwoPartsWithAPauseAsOneAndMessagesWrittenTogetherOneByOne() throws Exception {
        start(configuration()
            .binaryMessageFraming(BinaryMessageFraming.LENGTH_PREFIX)
            .binaryMessageLengthPrefixBytes(2)
            .binaryMessageLengthPrefixByteOrder(ByteOrder.LITTLE_ENDIAN));
        byte[] large = lengthPrefixed(3000, 'l');
        byte[] first = lengthPrefixed(10, 'a');
        byte[] second = lengthPrefixed(0, 'b');
        mock(large, lengthPrefixed(1, 'L'));
        mock(first, lengthPrefixed(1, 'A'));
        mock(second, lengthPrefixed(1, 'B'));
        Socket session = connect();

        sendInTwoPartsWithAPause(session, large);
        receives(session, lengthPrefixed(1, 'L'));
        send(session, joined(first, second));

        receives(session, joined(lengthPrefixed(1, 'A'), lengthPrefixed(1, 'B')));
    }

    @Test
    public void shouldCloseAConnectionThatDeclaresAMessageOverMaxRequestBodySizeWithEachFraming() throws Exception {
        byte[][] headersOverTheLimit = {
            Arrays.copyOf(mysqlPacket(0, 1021, 'q'), 4),
            "*1\r\n$1019\r\n".getBytes(StandardCharsets.US_ASCII),
            Arrays.copyOf(lengthPrefixed(1023, 'q'), 2),
        };
        BinaryMessageFraming[] framings = {BinaryMessageFraming.MYSQL, BinaryMessageFraming.REDIS, BinaryMessageFraming.LENGTH_PREFIX};
        for (int i = 0; i < framings.length; i++) {
            start(configuration()
                .binaryMessageFraming(framings[i])
                .binaryMessageLengthPrefixBytes(2)
                .binaryMessageLengthPrefixByteOrder(ByteOrder.LITTLE_ENDIAN)
                .maxRequestBodySize(1024));
            Socket session = connect();

            send(session, headersOverTheLimit[i]);

            assertThat(framings[i] + ": closed on the declared length alone, with nothing written", session.getInputStream().read(), is(-1));
            stopQuietly(mockServer);
        }
    }
}
