package org.mockserver.netty.integration.proxy.direct;

import io.netty.buffer.ByteBufUtil;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.KeyStoreFactory;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockserver.configuration.Configuration.configuration;

/**
 * An upstream that turns TLS on part way through a connection, as PostgreSQL does. On each connection it reads
 * what comes first: an 8-byte {@code SSLRequest} is answered {@code S} or {@code N}; after {@code S} the SAME socket
 * becomes a TLS server, so the session continues on that connection. Anything else opens a session in the clear,
 * unless it has been told every connection starts with TLS.
 * <p>
 * It answers the messages it has been given answers for, however the bytes are split or joined, records per
 * connection what arrived in the clear and what over TLS, and can send bytes nobody asked for. Its certificate is
 * one MockServer's certificate authority issued, unless another key store is given.
 */
public class StartTlsUpstream implements AutoCloseable {

    public static final byte[] SSL_REQUEST = {0, 0, 0, 8, 4, (byte) 0xd2, 0x16, 0x2f};

    private final ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final List<byte[][]> answers = new CopyOnWriteArrayList<>();
    private volatile byte sslResponse = 'S';
    private volatile boolean startingWithTls;
    private volatile String[] tlsProtocols;
    private volatile boolean requireClientCertificate;
    private volatile KeyStore keyStore;
    private volatile char[] keyStorePassword;
    private volatile SSLContext sslContext;

    public StartTlsUpstream() throws IOException {
        Thread accept = new Thread(this::acceptConnections, "start-tls-upstream-accept");
        accept.setDaemon(true);
        accept.start();
    }

    /** Answers an {@code SSLRequest} with {@code S} (the default) or {@code N}. */
    public StartTlsUpstream answeringSslRequestWith(char answer) {
        this.sslResponse = (byte) answer;
        return this;
    }

    /** Every connection is TLS from its first byte, as for a client that opens with TLS: no SSLRequest is expected. */
    public StartTlsUpstream startingWithTls() {
        this.startingWithTls = true;
        return this;
    }

    public StartTlsUpstream answering(byte[] message, byte[] answer) {
        answers.add(new byte[][]{message, answer});
        return this;
    }

    public StartTlsUpstream withTlsProtocols(String... protocols) {
        this.tlsProtocols = protocols;
        return this;
    }

    /** A handshake without a client certificate fails; any certificate presented is accepted and recorded. */
    public StartTlsUpstream requiringClientCertificate() {
        this.requireClientCertificate = true;
        return this;
    }

