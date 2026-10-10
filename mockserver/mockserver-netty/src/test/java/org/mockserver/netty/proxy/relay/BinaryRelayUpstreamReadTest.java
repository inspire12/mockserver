package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.After;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.model.BinaryMessage;
import org.mockserver.model.BinaryProxyListener;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;

/**
 * What the upstream sends is written to the client as the buffer it was read into, not a copy, and is logged from
 * that buffer; it is copied only for a listener that takes it, which then sees the bytes whatever later happens to
 * the buffer.
 */
public class BinaryRelayUpstreamReadTest {

    private final BinaryRelayHarness relay = new BinaryRelayHarness(true);
    private final List<String> reported = new ArrayList<>();
    private final List<CompletableFuture<BinaryMessage>> responses = new ArrayList<>();

    @After
    public void releaseBuffers() {
        relay.finish();
    }

    private static ByteBuf buffer(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
    }

    private List<String> loggedResponses() {
        return relay.logged().stream()
            .filter(entry -> entry.getType() == FORWARDED_REQUEST)
            .map(entry -> entry.getMessage(configuration()))
            .collect(Collectors.toList());
    }

    @Test
    public void shouldWriteTheBufferReadFromTheUpstreamToTheClientAndLogItsBytes() {
        relay.clientConnection();
        relay.clientSends("one");
        ByteBuf read = buffer("answer");

        relay.upstream.writeInbound(read);

        ByteBuf written = relay.client.readOutbound();
        try {
            assertThat("not a copy", written, is(sameInstance(read)));
            assertThat(written.toString(StandardCharsets.UTF_8), is("answer"));
        } finally {
            written.release();
        }
        assertThat("the client's write held the only reference left", read.refCnt(), is(0));
        List<String> logged = loggedResponses();
        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).contains("616e73776572"), is(true));
    }

    @Test
    public void shouldReleaseAReadThatHasNoClientToGoTo() {
        relay.clientConnection();
        relay.clientSends("one");
        // closed under the relay, which has not yet been told and so still has its upstream connection
        relay.client.unsafe().closeForcibly();
        ByteBuf read = buffer("too late");

        relay.upstream.writeInbound(read);

        assertThat(read.refCnt(), is(0));
    }

    @Test
    public void shouldGiveAListenerACopyThatOutlivesTheBuffer() throws Exception {
        relay.configuration.binaryProxyListener(new BinaryProxyListener() {
            @Override
            public void onProxy(BinaryMessage binaryRequest, CompletableFuture<BinaryMessage> binaryResponse, SocketAddress serverAddress, SocketAddress clientAddress) {
                responses.add(binaryResponse);
            }

            @Override
            public void onUpstreamMessage(BinaryMessage upstreamMessage, SocketAddress serverAddress, SocketAddress clientAddress) {
                reported.add(new String(upstreamMessage.getBytes(), StandardCharsets.UTF_8));
            }
        });
        relay.clientConnection();
        relay.clientSends("one");
        ByteBuf answer = buffer("answer");
        ByteBuf unprompted = buffer("unprompted");

        relay.upstream.writeInbound(answer);
        relay.upstream.writeInbound(unprompted);
        // the buffers are the client's now, and reused once written: what the listener has must not change with them
        overwrite(relay.client.readOutbound());
        overwrite(relay.client.readOutbound());
        while (!relay.listenerCalls.isEmpty()) {
            relay.runNextListenerCall();
        }

        assertThat(new String(responses.get(0).get().getBytes(), StandardCharsets.UTF_8), is("answer"));
        assertThat(reported, contains("unprompted"));
    }

    private static void overwrite(ByteBuf written) {
        try {
            written.setZero(written.readerIndex(), written.readableBytes());
        } finally {
            written.release();
        }
    }

    @Test
    public void shouldLogAReadAnsweringAMessageWithThatMessage() {
        relay.clientConnection();
        relay.clientSends("one");

        relay.upstreamSends("answer");

        List<LogEntry> returned = relay.logged().stream().filter(entry -> entry.getType() == FORWARDED_REQUEST).collect(Collectors.toList());
        assertThat(returned, hasSize(1));
        String message = returned.get(0).getMessage(configuration());
        assertThat(message, message.contains("616e73776572") && message.contains("6f6e65"), is(true));
    }
}
