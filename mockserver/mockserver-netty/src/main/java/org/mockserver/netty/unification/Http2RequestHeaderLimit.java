package org.mockserver.netty.unification;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

/**
 * Limits the header list of an HTTP/2 request to {@code maxHeaderSize}, as HTTP/1.1 limits its header section, and
 * logs each request refused for it. The limit is counted as HTTP/2 defines it (RFC 9113 section 6.5.2): every field's
 * name and value, the pseudo-header fields included, plus 32 bytes a field, after HPACK decoding.
 * <p>
 * Netty enforces it: a header list over the limit is answered {@code 431} and its stream reset, and a header block
 * of more than the limit plus a quarter closes the connection with {@code GOAWAY}. Neither reaches a handler, so the
 * codecs built here log them as they are raised.
 */
public final class Http2RequestHeaderLimit {

    /**
     * The start of Netty's message for a header block over the limit plus a quarter, which is a plain connection
     * error: {@code Http2CodecUtil.headerListSizeExceeded(long)}.
     */
    static final String HEADER_BLOCK_TOO_LARGE = "Header size exceeded max allowed size";

    private Http2RequestHeaderLimit() {
    }

    /**
     * @return Netty's default settings with {@code SETTINGS_MAX_HEADER_LIST_SIZE} set to {@code maxHeaderSize}
     */
    public static Http2Settings serverSettings(Configuration configuration) {
        return Http2Settings.defaultSettings().maxHeaderListSize(configuration.maxHeaderSize());
    }

    /**
     * @return the settings of both ends of the loopback leg of a CONNECT or SOCKS tunnel, which set no header list
     * limit. Its requests were limited on the tunnel's client leg and the relay has since added fields to them, so
     * limiting them again would refuse a request the client was told is within the limit; its responses are
     * MockServer's own
     */
    public static Http2Settings relayLoopbackSettings() {
        return Http2Settings.defaultSettings().maxHeaderListSize(Http2CodecUtil.MAX_HEADER_LIST_SIZE);
    }

    /**
     * @return a server {@link Http2FrameCodecBuilder} whose codec logs the requests it refuses for their header size
     */
    public static Http2FrameCodecBuilder frameCodecBuilder(MockServerLogger mockServerLogger) {
        return new Http2FrameCodecBuilder() {
            {
                // as Http2FrameCodecBuilder.forServer() sets them: without the zero, closing a connection with
                // streams open waits out Netty's 30 second graceful shutdown
                server(true);
                gracefulShutdownTimeoutMillis(0);
            }

            @Override
            protected Http2FrameCodec build(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder, Http2Settings initialSettings) {
                Http2FrameCodec codec = new Http2FrameCodec(encoder, decoder, initialSettings, decoupleCloseAndGoAway(), flushPreface()) {
                    @Override
                    public void onError(ChannelHandlerContext ctx, boolean outbound, Throwable cause) {
                        logRefusalThen(mockServerLogger, ctx, initialSettings, outbound, cause, () -> super.onError(ctx, outbound, cause));
                    }
                };
                codec.gracefulShutdownTimeoutMillis(gracefulShutdownTimeoutMillis());
                return codec;
            }
        };
    }

    /**
     * @return the server handler of a CONNECT or SOCKS tunnel carrying HTTP/2, which logs the requests it refuses
     * for their header size
     */
    public static HttpToHttp2ConnectionHandler tunnelServerHandler(Configuration configuration, MockServerLogger mockServerLogger, Http2Connection connection, Http2FrameListener frameListener, Http2FrameLogger frameLogger) {
        return tunnelServerHandlerBuilder(configuration, mockServerLogger, connection, frameListener, frameLogger).build();
    }

    static TunnelServerHandlerBuilder tunnelServerHandlerBuilder(Configuration configuration, MockServerLogger mockServerLogger, Http2Connection connection, Http2FrameListener frameListener, Http2FrameLogger frameLogger) {
        return new TunnelServerHandlerBuilder(mockServerLogger, serverSettings(configuration), connection, frameListener, frameLogger);
    }

    /**
     * Netty's own handling runs whatever the logging does: it is what answers 431, resets the stream or sends GOAWAY.
     */
    static void logRefusalThen(MockServerLogger mockServerLogger, ChannelHandlerContext ctx, Http2Settings settings, boolean outbound, Throwable cause, Runnable nettysOnError) {
        try {
            logRefusal(mockServerLogger, ctx, settings, outbound, cause);
        } finally {
            nettysOnError.run();
        }
    }

    static void logRefusal(MockServerLogger mockServerLogger, ChannelHandlerContext ctx, Http2Settings settings, boolean outbound, Throwable cause) {
        if (outbound || mockServerLogger == null || !mockServerLogger.isEnabledForInstance(Level.WARN)) {
            return;
        }
        Http2Exception failure = Http2CodecUtil.getEmbeddedHttp2Exception(cause);
        if (failure instanceof Http2Exception.HeaderListSizeException && ((Http2Exception.HeaderListSizeException) failure).duringDecode()) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("refusing request on HTTP/2 stream:{}from:{}because its header list is larger than maxHeaderSize:{}")
                    .setArguments(((Http2Exception.HeaderListSizeException) failure).streamId(), ctx.channel().remoteAddress(), settings.maxHeaderListSize())
            );
        } else if (isHeaderBlockTooLarge(failure)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("closing HTTP/2 connection from:{}because a request's header block is more than a quarter larger than maxHeaderSize:{}")
                    .setArguments(ctx.channel().remoteAddress(), settings.maxHeaderListSize())
            );
        }
    }

    private static boolean isHeaderBlockTooLarge(Http2Exception failure) {
        return failure != null
            && !Http2Exception.isStreamError(failure)
            && failure.error() == Http2Error.PROTOCOL_ERROR
            && failure.getMessage() != null
            && failure.getMessage().startsWith(HEADER_BLOCK_TOO_LARGE);
    }

    static final class TunnelServerHandlerBuilder extends AbstractHttp2ConnectionHandlerBuilder<HttpToHttp2ConnectionHandler, TunnelServerHandlerBuilder> {

        private final MockServerLogger mockServerLogger;

        private TunnelServerHandlerBuilder(MockServerLogger mockServerLogger, Http2Settings settings, Http2Connection connection, Http2FrameListener frameListener, Http2FrameLogger frameLogger) {
            this.mockServerLogger = mockServerLogger;
            initialSettings(settings);
            connection(connection);
            frameListener(frameListener);
            if (frameLogger != null) {
                frameLogger(frameLogger);
            }
        }

        @Override
        protected HttpToHttp2ConnectionHandler build() {
            return super.build();
        }

        @Override
        protected HttpToHttp2ConnectionHandler build(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder, Http2Settings initialSettings) {
            return new HttpToHttp2ConnectionHandler(decoder, encoder, initialSettings, isValidateHeaders(), decoupleCloseAndGoAway(), flushPreface(), null) {
                @Override
                public void onError(ChannelHandlerContext ctx, boolean outbound, Throwable cause) {
                    logRefusalThen(mockServerLogger, ctx, initialSettings, outbound, cause, () -> super.onError(ctx, outbound, cause));
                }
            };
        }
    }
}
