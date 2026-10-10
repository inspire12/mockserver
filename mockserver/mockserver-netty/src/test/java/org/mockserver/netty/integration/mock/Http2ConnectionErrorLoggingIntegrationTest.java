package org.mockserver.netty.integration.mock;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2Headers;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;
import org.slf4j.event.Level;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * What an HTTP/2 connection made straight to MockServer logs when the connection fails, over cleartext HTTP/2 and
 * over TLS: one entry in MockServer's own log at a level that fits the cause, and nothing through Netty's logger,
 * which reports whatever reaches the end of a pipeline at {@code WARN} with a stack trace. The connection is closed
 * as before: Netty's codec still sends the {@code GOAWAY}.
 * <p>
 * And what one of its streams logs when it ends before its request does, there and in a CONNECT or SOCKS tunnel: one
 * {@code INFO} entry for an upload its client cancelled or whose connection closed, one {@code WARN} entry for an
 * error of the stream's own, which resets it with that error's code, and none with a stack trace.
 */
public class Http2ConnectionErrorLoggingIntegrationTest {

    // a limit no other class uses, so Netty's message for a header block over it is this class's own
    private static final int LIMIT = 20_000;
    private static final String NETTYS_PIPELINE_LOGGER = "io.netty.channel.DefaultChannelPipeline";
    private static final String REACHED_THE_END = "reached at the tail of the pipeline";
    private static final String TUNNEL_TARGET_HOST = "localhost";
    private static final int TUNNEL_TARGET_PORT = 443;
    // AbstractHttp2ConnectionHandlerBuilder's default for a server, read or written, in 30 seconds
    private static final int RESETS_NETTY_ALLOWS = 200;
    // the delay of a response that must still be awaited when its stream is reset, and never written while a later test runs
    private static final int NEVER_WITHIN_THIS_CLASS_SECONDS = 600;

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
    // MockServer's side of each connection open to it, by the client's port
    private static final Map<Integer, Channel> acceptedByClientPort = new ConcurrentHashMap<>();

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
        recordEachAcceptedConnection();

