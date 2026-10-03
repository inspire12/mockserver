package org.mockserver.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.FullHttpRequest;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.FullHttpRequestToMockServerHttpRequest;
import org.mockserver.model.Header;
import org.slf4j.event.Level;

import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.List;

import static org.mockserver.socket.tls.SniHandler.getALPNProtocol;

/**
 * @author jamesdbloom
 */
public class NettyHttpToMockServerHttpRequestDecoder extends MessageToMessageDecoder<FullHttpRequest> {

    private final FullHttpRequestToMockServerHttpRequest fullHttpRequestToMockServerRequest;
    private final MockServerLogger mockServerLogger;

    public NettyHttpToMockServerHttpRequestDecoder(Configuration configuration, MockServerLogger mockServerLogger, boolean isSecure, Certificate[] clientCertificates, Integer port) {
        this(mockServerLogger, new FullHttpRequestToMockServerHttpRequest(configuration, mockServerLogger, isSecure, clientCertificates, port));
    }

    public NettyHttpToMockServerHttpRequestDecoder(MockServerLogger mockServerLogger, FullHttpRequestToMockServerHttpRequest fullHttpRequestToMockServerRequest) {
        this.mockServerLogger = mockServerLogger;
        this.fullHttpRequestToMockServerRequest = fullHttpRequestToMockServerRequest;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, FullHttpRequest fullHttpRequest, List<Object> out) {
        if (fullHttpRequest.decoderResult().isFailure()) {
            refuseUndecodable(ctx, fullHttpRequest);
            return;
        }
        List<Header> preservedHeaders = null;
        byte[] originalRawBody = null;
        SocketAddress localAddress = null;
        SocketAddress remoteAddress = null;
        if (ctx != null && ctx.channel() != null) {
            PreserveHeadersNettyRemoves.PreservedRequest preserved = PreserveHeadersNettyRemoves.preservedRequest(ctx.channel());
            preservedHeaders = preserved.headers();
            originalRawBody = preserved.originalRawBody();
            localAddress = ctx.channel().localAddress();
            remoteAddress = ctx.channel().remoteAddress();
        }
        out.add(fullHttpRequestToMockServerRequest.mapFullHttpRequestToMockServerRequest(fullHttpRequest, preservedHeaders, originalRawBody, localAddress, remoteAddress, getALPNProtocol(mockServerLogger, ctx)));
    }

    /**
     * HttpChunkLineLimiter answers and drops an undecodable HTTP/1.1 request straight after the codec, so one arriving
     * here came through a pipeline without it. Whatever the codec kept of it is not dispatched; no response is written,
     * since another may be owed first, so the connection is closed.
     */
    private void refuseUndecodable(ChannelHandlerContext ctx, FullHttpRequest fullHttpRequest) {
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("closing connection from:{}because its request could not be decoded:{}")
                    .setArguments(ctx.channel().remoteAddress(), fullHttpRequest.decoderResult().cause().getMessage())
            );
        }
        ctx.close();
    }

}
