package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * An aggregator must neither copy a large body into a second buffer (direct memory) nor keep one
 * component per chunk without limit (heap: a client can send one-byte chunks).
 */
public class HttpObjectAggregatorsTest {

    private static final int TEN_MIB = 10 * 1024 * 1024;

    @Test
    public void shouldSizeTheComponentLimitToTheMaximumContentLength() {
        assertThat(HttpObjectAggregators.componentLimit(TEN_MIB), is(10240));
        assertThat(HttpObjectAggregators.componentLimit(50 * 1024 * 1024), is(51200));
        assertThat(HttpObjectAggregators.componentLimit(64 * 1024), is(1024));
        assertThat(HttpObjectAggregators.componentLimit(0), is(1024));
        assertThat(new StreamingAwareHttpObjectAggregator(TEN_MIB).maxCumulationBufferComponents(), is(10240));
        assertThat(HttpObjectAggregators.httpObjectAggregator(TEN_MIB).maxCumulationBufferComponents(), is(10240));
    }

    @Test
    public void shouldBoundComponentsWhenAClientSendsOneByteChunks() {
        int chunks = 50_000;
        CompositeByteBuf content = aggregate(chunks, 1);
        try {
            assertThat(content.readableBytes(), is(chunks));
            assertThat(content.numComponents(), lessThanOrEqualTo(10240));
            for (int i = 0; i < chunks; i++) {
                assertThat(content.getByte(i), is((byte) (i % 251)));
            }
        } finally {
            content.release();
        }
    }

    @Test
    public void shouldNotConsolidateABodyOfOrdinaryChunks() {
        // 2,048 chunks is past Netty's default of 1,024 components, where the body used to be copied
        CompositeByteBuf content = aggregate(2048, 4096);
        try {
            assertThat(content.readableBytes(), is(2048 * 4096));
            assertThat(content.numComponents(), is(2048));
        } finally {
            content.release();
        }
    }

    private static CompositeByteBuf aggregate(int chunks, int chunkBytes) {
        EmbeddedChannel channel = new EmbeddedChannel(HttpObjectAggregators.httpObjectAggregator(TEN_MIB));
        HttpRequest head = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        HttpUtil.setTransferEncodingChunked(head, true);
        channel.writeInbound(head);
        int position = 0;
        for (int i = 0; i < chunks; i++) {
            ByteBuf chunk = Unpooled.buffer(chunkBytes);
            for (int b = 0; b < chunkBytes; b++) {
                chunk.writeByte((position++) % 251);
            }
            channel.writeInbound(new DefaultHttpContent(chunk));
        }
        channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
        FullHttpRequest request = channel.readInbound();
        channel.finishAndReleaseAll();
        assertThat(request.content(), instanceOf(CompositeByteBuf.class));
        return (CompositeByteBuf) request.content();
    }
}
