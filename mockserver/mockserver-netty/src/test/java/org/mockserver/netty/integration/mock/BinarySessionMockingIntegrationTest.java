package org.mockserver.netty.integration.mock;

import io.netty.buffer.ByteBufUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.BinaryResponse;
import org.mockserver.socket.PortFactory;
import org.mockserver.socket.tls.KeyStoreFactory;
import org.slf4j.event.Level;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.BinaryRequestDefinition.binaryRequest;
import static org.mockserver.model.BinaryResponse.binaryResponse;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A whole session of a binary protocol mocked with binary expectations, shaped as a PostgreSQL client's is: many
 * small messages, then larger ones, and messages that have no reply. Each message must be matched as one message,
 * whatever came before it on the connection: in the clear, over TLS turned on part way through, and over a
 * connection that started with TLS. The messages are PostgreSQL's in outline only; the client is the JDK's.
 */
public class BinarySessionMockingIntegrationTest {

    private static final byte[] SSL_REQUEST = {0, 0, 0, 8, 4, (byte) 0xd2, 0x16, 0x2f};
    private static final byte[] STARTUP = {0, 0, 0, 23, 0, 3, 0, 0, 'u', 's', 'e', 'r', 0, 'p', 'o', 's', 't', 'g', 'r', 'e', 's', 0, 0};
    private static final byte[] AUTHENTICATION_OK_AND_READY = {'R', 0, 0, 0, 8, 0, 0, 0, 0, 'Z', 0, 0, 0, 5, 'I'};
    private static final byte[] SYNC = {'S', 0, 0, 0, 4};
    private static final byte[] READY = {'Z', 0, 0, 0, 5, 'I'};
    // messages a server does not answer
    private static final byte[] FLUSH = {'H', 0, 0, 0, 4};
    private static final byte[] COPY_DONE = {'c', 0, 0, 0, 4};
    private static final byte[] COPY_FAIL = {'f', 0, 0, 0, 5, 0};
    private static final int SMALL_EXCHANGES_FIRST = 60;
    private static final int[] LARGER_MESSAGES = {100, 422, 800, 3000};

    private enum Transport {
        IN_THE_CLEAR(null, false),
        TLS_1_2_TURNED_ON_PART_WAY("TLSv1.2", true),
        TLS_1_3_TURNED_ON_PART_WAY("TLSv1.3", true),
        TLS_1_2_FROM_THE_START("TLSv1.2", false),
        TLS_1_3_FROM_THE_START("TLSv1.3", false);

        private final String tlsProtocol;
        private final boolean upgrade;

        Transport(String tlsProtocol, boolean upgrade) {
            this.tlsProtocol = tlsProtocol;
            this.upgrade = upgrade;
        }
    }

    private ClientAndServer mockServer;
    private final List<Socket> sockets = new ArrayList<>();

    @Before
    public void startMockServer() {
        // INFO on this instance whatever the JVM's default is: one test waits for what MockServer logs
        mockServer = startClientAndServer(configuration().logLevel(Level.INFO), PortFactory.findFreePort());
        mock(SSL_REQUEST, new byte[]{'S'});
        mock(STARTUP, AUTHENTICATION_OK_AND_READY);
        mock(SYNC, READY);
    }

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

    private void mock(byte[] request, byte[] response) {
        mockServer.upsert(new Expectation(binaryRequest(request)).thenRespondWithBinary(binaryResponse(response)));
    }

    private static byte[] messageOf(int length) {
        // its own length is in each message, so that no piece of one message is another whole message
        byte[] message = new byte[length];
        Arrays.fill(message, (byte) 'm');
        message[0] = 'Q';
        message[1] = (byte) (length >> 8);
        message[2] = (byte) length;
        return message;
    }

    private static byte[] replyTo(int length) {
        return new byte[]{'C', (byte) (length >> 8), (byte) length};
    }

