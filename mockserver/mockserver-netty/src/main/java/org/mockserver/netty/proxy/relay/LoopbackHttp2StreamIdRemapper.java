package org.mockserver.netty.proxy.relay;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionAdapter;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_DEPENDENCY_ID;
import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_ID;

/**
 * Gives each request the CONNECT/SOCKS relay writes to its HTTP/2 loopback a loopback stream id of its own, and puts
 * the proxy client's stream id back on the response. Requests reach the loopback in the order they finish, not the
 * order their streams opened, and a client stream id is only ever increasing on the client's connection: reused on the
 * loopback ({@code x-http2-stream-id}), a stream that finished after a later one would be opened below the last loopback
 * id, which is a connection error that closes the whole tunnel.
 * <p>
 * Each pair is forgotten when its loopback stream is removed, so a long-lived tunnel holds one entry per open stream.
 * A loopback response with no pair (only a server push could have one) is dropped. Flow control and PRIORITY frames are
 * not relayed between the legs, and a GOAWAY is not translated: {@link LoopbackHttp2ConnectionCloseHandler} sends the
 * client one of its own, from the client connection's ids. A priority dependency on a request is translated, or dropped
 * when it names no open stream. Sits after the loopback's {@code Http2ConnectionHandler}; one per
 * loopback. It reads and marks the proxy client connection's streams directly, which is safe only because the loopback is
 * bootstrapped on the proxy client's event loop ({@code RelayConnectHandler.channelRead0}), so both channels, both
 * connections and this handler are confined to that one thread.
 */
public class LoopbackHttp2StreamIdRemapper extends ChannelDuplexHandler {

    private final MockServerLogger mockServerLogger;
    private final Http2Connection loopbackConnection;
    private final IntObjectMap<Integer> loopbackIdByClientId = new IntObjectHashMap<>();
    private final IntObjectMap<Integer> clientIdByLoopbackId = new IntObjectHashMap<>();
    private final Channel proxyClientChannel;
    private Http2Connection proxyClientConnection;
    private Http2Connection.PropertyKey pairedKey;
    private Http2Connection.PropertyKey answeredKey;

