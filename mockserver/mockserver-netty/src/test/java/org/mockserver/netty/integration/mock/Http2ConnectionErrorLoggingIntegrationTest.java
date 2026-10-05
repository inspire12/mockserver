package org.mockserver.netty.integration.mock;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * What an HTTP/2 connection made straight to MockServer logs when the connection fails, over cleartext HTTP/2 and
 * over TLS: one entry in MockServer's own log at a level that fits the cause, and nothing through Netty's logger,
 * which reports whatever reaches the end of a pipeline at {@code WARN} with a stack trace. The connection is closed
 * as before: Netty's codec still sends the {@code GOAWAY}, and a stream's error still resets only that stream.
 */
public class Http2ConnectionErrorLoggingIntegrationTest {

    // a limit no other class uses, so Netty's message for a header block over it is this class's own
    private static final int LIMIT = 20_000;
    private static final String NETTYS_PIPELINE_LOGGER = "io.netty.channel.DefaultChannelPipeline";
    private static final String REACHED_THE_END = "reached at the tail of the pipeline";

    private static final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private static final List<LogRecord> reachedTheEndOfAPipeline = new CopyOnWriteArrayList<>();
    // the event loops of servers and clients other classes left running in this JVM, which are not this class's to judge
    private static volatile Set<Thread> threadsBeforeThisClass = Set.of();
    private static final Handler nettysLogCapture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (String.valueOf(record.getMessage()).contains(REACHED_THE_END) && !threadsBeforeThisClass.contains(Thread.currentThread())) {
                reachedTheEndOfAPipeline.add(record);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    // held here: java.util.logging keeps only a weak reference to a logger, and the handler would go with it
    private static Logger nettysPipelineLogger;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static EventLoopGroup clientGroup;

    private int reachedTheEndBeforeThisTest;

    @BeforeClass
    public static void startServer() throws Exception {
        threadsBeforeThisClass = Set.copyOf(Thread.getAllStackTraces().keySet());
        clientGroup = new NioEventLoopGroup(2);
        // a clone: the event log clears the entry it is handed once it has copied it
        MockServerLogger.setGlobalLogEventListener(logEntry -> logged.add(logEntry.clone()));
        // the test JVM defaults to ERROR, which would drop the entries this class asserts on
        mockServer = new MockServer(configuration().maxHeaderSize(LIMIT).logLevel("DEBUG"), 0);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());

        // after the server has started: MockServer's logging set-up removes the handlers of every logger
        nettysPipelineLogger = Logger.getLogger(NETTYS_PIPELINE_LOGGER);
        nettysPipelineLogger.addHandler(nettysLogCapture);
        assertThatTheCaptureSeesWhatReachesTheEndOfAPipeline();
    }

    /**
     * Without this the class would pass for as long as Netty logged somewhere this capture does not look, or once
     * something had taken the capture off its logger.
     */
    private static void assertThatTheCaptureSeesWhatReachesTheEndOfAPipeline() throws Exception {
        reachedTheEndOfAPipeline.clear();
        Channel unconnected = clientGroup.register(new NioSocketChannel()).sync().channel();
        try {
            unconnected.pipeline().fireExceptionCaught(new IllegalStateException("capture check"));
            unconnected.eventLoop().submit(() -> {
            }).get(10, TimeUnit.SECONDS);
            assertThat(thrownToTheEndOfAPipeline(), contains("java.lang.IllegalStateException: capture check"));
        } finally {
            unconnected.close().sync();
            reachedTheEndOfAPipeline.clear();
        }
    }

