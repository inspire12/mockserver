package org.mockserver.netty.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Test;
import org.mockserver.netty.unification.BinaryAwareRecvByteBufAllocator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.netty.proxy.BinaryMessageGatherer.MAX_GATHERED_BYTES;

/**
 * What one read loop delivers on a binary connection is one message: joined in order, never more than the limit,
 * never kept past the loop, and never lost or leaked when the connection ends.
 */
public class BinaryMessageGathererTest {

    private final List<Connection> connections = new ArrayList<>();

    @After
    public void closeConnections() {
        for (Connection connection : connections) {
            connection.channel.finishAndReleaseAll();
            connection.delivered.forEach(ByteBuf::release);
        }
    }

    /** A connection with the gatherer and, after it, what stands in for binary handling. */
    private final class Connection {

        private final EmbeddedChannel channel = new EmbeddedChannel();
        private final List<ByteBuf> delivered = new ArrayList<>();
        private final List<String> events = new ArrayList<>();

        private Connection() {
            BinaryAwareRecvByteBufAllocator.install(channel);
            channel.pipeline().addLast(new BinaryMessageGatherer(), new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object message) {
                    if (message instanceof ByteBuf) {
                        delivered.add((ByteBuf) message);
                        events.add(describe((ByteBuf) message));
                    } else {
                        events.add("not bytes: " + message);
                    }
                }

                @Override
                public void channelReadComplete(ChannelHandlerContext ctx) {
                    events.add("read complete");
                }
            });
            connections.add(this);
        }

        /** As Netty's read loop begins: from here until {@link #readLoopEnds()} the channel is reading. */
        @SuppressWarnings("deprecation")
        private void readLoopBegins() {
            channel.unsafe().recvBufAllocHandle().reset(channel.config());
        }

        private ByteBuf reads(String text) {
            return reads(Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII));
        }

        private ByteBuf reads(ByteBuf read) {
            channel.pipeline().fireChannelRead(read);
            return read;
        }

        @SuppressWarnings("deprecation")
        private void readLoopEnds() {
            channel.unsafe().recvBufAllocHandle().readComplete();
            channel.pipeline().fireChannelReadComplete();
        }

        private List<String> messages() {
            List<String> messages = new ArrayList<>(events);
            messages.removeIf("read complete"::equals);
            return messages;
        }
    }

    private static String describe(ByteBuf message) {
        return message.readableBytes() <= 40 ? message.toString(StandardCharsets.US_ASCII) : message.readableBytes() + " bytes from " + (char) message.getByte(message.readerIndex()) + " to " + (char) message.getByte(message.writerIndex() - 1);
    }

    private static ByteBuf filled(char letter, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) letter);
        return Unpooled.wrappedBuffer(bytes);
    }

    @Test
    public void shouldPassOnTheOnlyReadOfALoopAsItIsWhenTheLoopEnds() {
        Connection connection = new Connection();

        connection.readLoopBegins();
        ByteBuf read = connection.reads("one message");
        assertThat("held until the loop ends", connection.events, is(empty()));
        connection.readLoopEnds();

        assertThat(connection.events, contains("one message", "read complete"));
        assertThat("not copied", connection.delivered.get(0), is(sameInstance(read)));
    }

    @Test
    public void shouldJoinTheReadsOfOneLoopInTheOrderRead() {
        Connection connection = new Connection();

        connection.readLoopBegins();
        connection.reads("first, ");
        connection.reads("second, ");
        connection.reads("third");
        connection.readLoopEnds();

        assertThat(connection.events, contains("first, second, third", "read complete"));
    }

    @Test
    public void shouldKeepTheReadsOfDifferentLoopsApart() {
        Connection connection = new Connection();

        connection.readLoopBegins();
        connection.reads("first");
        connection.readLoopEnds();
        connection.readLoopBegins();
        connection.reads("second");
        connection.readLoopEnds();

        assertThat(connection.messages(), contains("first", "second"));
    }

    @Test
    public void shouldPassOnAtOnceWhatIsNotReadInALoop() {
        Connection connection = new Connection();

        connection.reads("given up by a timer");

        assertThat("there is no end of loop to wait for", connection.events, contains("given up by a timer"));
    }

    @Test
    public void shouldPassOnAtOnceWhatArrivesOnceALoopHasEnded() {
        Connection connection = new Connection();
        connection.readLoopBegins();
        connection.reads("read in a loop");
        connection.readLoopEnds();

        connection.reads("given up later");

        assertThat(connection.events, contains("read in a loop", "read complete", "given up later"));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void shouldPassOnWhatItHoldsAheadOfWhatArrivesOutsideALoop() {
        Connection connection = new Connection();
        connection.readLoopBegins();
        connection.reads("held");
        // the loop ends for the channel, and nothing has told the gatherer yet
        connection.channel.unsafe().recvBufAllocHandle().readComplete();

        connection.reads("arrived after");

        assertThat("in the order they arrived, as two messages", connection.events, contains("held", "arrived after"));
    }

    @Test
    public void shouldEndAMessageAtTheLimitAndStartTheNextWithWhatFollows() {
        Connection connection = new Connection();

        connection.readLoopBegins();
        connection.reads(filled('a', MAX_GATHERED_BYTES - 10));
        assertThat("under the limit nothing is passed on yet", connection.events, is(empty()));
        connection.reads(filled('b', 30));
        assertThat("at the limit the message is passed on without waiting for the loop to end", connection.messages(), contains(MAX_GATHERED_BYTES + " bytes from a to b"));
        connection.readLoopEnds();

        assertThat(connection.messages(), contains(MAX_GATHERED_BYTES + " bytes from a to b", "bbbbbbbbbbbbbbbbbbbb"));
    }

    @Test
    public void shouldPassOnAMessageThatReachesTheLimitExactly() {
        Connection connection = new Connection();

        connection.readLoopBegins();
        connection.reads(filled('a', MAX_GATHERED_BYTES / 2));
        connection.reads(filled('b', MAX_GATHERED_BYTES / 2));
        assertThat(connection.messages(), contains(MAX_GATHERED_BYTES + " bytes from a to b"));
        connection.readLoopEnds();

        assertThat("and nothing empty after it", connection.messages(), contains(MAX_GATHERED_BYTES + " bytes from a to b"));
    }

    @Test
    public void shouldCutOneReadLargerThanTheLimitIntoMessagesOfTheLimit() {
        Connection connection = new Connection();

        connection.readLoopBegins();
        ByteBuf read = connection.reads(filled('a', 2 * MAX_GATHERED_BYTES + 5));
        connection.readLoopEnds();

        assertThat(connection.messages(), contains(MAX_GATHERED_BYTES + " bytes from a to a", MAX_GATHERED_BYTES + " bytes from a to a", "aaaaa"));
        connection.delivered.forEach(ByteBuf::release);
        connection.delivered.clear();
        assertThat("each message held its own share of the read", read.refCnt(), is(0));
    }

    @Test
    public void shouldReleaseAnEmptyReadAndPassNothingOn() {
        Connection connection = new Connection();

        connection.readLoopBegins();
        ByteBuf empty = connection.reads(Unpooled.buffer(16));
        connection.readLoopEnds();

        assertThat(connection.events, contains("read complete"));
        assertThat(empty.refCnt(), is(0));
    }

    @Test
    public void shouldPassOnWhatIsNotBytes() {
        Connection connection = new Connection();

        connection.readLoopBegins();
        connection.channel.pipeline().fireChannelRead("an event");

        assertThat(connection.events, contains("not bytes: an event"));
    }

    @Test
    public void shouldPassOnWhatItHoldsWhenTheConnectionCloses() {
        Connection connection = new Connection();
        connection.readLoopBegins();
        connection.reads("sent, ");
        connection.reads("then closed");

        connection.channel.close();

        assertThat(connection.messages(), contains("sent, then closed"));
    }

    @Test
    public void shouldPassOnWhatItHoldsWhenItIsRemoved() {
        Connection connection = new Connection();
        connection.readLoopBegins();
        connection.reads("held");

        connection.channel.pipeline().remove(BinaryMessageGatherer.class);

        assertThat(connection.messages(), contains("held"));
    }

    @Test
    public void shouldHoldNothingOnceAMessageHasBeenPassedOn() {
        Connection connection = new Connection();
        connection.readLoopBegins();
        ByteBuf first = connection.reads("first, ");
        ByteBuf second = connection.reads("second");
        connection.readLoopEnds();

        connection.delivered.forEach(ByteBuf::release);
        connection.delivered.clear();

        assertThat("the reads belong to the message, and go with it", first.refCnt() + second.refCnt(), is(0));
        connection.channel.close();
        assertThat("so closing has nothing more to pass on", connection.messages(), contains("first, second"));
    }

    @Test
    public void shouldKeepEachConnectionsReadsToItself() {
        Connection one = new Connection();
        Connection another = new Connection();
        assertThat(one.channel.pipeline().get(BinaryMessageGatherer.class), is(not(sameInstance(another.channel.pipeline().get(BinaryMessageGatherer.class)))));

        one.readLoopBegins();
        one.reads("one's");
        another.readLoopBegins();
        another.reads("another's");
        another.readLoopEnds();
        one.readLoopEnds();

        assertThat(one.messages(), contains("one's"));
        assertThat(another.messages(), contains("another's"));
    }
}
