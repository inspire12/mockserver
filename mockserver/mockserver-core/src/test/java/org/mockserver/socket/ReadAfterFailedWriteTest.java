package org.mockserver.socket;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DuplexChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Over real sockets: a client sends bytes and resets its connection while the server still has a response to write,
 * and the server's event loop runs only once both have arrived, so the write is tried, and fails, before the bytes are
 * read.
 */
@SuppressWarnings("deprecation")
public class ReadAfterFailedWriteTest {

    private EventLoopGroup group;
    private Channel server;
    private final BlockingQueue<Channel> accepted = new LinkedBlockingQueue<>();
    private final StringBuffer read = new StringBuffer();
    private volatile boolean install = true;

    @Before
    public void startServer() throws InterruptedException {
        group = new NioEventLoopGroup(1);
        server = new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel channel) {
                    if (install) {
                        ReadAfterFailedWrite.install(channel);
                    }
                    channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            read.append(((ByteBuf) msg).toString(StandardCharsets.US_ASCII));
                            ReferenceCountUtil.release(msg);
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            // the client's reset, read after its bytes
                        }
                    });
                    accepted.add(channel);
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
    }

    @After
    public void stopServer() {
        server.close().syncUninterruptibly();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    public void shouldReadWhatTheClientSentBeforeAWriteFailedThenClose() throws Exception {
        Channel channel = resetBehindAStalledWrite("the request", false);

        await("the bytes sent before the reset were read", () -> read.toString().equals("the request"));
        await("the channel closed as its input ended", () -> !channel.isOpen());
    }

    @Test
    public void shouldLoseWhatTheClientSentWithoutIt() throws Exception {
        install = false;
        Channel channel = resetBehindAStalledWrite("the request", false);

        await("the channel closed", () -> !channel.isOpen());
        assertThat("Netty closes on the failed write with the bytes unread", read.toString(), is(""));
    }

    @Test
    public void shouldCloseAtOnceWhenReadsArePausedAsTheWriteFails() throws Exception {
        Channel channel = resetBehindAStalledWrite("the request", true);

        await("the channel closed", () -> !channel.isOpen(), 1);
        assertThat("nothing was read", read.toString(), is(""));
    }

    @Test
    public void shouldCloseAfterTheLingerLimitWhenTheInputDoesNotEnd() throws Exception {
        try (Socket client = new Socket("127.0.0.1", ((InetSocketAddress) server.localAddress()).getPort())) {
            Channel channel = accepted.poll(10, TimeUnit.SECONDS);
            // the output ends as it does after a failed write, but the client stays
            ((DuplexChannel) channel).shutdownOutput().sync();
            assertThat("reading on", ReadAfterFailedWrite.isReadingOn(channel), is(true));
            client.getOutputStream().write("more".getBytes(StandardCharsets.US_ASCII));
            await("bytes sent after the output ended are read", () -> read.toString().equals("more"));

            Thread.sleep(LingeringClose.LINGER_MILLIS - 1_000);
            assertThat("still open before the linger limit", channel.isOpen(), is(true));
            await("closed at the linger limit", () -> !channel.isOpen(), 3);
        }
    }

    @Test
    public void shouldLeaveAnOutputEndedOnPurposeToWhoeverEndedIt() throws Exception {
        try (Socket ignored = new Socket("127.0.0.1", ((InetSocketAddress) server.localAddress()).getPort())) {
            Channel channel = accepted.poll(10, TimeUnit.SECONDS);
            channel.eventLoop().submit(() -> channel.config().setAutoRead(false)).sync();
            ReadAfterFailedWrite.endOutput((DuplexChannel) channel).sync();

            Thread.sleep(500);
            assertThat("not closed, though its reads are paused", channel.isOpen(), is(true));
            channel.close().sync();
        }
    }

    @Test
    public void shouldCompleteADeferredCloseWhenTheChannelCloses() throws Exception {
        try (Socket client = new Socket("127.0.0.1", ((InetSocketAddress) server.localAddress()).getPort())) {
            Channel channel = accepted.poll(10, TimeUnit.SECONDS);
            assertThat("not reading on while the output is open", ReadAfterFailedWrite.isReadingOn(channel), is(false));
            ((DuplexChannel) channel).shutdownOutput().sync();
            ChannelPromise promise = channel.newPromise();
            ReadAfterFailedWrite.closeWhenInputEnds(channel, promise);
            assertThat("left open", promise.isDone() || !channel.isOpen(), is(false));

            client.close();
            await("the promise completed as the input ended", promise::isSuccess);
            assertThat("not reading on once closed", ReadAfterFailedWrite.isReadingOn(channel), is(false));
        }
    }

    @Test
    public void shouldNotTakeAChannelWithNoOutboundBufferOfItsOwnForOneReadOn() {
        // an HTTP/2 stream's channel is active with no outbound buffer, and is not a socket
        Channel stream = mock(Channel.class);
        Channel.Unsafe unsafe = mock(Channel.Unsafe.class);
        when(stream.isActive()).thenReturn(true);
        when(stream.unsafe()).thenReturn(unsafe);
        when(unsafe.outboundBuffer()).thenReturn(null);

        assertThat(ReadAfterFailedWrite.isReadingOn(stream), is(false));
    }

    /**
     * The server writes more than the sockets hold to a client that reads none of it; then, with the server's event loop
     * held, the client sends {@code bytes} and resets the connection.
     */
    private Channel resetBehindAStalledWrite(String bytes, boolean pauseReads) throws Exception {
        Socket client = new Socket("127.0.0.1", ((InetSocketAddress) server.localAddress()).getPort());
        Channel channel = accepted.poll(10, TimeUnit.SECONDS);
        channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[16 * 1024 * 1024]));
        awaitStalled(client);

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        channel.eventLoop().execute(() -> {
            if (pauseReads) {
                channel.config().setAutoRead(false);
            }
            held.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat("event loop held", held.await(10, TimeUnit.SECONDS), is(true));
            client.getOutputStream().write(bytes.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            client.setSoLinger(true, 0);
            client.close();
            // loopback delivers the bytes and the reset before the loop runs again
            Thread.sleep(300);
        } finally {
            release.countDown();
        }
        return channel;
    }

    private static void awaitStalled(Socket client) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int before = -1;
        while (System.nanoTime() < deadline) {
            Thread.sleep(250);
            int now = client.getInputStream().available();
            if (now > 0 && now == before) {
                return;
            }
            before = now;
        }
        throw new AssertionError("the write did not stall within 10 seconds");
    }

    private static void await(String reason, BooleanSupplier condition) throws InterruptedException {
        await(reason, condition, 10);
    }

    private static void await(String reason, BooleanSupplier condition, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(reason, condition.getAsBoolean(), is(true));
    }
}
