package org.mockserver.netty.unification;

import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpServerCodec;
import org.mockserver.configuration.Configuration;

/**
 * The {@link HttpServerCodec} of an HTTP/1.1 connection a client opens, directly or through a CONNECT/SOCKS tunnel.
 */
public final class HttpServerCodecs {

    private HttpServerCodecs() {
    }

    /**
     * Without a bound on requests awaiting their response, as on 8.0.0: the codec counts a request until it encodes
     * the response, and a raw-bytes {@code error()} response never passes its encoder, so Netty's default of 128
     * would close a connection on the 129th.
     */
    public static HttpServerCodec httpServerCodec(Configuration configuration) {
        return new HttpServerCodec(new HttpDecoderConfig()
            .setMaxInitialLineLength(configuration.maxInitialLineLength())
            .setMaxHeaderSize(configuration.maxHeaderSize())
            .setMaxChunkSize(configuration.maxChunkSize()), Integer.MAX_VALUE);
    }
}