    /** The key store whose key and certificate it presents, in place of one MockServer's certificate authority issued. */
    public StartTlsUpstream withServerKeyStore(KeyStore keyStore, char[] password) {
        this.keyStore = keyStore;
        this.keyStorePassword = password;
        return this;
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public List<Connection> connections() {
        return connections;
    }

    /** Waits until at least this many connections have been accepted, and returns the one at that position. */
    public Connection awaitConnection(int number, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (connections.size() < number) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("expected connection " + number + " within " + timeoutMillis + "ms but " + connections.size() + " were accepted");
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return connections.get(number - 1);
    }

    private void acceptConnections() {
        while (!serverSocket.isClosed()) {
            try {
                Connection connection = new Connection(serverSocket.accept());
                connections.add(connection);
                Thread serve = new Thread(connection::serve, "start-tls-upstream-serve");
                serve.setDaemon(true);
                serve.start();
            } catch (IOException closed) {
                // close() ends the accept
            }
        }
    }

    private synchronized SSLContext sslContext() throws Exception {
        if (sslContext == null) {
            KeyStore keys = keyStore;
            char[] password = keyStorePassword;
            if (keys == null) {
                keys = new KeyStoreFactory(configuration(), new MockServerLogger()).loadOrCreateKeyStore();
                password = KeyStoreFactory.KEY_STORE_PASSWORD.toCharArray();
            }
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), new TrustManager[]{new AcceptAnyClientCertificate()}, null);
            sslContext = context;
        }
        return sslContext;
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        for (Connection connection : connections) {
            connection.close();
        }
    }

    /** One accepted connection: what it received, how, and its way to send more. */
    public class Connection {

        private final Socket socket;
        private final ByteArrayOutputStream inTheClear = new ByteArrayOutputStream();
        private final ByteArrayOutputStream overTls = new ByteArrayOutputStream();
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final CountDownLatch ended = new CountDownLatch(1);
        private final Object writeLock = new Object();
        private volatile SSLSocket tls;
        private volatile String tlsProtocol;
        private volatile Certificate[] clientCertificates;
        private volatile List<String> serverNames;
        private volatile Exception handshakeFailure;

        private Connection(Socket socket) {
            this.socket = socket;
        }

        private void serve() {
            try {
                ByteArrayOutputStream pending = new ByteArrayOutputStream();
                InputStream clearInput = socket.getInputStream();
                byte[] buffer = new byte[16 * 1024];
                // read only as far as it takes to tell whether the connection opens with an SSLRequest
                while (!startingWithTls && pending.size() < SSL_REQUEST.length && startsWith(SSL_REQUEST, pending.toByteArray())) {
                    int read = clearInput.read(buffer, 0, SSL_REQUEST.length - pending.size());
                    if (read == -1) {
                        return;
                    }
                    pending.write(buffer, 0, read);
                    record(false, buffer, read);
                }
                InputStream input = clearInput;
                if (startingWithTls) {
                    input = upgrade();
                    if (input == null) {
                        return;
                    }
                } else if (Arrays.equals(pending.toByteArray(), SSL_REQUEST)) {
                    messages.add("clear " + ByteBufUtil.hexDump(SSL_REQUEST));
                    pending.reset();
                    write(new byte[]{sslResponse});
                    if (sslResponse == 'S') {
                        input = upgrade();
                        if (input == null) {
                            return;
                        }
                    }
                }
                answer(pending);
                for (int read; (read = input.read(buffer)) != -1; ) {
                    record(tls != null, buffer, read);
                    pending.write(buffer, 0, read);
                    answer(pending);
                }
            } catch (IOException closed) {
                // the peer, or close(), ended the connection
            } finally {
                close();
            }
        }

        private InputStream upgrade() throws IOException {
            try {
                SSLSocket upgraded = (SSLSocket) sslContext().getSocketFactory().createSocket(socket, null, true);
                upgraded.setUseClientMode(false);
                if (tlsProtocols != null) {
                    upgraded.setEnabledProtocols(tlsProtocols);
                }
                upgraded.setNeedClientAuth(requireClientCertificate);
                upgraded.startHandshake();
                tlsProtocol = upgraded.getSession().getProtocol();
                List<String> names = new ArrayList<>();
                for (SNIServerName name : ((ExtendedSSLSession) upgraded.getSession()).getRequestedServerNames()) {
                    names.add(((SNIHostName) name).getAsciiName());
                }
                serverNames = names;
                try {
                    clientCertificates = upgraded.getSession().getPeerCertificates();
                } catch (SSLPeerUnverifiedException none) {
                    // a client certificate was not asked for
                }
                synchronized (writeLock) {
                    tls = upgraded;
                }
                return upgraded.getInputStream();
            } catch (IOException failed) {
                handshakeFailure = failed;
                return null;
            } catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        }

        private void record(boolean secure, byte[] buffer, int length) {
            ByteArrayOutputStream received = secure ? overTls : inTheClear;
            synchronized (received) {
                received.write(buffer, 0, length);
            }
        }

        /** Answers every message at the start of what is pending; bytes that can begin none are dropped. */
        private void answer(ByteArrayOutputStream pending) throws IOException {
            byte[] bytes = pending.toByteArray();
            int start = 0;
            boolean progress = true;
            while (progress && start < bytes.length) {
                progress = false;
                byte[] rest = Arrays.copyOfRange(bytes, start, bytes.length);
                for (byte[][] answer : answers) {
                    if (startsWith(rest, answer[0])) {
                        messages.add((tls != null ? "TLS " : "clear ") + ByteBufUtil.hexDump(answer[0]));
                        write(answer[1]);
                        start += answer[0].length;
                        progress = true;
                        break;
                    }
                }
                if (!progress && !couldBeginAMessage(rest)) {
                    // skipped up to where a message it knows could begin
                    int skip = 1;
                    while (skip < rest.length && !couldBeginAMessage(Arrays.copyOfRange(rest, skip, rest.length))) {
                        skip++;
                    }
                    messages.add((tls != null ? "TLS unanswered " : "clear unanswered ") + ByteBufUtil.hexDump(rest, 0, skip));
                    start += skip;
                    progress = true;
                }
            }
            pending.reset();
            pending.write(bytes, start, bytes.length - start);
        }

        private boolean couldBeginAMessage(byte[] bytes) {
            for (byte[][] answer : answers) {
                if (startsWith(answer[0], bytes) || startsWith(bytes, answer[0])) {
                    return true;
                }
            }
            return false;
        }

        private void write(byte[] bytes) throws IOException {
            synchronized (writeLock) {
                OutputStream output = tls != null ? tls.getOutputStream() : socket.getOutputStream();
                output.write(bytes);
                output.flush();
            }
        }

        /** Sends bytes nobody asked for, over TLS once the connection has upgraded, else in the clear. */
        public void push(byte[] bytes) throws IOException {
            write(bytes);
        }

        public byte[] receivedInTheClear() {
            synchronized (inTheClear) {
                return inTheClear.toByteArray();
            }
        }

        public byte[] receivedOverTls() {
            synchronized (overTls) {
                return overTls.toByteArray();
            }
        }

        /** Each message answered, in order, as "clear" or "TLS" then its hex; bytes no answer fits are "unanswered". */
        public List<String> messages() {
            return new ArrayList<>(messages);
        }

        public boolean upgraded() {
            return tls != null;
        }

        /** The TLS protocol negotiated, or null before an upgrade. */
        public String tlsProtocol() {
            return tlsProtocol;
        }

        /** The server names (SNI) the TLS client asked for, or null before an upgrade. */
        public List<String> serverNames() {
            return serverNames;
        }

        /** The client's certificate chain, or null when none was asked for or the connection is in the clear. */
        public X509Certificate[] clientCertificates() {
            Certificate[] chain = clientCertificates;
            return chain == null ? null : Arrays.copyOf(chain, chain.length, X509Certificate[].class);
        }

        /** Why the handshake after {@code S} failed, or null. */
        public Exception handshakeFailure() {
            return handshakeFailure;
        }

        /** Waits for the connection to end, by the peer or by {@link #close()}. */
        public boolean awaitEnded(long timeoutMillis) throws InterruptedException {
            return ended.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        public void close() {
            try {
                if (tls != null) {
                    tls.close();
                }
                socket.close();
            } catch (IOException ignored) {
                // closing what may already be closed
            } finally {
                ended.countDown();
            }
        }
    }

    /**
     * Starts TLS as a client on a connection already open, trusting only MockServer's certificate authority, so a
     * handshake that completes was with a certificate it issued. A host name, not an IP address, is sent as SNI.
     */
    public static SSLSocket startTlsAsClient(Socket socket, String host, String protocol, boolean withClientCertificate) throws Exception {
        return startTlsAsClient(socket, host, protocol, withClientCertificate, null);
    }

    /** As above, sending {@code serverName} as SNI, which the JDK does not do by itself for a name without a dot. */
    public static SSLSocket startTlsAsClient(Socket socket, String host, String protocol, boolean withClientCertificate, String serverName) throws Exception {
        KeyStore keyStore = new KeyStoreFactory(configuration(), new MockServerLogger()).loadOrCreateKeyStore();
        KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
        trusted.load(null, null);
        trusted.setCertificateEntry("mockserver-ca", keyStore.getCertificate(KeyStoreFactory.KEY_STORE_CA_ALIAS));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore, KeyStoreFactory.KEY_STORE_PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(withClientCertificate ? keys.getKeyManagers() : null, trust.getTrustManagers(), null);
        SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(socket, host, socket.getPort(), true);
        tls.setUseClientMode(true);
        tls.setEnabledProtocols(new String[]{protocol});
        if (serverName != null) {
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setServerNames(Collections.singletonList(new SNIHostName(serverName)));
            tls.setSSLParameters(parameters);
        }
        tls.startHandshake();
        return tls;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    /** Any client certificate is accepted: what the test checks is which one was presented. */
    private static class AcceptAnyClientCertificate implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            throw new UnsupportedOperationException("a server only");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
