package org.mockserver.responsewriter;

import io.netty.handler.codec.http.HttpServerCodec;

/**
 * User event announcing a response about to be written as raw bytes: the next write issued from
 * {@link HttpServerCodec}'s context, in the same event loop task, is {@link #length()} bytes that do not pass
 * through the codec. {@link HttpExchangeEndedEvent#RAW_RESPONSE_WRITTEN} follows when that write completes.
 * The CONNECT/SOCKS relay uses it to tell those bytes from an encoded response's on its loopback.
 */
public final class RawResponseBytesEvent {

    private final int length;

    public RawResponseBytesEvent(int length) {
        this.length = length;
    }

    public int length() {
        return length;
    }

    @Override
    public String toString() {
        return "RawResponseBytesEvent(" + length + " bytes)";
    }
}
