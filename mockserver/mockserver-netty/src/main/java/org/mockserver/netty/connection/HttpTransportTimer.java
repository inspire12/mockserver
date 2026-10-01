package org.mockserver.netty.connection;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.LastHttpContent;
import org.mockserver.metrics.Metrics;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;

/**
 * Times each HTTP/1.1 exchange on a connection for {@code mock_server_request_transport_duration_seconds}:
 * from the request head being decoded to the write of the last part of its response completing on the
 * socket. Unlike the handler timer it includes aggregation, event-loop hand-off of a response written from
 * another thread, encoding, and a slow reader holding the write back.
 * <p>
 * Installed directly after {@code HttpServerCodec}, one instance per connection, only when metrics are
 * enabled. Requests are paired with responses in arrival order, as HTTP/1.1 requires, so pipelined
 * requests are timed separately. A {@code 1xx} response other than {@code 101} precedes the real response
 * and ends nothing; a {@code 101} turns the connection into a WebSocket and the timer removes itself.
 * An exchange that ends without a {@code LastHttpContent} passing through (a raw-bytes {@code HttpError},
 * an abandoned exchange, a final {@code 1xx}) is announced by {@link HttpExchangeEndedEvent}: its start is
 * dropped unrecorded, so later exchanges on that keep-alive connection are still timed from their own start.
 */
public final class HttpTransportTimer extends ChannelDuplexHandler {

    private long[] startNanos = new long[2];
    private int head;
    private int size;
    private boolean informationalResponseHeadWritten;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            enqueue(System.nanoTime());
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt == HttpExchangeEndedEvent.INSTANCE && size > 0) {
            dequeue();
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        boolean switchingProtocols = false;
        if (msg instanceof HttpResponse) {
            HttpResponseStatus status = ((HttpResponse) msg).status();
            informationalResponseHeadWritten = status.codeClass() == HttpStatusClass.INFORMATIONAL;
            switchingProtocols = status.code() == HttpResponseStatus.SWITCHING_PROTOCOLS.code();
        }
        if (msg instanceof LastHttpContent) {
            if (informationalResponseHeadWritten) {
                informationalResponseHeadWritten = false;
            } else if (size > 0) {
                long start = dequeue();
                promise = promise.unvoid();
                promise.addListener(future -> {
                    if (future.isSuccess()) {
                        Metrics.observeRequestTransportDurationSeconds((System.nanoTime() - start) / 1_000_000_000.0);
                    }
                });
            }
        }
        ctx.write(msg, promise);
        if (switchingProtocols) {
            size = 0;
            ctx.pipeline().remove(this);
        }
    }

    private void enqueue(long nanos) {
        if (size == startNanos.length) {
            long[] grown = new long[size * 2];
            for (int i = 0; i < size; i++) {
                grown[i] = startNanos[(head + i) % size];
            }
            startNanos = grown;
            head = 0;
        }
        startNanos[(head + size) % startNanos.length] = nanos;
        size++;
    }

    private long dequeue() {
        long nanos = startNanos[head];
        head = (head + 1) % startNanos.length;
        size--;
        return nanos;
    }
}
