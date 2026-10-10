package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;

/**
 * Bytes of a response MockServer wrote as raw bytes, taken out of what the relay's HTTP/1.1 loopback reads by
 * {@link LoopbackRawResponseSplitter} for {@link DownstreamProxyRelayHandler} to write to the proxy client as
 * they are. Not an {@code HttpObject}, so the loopback's HTTP handlers pass it by.
 */
final class RawResponseBytes extends DefaultByteBufHolder {

    private final boolean endsResponse;
    private final int length;

    // owns the bytes: they are released with it, or by whoever takes its content to write it
    RawResponseBytes(ByteBuf bytes, boolean endsResponse) {
        super(bytes);
        this.endsResponse = endsResponse;
        this.length = bytes.readableBytes();
    }

    boolean endsResponse() {
        return endsResponse;
    }

    @Override
    public String toString() {
        // never the bytes themselves: this is logged when their write fails, by when they may have been released
        return "raw response bytes (" + length + (endsResponse ? " bytes, the last)" : " bytes)");
    }
}