        // after the server has started: MockServer's logging set-up removes the handlers of every logger
        nettysPipelineLogger = Logger.getLogger(NETTYS_PIPELINE_LOGGER);
        nettysPipelineLogger.addHandler(nettysLogCapture);
        assertThatTheCaptureSeesWhatReachesTheEndOfAPipeline();
    }

    @SuppressWarnings("unchecked")
    private static void recordEachAcceptedConnection() throws Exception {
        Field listeners = LifeCycle.class.getDeclaredField("serverChannelFutures");
        listeners.setAccessible(true);
        for (Future<Channel> listener : (List<Future<Channel>>) listeners.get(mockServer)) {
            // ahead of the handler that hands each accepted connection to its own pipeline
            listener.get(10, TimeUnit.SECONDS).pipeline().addFirst(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    Channel accepted = (Channel) msg;
                    int clientPort = ((InetSocketAddress) accepted.remoteAddress()).getPort();
                    acceptedByClientPort.put(clientPort, accepted);
                    accepted.closeFuture().addListener(closed -> acceptedByClientPort.remove(clientPort, accepted));
                    ctx.fireChannelRead(msg);
                }
            });
        }
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

    /**
     * A tunnel's client leg logs it as a direct connection does: its handler fires no connection error down the
     * pipeline, as the direct connection's codec does, so it is logged where Netty raises it.
     */
    @Test
    public void shouldLogAConnectionErrorOnceAsAWarningWithItsCauseAndStillSendGoAway() throws Exception {
        for (Route route : Route.values()) {
            logged.clear();
            InetSocketAddress client;
            try (Http2TestClient connection = connect(route)) {
                client = connection.localAddress();
                assertThat(route.name(), connection.send(pseudoHeaders(route, HttpMethod.GET, "/served"), true).status(), is(200));

                // a DATA frame on stream 0, which no stream can be blamed for
                connection.sendRaw(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0});

                assertThat(route.name(), connection.goAwayErrorCode(), is(Http2Error.PROTOCOL_ERROR.code()));
                assertThat(route.name(), connection.closedWithin(10), is(true));
            }

            List<LogEntry> entries = awaitConnectionEntries(client);
            assertThat(route.name(), thrownToTheEndOfAPipelineInThisTest(), empty());
            assertThat(route.name(), entries, hasSize(1));
            assertThat(route.name(), entries.get(0).getLogLevel(), is(Level.WARN));
            assertThat(route.name(), entries.get(0).getMessageFormat(), is("closing HTTP/2 connection from:{}for connection error:{}"));
            assertThat(route.name(), Arrays.asList(entries.get(0).getArguments()), hasItem(Http2Error.PROTOCOL_ERROR));
            assertThat(route.name(), entries.get(0).getThrowable(), instanceOf(Http2Exception.class));
            assertThat(route.name(), warningsAndErrors(), hasSize(1));
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

    /**
     * No way is known for a client to raise such an exception, so the test fires it into MockServer's side of the
     * connection, where a handler ahead of the last one or the transport would raise it.
     */
    @Test
    public void shouldLogAnUnexpectedExceptionOnceAsAnErrorAndCloseTheConnectionWithGoAway() throws Exception {
        for (boolean tls : new boolean[]{false, true}) {
            logged.clear();
            IllegalStateException unexpected = new IllegalStateException("unexpected " + transport(tls));
            InetSocketAddress client;
            try (Http2TestClient connection = connect(tls)) {
                client = connection.localAddress();
                Http2TestClient.Exchange served = connection.send(pseudoHeaders(tls, HttpMethod.GET), true);
                assertThat(transport(tls), served.status(), is(200));
                Channel serverSide = acceptedByClientPort.get(client.getPort());
                assertThat(transport(tls), serverSide, is(notNullValue()));

                serverSide.pipeline().fireExceptionCaught(unexpected);

                assertThat(transport(tls), connection.goAwayErrorCode(), is(Http2Error.INTERNAL_ERROR.code()));
                assertThat(transport(tls), connection.closedWithin(10), is(true));
                assertThat(transport(tls) + ": the response written before it", served.body(), is("served"));
            }

            List<LogEntry> entries = connectionEntries(client);
            assertThat(transport(tls), thrownToTheEndOfAPipelineInThisTest(), empty());
            assertThat(transport(tls), entries, hasSize(1));
            assertThat(transport(tls), entries.get(0).getLogLevel(), is(Level.ERROR));
            assertThat(transport(tls), entries.get(0).getMessageFormat(), both(startsWith("closing HTTP/2 connection ")).and(endsWith(" for unexpected exception")));
            assertThat(transport(tls), entries.get(0).getThrowable(), is(sameInstance(unexpected)));
            assertThat(transport(tls), warningsAndErrors(), hasSize(1));
        }
    }

    /**
     * Netty's codec fires an exception it caught while decoding, which is no HTTP/2 error, down the pipeline before it
     * sends its own {@code GOAWAY} for it; the test raises one there on the connection's event loop, as a read would.
     */
    @Test
    public void shouldLeaveTheGoAwayToTheCodecForAnUnexpectedExceptionItCaughtDecoding() throws Exception {
        for (boolean tls : new boolean[]{false, true}) {
            logged.clear();
            IllegalStateException unexpected = new IllegalStateException("thrown while decoding " + transport(tls));
            InetSocketAddress client;
            try (Http2TestClient connection = connect(tls)) {
                client = connection.localAddress();
                assertThat(transport(tls), connection.send(pseudoHeaders(tls, HttpMethod.GET), true).status(), is(200));
                Channel serverSide = acceptedByClientPort.get(client.getPort());
                assertThat(transport(tls), serverSide, is(notNullValue()));
                ChannelHandlerContext codec = serverSide.pipeline().context(Http2FrameCodec.class);

                serverSide.eventLoop().execute(() -> ((Http2FrameCodec) codec.handler()).onError(codec, false, unexpected));

                assertThat(transport(tls), connection.goAwayErrorCode(), is(Http2Error.INTERNAL_ERROR.code()));
                assertThat(transport(tls) + ": the codec's GOAWAY", connection.goAwayDebugData(), is(unexpected.getMessage()));
                assertThat(transport(tls), connection.closedWithin(10), is(true));
            }

            List<LogEntry> entries = connectionEntries(client);
            assertThat(transport(tls), thrownToTheEndOfAPipelineInThisTest(), empty());
            assertThat(transport(tls), entries, hasSize(1));
            assertThat(transport(tls), entries.get(0).getLogLevel(), is(Level.ERROR));
            assertThat(transport(tls), entries.get(0).getThrowable(), is(sameInstance(unexpected)));
        }
    }

    /**
     * The stream is open in both tests: in the first its request is still being uploaded, so the part MockServer
     * holds is dropped with it; in the second the request is complete and its response is not due yet.
     */
    @Test
    public void shouldResetOnlyTheStreamForAStreamErrorWithThatErrorsCodeAndWarnOnce() throws Exception {
        mockServerClient.when(request().withPath("/slow")).respond(response().withBody("slow").withDelay(TimeUnit.SECONDS, NEVER_WITHIN_THIS_CLASS_SECONDS));
        for (Route route : Route.values()) {
            for (boolean uploading : new boolean[]{true, false}) {
                String where = route + (uploading ? " while uploading" : " while awaiting the response");
                logged.clear();
                InetSocketAddress client;
                int stream;
                try (Http2TestClient connection = connect(route)) {
                    client = connection.localAddress();
                    Http2TestClient.Exchange open = uploading
                        ? connection.send(pseudoHeaders(route, HttpMethod.POST, "/served"), false).data("part of the request body", false)
                        : connection.send(pseudoHeaders(route, HttpMethod.GET, "/slow"), true);
                    stream = open.streamId();

                    connection.sendRaw(windowUpdateOfZero(stream));

                    assertThat(where, open.resetErrorCode(), is(Http2Error.PROTOCOL_ERROR.code()));
                    assertThat(where + ": the connection carries on", connection.send(pseudoHeaders(route, HttpMethod.GET, "/served"), true).status(), is(200));
                    assertThat(where, connection.isOpen(), is(true));
                }

                List<LogEntry> entries = streamEntries(client);
                assertThat(where, entries, hasSize(1));
                assertThat(where, entries.get(0).getLogLevel(), is(Level.WARN));
                assertThat(where, entries.get(0).getMessageFormat(), is("resetting HTTP/2 stream:{}from:{}for stream error:{}because:{}"));
                assertThat(where, Arrays.asList(entries.get(0).getArguments()), hasItems(stream, Http2Error.PROTOCOL_ERROR));
                assertThat(where, entries.get(0).getThrowable(), is(nullValue()));
                assertThat(where, warningsAndErrors(), hasSize(1));
                assertThat(where, connectionEntries(client), empty());
                assertThat(where, thrownToTheEndOfAPipelineInThisTest(), empty());
            }
        }
    }

    @Test
    public void shouldLogAnUploadItsClientCancelsOnceAtInfoWithNoStackTrace() throws Exception {
        for (Route route : Route.values()) {
            logged.clear();
            InetSocketAddress client;
            int stream;
            try (Http2TestClient connection = connect(route)) {
                client = connection.localAddress();
                Http2TestClient.Exchange upload = connection.send(pseudoHeaders(route, HttpMethod.POST, "/served"), false)
                    .data("part of the request body", false)
                    .reset(Http2Error.CANCEL);
                stream = upload.streamId();

                // a frame already on its way to a stream that has gone is an error of that stream, and not worth an entry
                connection.sendRaw(dataFrame(stream, "more of the request body"));

                // on the same connection, so MockServer has read both by the time this is answered
                assertThat(route.name(), connection.send(pseudoHeaders(route, HttpMethod.GET, "/served"), true).status(), is(200));
            }

            List<LogEntry> entries = streamEntries(client);
            assertThat(route.name(), entries, hasSize(1));
            assertThat(route.name(), entries.get(0).getLogLevel(), is(Level.INFO));
            assertThat(route.name(), entries.get(0).getMessageFormat(), is("HTTP/2 stream:{}from:{}was cancelled by its client with:{}before its request was complete"));
            assertThat(route.name(), Arrays.asList(entries.get(0).getArguments()), hasItems(stream, Http2Error.CANCEL));
            assertThat(route.name(), entries.get(0).getThrowable(), is(nullValue()));
            assertThat(route.name(), warningsAndErrors(), empty());
            assertThat(route.name(), thrownToTheEndOfAPipelineInThisTest(), empty());
        }
        assertThat("no upload was dispatched", mockServerClient.retrieveRecordedRequests(request().withMethod("POST")), emptyArray());
    }

    /**
     * A stream its client cancels once its request is complete held nothing, and is not an upload cut short.
     */
    @Test
    public void shouldLogNothingForAStreamItsClientCancelsOnceItsRequestIsComplete() throws Exception {
        mockServerClient.when(request().withPath("/slow")).respond(response().withBody("slow").withDelay(TimeUnit.SECONDS, NEVER_WITHIN_THIS_CLASS_SECONDS));
        for (Route route : Route.values()) {
            logged.clear();
            InetSocketAddress client;
            try (Http2TestClient connection = connect(route)) {
                client = connection.localAddress();
                connection.send(pseudoHeaders(route, HttpMethod.POST, "/slow"), false)
                    .data("the whole request body", true)
                    .reset(Http2Error.CANCEL);

                assertThat(route.name(), connection.send(pseudoHeaders(route, HttpMethod.GET, "/served"), true).status(), is(200));
            }

            assertThat(route.name(), streamEntries(client), empty());
            assertThat(route.name(), warningsAndErrors(), empty());
        }
    }

    @Test
    public void shouldLogAnUploadCutShortByItsConnectionClosingOnceAtInfoWithNoStackTrace() throws Exception {
        for (Route route : Route.values()) {
            for (boolean reset : new boolean[]{true, false}) {
                String where = route + (reset ? " reset" : " closed");
                logged.clear();
                InetSocketAddress client;
                int stream;
                try (Http2TestClient connection = connect(route)) {
                    client = connection.localAddress();
                    stream = connection.send(pseudoHeaders(route, HttpMethod.POST, "/served"), false)
                        .data("part of the request body", false)
                        .streamId();
                    // a stream whose request is complete is not an upload, and is not reported
                    connection.send(pseudoHeaders(route, HttpMethod.GET, "/served"), true).body();

                    if (reset) {
                        connection.resetConnection();
                    } else {
                        connection.closeAbruptly();
                    }
                }

                List<LogEntry> entries = awaitStreamEntries(client);
                assertThat(where, entries, hasSize(1));
                assertThat(where, entries.get(0).getLogLevel(), is(Level.INFO));
                assertThat(where, entries.get(0).getMessageFormat(), is("HTTP/2 stream:{}from:{}ended with its connection before its request was complete"));
                assertThat(where, Arrays.asList(entries.get(0).getArguments()), hasItem(stream));
                assertThat(where, entries.get(0).getThrowable(), is(nullValue()));
                assertThat(where, warningsAndErrors(), empty());
                assertThat(where, thrownToTheEndOfAPipelineInThisTest(), empty());
            }
        }
        assertThat("no upload was dispatched", mockServerClient.retrieveRecordedRequests(request().withMethod("POST")), emptyArray());
    }

    /**
     * What bounds the entries one connection can cause: Netty closes a connection that sends more than 200
     * RST_STREAM frames in 30 seconds, so a client cannot go on cancelling uploads on it.
     */
    @Test
    public void shouldCloseAConnectionThatCancelsMoreThan200UploadsAtOnce() throws Exception {
        for (Route route : new Route[]{Route.H2C, Route.CONNECT_TLS}) {
            logged.clear();
            InetSocketAddress client;
            try (Http2TestClient connection = connect(route)) {
                client = connection.localAddress();
                for (int upload = 0; upload < RESETS_NETTY_ALLOWS + 1 && connection.isOpen(); upload++) {
                    connection.send(pseudoHeaders(route, HttpMethod.POST, "/served"), false).reset(Http2Error.CANCEL);
                }

                assertThat(route.name(), connection.goAwayErrorCode(), is(Http2Error.ENHANCE_YOUR_CALM.code()));
                assertThat(route.name(), connection.closedWithin(10), is(true));
            }

            // each stream's entry is logged as Netty closes the stream, which can be after the client has seen the GOAWAY:
            // one for each of the 200 resets Netty read, and one for the stream whose reset it refused
            List<LogEntry> entries = awaitStreamEntries(client, RESETS_NETTY_ALLOWS + 1);
            assertThat(route.name(), entries.stream().filter(entry -> entry.getMessageFormat().contains("was cancelled by its client")).count(), is((long) RESETS_NETTY_ALLOWS));
            assertThat(route.name(), entries, hasSize(RESETS_NETTY_ALLOWS + 1));
            assertThat(route.name(), warningsAndErrors().stream().filter(entry -> entry.getLogLevel() == Level.ERROR).collect(Collectors.toList()), empty());
            List<LogEntry> closing = awaitConnectionEntries(client);
            assertThat(route.name(), closing, hasSize(1));
            assertThat(route.name(), closing.get(0).getLogLevel(), is(Level.WARN));
            assertThat(route.name(), closing.get(0).getMessageFormat(), is("closing HTTP/2 connection from:{}for connection error:{}"));
            assertThat(route.name(), Arrays.asList(closing.get(0).getArguments()), hasItem(Http2Error.ENHANCE_YOUR_CALM));
        }
    }

    /**
     * And Netty closes a connection it has had to send more than 200 resets for an error in 30 seconds. It counts a
     * reset by its code, and not {@code CANCEL}: resetting a stream with its error's own code is what makes this hold.
     */
    @Test
    public void shouldCloseAConnectionThatCausesMoreThan200StreamErrorsAtOnce() throws Exception {
        for (Route route : new Route[]{Route.H2C, Route.CONNECT_TLS}) {
            logged.clear();
            InetSocketAddress client;
            try (Http2TestClient connection = connect(route)) {
                client = connection.localAddress();
                for (int error = 0; error < RESETS_NETTY_ALLOWS + 1 && connection.isOpen(); error++) {
                    Http2TestClient.Exchange open = connection.send(pseudoHeaders(route, HttpMethod.POST, "/served"), false);
                    connection.sendRaw(windowUpdateOfZero(open.streamId()));
                    assertThat(route + " error " + error, open.resetErrorCode(), is(Http2Error.PROTOCOL_ERROR.code()));
                }

                assertThat(route.name(), connection.goAwayErrorCode(), is(Http2Error.ENHANCE_YOUR_CALM.code()));
                assertThat(route.name(), connection.closedWithin(10), is(true));
            }

            assertThat(route.name(), streamEntries(client), hasSize(RESETS_NETTY_ALLOWS + 1));
            List<LogEntry> closing = awaitConnectionEntries(client);
            assertThat(route.name(), closing, hasSize(1));
            assertThat(route.name(), closing.get(0).getLogLevel(), is(Level.WARN));
            assertThat(route.name(), closing.get(0).getMessageFormat(), is("closing HTTP/2 connection from:{}for connection error:{}because:{}"));
            assertThat(route.name(), Arrays.asList(closing.get(0).getArguments()), hasItem(Http2Error.ENHANCE_YOUR_CALM));
            assertThat(route.name(), closing.get(0).getThrowable(), is(nullValue()));
            assertThat(route.name(), warningsAndErrors(), hasSize(RESETS_NETTY_ALLOWS + 2));
        }
    }

    public enum Route {
        H2C(false, false), TLS(false, true), CONNECT_TLS(true, true), SOCKS5_H2C(true, false);

        private final boolean tunnel;
        private final boolean tls;

        Route(boolean tunnel, boolean tls) {
            this.tunnel = tunnel;
            this.tls = tls;
        }
    }

    private static Http2TestClient connect(Route route) throws Exception {
        int port = mockServer.getLocalPort();
        switch (route) {
            case H2C:
                return Http2TestClient.h2c(clientGroup, port);
            case TLS:
                return Http2TestClient.tls(clientGroup, port);
            case CONNECT_TLS:
                return Http2TestClient.throughConnect(clientGroup, port, TUNNEL_TARGET_HOST, TUNNEL_TARGET_PORT);
            default:
                return Http2TestClient.throughSocks5(clientGroup, port, TUNNEL_TARGET_HOST, TUNNEL_TARGET_PORT, false);
        }
    }

    private static Http2Headers pseudoHeaders(Route route, HttpMethod method, String path) {
        return new DefaultHttp2Headers()
            .method(method.asciiName())
            .scheme(route.tls ? "https" : "http")
            .authority(route.tunnel ? TUNNEL_TARGET_HOST + ":" + TUNNEL_TARGET_PORT : "localhost:" + mockServer.getLocalPort())
            .path(path);
    }

    // WINDOW_UPDATE with an increment of 0, which is an error of the stream it names
    private static byte[] windowUpdateOfZero(int stream) {
        return new byte[]{0, 0, 4, 8, 0, (byte) (stream >>> 24), (byte) (stream >>> 16), (byte) (stream >>> 8), (byte) stream, 0, 0, 0, 0};
    }

    private static byte[] dataFrame(int stream, String payload) {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[9 + data.length];
        frame[2] = (byte) data.length;
        frame[5] = (byte) (stream >>> 24);
        frame[6] = (byte) (stream >>> 16);
        frame[7] = (byte) (stream >>> 8);
        frame[8] = (byte) stream;
        System.arraycopy(data, 0, frame, 9, data.length);
        return frame;
    }

    /**
     * The entries MockServer logged about one stream of the HTTP/2 connection from a client's address.
     */
    private static List<LogEntry> streamEntries(InetSocketAddress client) {
        Pattern address = Pattern.compile(":" + client.getPort() + "(?!\\d)");
        return logged.stream()
            .filter(entry -> String.valueOf(entry.getMessageFormat()).contains("HTTP/2 stream"))
            .filter(entry -> address.matcher(Arrays.toString(entry.getArguments())).find())
            .collect(Collectors.toList());
    }

    private static List<LogEntry> awaitStreamEntries(InetSocketAddress client) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (streamEntries(client).isEmpty() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return streamEntries(client);
    }

    private static List<LogEntry> awaitStreamEntries(InetSocketAddress client, int atLeast) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (streamEntries(client).size() < atLeast && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return streamEntries(client);
    }

    private static List<LogEntry> warningsAndErrors() {
        return logged.stream()
            .filter(entry -> entry.getLogLevel() == Level.WARN || entry.getLogLevel() == Level.ERROR)
            .collect(Collectors.toList());
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
