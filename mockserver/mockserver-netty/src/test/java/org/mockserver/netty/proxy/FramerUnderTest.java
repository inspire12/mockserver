package org.mockserver.netty.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * A framer on a connection of its own: what is read is fed to it in the pieces given, the whole messages it passes on
 * are collected as bytes, and every read must be released by the end.
 */
class FramerUnderTest {

    final List<byte[]> messages = new ArrayList<>();
    final EmbeddedChannel channel;
    private final List<ByteBuf> reads = new ArrayList<>();

    FramerUnderTest(BinaryMessageFramer framer) {
        channel = new EmbeddedChannel(framer, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object message) {
                ByteBuf bytes = (ByteBuf) message;
                messages.add(ByteBufUtil.getBytes(bytes));
                bytes.release();
            }
        });
    }

    void reads(byte[] bytes, int from, int to) {
        ByteBuf read = Unpooled.copiedBuffer(bytes, from, to - from);
        reads.add(read);
        channel.writeInbound(read);
    }

    void reads(byte[] bytes) {
        reads(bytes, 0, bytes.length);
    }

    void readsOneByteAtATime(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            reads(bytes, i, i + 1);
        }
    }

    void releaseEverything() {
        channel.finishAndReleaseAll();
        for (ByteBuf read : reads) {
            assertThat("every read is released", read.refCnt(), is(0));
        }
    }

    static byte[] joined(byte[]... parts) {
        ByteBuffer all = ByteBuffer.allocate(Arrays.stream(parts).mapToInt(part -> part.length).sum());
        for (byte[] part : parts) {
            all.put(part);
        }
        return all.array();
    }

    static byte[] ascii(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
