package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http2.Http2Headers;

/**
 * One frame of a response the CONNECT/SOCKS relay hands from its HTTP/2 loopback to the proxy client as it is read:
 * the headers, one DATA frame's payload, or the trailers, with its stream id (the loopback's until
 * {@link LoopbackHttp2StreamIdRemapper} maps it). An {@link HttpObject} so {@link DownstreamProxyRelayHandler} relays
 * it, and not an {@code HttpContent}, which the client leg's handler would write to the stream it wrote to last.
 */
final class StreamedHttp2ResponsePart extends DefaultByteBufHolder implements HttpObject {

    private static final Runnable NOTHING_TO_RETURN = () -> {
    };

    private final Http2Headers headers;
    private final boolean endOfStream;
    private final Runnable written;
    private final int dataBytes;
    private int streamId;

    private StreamedHttp2ResponsePart(int streamId, Http2Headers headers, ByteBuf data, boolean endOfStream, Runnable written) {
        super(data);
        this.streamId = streamId;
        this.headers = headers;
        this.endOfStream = endOfStream;
        this.written = written;
        this.dataBytes = data.readableBytes();
    }

    // the response's headers, or with endOfStream its trailers
    static StreamedHttp2ResponsePart headers(int streamId, Http2Headers headers, boolean endOfStream) {
        return new StreamedHttp2ResponsePart(streamId, headers, Unpooled.EMPTY_BUFFER, endOfStream, NOTHING_TO_RETURN);
    }

    /**
     * @param data    owned by the part: released when the part is, or by whoever takes its content to write it
     * @param written run once the data has been written to the proxy client, or has failed to be
     */
    static StreamedHttp2ResponsePart data(int streamId, ByteBuf data, boolean endOfStream, Runnable written) {
        return new StreamedHttp2ResponsePart(streamId, null, data, endOfStream, written);
    }

    int streamId() {
        return streamId;
    }

    void streamId(int streamId) {
        this.streamId = streamId;
    }

    // null for a part that carries data
    Http2Headers headers() {
        return headers;
    }

    boolean endOfStream() {
        return endOfStream;
    }

    void written() {
        written.run();
    }

    @Override
    public DecoderResult decoderResult() {
        return DecoderResult.SUCCESS;
    }

    @Override
    @Deprecated
    public DecoderResult getDecoderResult() {
        return DecoderResult.SUCCESS;
    }

    @Override
    public void setDecoderResult(DecoderResult result) {
        // a part is built from a frame that has already been decoded
    }

    // no header value, and nothing read from the buffer: a part is logged when its write has failed and released it
    @Override
    public String toString() {
        return "StreamedHttp2ResponsePart(stream: " + streamId
            + (headers != null ? ", headers: " + headers.size() : ", data: " + dataBytes + " bytes")
            + (endOfStream ? ", end of stream)" : ")");
    }
}
