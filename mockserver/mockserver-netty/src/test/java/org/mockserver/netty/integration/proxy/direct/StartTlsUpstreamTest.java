package org.mockserver.netty.integration.proxy.direct;

import io.netty.buffer.ByteBufUtil;
import org.junit.After;
import org.junit.Test;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.netty.integration.proxy.direct.StartTlsUpstream.SSL_REQUEST;

/**
 * The test upstream itself, driven by the JDK's own sockets: what a test of MockServer through it relies on.
 */
public class StartTlsUpstreamTest {

    private static final byte[] STARTUP = {0, 0, 0, 23, 0, 3, 0, 0, 'u', 's', 'e', 'r', 0, 'p', 'o', 's', 't', 'g', 'r', 'e', 's', 0, 0};
    private static final byte[] AUTHENTICATION_OK_AND_READY = {'R', 0, 0, 0, 8, 0, 0, 0, 0, 'Z', 0, 0, 0, 5, 'I'};
    private static final byte[] SYNC = {'S', 0, 0, 0, 4};
    private static final byte[] READY = {'Z', 0, 0, 0, 5, 'I'};
    private static final byte[] NOTIFICATION = {'A', 0, 0, 0, 10, 0, 0, 0, 1, 'c', 0, 0};

    private final List<Socket> sockets = new ArrayList<>();
    private StartTlsUpstream upstream;