    private <T extends Socket> T opened(T socket) throws IOException {
        sockets.add(socket);
        socket.setSoTimeout(10_000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    /** As the JDK's own TLS client, trusting only MockServer's certificate authority and sending no certificate. */
    private SSLSocket startTls(Socket socket, String protocol) throws Exception {
        KeyStore keyStore = new KeyStoreFactory(configuration(), new MockServerLogger()).loadOrCreateKeyStore();
        KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
        trusted.load(null, null);
        trusted.setCertificateEntry("mockserver-ca", keyStore.getCertificate(KeyStoreFactory.KEY_STORE_CA_ALIAS));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(socket, "127.0.0.1", socket.getPort(), true);
        sockets.add(tls);
        tls.setUseClientMode(true);
        tls.setEnabledProtocols(new String[]{protocol});
        tls.startHandshake();
        return tls;
    }

    /** A connection on which the next thing the client sends is the first message of its session. */
    private Socket connect(Transport transport) throws Exception {
        return connect(transport, new Socket("127.0.0.1", mockServer.getLocalPort()));
    }

    private Socket connect(Transport transport, Socket connected) throws Exception {
        Socket socket = opened(connected);
        if (transport.upgrade) {
            exchange(socket, SSL_REQUEST, new byte[]{'S'});
        }
        return transport.tlsProtocol != null ? startTls(socket, transport.tlsProtocol) : socket;
    }

    private static void send(Socket socket, byte[] message) throws IOException {
        // one write, so that the client itself never sends a message in pieces
        socket.getOutputStream().write(message);
        socket.getOutputStream().flush();
    }

    /** Sends one message and reads exactly the reply expected, which fails on anything else or on nothing. */
    private static void exchange(Socket socket, byte[] message, byte[] expectedReply) throws IOException {
        send(socket, message);
        assertThat("reply to a message of " + message.length + " byte(s)", ByteBufUtil.hexDump(socket.getInputStream().readNBytes(expectedReply.length)), is(ByteBufUtil.hexDump(expectedReply)));
    }

    private void matchLargerMessagesWholeAfterManySmallOnes(Transport transport) throws Exception {
        for (int length : LARGER_MESSAGES) {
            mock(messageOf(length), replyTo(length));
        }
        for (int length : LARGER_MESSAGES) {
            Socket session = connect(transport);
            exchange(session, STARTUP, AUTHENTICATION_OK_AND_READY);
            for (int i = 0; i < SMALL_EXCHANGES_FIRST; i++) {
                exchange(session, SYNC, READY);
            }

            exchange(session, messageOf(length), replyTo(length));

            // nothing more was written, and the connection was not closed
            exchange(session, SYNC, READY);
            session.close();
        }
    }

    @Test
    public void shouldMatchLargerMessagesWholeAfterManySmallOnesInTheClear() throws Exception {
        matchLargerMessagesWholeAfterManySmallOnes(Transport.IN_THE_CLEAR);
    }

    @Test
    public void shouldMatchLargerMessagesWholeAfterManySmallOnesOverTls12TurnedOnPartWay() throws Exception {
        matchLargerMessagesWholeAfterManySmallOnes(Transport.TLS_1_2_TURNED_ON_PART_WAY);
    }

    @Test
    public void shouldMatchLargerMessagesWholeAfterManySmallOnesOverTls13TurnedOnPartWay() throws Exception {
        matchLargerMessagesWholeAfterManySmallOnes(Transport.TLS_1_3_TURNED_ON_PART_WAY);
    }

    @Test
    public void shouldMatchLargerMessagesWholeAfterManySmallOnesOverTls12FromTheStart() throws Exception {
        matchLargerMessagesWholeAfterManySmallOnes(Transport.TLS_1_2_FROM_THE_START);
    }

    @Test
    public void shouldMatchLargerMessagesWholeAfterManySmallOnesOverTls13FromTheStart() throws Exception {
        matchLargerMessagesWholeAfterManySmallOnes(Transport.TLS_1_3_FROM_THE_START);
    }

    private void matchAMessageOf1200BytesStraightAfterTheHandshake(Transport transport) throws Exception {
        mock(messageOf(1200), replyTo(1200));
        // more than once: each connection's handshake is read in its own pattern of reads
        for (int connection = 0; connection < 5; connection++) {
            Socket session = connect(transport);

            exchange(session, messageOf(1200), replyTo(1200));

            exchange(session, SYNC, READY);
            session.close();
        }
    }

    @Test
    public void shouldMatchAFirstMessageOf1200BytesStraightAfterAHandshakeOfTls12TurnedOnPartWay() throws Exception {
        matchAMessageOf1200BytesStraightAfterTheHandshake(Transport.TLS_1_2_TURNED_ON_PART_WAY);
    }

    @Test
    public void shouldMatchAFirstMessageOf1200BytesStraightAfterAHandshakeOfTls13TurnedOnPartWay() throws Exception {
        matchAMessageOf1200BytesStraightAfterTheHandshake(Transport.TLS_1_3_TURNED_ON_PART_WAY);
    }

    @Test
    public void shouldMatchAMessageOf3000BytesStraightAfterTheFirstMessageInTheClear() throws Exception {
        mock(messageOf(3000), replyTo(3000));
        Socket session = connect(Transport.IN_THE_CLEAR);
        exchange(session, STARTUP, AUTHENTICATION_OK_AND_READY);

        exchange(session, messageOf(3000), replyTo(3000));

        exchange(session, SYNC, READY);
    }

    private void matchAFirstMessageLargerThanTheReadThatShowsTheConnectionIsBinary(Transport transport, int length) throws Exception {
        mock(messageOf(length), replyTo(length));
        for (int connection = 0; connection < 5; connection++) {
            Socket session = connect(transport);

            exchange(session, messageOf(length), replyTo(length));

            exchange(session, SYNC, READY);
            session.close();
        }
    }

    @Test
    public void shouldMatchAFirstMessageOf3000BytesInTheClear() throws Exception {
        matchAFirstMessageLargerThanTheReadThatShowsTheConnectionIsBinary(Transport.IN_THE_CLEAR, 3000);
    }

    @Test
    public void shouldMatchAFirstMessageOf1200BytesOverTlsFromTheStart() throws Exception {
        matchAFirstMessageLargerThanTheReadThatShowsTheConnectionIsBinary(Transport.TLS_1_2_FROM_THE_START, 1200);
        matchAFirstMessageLargerThanTheReadThatShowsTheConnectionIsBinary(Transport.TLS_1_3_FROM_THE_START, 1200);
    }

    @Test
    public void shouldMatchAFirstMessageOf3000BytesOverTlsFromTheStart() throws Exception {
        matchAFirstMessageLargerThanTheReadThatShowsTheConnectionIsBinary(Transport.TLS_1_2_FROM_THE_START, 3000);
        matchAFirstMessageLargerThanTheReadThatShowsTheConnectionIsBinary(Transport.TLS_1_3_FROM_THE_START, 3000);
    }

    /** A socket that can write what it is given in two parts with a pause between, as a slow network delivers it. */
    private static class TwoPartSocket extends Socket {

        private volatile boolean inTwoParts;

        private TwoPartSocket(String host, int port) throws IOException {
            super(host, port);
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            OutputStream out = super.getOutputStream();
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    out.write(b);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (inTwoParts && length > 1) {
                        out.write(bytes, offset, length / 2);
                        out.flush();
                        try {
                            TimeUnit.MILLISECONDS.sleep(50);
                        } catch (InterruptedException interrupted) {
                            throw new IOException(interrupted);
                        }
                        out.write(bytes, offset + length / 2, length - length / 2);
                    } else {
                        out.write(bytes, offset, length);
                    }
                }

                @Override
                public void flush() throws IOException {
                    out.flush();
                }
            };
        }
    }

