package org.mockserver.netty.unification;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.util.DefaultAttributeMap;
import org.junit.After;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The cases of {@link Http2StreamFaults} a socket does not reach easily: what it leaves to others to report, and what
 * it logs at a level that is switched off. {@code Http2ConnectionErrorLoggingIntegrationTest} covers the rest over
 * real connections.
 */
public class Http2StreamFaultsTest {

    private static final InetSocketAddress CLIENT = new InetSocketAddress("127.0.0.1", 54321);

    private final List<LogEntry> logged = new ArrayList<>();
    private Level logLevel = Level.INFO;
    private final MockServerLogger mockServerLogger = new MockServerLogger(Http2StreamFaultsTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return isEnabled(level, logLevel);
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry);
        }
    };
    private final EmbeddedChannel connection = new EmbeddedChannel();

    @After
    public void closeConnection() {
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldLeaveARequestCutShortForNoReasonItKnowsToItsCaller() {
        Http2StreamChannel stream = stream(3);

        assertThat(Http2StreamFaults.isRequestCutShort(mockServerLogger, stream, new PrematureChannelClosureException("closed")), is(false));

        assertThat(logged, is(empty()));
    }

    @Test
    public void shouldLeaveWhatIsNotARequestCutShortOnAnHttp2StreamToItsCaller() {
        Http2StreamChannel stream = stream(3);
        Http2StreamFaults.noteCancelled(stream, new DefaultHttp2ResetFrame(Http2Error.CANCEL));

        assertThat("another exception", Http2StreamFaults.isRequestCutShort(mockServerLogger, stream, new IllegalStateException("closed")), is(false));
        connection.close();
        assertThat("an HTTP/1.1 connection", Http2StreamFaults.isRequestCutShort(mockServerLogger, connection, new PrematureChannelClosureException("closed")), is(false));

        assertThat(logged, is(empty()));
    }

    @Test
    public void shouldLogACancelledRequestOnceWithItsErrorCodeWhateverTheCode() {
        for (long code : new long[]{Http2Error.NO_ERROR.code(), Http2Error.CANCEL.code(), 0xcafeL}) {
            logged.clear();
            Http2StreamChannel stream = stream(5);
            Http2StreamFaults.noteCancelled(stream, new DefaultHttp2ResetFrame(code));

            assertThat(Http2StreamFaults.isRequestCutShort(mockServerLogger, stream, new PrematureChannelClosureException("closed")), is(true));

            assertThat(formats(), contains("INFO " + Http2StreamFaults.CANCELLED));
            Http2Error known = Http2Error.valueOf(code);
            assertThat(Arrays.asList(logged.get(0).getArguments()), hasItems(5, CLIENT, known != null ? known : code));
            assertThat(logged.get(0).getThrowable(), is(nullValue()));
        }
    }

    @Test
    public void shouldLogARequestThatEndedWithItsConnectionOnce() {
        Http2StreamChannel stream = stream(7);
        connection.close();

        assertThat(Http2StreamFaults.isRequestCutShort(mockServerLogger, stream, new PrematureChannelClosureException("closed")), is(true));

        assertThat(formats(), contains("INFO " + Http2StreamFaults.ENDED_WITH_CONNECTION));
        assertThat(Arrays.asList(logged.get(0).getArguments()), hasItems(7, CLIENT));
        assertThat(logged.get(0).getThrowable(), is(nullValue()));
    }

    @Test
    public void shouldLogNothingMoreForARequestCutShortByAnErrorAlreadyLogged() {
        Http2StreamChannel stream = stream(9);
        Http2StreamFaults.errorLogged(stream);

        assertThat(Http2StreamFaults.isRequestCutShort(mockServerLogger, stream, new PrematureChannelClosureException("closed")), is(true));

        assertThat(logged, is(empty()));
    }

    @Test
    public void shouldStillAccountForACutShortRequestWhenInfoIsOff() {
        logLevel = Level.WARN;
        Http2StreamChannel cancelled = stream(11);
        Http2StreamFaults.noteCancelled(cancelled, new DefaultHttp2ResetFrame(Http2Error.CANCEL));
        assertThat(Http2StreamFaults.isRequestCutShort(mockServerLogger, cancelled, new PrematureChannelClosureException("closed")), is(true));

        Http2StreamChannel ended = stream(13);
        connection.close();
        assertThat(Http2StreamFaults.isRequestCutShort(mockServerLogger, ended, new PrematureChannelClosureException("closed")), is(true));

        assertThat(logged, is(empty()));
    }

    @Test
    public void shouldLogOnlyAnInboundErrorOfAnOpenStreamOrTheCloseForTooManyResets() throws Exception {
        Http2FrameCodec codec = Http2RequestHeaderLimit.frameCodecBuilder(mockServerLogger).initialSettings(Http2RequestHeaderLimit.serverSettings(configuration())).build();
        EmbeddedChannel direct = new EmbeddedChannel(codec);
        try {
            ChannelHandlerContext ctx = direct.pipeline().context(codec);
            codec.connection().remote().createStream(1, false);
            Http2Exception ofAnOpenStream = Http2Exception.streamError(1, Http2Error.PROTOCOL_ERROR, "of an open stream");
            Http2Exception ofAStreamThatHasGone = Http2Exception.streamError(3, Http2Error.STREAM_CLOSED, "of a stream that has gone");
            Http2Exception tooManyResets = Http2Exception.connectionError(Http2Error.ENHANCE_YOUR_CALM, "too many resets");
            Http2Exception ofTheConnection = Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, "of the connection");

            Http2StreamFaults.logError(mockServerLogger, ctx, false, ofAStreamThatHasGone);
            Http2StreamFaults.logError(mockServerLogger, ctx, true, ofAnOpenStream);
            Http2StreamFaults.logError(mockServerLogger, ctx, false, Http2Exception.headerListSizeError(1, Http2Error.PROTOCOL_ERROR, true, "refused for its size"));
            // an inbound connection error reaches Http2ConnectionExceptionHandler, which logs it
            Http2StreamFaults.logError(mockServerLogger, ctx, false, tooManyResets);
            Http2StreamFaults.logError(mockServerLogger, ctx, false, ofTheConnection);
            Http2StreamFaults.logError(mockServerLogger, ctx, true, ofTheConnection);
            Http2StreamFaults.logError(mockServerLogger, ctx, false, new IllegalStateException("not HTTP/2's"));
            assertThat(logged, is(empty()));

            Http2StreamFaults.logError(mockServerLogger, ctx, false, ofAnOpenStream);
            Http2StreamFaults.logError(mockServerLogger, ctx, true, tooManyResets);

            assertThat(formats(), contains("WARN " + Http2StreamFaults.STREAM_ERROR, "WARN " + Http2StreamFaults.CLOSING_CONNECTION));
            assertThat(Arrays.asList(logged.get(0).getArguments()), hasItems(1, Http2Error.PROTOCOL_ERROR, "of an open stream"));
            assertThat(Arrays.asList(logged.get(1).getArguments()), hasItems(Http2Error.ENHANCE_YOUR_CALM, "too many resets"));
            assertThat(logged.stream().map(LogEntry::getThrowable).collect(Collectors.toList()), contains(nullValue(), nullValue()));

            logged.clear();
            logLevel = Level.ERROR;
            Http2StreamFaults.logError(mockServerLogger, ctx, false, ofAnOpenStream);
            Http2StreamFaults.logError(mockServerLogger, ctx, true, tooManyResets);
            assertThat("nothing at a level that is off", logged, is(empty()));
        } finally {
            direct.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldLogARequestATunnelsClientCancelsOnlyWhileItIsIncomplete() throws Exception {
        Http2FrameCodec codec = Http2RequestHeaderLimit.frameCodecBuilder(mockServerLogger).initialSettings(Http2RequestHeaderLimit.serverSettings(configuration())).build();
        EmbeddedChannel tunnel = new EmbeddedChannel(codec);
        try {
            ChannelHandlerContext ctx = tunnel.pipeline().context(codec);
            codec.connection().remote().createStream(1, false);
            codec.connection().remote().createStream(3, true);
            List<Integer> handedOn = new ArrayList<>();
            Http2FrameListener listener = Http2StreamFaults.tunnelFrameListener(mockServerLogger, codec.connection(), new Http2FrameAdapter() {
                @Override
                public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
                    handedOn.add(streamId);
                }
            });

            listener.onRstStreamRead(ctx, 1, Http2Error.CANCEL.code());
            listener.onRstStreamRead(ctx, 3, Http2Error.CANCEL.code());
            listener.onRstStreamRead(ctx, 5, Http2Error.CANCEL.code());

            assertThat(formats(), contains("INFO " + Http2StreamFaults.CANCELLED));
            assertThat(Arrays.asList(logged.get(0).getArguments()), hasItems(1, Http2Error.CANCEL));
            assertThat("every reset is handed on", handedOn, contains(1, 3, 5));
        } finally {
            tunnel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldLogEachRequestATunnelWasStillReceivingWhenItsConnectionClosed() throws Exception {
        Http2FrameCodec codec = Http2RequestHeaderLimit.frameCodecBuilder(mockServerLogger).initialSettings(Http2RequestHeaderLimit.serverSettings(configuration())).build();
        EmbeddedChannel tunnel = new EmbeddedChannel(codec);
        try {
            ChannelHandlerContext ctx = tunnel.pipeline().context(codec);
            codec.connection().remote().createStream(1, false);
            // its request is complete
            codec.connection().remote().createStream(3, true);
            codec.connection().remote().createStream(5, false);

            Http2StreamFaults.logRequestsEndedWithConnection(mockServerLogger, ctx, codec.connection());

            assertThat(formats(), contains("INFO " + Http2StreamFaults.ENDED_WITH_CONNECTION, "INFO " + Http2StreamFaults.ENDED_WITH_CONNECTION));
            assertThat(logged.stream().map(entry -> entry.getArguments()[0]).collect(Collectors.toList()), contains(1, 5));

            logged.clear();
            logLevel = Level.WARN;
            Http2StreamFaults.logRequestsEndedWithConnection(mockServerLogger, ctx, codec.connection());
            assertThat("nothing at a level that is off", logged, is(empty()));
        } finally {
            tunnel.finishAndReleaseAll();
        }
    }

    private List<String> formats() {
        return logged.stream().map(entry -> entry.getLogLevel() + " " + entry.getMessageFormat()).collect(Collectors.toList());
    }

    /**
     * A stream of {@link #connection}, with attributes of its own as a stream's channel has.
     */
    private Http2StreamChannel stream(int id) {
        DefaultAttributeMap attributes = new DefaultAttributeMap();
        Http2FrameStream frameStream = mock(Http2FrameStream.class);
        when(frameStream.id()).thenReturn(id);
        Http2StreamChannel stream = mock(Http2StreamChannel.class);
        when(stream.stream()).thenReturn(frameStream);
        when(stream.parent()).thenReturn(connection);
        when(stream.remoteAddress()).thenReturn(CLIENT);
        when(stream.attr(any())).thenAnswer(invocation -> attributes.attr(invocation.getArgument(0)));
        when(stream.hasAttr(any())).thenAnswer(invocation -> attributes.hasAttr(invocation.getArgument(0)));
        return stream;
    }
}