    @AfterClass
    public static void stopServer() throws Exception {
        try {
            stopQuietly(mockServerClient);
            stopQuietly(mockServer);
            List<String> reachedTheEndWhileThisClassRan = thrownToTheEndOfAPipeline();
            assertThatTheCaptureSeesWhatReachesTheEndOfAPipeline();
            assertThat("reached the end of a pipeline while this class ran", reachedTheEndWhileThisClassRan, empty());
        } finally {
            MockServerLogger.setGlobalLogEventListener(null);
            nettysPipelineLogger.removeHandler(nettysLogCapture);
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Before
    public void resetServer() {
        logged.clear();
        reachedTheEndBeforeThisTest = reachedTheEndOfAPipeline.size();
        mockServerClient.reset();
        mockServerClient.when(request().withPath("/served")).respond(response().withBody("served"));
    }

    @Test
    public void shouldLogAConnectionItsClientResetOnceBelowAWarning() throws Exception {
        for (boolean tls : new boolean[]{false, true}) {
            InetSocketAddress client;
            try (Http2TestClient connection = connect(tls)) {
                client = connection.localAddress();
                assertThat(transport(tls), connection.send(pseudoHeaders(tls, HttpMethod.GET), true).status(), is(200));

                connection.resetConnection();
            }

            List<LogEntry> entries = awaitConnectionEntries(client);
            assertThat(transport(tls), thrownToTheEndOfAPipelineInThisTest(), empty());
            assertThat(transport(tls), entries, hasSize(1));
            assertThat(transport(tls), entries.get(0).getLogLevel(), is(Level.DEBUG));
            assertThat(transport(tls), entries.get(0).getMessageFormat(), is("HTTP/2 connection from:{}closed by its client:{}"));
            assertThat(transport(tls), entries.get(0).getThrowable(), is(nullValue()));
        }
    }

    @Test
    public void shouldLogAHeaderBlockOverTheLimitOnlyWhereItIsRefused() throws Exception {
        for (boolean tls : new boolean[]{false, true}) {
            InetSocketAddress client;
            try (Http2TestClient connection = connect(tls)) {
                client = connection.localAddress();

                // 'a' takes 5 bits in HPACK's Huffman code, so this block is about 1.9 times the limit
                connection.send(pseudoHeaders(tls, HttpMethod.GET).add("x-filler", "a".repeat(3 * LIMIT)), true);

                assertThat(transport(tls), connection.goAwayErrorCode(), is(Http2Error.PROTOCOL_ERROR.code()));
                assertThat(transport(tls), connection.closedWithin(10), is(true));
            }

            List<LogEntry> entries = connectionEntries(client);
            assertThat(transport(tls), thrownToTheEndOfAPipelineInThisTest(), empty());
            assertThat(transport(tls), entries, hasSize(1));
            assertThat(transport(tls), entries.get(0).getLogLevel(), is(Level.WARN));
            assertThat(transport(tls), entries.get(0).getMessageFormat(), containsString("because a request's header block is more than a quarter larger than maxHeaderSize"));
        }
    }

    @Test
    public void shouldLogAConnectionErrorOnceAsAWarningWithItsCauseAndStillSendGoAway() throws Exception {
        for (boolean tls : new boolean[]{false, true}) {
            InetSocketAddress client;
            try (Http2TestClient connection = connect(tls)) {
                client = connection.localAddress();
                assertThat(transport(tls), connection.send(pseudoHeaders(tls, HttpMethod.GET), true).status(), is(200));

                // a DATA frame on stream 0, which no stream can be blamed for
                connection.sendRaw(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0});

                assertThat(transport(tls), connection.goAwayErrorCode(), is(Http2Error.PROTOCOL_ERROR.code()));
                assertThat(transport(tls), connection.closedWithin(10), is(true));
            }

            List<LogEntry> entries = connectionEntries(client);
            assertThat(transport(tls), thrownToTheEndOfAPipelineInThisTest(), empty());
            assertThat(transport(tls), entries, hasSize(1));
            assertThat(transport(tls), entries.get(0).getLogLevel(), is(Level.WARN));
            assertThat(transport(tls), entries.get(0).getMessageFormat(), is("closing HTTP/2 connection from:{}for connection error:{}"));
            assertThat(transport(tls), Arrays.asList(entries.get(0).getArguments()), hasItem(Http2Error.PROTOCOL_ERROR));
            assertThat(transport(tls), entries.get(0).getThrowable(), instanceOf(Http2Exception.class));
        }
    }

    /**
     * Netty's OpenSSL TLS handler closes such a connection itself and its JDK one does not, so on a JVM without the
     * OpenSSL native this is the test of the close; {@code Http2ConnectionExceptionHandlerTest} tests it with the
     * JDK handler whatever the JVM has.
     */
    @Test
    public void shouldCloseATlsConnectionSentBytesThatAreNotTlsAndLogItOnce() throws Exception {
        InetSocketAddress client;
        try (Http2TestClient connection = connect(true)) {
            client = connection.localAddress();
            assertThat(connection.send(pseudoHeaders(true, HttpMethod.GET), true).status(), is(200));

            // several writes: a server that kept the connection would log each one it read
            for (int write = 0; write < 4 && connection.isOpen(); write++) {
                connection.sendBeneathTls(new byte[2000]);
            }

            assertThat(connection.closedWithin(10), is(true));
        }

        // awaited: Netty's OpenSSL TLS handler closes the connection before it reports the fault
        List<LogEntry> entries = awaitConnectionEntries(client);
        assertThat(thrownToTheEndOfAPipelineInThisTest(), empty());
        assertThat(entries, hasSize(1));
        assertThat(entries.get(0).getLogLevel(), is(Level.WARN));
        assertThat(entries.get(0).getMessageFormat(), containsString(" for SSL or decoder fault "));
        assertThat(entries.get(0).getThrowable(), is(nullValue()));
    }

    @Test
    public void shouldStillResetOnlyTheStreamForAStreamError() throws Exception {
        for (boolean tls : new boolean[]{false, true}) {
            InetSocketAddress client;
            try (Http2TestClient connection = connect(tls)) {
                client = connection.localAddress();
                Http2TestClient.Exchange open = connection.send(pseudoHeaders(tls, HttpMethod.POST), false);
                int stream = open.streamId();

                // WINDOW_UPDATE with an increment of 0, which is an error of the stream it names
                connection.sendRaw(new byte[]{0, 0, 4, 8, 0, (byte) (stream >>> 24), (byte) (stream >>> 16), (byte) (stream >>> 8), (byte) stream, 0, 0, 0, 0});

                // by the stream's own handlers, which are told of the error and close their stream
                assertThat(transport(tls), open.resetErrorCode(), is(Http2Error.CANCEL.code()));
                assertThat("the connection carries on", connection.send(pseudoHeaders(tls, HttpMethod.GET), true).status(), is(200));
                assertThat(transport(tls), connection.isOpen(), is(true));
            }

            assertThat(transport(tls), connectionEntries(client), empty());
            assertThat(transport(tls), thrownToTheEndOfAPipelineInThisTest(), empty());
        }
    }

    private static Http2TestClient connect(boolean tls) throws Exception {
        return tls ? Http2TestClient.tls(clientGroup, mockServer.getLocalPort()) : Http2TestClient.h2c(clientGroup, mockServer.getLocalPort());
    }

    private static String transport(boolean tls) {
        return tls ? "over TLS" : "over cleartext";
    }

    private static Http2Headers pseudoHeaders(boolean tls, HttpMethod method) {
        return new DefaultHttp2Headers()
            .method(method.asciiName())
            .scheme(tls ? "https" : "http")
            .authority("localhost:" + mockServer.getLocalPort())
            .path("/served");
    }

    /**
     * The entries MockServer logged about the HTTP/2 connection from a client's address, as distinct from those about
     * its requests.
     */
    private static List<LogEntry> connectionEntries(InetSocketAddress client) {
        Pattern address = Pattern.compile(":" + client.getPort() + "(?!\\d)");
        return logged.stream()
            .filter(entry -> String.valueOf(entry.getMessageFormat()).contains("HTTP/2 connection"))
            .filter(entry -> address.matcher(entry.getMessageFormat() + Arrays.toString(entry.getArguments())).find())
            .collect(Collectors.toList());
    }

    private static List<LogEntry> awaitConnectionEntries(InetSocketAddress client) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (connectionEntries(client).isEmpty() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return connectionEntries(client);
    }

    private List<String> thrownToTheEndOfAPipelineInThisTest() {
        List<String> thrown = thrownToTheEndOfAPipeline();
        return thrown.subList(Math.min(reachedTheEndBeforeThisTest, thrown.size()), thrown.size());
    }

    private static List<String> thrownToTheEndOfAPipeline() {
        return reachedTheEndOfAPipeline.stream().map(record -> String.valueOf(record.getThrown())).collect(Collectors.toList());
    }
}