    private void matchAMessageWhoseTlsRecordArrivesInTwoParts(Transport transport) throws Exception {
        mock(messageOf(800), replyTo(800));
        TwoPartSocket socket = new TwoPartSocket("127.0.0.1", mockServer.getLocalPort());
        Socket session = connect(transport, socket);
        exchange(session, STARTUP, AUTHENTICATION_OK_AND_READY);

        // TLS hands nothing over until the record is whole, so however it arrives the message is one message
        socket.inTwoParts = true;
        exchange(session, messageOf(800), replyTo(800));
        socket.inTwoParts = false;

        exchange(session, SYNC, READY);
    }

    @Test
    public void shouldMatchAMessageWhoseTlsRecordArrivesInTwoPartsOverTls12TurnedOnPartWay() throws Exception {
        matchAMessageWhoseTlsRecordArrivesInTwoParts(Transport.TLS_1_2_TURNED_ON_PART_WAY);
    }

    @Test
    public void shouldMatchAMessageWhoseTlsRecordArrivesInTwoPartsOverTls13TurnedOnPartWay() throws Exception {
        matchAMessageWhoseTlsRecordArrivesInTwoParts(Transport.TLS_1_3_TURNED_ON_PART_WAY);
    }

    @Test
    public void shouldMatchAMessageWhoseTlsRecordArrivesInTwoPartsOverTls13FromTheStart() throws Exception {
        matchAMessageWhoseTlsRecordArrivesInTwoParts(Transport.TLS_1_3_FROM_THE_START);
    }

