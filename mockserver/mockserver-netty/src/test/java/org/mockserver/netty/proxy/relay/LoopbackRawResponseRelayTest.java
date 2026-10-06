package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.http.HttpErrorActionHandler;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;
import org.mockserver.socket.ChannelReadPause;

import java.io.ByteArrayOutputStream;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.model.HttpError.error;

/**
 * Both ends of a relay's HTTP/1.1 loopback and its client leg, as embedded channels: the bytes MockServer writes as raw
 * bytes reach the proxy client as they were written, wherever a read of the loopback happens to end, and the encoded
 * responses either side of them are still decoded and relayed.
 */
public class LoopbackRawResponseRelayTest {

    private static final int BOUND = 4 * 1024;
    private static final byte[] INCOMPLETE = "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nseven!!".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NOT_HTTP = new byte[]{0, 1, 2, (byte) 0xff, 'n', 'o', 't', '\r', '\n', '\r', '\n', (byte) 0xfe};

    // unique: the relay's loopback addresses are held JVM-wide and tests run in parallel
    private final SocketAddress loopbackAddress = new LocalAddress("loopback-" + UUID.randomUUID());
    private final List<Object> writtenToProxyClient = new ArrayList<>();
    private final List<ChannelPromise> heldWrites = new ArrayList<>();
    private boolean holdWrites;
    private boolean failWrites;
    private EmbeddedChannel proxyClient;
    private EmbeddedChannel relayLoopback;
    private EmbeddedChannel acceptedLoopback;

