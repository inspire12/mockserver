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
     * Without a bound on requests awaiting their response, as on 8.0.0: Netty's default of 128 would close a
     * connection whose client pipelines more requests than that before the first is answered. Add it between the
     * handlers of an {@link HttpServerCodecResponsePairing}, which keeps its pairing of requests and responses in step.
     */
    public static HttpServerCodec httpServerCodec(Configuration configuration) {
        return new HttpServerCodec(new HttpDecoderConfig()
            .setMaxInitialLineLength(configuration.maxInitialLineLength())
            .setMaxHeaderSize(configuration.maxHeaderSize())
            .setMaxChunkSize(configuration.maxChunkSize()), Integer.MAX_VALUE);
    }
}
