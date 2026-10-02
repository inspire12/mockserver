package org.mockserver.model;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class StreamingBodyUnwrittenBytesBoundTest {

    private static final int CHUNK = 1024;

    @Test
    public void shouldRelayAStreamManyTimesTheBoundWhileEachChunkIsWritten() {
        StreamingBody body = new StreamingBody(0, false, 4 * CHUNK);
        AtomicInteger delivered = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        body.subscribe(chunk -> {
            delivered.addAndGet(chunk.readableBytes());
            body.chunkWritten(chunk.readableBytes());
        }, () -> {
        }, error::set);

        for (int i = 0; i < 1000; i++) {
            assertThat(addChunk(body, CHUNK), is(true));
        }
        body.complete();

        assertThat(delivered.get(), is(1000 * CHUNK));
        assertThat(error.get(), is(nullValue()));
    }

    @Test
    public void shouldFailTheStreamWhenUnwrittenChunksPassTheBound() {
        StreamingBody body = new StreamingBody(0, false, 4 * CHUNK);
        List<ByteBuf> held = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        // a client that never finishes a write
        body.subscribe(chunk -> held.add(chunk.copy()), () -> {
        }, error::set);

        for (int i = 0; i < 4; i++) {
            assertThat(addChunk(body, CHUNK), is(true));
        }
        assertThat(error.get(), is(nullValue()));
        assertThat("the chunk that passes the bound is refused", addChunk(body, 1), is(false));

        assertThat(error.get(), instanceOf(StreamingBody.UnwrittenBytesLimitExceededException.class));
        assertThat(body.isCompleted(), is(true));
        assertThat(body.getError(), instanceOf(StreamingBody.UnwrittenBytesLimitExceededException.class));
        assertThat("nothing more is taken", addChunk(body, 1), is(false));
        assertThat(held.size(), is(4));
        held.forEach(ByteBuf::release);
    }

    @Test
    public void shouldFreeTheBoundAsChunksAreWritten() {
        StreamingBody body = new StreamingBody(0, false, 4 * CHUNK);
        List<Integer> unacknowledged = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        body.subscribe(chunk -> unacknowledged.add(chunk.readableBytes()), () -> {
        }, error::set);

        for (int i = 0; i < 4; i++) {
            addChunk(body, CHUNK);
        }
        body.chunkWritten(unacknowledged.remove(0));
        assertThat("one written chunk makes room for one more", addChunk(body, CHUNK), is(true));
        assertThat(addChunk(body, 1), is(false));
        assertThat(error.get(), instanceOf(StreamingBody.UnwrittenBytesLimitExceededException.class));
    }

    @Test
    public void shouldCountChunksPendingBeforeSubscribeAndDropThemWhenTheyPassTheBound() {
        StreamingBody body = new StreamingBody(0, false, 4 * CHUNK);
        for (int i = 0; i < 4; i++) {
            assertThat(addChunk(body, CHUNK), is(true));
        }
        assertThat(addChunk(body, CHUNK), is(false));

        AtomicInteger delivered = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        body.subscribe(chunk -> delivered.addAndGet(chunk.readableBytes()), () -> {
        }, error::set);

        assertThat("a stream that failed before subscribe delivers nothing", delivered.get(), is(0));
        assertThat(error.get(), instanceOf(StreamingBody.UnwrittenBytesLimitExceededException.class));
    }

    @Test
    public void shouldDrainPendingChunksWithinTheBoundAndCountThemUntilWritten() {
        StreamingBody body = new StreamingBody(0, false, 4 * CHUNK);
        for (int i = 0; i < 3; i++) {
            addChunk(body, CHUNK);
        }
        AtomicInteger delivered = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        body.subscribe(chunk -> delivered.addAndGet(chunk.readableBytes()), () -> {
        }, error::set);

        assertThat(delivered.get(), is(3 * CHUNK));
        assertThat(addChunk(body, CHUNK), is(true));
        assertThat("drained chunks still count until written", addChunk(body, 1), is(false));
        assertThat(error.get(), instanceOf(StreamingBody.UnwrittenBytesLimitExceededException.class));
    }

    @Test
    public void shouldRequestMoreOnlyOnceTheBacklogHasDrainedToAQuarterOfTheBound() {
        StreamingBody body = new StreamingBody(0, false, 4 * CHUNK);
        AtomicInteger requests = new AtomicInteger();
        body.setRequestMoreCallback(requests::incrementAndGet);
        body.subscribe(chunk -> {
        }, () -> {
        }, error -> {
        });
        requests.set(0);
        for (int i = 0; i < 3; i++) {
            addChunk(body, CHUNK);
        }

        body.chunkWritten(CHUNK);
        assertThat("2 KiB still waiting", requests.get(), is(0));
        body.chunkWritten(CHUNK);
        assertThat("1 KiB waiting, a quarter of the bound", requests.get(), is(1));
        body.chunkWritten(CHUNK);
        assertThat(requests.get(), is(2));
    }

    @Test
    public void shouldRequestMoreForEveryWrittenChunkWithoutABound() {
        StreamingBody body = new StreamingBody(0, false);
        AtomicInteger requests = new AtomicInteger();
        body.setRequestMoreCallback(requests::incrementAndGet);
        body.subscribe(chunk -> {
        }, () -> {
        }, error -> {
        });
        requests.set(0);
        for (int i = 0; i < 3; i++) {
            addChunk(body, CHUNK);
            body.chunkWritten(CHUNK);
        }
        assertThat(requests.get(), is(3));
    }

    @Test
    public void shouldNotBoundABodyBuiltWithoutABound() {
        StreamingBody body = new StreamingBody(0, false);
        AtomicReference<Throwable> error = new AtomicReference<>();
        body.subscribe(chunk -> {
        }, () -> {
        }, error::set);

        for (int i = 0; i < 1000; i++) {
            assertThat(addChunk(body, CHUNK), is(true));
        }
        assertThat(error.get(), is(nullValue()));
    }

    private static boolean addChunk(StreamingBody body, int size) {
        ByteBuf chunk = Unpooled.buffer(size).writeZero(size);
        try {
            return body.addChunk(chunk);
        } finally {
            chunk.release();
        }
    }
}