    @After
    public void closeEverything() throws IOException {
        for (Socket socket : sockets) {
            socket.close();
        }
        if (upstream != null) {
            upstream.close();
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket("127.0.0.1", upstream.port());
        sockets.add(socket);
        socket.setSoTimeout(10_000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private static void exchange(Socket socket, byte[] message, byte[] expectedReply) throws IOException {
        socket.getOutputStream().write(message);
        socket.getOutputStream().flush();
        assertThat(ByteBufUtil.hexDump(socket.getInputStream().readNBytes(expectedReply.length)), is(ByteBufUtil.hexDump(expectedReply)));
    }

    private SSLSocket startTls(Socket socket, String protocol, boolean withClientCertificate) throws Exception {
        SSLSocket tls = StartTlsUpstream.startTlsAsClient(socket, "127.0.0.1", protocol, withClientCertificate);
        sockets.add(tls);
        return tls;
    }

    @Test
    public void shouldUpgradeTheSameConnectionToTlsAfterAnsweringS() throws Exception {
        upstream = new StartTlsUpstream().answering(STARTUP, AUTHENTICATION_OK_AND_READY).answering(SYNC, READY);
        Socket socket = connect();

        exchange(socket, SSL_REQUEST, new byte[]{'S'});
        SSLSocket tls = startTls(socket, "TLSv1.3", false);
        exchange(tls, STARTUP, AUTHENTICATION_OK_AND_READY);
        exchange(tls, SYNC, READY);

        StartTlsUpstream.Connection connection = upstream.awaitConnection(1, 10_000);
        assertThat(upstream.connections().size(), is(1));
        assertThat(connection.upgraded(), is(true));
        assertThat(connection.tlsProtocol(), is("TLSv1.3"));
        assertThat(ByteBufUtil.hexDump(connection.receivedInTheClear()), is(ByteBufUtil.hexDump(SSL_REQUEST)));
        assertThat(ByteBufUtil.hexDump(connection.receivedOverTls()), is(ByteBufUtil.hexDump(STARTUP) + ByteBufUtil.hexDump(SYNC)));
        assertThat(connection.messages(), contains(
            "clear " + ByteBufUtil.hexDump(SSL_REQUEST),
            "TLS " + ByteBufUtil.hexDump(STARTUP),
            "TLS " + ByteBufUtil.hexDump(SYNC)
        ));
        assertThat(connection.clientCertificates(), is(nullValue()));
    }

    @Test
    public void shouldNegotiateTls12WhenThatIsAllItOffers() throws Exception {
        upstream = new StartTlsUpstream().withTlsProtocols("TLSv1.2").answering(SYNC, READY);
        Socket socket = connect();

        exchange(socket, SSL_REQUEST, new byte[]{'S'});
        exchange(startTls(socket, "TLSv1.2", false), SYNC, READY);

        assertThat(upstream.awaitConnection(1, 10_000).tlsProtocol(), is("TLSv1.2"));
    }

    @Test
    public void shouldStayInTheClearAfterAnsweringN() throws Exception {
        upstream = new StartTlsUpstream().answeringSslRequestWith('N').answering(STARTUP, AUTHENTICATION_OK_AND_READY);
        Socket socket = connect();

        exchange(socket, SSL_REQUEST, new byte[]{'N'});
        exchange(socket, STARTUP, AUTHENTICATION_OK_AND_READY);

        StartTlsUpstream.Connection connection = upstream.awaitConnection(1, 10_000);
        assertThat(connection.upgraded(), is(false));
        assertThat(ByteBufUtil.hexDump(connection.receivedInTheClear()), is(ByteBufUtil.hexDump(SSL_REQUEST) + ByteBufUtil.hexDump(STARTUP)));
        assertThat(connection.receivedOverTls().length, is(0));
    }

    @Test
    public void shouldServeAConnectionThatDoesNotAskForTlsInTheClear() throws Exception {
        upstream = new StartTlsUpstream().answering(STARTUP, AUTHENTICATION_OK_AND_READY).answering(SYNC, READY);
        Socket socket = connect();

        exchange(socket, STARTUP, AUTHENTICATION_OK_AND_READY);
        exchange(socket, SYNC, READY);

        StartTlsUpstream.Connection connection = upstream.awaitConnection(1, 10_000);
        assertThat(connection.upgraded(), is(false));
        assertThat(connection.messages(), contains("clear " + ByteBufUtil.hexDump(STARTUP), "clear " + ByteBufUtil.hexDump(SYNC)));
    }

    @Test
    public void shouldBeTlsFromTheFirstByteWhenToldConnectionsStartWithTls() throws Exception {
        upstream = new StartTlsUpstream().startingWithTls().answering(STARTUP, AUTHENTICATION_OK_AND_READY);
        Socket socket = connect();

        exchange(startTls(socket, "TLSv1.3", false), STARTUP, AUTHENTICATION_OK_AND_READY);

        StartTlsUpstream.Connection connection = upstream.awaitConnection(1, 10_000);
        assertThat(connection.upgraded(), is(true));
        assertThat(connection.receivedInTheClear().length, is(0));
        assertThat(connection.messages(), contains("TLS " + ByteBufUtil.hexDump(STARTUP)));
    }

    @Test
    public void shouldAnswerMessagesHoweverTheyAreSplitOrJoined() throws Exception {
        upstream = new StartTlsUpstream().answering(STARTUP, AUTHENTICATION_OK_AND_READY).answering(SYNC, READY);
        Socket socket = connect();
        exchange(socket, SSL_REQUEST, new byte[]{'S'});
        SSLSocket tls = startTls(socket, "TLSv1.3", false);

        // two messages in one write
        byte[] joined = new byte[STARTUP.length + SYNC.length];
        System.arraycopy(STARTUP, 0, joined, 0, STARTUP.length);
        System.arraycopy(SYNC, 0, joined, STARTUP.length, SYNC.length);
        tls.getOutputStream().write(joined);
        tls.getOutputStream().flush();
        assertThat(ByteBufUtil.hexDump(tls.getInputStream().readNBytes(AUTHENTICATION_OK_AND_READY.length + READY.length)), is(ByteBufUtil.hexDump(AUTHENTICATION_OK_AND_READY) + ByteBufUtil.hexDump(READY)));
        // one message a byte at a time
        for (byte b : SYNC) {
            tls.getOutputStream().write(b);
            tls.getOutputStream().flush();
        }
        assertThat(ByteBufUtil.hexDump(tls.getInputStream().readNBytes(READY.length)), is(ByteBufUtil.hexDump(READY)));
    }

    @Test
    public void shouldRecordWhatNoAnswerFitsAndCarryOn() throws Exception {
        upstream = new StartTlsUpstream().answering(SYNC, READY);
        Socket socket = connect();
        exchange(socket, SSL_REQUEST, new byte[]{'S'});
        SSLSocket tls = startTls(socket, "TLSv1.3", false);

        tls.getOutputStream().write(STARTUP);
        exchange(tls, SYNC, READY);

        assertThat(upstream.awaitConnection(1, 10_000).messages(), contains(
            "clear " + ByteBufUtil.hexDump(SSL_REQUEST),
            "TLS unanswered " + ByteBufUtil.hexDump(STARTUP),
            "TLS " + ByteBufUtil.hexDump(SYNC)
        ));
    }

    @Test
    public void shouldSendBytesNobodyAskedForOverTheUpgradedConnection() throws Exception {
        upstream = new StartTlsUpstream().answering(STARTUP, AUTHENTICATION_OK_AND_READY);
        Socket socket = connect();
        exchange(socket, SSL_REQUEST, new byte[]{'S'});
        SSLSocket tls = startTls(socket, "TLSv1.3", false);
        exchange(tls, STARTUP, AUTHENTICATION_OK_AND_READY);

        upstream.awaitConnection(1, 10_000).push(NOTIFICATION);

        assertThat(ByteBufUtil.hexDump(tls.getInputStream().readNBytes(NOTIFICATION.length)), is(ByteBufUtil.hexDump(NOTIFICATION)));
    }

    @Test
    public void shouldRecordTheClientCertificateItRequires() throws Exception {
        upstream = new StartTlsUpstream().requiringClientCertificate().answering(SYNC, READY);
        Socket socket = connect();
        exchange(socket, SSL_REQUEST, new byte[]{'S'});

        exchange(startTls(socket, "TLSv1.3", true), SYNC, READY);

        StartTlsUpstream.Connection connection = upstream.awaitConnection(1, 10_000);
        assertThat(connection.clientCertificates(), is(notNullValue()));
        assertThat(connection.clientCertificates()[0].getIssuerX500Principal().getName(), containsString("MockServer"));
    }

    @Test
    public void shouldFailTheHandshakeOfAClientWithoutTheCertificateItRequires() throws Exception {
        upstream = new StartTlsUpstream().requiringClientCertificate().answering(SYNC, READY);
        Socket socket = connect();
        exchange(socket, SSL_REQUEST, new byte[]{'S'});

        String outcome;
        try {
            SSLSocket tls = startTls(socket, "TLSv1.3", false);
            // TLS 1.3 reports the refusal on the client's first read, not in its handshake
            tls.getOutputStream().write(SYNC);
            tls.getOutputStream().flush();
            outcome = tls.getInputStream().read() == -1 ? "closed" : "answered";
        } catch (IOException refused) {
            outcome = "refused";
        }

        assertThat(outcome.equals("answered"), is(false));
        StartTlsUpstream.Connection connection = upstream.awaitConnection(1, 10_000);
        assertThat(connection.awaitEnded(10_000), is(true));
        assertThat(connection.handshakeFailure(), is(notNullValue()));
        assertThat(connection.upgraded(), is(false));
    }

    @Test
    public void shouldEndTheClientsConnectionWhenClosed() throws Exception {
        upstream = new StartTlsUpstream().answering(SYNC, READY);
        Socket socket = connect();
        exchange(socket, SSL_REQUEST, new byte[]{'S'});
        SSLSocket tls = startTls(socket, "TLSv1.3", false);
        exchange(tls, SYNC, READY);

        upstream.awaitConnection(1, 10_000).close();

        assertThat(tls.getInputStream().read(), is(-1));
    }
}
