package org.mockserver.netty.unification;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * A large HTTP/1.1 body must reach a slow reader a slice at a time, not sit whole in the connection's
 * outbound buffer, and pacing must not change a single byte on the wire or the order of later writes.
 */
public class PacedLargeWriteHandlerTest {

    private static final WriteBufferWaterMark WATER_MARK = new WriteBufferWaterMark(8 * 1024, 32 * 1024);
    private static NioEventLoopGroup group;

    @BeforeClass
    public static void createEventLoop() {
        group = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopEventLoop() {
        group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }

    @Test
    public void shouldBufferAboutOneSliceOfALargeBodyForASlowReader() throws Exception {
        int bodySize = 8 * 1024 * 1024;

        long pacedPending = pendingOutboundWhileClientDoesNotRead(true, bodySize);
        long unpacedPending = pendingOutboundWhileClientDoesNotRead(false, bodySize);

        // the kernel socket buffers absorb some of the body either way; what is left is the connection's own buffering
        assertThat(pacedPending, lessThanOrEqualTo((long) PacedLargeWriteHandler.SLICE_BYTES + WATER_MARK.high()));
        assertThat(unpacedPending, greaterThan(bodySize / 2L));
    }

    @Test
    public void shouldDeliverTheWholeBodyToASlowReader() throws Exception {
        int bodySize = 3 * 1024 * 1024 + 17;
        CompletableFuture<Channel> serverChannel = new CompletableFuture<>();
        Channel server = startServer(true, serverChannel, (ctx, request) -> ctx.writeAndFlush(response(bodySize, false)));
        try (Socket socket = slowClient(server)) {
            sendRequest(socket, "GET", "/");
            Thread.sleep(300);
            byte[] received = readResponseBody(socket.getInputStream());
            assertThat(Arrays.equals(received, body(bodySize)), is(true));
        } finally {
            server.close().syncUninterruptibly();
        }
    }

    @Test
    public void shouldWriteIdenticalBytesWithAndWithoutPacing() throws Exception {
        int slice = PacedLargeWriteHandler.SLICE_BYTES;
        int threshold = PacedLargeWriteHandler.PACE_THRESHOLD_BYTES;
        int[] sizes = {0, 1, threshold - 1, threshold, threshold + 1, threshold + slice + 1, 1024 * 1024 + 7};
        for (String method : new String[]{"GET", "HEAD"}) {
            for (boolean chunked : new boolean[]{false, true}) {
                for (int size : sizes) {
                    byte[] paced = rawResponse(true, method, size, chunked);
                    byte[] unpaced = rawResponse(false, method, size, chunked);
                    assertThat(method + " size=" + size + " chunked=" + chunked, Arrays.equals(paced, unpaced), is(true));
                    if (method.equals("GET") && !chunked) {
                        assertThat(paced.length, greaterThanOrEqualTo(size));
                    }
                }
            }
        }
    }

    @Test
    public void shouldKeepLaterWritesBehindAPacedBody() throws Exception {
        int bodySize = 2 * 1024 * 1024;
        byte[] rawBytes = "RAW-BYTES-WRITTEN-FROM-THE-CODEC-CONTEXT".getBytes(StandardCharsets.US_ASCII);
        Channel server = startServer(true, new CompletableFuture<>(), (ctx, request) -> {
            ctx.writeAndFlush(response(bodySize, false));
            // HttpErrorActionHandler writes raw response bytes from the codec's context
            ctx.pipeline().context(HttpServerCodec.class).writeAndFlush(Unpooled.wrappedBuffer(rawBytes));
            ctx.writeAndFlush(response(10, false)).addListener(ChannelFutureListener.CLOSE);
        });
        try (Socket socket = slowClient(server)) {
            sendRequest(socket, "GET", "/");
            Thread.sleep(300);
            byte[] all = readToEnd(socket.getInputStream());
            byte[] expected = concat(encoded(response(bodySize, false)), rawBytes, encoded(response(10, false)));
            assertThat(Arrays.equals(all, expected), is(true));
        } finally {
            server.close().syncUninterruptibly();
        }
    }

    @Test
    public void shouldReleaseTheBodyAndFailTheWriteWhenTheReaderGoesAway() throws Exception {
        int bodySize = 4 * 1024 * 1024;
        FullHttpResponse response = response(bodySize, false);
        ByteBuf content = response.content();
        CompletableFuture<ChannelFuture> write = new CompletableFuture<>();
        CompletableFuture<Channel> serverChannel = new CompletableFuture<>();
        Channel server = startServer(true, serverChannel, (ctx, request) -> write.complete(ctx.writeAndFlush(response)));
        try {
            Socket socket = slowClient(server);
            sendRequest(socket, "GET", "/");
            ChannelFuture writeFuture = write.get(10, TimeUnit.SECONDS);
            socket.setSoLinger(true, 0);
            socket.close();

            assertThat(writeFuture.await(10, TimeUnit.SECONDS), is(true));
            assertThat(writeFuture.isSuccess(), is(false));
            Channel channel = serverChannel.get(10, TimeUnit.SECONDS);
            channel.closeFuture().await(10, TimeUnit.SECONDS);
            assertThat(channel.eventLoop().submit(content::refCnt).get(10, TimeUnit.SECONDS), is(0));
        } finally {
            server.close().syncUninterruptibly();
        }
    }

    @Test
    public void shouldSliceOnlyBuffersAboveTheThresholdWhileTheHttpCodecIsPresent() {
        EmbeddedChannel withCodec = new EmbeddedChannel(new PacedLargeWriteHandler(), new HttpServerCodec());
        ByteBuf large = Unpooled.wrappedBuffer(body(PacedLargeWriteHandler.PACE_THRESHOLD_BYTES + 1));
        ChannelFuture paced = withCodec.pipeline().context(HttpServerCodec.class).writeAndFlush(large);

        List<ByteBuf> slices = drainOutbound(withCodec);
        assertThat(slices.size(), is(3));
        assertThat(slices.get(0).readableBytes(), is(PacedLargeWriteHandler.SLICE_BYTES));
        assertThat(slices.get(2).readableBytes(), is(1));
        assertThat(Arrays.equals(concat(slices), body(PacedLargeWriteHandler.PACE_THRESHOLD_BYTES + 1)), is(true));
        assertThat(paced.isSuccess(), is(true));
        slices.forEach(ByteBuf::release);
        assertThat(large.refCnt(), is(0));

        ByteBuf atThreshold = Unpooled.wrappedBuffer(body(PacedLargeWriteHandler.PACE_THRESHOLD_BYTES));
        withCodec.pipeline().context(HttpServerCodec.class).writeAndFlush(atThreshold);
        assertThat(withCodec.readOutbound(), sameInstance(atThreshold));
        atThreshold.release();

        // after a WebSocket upgrade the codec is gone and frames must reach the outbound buffer whole
        EmbeddedChannel withoutCodec = new EmbeddedChannel(new PacedLargeWriteHandler());
        ByteBuf frame = Unpooled.wrappedBuffer(body(PacedLargeWriteHandler.PACE_THRESHOLD_BYTES * 2));
        withoutCodec.writeAndFlush(frame);
        assertThat(withoutCodec.readOutbound(), sameInstance(frame));
        frame.release();

        assertThat(withCodec.finishAndReleaseAll(), is(false));
        assertThat(withoutCodec.finishAndReleaseAll(), is(false));
    }

    private long pendingOutboundWhileClientDoesNotRead(boolean paced, int bodySize) throws Exception {
        CompletableFuture<Channel> serverChannel = new CompletableFuture<>();
        Channel server = startServer(paced, serverChannel, (ctx, request) -> ctx.writeAndFlush(response(bodySize, false)));
        try (Socket socket = slowClient(server)) {
            sendRequest(socket, "GET", "/");
            Channel channel = serverChannel.get(10, TimeUnit.SECONDS);
            long pending = -1;
            long previous = -2;
            // settled once two reads 200ms apart agree and the connection has stopped being writable
            for (int i = 0; i < 50 && (pending != previous || channel.isWritable()); i++) {
                Thread.sleep(200);
                previous = pending;
                pending = channel.eventLoop().submit(() -> channel.unsafe().outboundBuffer().totalPendingWriteBytes()).get(5, TimeUnit.SECONDS);
            }
            return pending;
        } finally {
            server.close().syncUninterruptibly();
        }
    }

    private Channel startServer(boolean paced, CompletableFuture<Channel> acceptedChannel, BiConsumer<ChannelHandlerContext, FullHttpRequest> responder) {
        return new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WATER_MARK)
            .childOption(ChannelOption.SO_SNDBUF, 8 * 1024)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    if (paced) {
                        ch.pipeline().addLast(new PacedLargeWriteHandler());
                    }
                    ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(1024 * 1024), new SimpleChannelInboundHandler<FullHttpRequest>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                            acceptedChannel.complete(ctx.channel());
                            responder.accept(ctx, request);
                        }
                    });
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0))
            .syncUninterruptibly()
            .channel();
    }

    private byte[] rawResponse(boolean paced, String method, int size, boolean chunked) throws Exception {
        Channel server = startServer(paced, new CompletableFuture<>(), (ctx, request) -> ctx.writeAndFlush(response(size, chunked)).addListener(ChannelFutureListener.CLOSE));
        try (Socket socket = new Socket()) {
            socket.connect(server.localAddress(), 5000);
            socket.setSoTimeout(10000);
            sendRequest(socket, method, "/");
            return readToEnd(socket.getInputStream());
        } finally {
            server.close().syncUninterruptibly();
        }
    }

    private static Socket slowClient(Channel server) throws IOException {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(4 * 1024);
        socket.connect(server.localAddress(), 5000);
        socket.setSoTimeout(30000);
        return socket;
    }

    private static void sendRequest(Socket socket, String method, String path) throws IOException {
        socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static FullHttpResponse response(int size, boolean chunked) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body(size)));
        if (chunked) {
            HttpUtil.setTransferEncodingChunked(response, true);
        } else {
            HttpUtil.setContentLength(response, size);
        }
        return response;
    }

    private static byte[] encoded(FullHttpResponse response) {
        EmbeddedChannel encoder = new EmbeddedChannel(new HttpResponseEncoder());
        encoder.writeOutbound(response);
        List<ByteBuf> buffers = drainOutbound(encoder);
        byte[] bytes = concat(buffers);
        buffers.forEach(ByteBuf::release);
        encoder.finishAndReleaseAll();
        return bytes;
    }

    private static byte[] body(int size) {
        byte[] body = new byte[size];
        for (int i = 0; i < size; i++) {
            body[i] = (byte) ((i * 31 + i / 251) % 251);
        }
        return body;
    }

    private static List<ByteBuf> drainOutbound(EmbeddedChannel channel) {
        List<ByteBuf> buffers = new ArrayList<>();
        for (Object message = channel.readOutbound(); message != null; message = channel.readOutbound()) {
            buffers.add((ByteBuf) message);
        }
        return buffers;
    }

    private static byte[] concat(List<ByteBuf> buffers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (ByteBuf buffer : buffers) {
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            out.write(bytes, 0, bytes.length);
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private static byte[] readToEnd(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] readResponseBody(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int c = in.read();
            if (c < 0) {
                throw new IOException("connection closed in the response head");
            }
            head.append((char) c);
        }
        int length = -1;
        for (String line : head.toString().split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                length = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) {
                throw new IOException("connection closed after " + read + " of " + length + " body bytes");
            }
            read += n;
        }
        return body;
    }
}