    public LoopbackHttp2StreamIdRemapper(MockServerLogger mockServerLogger, Http2Connection loopbackConnection, Channel proxyClientChannel) {
        this.mockServerLogger = mockServerLogger;
        this.loopbackConnection = loopbackConnection;
        this.proxyClientChannel = proxyClientChannel;
        // removal follows every listener's onStreamClosed, so a handler reacting to the close can still map the id
        loopbackConnection.addListener(new Http2ConnectionAdapter() {
            @Override
            public void onStreamRemoved(Http2Stream stream) {
                forget(stream.id());
            }
        });
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        Integer clientId = msg instanceof HttpMessage ? ((HttpMessage) msg).headers().getInt(STREAM_ID.text()) : null;
        if (clientId == null) {
            ctx.write(msg, promise);
            return;
        }
        HttpHeaders headers = ((HttpMessage) msg).headers();
        Integer paired = loopbackIdByClientId.get(clientId);
        Http2Stream pairedStream = paired != null ? loopbackConnection.stream(paired) : null;
        Integer loopbackId = paired != null && pairedStream != null && pairedStream.state().localSideOpen() ? paired : null;
        if (paired == null) {
            loopbackId = pair(clientId);
        }
        if (loopbackId == null) {
            // a second request on a client stream whose relayed request has already been sent whole: a request with
            // Expect reaches the relay as its headers, then its body, and a HEADERS frame on that stream now would close
            // the whole loopback (if it is still open) or have MockServer answer the request twice (if it has closed)
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("dropping a second request on stream {} from {} whose relayed request was already sent")
                        .setArguments(clientId, proxyClientAddress())
                );
            }
            ReferenceCountUtil.release(msg);
            // not failed: UpstreamProxyRelayHandler closes the whole tunnel when a relayed write fails
            promise.trySuccess();
            return;
        }
        headers.setInt(STREAM_ID.text(), loopbackId);
        translateDependency(headers, loopbackIdByClientId, loopbackId);
        // the loopback's Http2ConnectionHandler opens the stream as it handles this write, in this call
        ctx.write(msg, promise);
        if (loopbackConnection.stream(loopbackId) == null && !loopbackConnection.streamMayHaveExisted(loopbackId)) {
            // never opened (the write failed first), so no removal will ever forget it, and MockServer never saw it
            forget(loopbackId);
            Http2Stream clientStream = paired == null ? proxyClientStream(clientId) : null;
            if (clientStream != null) {
                clientStream.setProperty(pairedKey, Boolean.FALSE);
            }
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Integer loopbackId = msg instanceof HttpMessage ? ((HttpMessage) msg).headers().getInt(STREAM_ID.text()) : null;
        if (loopbackId != null) {
            Integer clientId = clientIdByLoopbackId.get(loopbackId);
            if (clientId == null) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setMessageFormat("dropping a response on relayed stream {} that answers no request from {}")
                            .setArguments(loopbackId, proxyClientAddress())
                    );
                }
                ReferenceCountUtil.release(msg);
                return;
            }
            HttpHeaders headers = ((HttpMessage) msg).headers();
            headers.setInt(STREAM_ID.text(), clientId);
            translateDependency(headers, clientIdByLoopbackId, clientId);
            // a 1xx is handed on as soon as it arrives; only a final response is the whole answer
            Http2Stream clientStream = msg instanceof FullHttpResponse && ((FullHttpResponse) msg).status().codeClass() != HttpStatusClass.INFORMATIONAL ? proxyClientStream(clientId) : null;
            if (clientStream != null) {
                clientStream.setProperty(answeredKey, Boolean.TRUE);
            }
        }
        ctx.fireChannelRead(msg);
    }

    /**
     * The proxy client's stream id for a loopback stream id, or {@code null} when the loopback stream answers no client
     * stream.
     */
    Integer clientStreamId(int loopbackStreamId) {
        return clientIdByLoopbackId.get(loopbackStreamId);
    }

    /**
     * The loopback stream id carrying a proxy client stream's request, or {@code null} before its request is written.
     */
    Integer loopbackStreamId(int clientStreamId) {
        return loopbackIdByClientId.get(clientStreamId);
    }

    /**
     * The loopback stream id for a proxy client stream's request: the one already paired with it, or the next one,
     * which is paired with it until that loopback stream is removed. A new id is the last stream created plus 2, so the
     * stream must be opened (or the pair forgotten) before another client stream is paired. {@code null} for a client
     * stream that was paired before and whose loopback stream has closed: a second request on a stream MockServer has
     * already answered (a request with {@code Expect} reaches the relay as its headers, then later its body). The mark
     * lives on the client's own stream, so it goes with it; a loopback stream that never opens leaves the client stream
     * marked as paired but not relayed.
     */
    Integer pair(int clientStreamId) {
        Integer paired = loopbackIdByClientId.get(clientStreamId);
        if (paired != null) {
            return paired;
        }
        Http2Stream clientStream = proxyClientStream(clientStreamId);
        if (clientStream != null && clientStream.getProperty(pairedKey) != null) {
            return null;
        }
        int loopbackId = nextLoopbackStreamId();
        if (clientStream != null) {
            clientStream.setProperty(pairedKey, Boolean.TRUE);
        }
        loopbackIdByClientId.put(clientStreamId, Integer.valueOf(loopbackId));
        clientIdByLoopbackId.put(loopbackId, Integer.valueOf(clientStreamId));
        return loopbackId;
    }

    /**
     * Whether a request on this proxy client stream has been handed to the loopback, so MockServer may have received it.
     * A request with {@code Expect} is handed on as its headers while the client is still sending the body.
     */
    boolean relayed(int clientStreamId) {
        Http2Stream clientStream = proxyClientStream(clientStreamId);
        return clientStream != null && Boolean.TRUE.equals(clientStream.getProperty(pairedKey));
    }

    /**
     * Whether a whole final response for this proxy client stream has been handed on towards the client, so all that is
     * left of the stream is the client's side of it and the response's bytes still queued for the client.
     */
    boolean answered(int clientStreamId) {
        Http2Stream clientStream = proxyClientStream(clientStreamId);
        return clientStream != null && clientStream.getProperty(answeredKey) != null;
    }

    private Http2Stream proxyClientStream(int clientStreamId) {
        if (proxyClientConnection == null) {
            Http2ConnectionHandler clientHandler = proxyClientChannel != null ? proxyClientChannel.pipeline().get(Http2ConnectionHandler.class) : null;
            if (clientHandler == null) {
                return null;
            }
            proxyClientConnection = clientHandler.connection();
            pairedKey = proxyClientConnection.newKey();
            answeredKey = proxyClientConnection.newKey();
        }
        return proxyClientConnection.stream(clientStreamId);
    }

    private Object proxyClientAddress() {
        return proxyClientChannel != null ? proxyClientChannel.remoteAddress() : null;
    }

    int mappedStreams() {
        return clientIdByLoopbackId.size();
    }

    private int nextLoopbackStreamId() {
        // the loopback is the client side, so its ids are odd; this handler is the only thing that opens its streams
        int lastCreated = loopbackConnection.local().lastStreamCreated();
        return lastCreated == 0 ? 1 : lastCreated + 2;
    }

    private static void translateDependency(HttpHeaders headers, IntObjectMap<Integer> ids, int ownStreamId) {
        Integer dependency = headers.getInt(STREAM_DEPENDENCY_ID.text());
        if (dependency != null) {
            Integer translated = ids.get(dependency);
            // a stream depending on itself is a stream error, so a dependency naming its own stream is dropped too
            if (translated != null && translated != ownStreamId) {
                headers.setInt(STREAM_DEPENDENCY_ID.text(), translated);
            } else {
                headers.remove(STREAM_DEPENDENCY_ID.text());
            }
        }
    }

    private void forget(int loopbackStreamId) {
        Integer clientId = clientIdByLoopbackId.remove(loopbackStreamId);
        if (clientId != null) {
            loopbackIdByClientId.remove(clientId);
        }
    }
}