    @Test
    public void shouldLogAMessageOnceHoweverManyReadsItTook() throws Exception {
        mock(messageOf(3000), replyTo(3000));
        Socket session = connect(Transport.IN_THE_CLEAR);

        // read as 2,048 bytes and then the rest: the connection is not known to be binary until the first read
        exchange(session, messageOf(3000), replyTo(3000));
        exchange(session, SYNC, READY);

        long received = Arrays.stream(mockServer.retrieveLogMessagesArray(null)).filter(logged -> logged.contains("received binary request")).count();
        assertThat("one log entry for each message the client sent", received, is(2L));
    }

    private long timesNothingWasReturned() {
        return Arrays.stream(mockServer.retrieveLogMessagesArray(null)).filter(logged -> logged.contains("returning nothing, as the binary mock response is empty")).count();
    }

    /**
     * Sends a message that has no reply and waits until MockServer has taken it as a message. The client has no
     * reply to wait for, and what it sent next could otherwise be read together with it, as one message.
     */
    private void sendMessageWithNoReply(Socket session, byte[] message) throws Exception {
        long before = timesNothingWasReturned();
        send(session, message);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (timesNothingWasReturned() == before) {
            assertThat("MockServer matched " + ByteBufUtil.hexDump(message) + " within 20 seconds", System.nanoTime() < deadline, is(true));
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private void writeNothingAndKeepTheConnectionOpen(Transport transport) throws Exception {
        Socket session = connect(transport);
        exchange(session, STARTUP, AUTHENTICATION_OK_AND_READY);

        for (byte[] noReply : Arrays.asList(FLUSH, COPY_DONE, COPY_FAIL)) {
            sendMessageWithNoReply(session, noReply);
            // the next bytes the client reads are the reply to the next message: nothing was written, nothing closed
            exchange(session, SYNC, READY);
        }
    }

    /** The three ways an expectation can say "no reply", which MockServer must treat alike. */
    private void mockMessagesThatHaveNoReply() throws Exception {
        // through the Java client an empty array is dropped on the way and reaches MockServer as no data at all
        mockServer.upsert(new Expectation(binaryRequest(FLUSH)).thenRespondWithBinary(binaryResponse(new byte[0])));
        mockServer.upsert(new Expectation(binaryRequest(COPY_DONE)).thenRespondWithBinary(binaryResponse()));
        // a client that does send the empty value: it reaches MockServer as an empty array
        String json = "{\"httpRequest\":{\"binaryData\":\"" + Base64.getEncoder().encodeToString(COPY_FAIL) + "\"},\"binaryResponse\":{\"binaryData\":\"\"}}";
        HttpResponse<String> created = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mockServer.getLocalPort() + "/mockserver/expectation")).PUT(HttpRequest.BodyPublishers.ofString(json)).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(created.body(), created.statusCode(), is(201));
    }

    @Test
    public void shouldWriteNothingAndKeepTheConnectionOpenForAMessageWithNoReplyInTheClear() throws Exception {
        mockMessagesThatHaveNoReply();

        writeNothingAndKeepTheConnectionOpen(Transport.IN_THE_CLEAR);
    }

    @Test
    public void shouldWriteNothingAndKeepTheConnectionOpenForAMessageWithNoReplyOverTlsTurnedOnPartWay() throws Exception {
        mockMessagesThatHaveNoReply();

        writeNothingAndKeepTheConnectionOpen(Transport.TLS_1_3_TURNED_ON_PART_WAY);
    }

    private static final String UNKNOWN_MESSAGE_FORMAT = "unknown message format, only HTTP requests are supported for mocking or HTTP & binary requests for proxying, but request is not being proxied and request is not valid HTTP";