    @Before
    public void openTunnel() {
        // beneath the codec: records what is written, and events fired from the codec are recorded after it
        proxyClient = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                writtenToProxyClient.add(((ByteBuf) msg).toString(StandardCharsets.ISO_8859_1));
                ReferenceCountUtil.release(msg);
                if (failWrites) {
                    promise.setFailure(new ClosedChannelException());
                } else if (holdWrites) {
                    heldWrites.add(promise);
                } else {
                    promise.setSuccess();
                }
            }
        }, new HttpServerCodec(), new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                writtenToProxyClient.add(evt);
            }
        });
        relayLoopback = new EmbeddedChannel() {
            @Override
            protected SocketAddress localAddress0() {
                return loopbackAddress;
            }
        };
        relayLoopback.pipeline().addLast(
            LoopbackRawResponseSplitter.forTunnel(proxyClient),
            new HttpClientCodec(),
            new HttpObjectAggregator(BOUND),
            new LoopbackHttp1ResponseErrorHandler(proxyClient),
            new DownstreamProxyRelayHandler(new MockServerLogger(), proxyClient, BOUND)
        );
        RelayLoopbackAddresses.register(relayLoopback, proxyClient);
        acceptedLoopback = new EmbeddedChannel(new HttpServerCodec(), LoopbackExchangeEndedHandler.INSTANCE) {
            @Override
            protected SocketAddress remoteAddress0() {
                return loopbackAddress;
            }
        };
    }

    @After
    public void closeTunnel() {
        acceptedLoopback.finishAndReleaseAll();
        relayLoopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    @Test
    public void shouldRelayRawBytesAsTheyWereWrittenBetweenEncodedResponsesWhereverAReadEnds() {
        for (int readSize = 1; ; readSize++) {
            acceptedLoopback.writeOutbound(response("before"));
            writeRawBytes(INCOMPLETE);
            writeRawBytes(NOT_HTTP);
            writeRawBytes(new byte[0]);
            acceptedLoopback.writeOutbound(response("after"));
            byte[] writtenToLoopback = writtenToLoopback();

            List<ByteBuf> reads = new ArrayList<>();
            for (int offset = 0; offset < writtenToLoopback.length; offset += readSize) {
                reads.add(Unpooled.copiedBuffer(writtenToLoopback, offset, Math.min(readSize, writtenToLoopback.length - offset)));
                relayLoopback.writeInbound(reads.get(reads.size() - 1));
            }
            for (ByteBuf read : reads) {
                assertThat("reads of " + readSize + ": every read, and every slice of it handed on, is released", read.refCnt(), is(0));
            }

            assertThat("reads of " + readSize, relayedBytes(), is(
                encoded("before") + new String(INCOMPLETE, StandardCharsets.ISO_8859_1) + new String(NOT_HTTP, StandardCharsets.ISO_8859_1) + encoded("after")
            ));
            assertThat("reads of " + readSize + ": each raw response, the empty one too, ends the client leg's exchange once", exchangesEndedByRawBytes(), is(3));
            assertThat(proxyClient.isOpen(), is(true));
            assertThat(relayLoopback.isOpen(), is(true));
            if (readSize >= writtenToLoopback.length) {
                break;
            }
            closeTunnel();
            writtenToProxyClient.clear();
            openTunnel();
        }
    }

    @Test
    public void shouldPassAReadOnUnchangedWhenNoRawBytesAreAnnounced() {
        List<Object> passedOn = new ArrayList<>();
        EmbeddedChannel loopback = new EmbeddedChannel(LoopbackRawResponseSplitter.forTunnel(new EmbeddedChannel()), new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                passedOn.add(msg);
            }
        });
        ByteBuf read = Unpooled.copiedBuffer("HTTP/1.1 200 OK\r\n", StandardCharsets.US_ASCII);

        loopback.writeInbound(read);

        assertThat("the same buffer, not a slice: the codec's cumulation can then take it without a copy", passedOn.size() == 1 && passedOn.get(0) == read, is(true));
        read.release();
        loopback.finishAndReleaseAll();
    }

    @Test
    public void shouldRelayAResponseThatStraddlesTwoReadsUnchanged() {
        acceptedLoopback.writeOutbound(response("straddles"));
        byte[] writtenToLoopback = writtenToLoopback();
        int half = writtenToLoopback.length / 2;

        relayLoopback.writeInbound(Unpooled.copiedBuffer(writtenToLoopback, 0, half));
        relayLoopback.writeInbound(Unpooled.copiedBuffer(writtenToLoopback, half, writtenToLoopback.length - half));

        assertThat(relayedBytes(), is(encoded("straddles")));
        assertThat(exchangesEndedByRawBytes(), is(0));
    }

    @Test
    public void shouldEndTheClientLegsExchangeOnlyAsTheLastOfTheRawBytesIsWritten() {
        holdWrites = true;
        writeRawBytes(INCOMPLETE);
        byte[] writtenToLoopback = writtenToLoopback();

        relayLoopback.writeInbound(Unpooled.copiedBuffer(writtenToLoopback, 0, 10));
        heldWrites.remove(0).setSuccess();
        assertThat("more of the response is to come", exchangesEndedByRawBytes(), is(0));

        relayLoopback.writeInbound(Unpooled.copiedBuffer(writtenToLoopback, 10, writtenToLoopback.length - 10));
        assertThat("the last of it is still being written", exchangesEndedByRawBytes(), is(0));
        heldWrites.remove(0).setSuccess();

        assertThat(exchangesEndedByRawBytes(), is(1));
        assertThat("after the bytes, as on a direct connection", writtenToProxyClient.get(writtenToProxyClient.size() - 1), is((Object) HttpExchangeEndedEvent.RAW_RESPONSE_WRITTEN));
    }

    @Test
    public void shouldEndTheClientLegsExchangeForARawResponseOfNoBytes() {
        writeRawBytes(new byte[0]);
        assertThat("on the loopback's own event loop", writtenToProxyClient, not(contains((Object) HttpExchangeEndedEvent.RAW_RESPONSE_WRITTEN)));

        relayLoopback.runPendingTasks();

        assertThat(exchangesEndedByRawBytes(), is(1));
        assertThat(relayedBytes(), is(""));
    }

    @Test
    public void shouldEndTheExchangeOfARawResponseOfNoBytesOnlyAfterTheResponseWrittenBeforeIt() {
        acceptedLoopback.writeOutbound(response("before"));
        byte[] before = writtenToLoopback();
        writeRawBytes(new byte[0]);
        acceptedLoopback.writeOutbound(response("after"));
        byte[] after = writtenToLoopback();

        relayLoopback.runPendingTasks();
        assertThat("the response before it has not been read yet", exchangesEndedByRawBytes(), is(0));

        // a read that ends where the empty response falls: no later byte marks its place
        relayLoopback.writeInbound(Unpooled.copiedBuffer(before));

        assertThat(relayedBytes(), is(encoded("before")));
        assertThat(exchangesEndedByRawBytes(), is(1));
        assertThat("after the response before it", writtenToProxyClient.get(writtenToProxyClient.size() - 1), is((Object) HttpExchangeEndedEvent.RAW_RESPONSE_WRITTEN));

        relayLoopback.writeInbound(Unpooled.copiedBuffer(after));
        assertThat(relayedBytes(), is(encoded("before") + encoded("after")));
        assertThat(exchangesEndedByRawBytes(), is(1));
    }

    @Test
    public void shouldNotAnswerBadGatewayForRawBytesThatAreNotHttp() {
        writeRawBytes(NOT_HTTP);

        relayLoopback.writeInbound(Unpooled.copiedBuffer(writtenToLoopback()));

        assertThat(relayedBytes(), is(new String(NOT_HTTP, StandardCharsets.ISO_8859_1)));
        assertThat(relayedBytes(), not(containsString("502")));
        assertThat(proxyClient.isOpen(), is(true));
        assertThat(relayLoopback.isOpen(), is(true));
    }

    @Test
    public void shouldPauseReadsOfTheLoopbackWhileRawBytesWaitAndNeverCutThemShort() {
        holdWrites = true;
        byte[] large = new byte[3 * BOUND];
        writeRawBytes(large);
        byte[] writtenToLoopback = writtenToLoopback();

        relayLoopback.writeInbound(Unpooled.copiedBuffer(writtenToLoopback, 0, BOUND));
        assertThat("above half the bound unwritten", ChannelReadPause.holds(relayLoopback), is(1));
        // the rest of a read already in progress
        relayLoopback.writeInbound(Unpooled.copiedBuffer(writtenToLoopback, BOUND, 2 * BOUND));

        assertThat("past the bound, where a streamed response is cut", proxyClient.isOpen(), is(true));
        assertThat(relayLoopback.isOpen(), is(true));
        heldWrites.remove(0).setSuccess();
        assertThat(ChannelReadPause.holds(relayLoopback), is(1));
        heldWrites.remove(0).setSuccess();
        assertThat("drained", ChannelReadPause.holds(relayLoopback), is(0));
        assertThat(relayedBytes().length(), is(large.length));
        assertThat(exchangesEndedByRawBytes(), is(1));
    }

    @Test
    public void shouldReleaseRawBytesStillToComeFromTheReadThatEndedTheRelay() {
        // the proxy client has gone: the first write to it fails
        failWrites = true;
        writeRawBytes(INCOMPLETE);
        writeRawBytes(NOT_HTTP);
        ByteBuf oneRead = Unpooled.copiedBuffer(writtenToLoopback());

        relayLoopback.writeInbound(oneRead);
        relayLoopback.runPendingTasks();
        proxyClient.runPendingTasks();

        assertThat("only the first response's bytes were written", stringsOf(writtenToProxyClient).size(), is(1));
        assertThat("the second response's bytes were released unwritten", oneRead.refCnt(), is(0));
        assertThat(proxyClient.isOpen(), is(false));
        assertThat(relayLoopback.isOpen(), is(false));
    }

    private void writeRawBytes(byte[] bytes) {
        new HttpErrorActionHandler().handle(error().withResponseBytes(bytes), acceptedLoopback.pipeline().lastContext());
    }

    private static FullHttpResponse response(String body) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer(body, StandardCharsets.US_ASCII));
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length());
        return response;
    }

    // what the client leg's codec writes for the response the loopback's codec decoded
    private static String encoded(String body) {
        EmbeddedChannel encoder = new EmbeddedChannel(new HttpServerCodec());
        encoder.writeOutbound(response(body));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (ByteBuf written; (written = encoder.readOutbound()) != null; written.release()) {
            bytes.writeBytes(ByteBufUtil.getBytes(written));
        }
        encoder.finishAndReleaseAll();
        return bytes.toString(StandardCharsets.ISO_8859_1);
    }

    private byte[] writtenToLoopback() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (ByteBuf written; (written = acceptedLoopback.readOutbound()) != null; written.release()) {
            bytes.writeBytes(ByteBufUtil.getBytes(written));
        }
        return bytes.toByteArray();
    }

    private String relayedBytes() {
        return String.join("", stringsOf(writtenToProxyClient));
    }

    private static List<String> stringsOf(List<Object> recorded) {
        List<String> strings = new ArrayList<>();
        for (Object item : recorded) {
            if (item instanceof String) {
                strings.add((String) item);
            }
        }
        return strings;
    }

    private int exchangesEndedByRawBytes() {
        int ended = 0;
        for (Object item : writtenToProxyClient) {
            if (item == HttpExchangeEndedEvent.RAW_RESPONSE_WRITTEN) {
                ended++;
            }
        }
        return ended;
    }
}
