package org.mockserver.netty.unification;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ChannelOutputShutdownException;
import io.netty.channel.socket.DuplexChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.ReadAfterFailedWrite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The server HTTP/2 handlers of a connection whose output has ended, as a failed write ends it on an accepted
 * connection: asked to close, or told of the failed write, they must not send a {@code GOAWAY} and close (after a
 * {@code GOAWAY} a stream the client opens later is ignored), so that a request the client sent before it went is still
 * decoded. Over real sockets, as only a socket's output can end on its own.
 */
@SuppressWarnings("deprecation")
public class Http2ReadAfterFailedWriteTest {

    private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final MockServerLogger LOGGER = new MockServerLogger(Http2ReadAfterFailedWriteTest.class);

    private final Set<Integer> streamsRead = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<Channel> accepted = new LinkedBlockingQueue<>();
    private EventLoopGroup group;
    private Channel server;

    @After
    public void stopServer() {
        if (server != null) {
            server.close().syncUninterruptibly();
        }
        if (group != null) {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void shouldWaitForTheInputToEndWhenTheDirectCodecIsAskedToClose() throws Exception {
        shouldReadOnAfter(this::directCodec, this::close);
    }

    @Test
    public void shouldWaitForTheInputToEndWhenTheTunnelHandlerIsAskedToClose() throws Exception {
        shouldReadOnAfter(this::tunnelHandler, this::close);
    }

    @Test
    public void shouldNotCloseTheDirectCodecForTheFailedWrite() throws Exception {
        shouldReadOnAfter(this::directCodec, this::reportFailedWrite);
    }

    @Test
    public void shouldNotCloseTheTunnelHandlerForTheFailedWrite() throws Exception {
        shouldReadOnAfter(this::tunnelHandler, this::reportFailedWrite);
    }

    private interface AfterOutputEnded {
        ChannelFuture apply(Channel channel);
    }

    private ChannelFuture close(Channel channel) {
        return channel.close();
    }

    private ChannelFuture reportFailedWrite(Channel channel) {
        ChannelHandlerContext ctx = channel.pipeline().context(Http2ConnectionHandler.class);
        ((Http2ConnectionHandler) ctx.handler()).onError(ctx, true, new ChannelOutputShutdownException("Channel output shutdown", new IOException("Broken pipe")));
        return channel.closeFuture();
    }

    private void shouldReadOnAfter(Supplier<ChannelHandler> handler, AfterOutputEnded afterOutputEnded) throws Exception {
        startServer(handler);
        try (Socket client = new Socket("127.0.0.1", ((InetSocketAddress) server.localAddress()).getPort())) {
            client.setSoTimeout(10_000);
            ByteArrayOutputStream preface = new ByteArrayOutputStream();
            preface.write(PREFACE);
            writeFrame(preface, 0x4, 0x0, 0, new byte[0]);
            client.getOutputStream().write(preface.toByteArray());
            awaitServerSettings(client.getInputStream());
            Channel channel = accepted.poll(10, TimeUnit.SECONDS);

            // the output ends as a failed write ends it, then the handler is asked to close or told of the failure
            ChannelFuture closed = channel.eventLoop().submit(() -> {
                ((DuplexChannel) channel).shutdownOutput();
                return afterOutputEnded.apply(channel);
            }).get(10, TimeUnit.SECONDS);
            Thread.sleep(300);
            assertThat("still open, reading on", channel.isOpen(), is(true));
            assertThat("reading on", ReadAfterFailedWrite.isReadingOn(channel), is(true));

            client.getOutputStream().write(get(1));
            await("the request sent after the output ended was decoded", () -> streamsRead.contains(1));

            client.close();
            await("closed as the input ended", closed::isDone);
            assertThat("closed", channel.isOpen(), is(false));
        }
    }

    private void startServer(Supplier<ChannelHandler> handler) throws InterruptedException {
        group = new NioEventLoopGroup(1);
        server = new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel channel) {
                    ReadAfterFailedWrite.install(channel);
                    channel.pipeline().addLast(handler.get());
                    channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            if (msg instanceof Http2HeadersFrame) {
                                streamsRead.add(((Http2HeadersFrame) msg).stream().id());
                            }
                            ReferenceCountUtil.release(msg);
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            // the client leaving
                        }
                    });
                    accepted.add(channel);
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
    }

    private ChannelHandler directCodec() {
        return Http2RequestHeaderLimit.frameCodecBuilder(LOGGER).initialSettings(Http2RequestHeaderLimit.serverSettings(configuration())).build();
    }

    private ChannelHandler tunnelHandler() {
        return Http2RequestHeaderLimit.tunnelServerHandler(configuration(), LOGGER, new DefaultHttp2Connection(true), new Http2FrameAdapter() {
            @Override
            public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding, boolean endOfStream) {
                streamsRead.add(streamId);
            }

            @Override
            public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endOfStream) {
                streamsRead.add(streamId);
            }
        }, null, false);
    }

    /**
     * HEADERS ending the stream, with no dynamic table: indexed ":method: GET" and ":scheme: http", literal ":path" and
     * ":authority".
     */
    private static byte[] get(int streamId) {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        headers.write(0x82);
        headers.write(0x86);
        literal(headers, 0x04, "/");
        literal(headers, 0x01, "localhost");
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeFrame(frame, 0x1, 0x5, streamId, headers.toByteArray());
        return frame.toByteArray();
    }

    private static void literal(ByteArrayOutputStream headers, int nameIndex, String value) {
        headers.write(nameIndex);
        headers.write(value.length());
        headers.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeFrame(ByteArrayOutputStream out, int type, int flags, int streamId, byte[] payload) {
        out.write(payload.length >> 16);
        out.write(payload.length >> 8);
        out.write(payload.length);
        out.write(type);
        out.write(flags);
        out.write(streamId >> 24);
        out.write(streamId >> 16);
        out.write(streamId >> 8);
        out.write(streamId);
        out.writeBytes(payload);
    }

    private static void awaitServerSettings(InputStream input) throws IOException {
        while (true) {
            byte[] header = input.readNBytes(9);
            if (header.length < 9) {
                throw new IOException("connection closed before the server's SETTINGS");
            }
            int length = (header[0] & 0xff) << 16 | (header[1] & 0xff) << 8 | header[2] & 0xff;
            input.readNBytes(length);
            if (header[3] == 0x4 && (header[4] & 0x1) == 0) {
                return;
            }
        }
    }

    private static void await(String reason, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(reason, condition.getAsBoolean(), is(true));
    }
}
