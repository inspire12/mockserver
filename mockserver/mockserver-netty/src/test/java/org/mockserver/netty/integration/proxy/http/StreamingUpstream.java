package org.mockserver.netty.integration.proxy.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A plain HTTP/1.1 server, independent of MockServer, whose responses are streamed: tokens sent at an interval, a
 * large body sent as fast as it is taken, and a response cut off part way. Each request names itself with an
 * {@code id} query parameter, by which a test reads what was written for it and whether its connection has closed.
 */
final class StreamingUpstream implements AutoCloseable {

    static final int FLOOD_CHUNK_BYTES = 8 * 1024;

    private final EventLoopGroup group = new NioEventLoopGroup(2);
    private final Channel server;
    private final Map<String, AtomicLong> written = new ConcurrentHashMap<>();
    private final Map<String, Channel> connections = new ConcurrentHashMap<>();

    StreamingUpstream() throws InterruptedException {
        server = new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HttpServerCodec());
                    ch.pipeline().addLast(new HttpObjectAggregator(1024 * 1024));
                    ch.pipeline().addLast(new StreamingHandler());
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
    }

    int port() {
        return ((InetSocketAddress) server.localAddress()).getPort();
    }

    /**
     * @return the body bytes the response to request {@code id} has had taken by its connection so far
     */
    long written(String id) {
        AtomicLong count = written.get(id);
        return count != null ? count.get() : 0;
    }

    boolean closedWithin(String id, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (connections.get(id) == null) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return connections.get(id).closeFuture().await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    boolean isOpen(String id) {
        Channel connection = connections.get(id);
        return connection != null && connection.isActive();
    }

    static String token(int index) {
        return "data: {\"token\":\"t" + index + "\"}\n\n";
    }

    static String tokens(int count) {
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < count; i++) {
            all.append(token(i));
        }
        return all.toString();
    }

    /**
     * The chunk of the flood that starts at {@code offset}: text, so a misplaced or missing chunk changes the digest.
     */
    static byte[] floodChunk(long offset) {
        byte[] chunk = new byte[FLOOD_CHUNK_BYTES];
        byte[] label = ("[" + offset + "]").getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = label[i % label.length];
        }
        return chunk;
    }

    static byte[] floodDigest(long totalBytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (long offset = 0; offset < totalBytes; offset += FLOOD_CHUNK_BYTES) {
            digest.update(floodChunk(offset));
        }
        return digest.digest();
    }

    @Override
    public void close() {
        server.close().syncUninterruptibly();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
    }

    private final class StreamingHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        private String id;
        private long floodBytes;
        private long floodOffset;
        private boolean flooding;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            QueryStringDecoder query = new QueryStringDecoder(request.uri());
            id = parameter(query, "id", "none");
            written.put(id, new AtomicLong());
            connections.put(id, ctx.channel());
            HttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);
            if (query.path().endsWith("/tokens")) {
                sendToken(ctx, 0, Integer.parseInt(parameter(query, "count", "10")), Long.parseLong(parameter(query, "gap", "100")), -1);
            } else if (query.path().endsWith("/dies")) {
                sendToken(ctx, 0, Integer.MAX_VALUE, 50, 3);
            } else {
                floodBytes = Long.parseLong(parameter(query, "bytes", "0"));
                flood(ctx);
            }
        }

        private void sendToken(ChannelHandlerContext ctx, int index, int count, long gapMillis, int dieAfter) {
            if (!ctx.channel().isActive()) {
                return;
            }
            if (index == dieAfter) {
                // cut off with no terminating chunk
                ctx.close();
                return;
            }
            if (index == count) {
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                return;
            }
            byte[] token = token(index).getBytes(StandardCharsets.UTF_8);
            ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(token)))
                .addListener(future -> {
                    if (future.isSuccess()) {
                        written.get(id).addAndGet(token.length);
                    }
                });
            ctx.executor().schedule(() -> sendToken(ctx, index + 1, count, gapMillis, dieAfter), gapMillis, TimeUnit.MILLISECONDS);
        }

        // writes while the connection takes it, so a reader that stops is seen as the count standing still
        private void flood(ChannelHandlerContext ctx) {
            // a write or a flush can change the writability, which calls back here
            if (flooding) {
                return;
            }
            flooding = true;
            try {
                while (ctx.channel().isActive() && ctx.channel().isWritable() && floodOffset < floodBytes) {
                    byte[] chunk = floodChunk(floodOffset);
                    floodOffset += chunk.length;
                    ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(chunk)))
                        .addListener(future -> {
                            if (future.isSuccess()) {
                                written.get(id).addAndGet(chunk.length);
                            }
                        });
                }
                if (floodBytes > 0 && floodOffset >= floodBytes) {
                    floodBytes = 0;
                    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                }
            } finally {
                flooding = false;
            }
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            if (floodBytes > 0) {
                // not from inside the write that changed it
                ctx.executor().execute(() -> flood(ctx));
            }
            ctx.fireChannelWritabilityChanged();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }

        private String parameter(QueryStringDecoder query, String name, String otherwise) {
            return query.parameters().containsKey(name) ? query.parameters().get(name).get(0) : otherwise;
        }
    }
}
