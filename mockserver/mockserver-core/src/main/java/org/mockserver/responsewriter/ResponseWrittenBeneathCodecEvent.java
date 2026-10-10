package org.mockserver.responsewriter;

import io.netty.handler.codec.http.HttpServerCodec;

/**
 * User event announcing that the write of a response to the oldest request awaiting one has just been issued from
 * {@link HttpServerCodec}'s context, as raw bytes the codec's encoder never sees. The codec pairs each request with
 * the next response it encodes, so it must be told, or it pairs the next one with this request.
 * Fired from the codec's context, in the event loop task that issued the write, immediately after it.
 */
public final class ResponseWrittenBeneathCodecEvent {

    public static final ResponseWrittenBeneathCodecEvent INSTANCE = new ResponseWrittenBeneathCodecEvent();

    private ResponseWrittenBeneathCodecEvent() {
    }

    @Override
    public String toString() {
        return "ResponseWrittenBeneathCodecEvent";
    }
}