    private static boolean endOfStreamOrReset(Socket socket) throws IOException {
        try {
            return socket.getInputStream().read() == -1;
        } catch (SocketException reset) {
            // a close with bytes still unread on MockServer's side is a reset
            return true;
        }
    }

    @Test
    public void shouldAnswerAndCloseQuietlyWhenNoExpectationMatchesAMessageThatAHandshakeFollows() throws Exception {
        SSLEngine client = SSLContext.getDefault().createSSLEngine();
        client.setUseClientMode(true);
        client.beginHandshake();
        ByteBuffer clientHello = ByteBuffer.allocate(client.getSession().getPacketBufferSize());
        assertThat(client.wrap(ByteBuffer.allocate(0), clientHello).getStatus(), is(SSLEngineResult.Status.OK));
        // it exactly fills the connection's first read, so the handshake starts the next read of the same loop
        byte[] unmatched = new byte[2048];
        Arrays.fill(unmatched, (byte) 'u');
        ByteArrayOutputStream onTheWire = new ByteArrayOutputStream();
        onTheWire.write(unmatched);
        onTheWire.write(clientHello.array(), 0, clientHello.position());
        for (int connection = 0; connection < 5; connection++) {
            Socket session = opened(new Socket("127.0.0.1", mockServer.getLocalPort()));

            send(session, onTheWire.toByteArray());

            byte[] reply = session.getInputStream().readNBytes(UNKNOWN_MESSAGE_FORMAT.length());
            assertThat("the answer, in the clear", new String(reply, StandardCharsets.UTF_8), is(UNKNOWN_MESSAGE_FORMAT));
            assertThat("then the close, with no handshake begun", endOfStreamOrReset(session), is(true));
        }
        assertThat("one answer for each connection", timesAnUnknownMessageWasAnswered(), is(5L));
        assertThat(faultsLogged(), is(empty()));
    }

    @Test
    public void shouldDropWhatFollowsBytesHeldAsAPossibleHandshakeWhenNoExpectationMatchesThem() throws Exception {
        Socket session = connect(Transport.IN_THE_CLEAR);
        exchange(session, STARTUP, AUTHENTICATION_OK_AND_READY);

        // held, as the possible start of a TLS handshake, until what follows shows it is a message of its own
        send(session, new byte[]{22});
        TimeUnit.MILLISECONDS.sleep(300);
        send(session, new byte[]{'x', 'x', 'x'});

        byte[] reply = session.getInputStream().readNBytes(UNKNOWN_MESSAGE_FORMAT.length());
        assertThat("the held byte is answered", new String(reply, StandardCharsets.UTF_8), is(UNKNOWN_MESSAGE_FORMAT));
        assertThat("and the connection closed", endOfStreamOrReset(session), is(true));
        // what followed it, read in the same loop, would be answered on the closed connection straight after
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat("what followed it is not taken as a message", timesAnUnknownMessageWasAnswered(), is(1L));
        assertThat(faultsLogged(), is(empty()));
    }

    private long timesAnUnknownMessageWasAnswered() {
        return Arrays.stream(mockServer.retrieveLogMessagesArray(null)).filter(logged -> logged.contains("unknown message format")).count();
    }

    private List<String> faultsLogged() {
        return Arrays.stream(mockServer.retrieveLogMessagesArray(null))
            .filter(logged -> logged.contains("Exception") || logged.contains("caught by port unification handler"))
            .collect(Collectors.toList());
    }

    @Test
    public void shouldReturnAnEmptyBinaryResponseAsOneWithNoData() throws Exception {
        mockMessagesThatHaveNoReply();

        for (byte[] request : Arrays.asList(FLUSH, COPY_DONE, COPY_FAIL)) {
            List<Expectation> retrieved = Arrays.stream(mockServer.retrieveActiveExpectations(null))
                .filter(expectation -> binaryRequest(request).equals(expectation.getHttpRequest()))
                .collect(Collectors.toList());
            assertThat(ByteBufUtil.hexDump(request), retrieved.size(), is(1));
            BinaryResponse response = retrieved.get(0).getBinaryResponse();
            assertThat("the expectation is still one that answers, with nothing", response, is(notNullValue()));
            assertThat(ByteBufUtil.hexDump(request), response.getBinaryData(), is(nullValue()));
        }
    }
}
