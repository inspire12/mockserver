package org.mockserver.netty.http3;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Which streams of a connection the watcher resets, and when, on a clock the test moves: the timeout is 1000 ms, and a
 * stream must have been sent 50,000 bytes to be able to hold the connection's credit unless a test says otherwise.
 */
public class Http3ConnectionWriteStallWatcherTest {

    private static final long TIMEOUT_MILLIS = 1000;
    private static final long HOLDER_BYTES = 50_000;

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final List<String> resets = new ArrayList<>();
    private final Map<String, Long> resetAtMillis = new HashMap<>();
    private long nowMillis;
    private Http3ConnectionWriteStallWatcher watcher = watcher(HOLDER_BYTES);

    @After
    public void closeChannel() {
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldResetAStreamOnceItsWritesHaveWaitedForTheTimeout() {
        Http3ConnectionWriteStallWatcher.Stream stream = waitingStream("stalled", 0, 500);

        checkAt(999);
        assertThat(resets, is(empty()));

        checkAt(1000);
        assertThat(resets, contains("stalled"));

        checkAt(3000);
        assertThat("reset once", resets, contains("stalled"));
        assertThat(stream.isWaiting(), is(true));
    }

    @Test
    public void shouldTimeAStreamFromTheLastWriteQuicTookWhole() {
        Http3ConnectionWriteStallWatcher.Stream stream = watcher.stream(() -> resets.add("slow"));
        watcher.writing(stream, 500);
        watcher.writing(stream, 500);
        watcher.watch(stream);

        nowMillis = 900;
        watcher.written(stream, true);
        checkAt(1899);
        assertThat(resets, is(empty()));

        checkAt(1900);
        assertThat(resets, contains("slow"));
    }

    @Test
    public void shouldTimeAStreamFromWhenItsWritesStartedWaiting() {
        Http3ConnectionWriteStallWatcher.Stream stream = watcher.stream(() -> resets.add("idle then waiting"));
        watcher.writing(stream, 500);
        watcher.written(stream, true);

        nowMillis = 5000;
        watcher.writing(stream, 500);
        watcher.watch(stream);
        checkAt(5999);
        assertThat(resets, is(empty()));

        checkAt(6000);
        assertThat(resets, contains("idle then waiting"));
    }

    @Test
    public void shouldNotCountAFailedWriteAsDataMoving() {
        Http3ConnectionWriteStallWatcher.Stream stream = watcher.stream(() -> resets.add("failed"));
        watcher.writing(stream, 500);
        watcher.writing(stream, 500);
        watcher.watch(stream);

        nowMillis = 900;
        watcher.written(stream, false);

        checkAt(1000);
        assertThat(resets, contains("failed"));
    }

    @Test
    public void shouldNotResetAStreamWhoseWritesWereAllTaken() {
        Http3ConnectionWriteStallWatcher.Stream stream = watcher.stream(() -> resets.add("done"));
        watcher.writing(stream, 500);
        watcher.watch(stream);
        watcher.written(stream, true);

        checkAt(5000);

        assertThat(resets, is(empty()));
    }

    @Test
    public void shouldNotResetAStreamThatHasClosed() {
        Http3ConnectionWriteStallWatcher.Stream stream = waitingStream("closed", 0, 500);

        watcher.closed(stream);
        checkAt(5000);

        assertThat(resets, is(empty()));
    }

    @Test
    public void shouldRunATimerOnlyWhileAStreamHasWritesWaiting() {
        Http3ConnectionWriteStallWatcher.Stream stream = watcher.stream(() -> resets.add("timed"));
        watcher.writing(stream, 500);
        watcher.written(stream, true);
        watcher.watch(stream);
        assertThat("no timer for a write taken at once", channel.runScheduledPendingTasks(), is(-1L));

        watcher.writing(stream, 500);
        watcher.watch(stream);
        assertThat("a timer while a write waits", channel.runScheduledPendingTasks(), greaterThanOrEqualTo(0L));

        watcher.written(stream, true);
        channel.advanceTimeBy(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        assertThat("no timer once nothing waits", channel.runScheduledPendingTasks(), is(-1L));
        assertThat(resets, is(empty()));
    }

    @Test
    public void shouldTimeAgainAStreamThatWaitsAfterACheckFoundItsWritesAllTaken() {
        Http3ConnectionWriteStallWatcher.Stream stream = waitingStream("waits twice", 0, 500);
        nowMillis = 100;
        watcher.written(stream, true);
        checkAt(500);

        nowMillis = 600;
        watcher.writing(stream, 500);
        watcher.watch(stream);
        checkAt(1599);
        assertThat(resets, is(empty()));

        checkAt(1600);
        assertThat(resets, contains("waits twice"));
    }

    @Test
    public void shouldArmTheTimerAgainForAStreamThatWaitsAfterTheTimerFoundNothingWaiting() {
        Http3ConnectionWriteStallWatcher.Stream stream = waitingStream("waits after the timer stopped", 0, 500);
        watcher.written(stream, true);
        channel.advanceTimeBy(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        assertThat("the timer ran and was not kept", channel.runScheduledPendingTasks(), is(-1L));

        watcher.writing(stream, 500);
        watcher.watch(stream);
        assertThat("a timer for the write now waiting", channel.runScheduledPendingTasks(), greaterThanOrEqualTo(0L));

        nowMillis = TIMEOUT_MILLIS;
        channel.advanceTimeBy(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat(resets, contains("waits after the timer stopped"));
    }

    @Test
    public void shouldResetEveryStalledStreamOnItsOwnClockWhenAnyStreamMayHoldTheCredit() {
        watcher = watcher(0);
        waitingStream("first", 60_000, 500);
        nowMillis = 400;
        waitingStream("second", 0, 500);

        checkAt(1000);
        assertThat(resets, contains("first"));

        checkAt(1400);
        assertThat(resets, contains("first", "second"));
    }

    @Test
    public void shouldGiveAStreamSentTooLittleToHoldTheCreditOneMorePeriodWhenAStreamThatMayHoldItIsReset() {
        waitingStream("holder", 60_000, 500);
        Http3ConnectionWriteStallWatcher.Stream waiter = waitingStream("waiter", 0, 500);

        checkAt(1000);
        assertThat("only the stream that may hold the credit", resets, contains("holder"));

        // the reset returned the credit, and the waiting stream's data moves
        nowMillis = 1100;
        watcher.written(waiter, true);
        watcher.writing(waiter, 500);
        checkAt(2099);
        assertThat(resets, contains("holder"));
    }

    @Test
    public void shouldResetASparedStreamOnePeriodLaterWhenNoDataHasMoved() {
        waitingStream("holder", 60_000, 500);
        waitingStream("waiter", 0, 500);

        checkAt(1000);
        checkAt(1999);
        assertThat(resets, contains("holder"));

        checkAt(2000);
        assertThat(resets, contains("holder", "waiter"));
    }

    @Test
    public void shouldSpareAStreamAgainWhenAnotherStreamTookTheCreditAndStalled() {
        waitingStream("first holder", 60_000, 500);
        waitingStream("waiter", 0, 500);
        checkAt(1000);
        assertThat(resets, contains("first holder"));

        // the returned credit goes to another stream the client does not read
        nowMillis = 1100;
        waitingStream("second holder", 60_000, 500);

        checkAt(2000);
        assertThat("the second holder is still within its period", resets, contains("first holder"));
        checkAt(2100);
        assertThat(resets, contains("first holder", "second holder"));

        // and again to a third: each reset lets the waiter wait once more for a holder that is still moving
        nowMillis = 2200;
        waitingStream("third holder", 60_000, 500);
        checkAt(3100);
        assertThat("the third holder is still within its period", resets, contains("first holder", "second holder"));
        checkAt(3200);
        assertThat(resets, contains("first holder", "second holder", "third holder"));

        checkAt(4199);
        assertThat(resets, contains("first holder", "second holder", "third holder"));
        checkAt(4200);
        assertThat(resets, contains("first holder", "second holder", "third holder", "waiter"));
    }

    @Test
    public void shouldWaitOnlyOnePeriodForAStreamThatMayHoldTheCreditAndKeepsMoving() {
        waitingStream("waiter", 0, 500);
        nowMillis = 500;
        Http3ConnectionWriteStallWatcher.Stream holder = waitingStream("holder", 60_000, 500);

        checkAt(1000);
        nowMillis = 1400;
        watcher.written(holder, true);
        watcher.writing(holder, 500);
        checkAt(1999);
        assertThat(resets, is(empty()));

        checkAt(2000);
        assertThat("the holder is moving, so only the waiter is reset", resets, contains("waiter"));
    }

    @Test
    public void shouldWaitAgainForAMovingStreamOnceItsOwnDataHasMoved() {
        Http3ConnectionWriteStallWatcher.Stream waiter = waitingStream("waiter", 0, 500);
        nowMillis = 500;
        Http3ConnectionWriteStallWatcher.Stream holder = waitingStream("holder", 60_000, 500);
        checkAt(1000);

        nowMillis = 1400;
        watcher.written(waiter, true);
        watcher.writing(waiter, 500);
        nowMillis = 2390;
        watcher.written(holder, true);
        watcher.writing(holder, 500);

        checkAt(2400);
        checkAt(3389);
        assertThat("the waiter stalled again at 2400 and waits one period more", resets, is(empty()));
    }

    @Test
    public void shouldGiveAStreamThatHasNotYetStalledAFullPeriodFromTheResetOfAStreamThatMayHoldTheCredit() {
        waitingStream("holder", 60_000, 500);
        nowMillis = 990;
        waitingStream("waiter", 10_000, 500);

        checkAt(1000);
        assertThat(resets, contains("holder"));

        checkAt(1999);
        assertThat("stalled at 1990, but the credit was returned only at 1000", resets, contains("holder"));
        checkAt(2000);
        assertThat(resets, contains("holder", "waiter"));
    }

    @Test
    public void shouldWaitForAStreamThatMayHoldTheCreditAndIsStillMovingBeforeResettingAStreamSentTooLittleToHoldIt() {
        waitingStream("waiter", 0, 500);
        nowMillis = 500;
        waitingStream("holder", 60_000, 500);

        checkAt(1000);
        checkAt(1499);
        assertThat("the waiter started waiting first", resets, is(empty()));

        checkAt(1500);
        assertThat(resets, contains("holder"));

        checkAt(2499);
        assertThat(resets, contains("holder"));
        checkAt(2500);
        assertThat(resets, contains("holder", "waiter"));
    }

    @Test
    public void shouldResetAStalledStreamSentTooLittleToHoldTheCreditWhenNoStreamMayHoldIt() {
        waitingStream("first", 0, 500);
        waitingStream("second", 10_000, 500);

        checkAt(1000);

        assertThat(resets, containsInAnyOrder("first", "second"));
    }

    @Test
    public void shouldResetTogetherTheStalledStreamsThatMayEachHoldTheCredit() {
        waitingStream("first", 60_000, 500);
        waitingStream("second", 50_000, 500);

        checkAt(1000);

        assertThat(resets, containsInAnyOrder("first", "second"));
    }

    @Test
    public void shouldJudgeWhatAStreamMayHoldByTheBytesQuicTookNotTheWritesWaiting() {
        waitingStream("holder", 50_000, 500);
        waitingStream("waiter", 49_999, 32_768);

        checkAt(1000);

        assertThat(resets, contains("holder"));
    }

    @Test
    public void shouldNotWaitForAStreamThatBeganWaiting200MillisLaterWhenTheStreamsSentLessCouldTogetherHoldTheCredit() {
        assertOnlyTheUnreadStreamsAreResetWhenAReadStreamBeginsWaitingAt(200);
    }

    @Test
    public void shouldNotWaitForAStreamThatBeganWaiting400MillisLaterWhenTheStreamsSentLessCouldTogetherHoldTheCredit() {
        assertOnlyTheUnreadStreamsAreResetWhenAReadStreamBeginsWaitingAt(400);
    }

    @Test
    public void shouldNotWaitForAStreamThatBeganWaiting900MillisLaterWhenTheStreamsSentLessCouldTogetherHoldTheCredit() {
        assertOnlyTheUnreadStreamsAreResetWhenAReadStreamBeginsWaitingAt(900);
    }

    /**
     * Three unread streams, each sent too little to hold the credit, hold it between them. A stream the client reads
     * has been sent enough to hold it and begins waiting later; its write is taken once an unread stream's reset
     * returns credit. Timed alone, the unread streams are reset at 1000 and the read stream is delivered.
     */
    private void assertOnlyTheUnreadStreamsAreResetWhenAReadStreamBeginsWaitingAt(long readStreamWaitsAtMillis) {
        Http3ConnectionWriteStallWatcher.Stream read = watcher.stream(() -> resets.add("read"));
        watcher.writing(read, 60_000);
        watcher.written(read, true);
        for (String unread : List.of("first unread", "second unread", "third unread")) {
            Http3ConnectionWriteStallWatcher.Stream stream = watcher.stream(() -> {
                resets.add(unread);
                if (read.isWaiting()) {
                    watcher.written(read, true);
                }
            });
            watcher.writing(stream, 20_000);
            watcher.written(stream, true);
            watcher.writing(stream, 500);
            watcher.watch(stream);
        }

        checkEvery100MillisUntil(readStreamWaitsAtMillis);
        watcher.writing(read, 500);
        watcher.watch(read);
        checkEvery100MillisUntil(900);
        assertThat(resets, is(empty()));

        checkAt(1000);
        assertThat(resets, containsInAnyOrder("first unread", "second unread", "third unread"));
        assertThat("the read stream's write was taken", read.isWaiting(), is(false));

        checkEvery100MillisUntil(6000);
        assertThat(resets, not(hasItem("read")));
    }

    @Test
    public void shouldLeaveEveryStreamToItsOwnClockOnceTheStreamsSentLessHaveTogetherBeenSentWhatCouldHoldTheCredit() {
        waitingStream("first", 25_000, 500);
        waitingStream("second", 25_000, 500);
        nowMillis = 500;
        waitingStream("sent enough to hold the credit", 60_000, 500);

        checkAt(1000);
        assertThat(resets, containsInAnyOrder("first", "second"));

        checkAt(1500);
        assertThat(resets, contains("first", "second", "sent enough to hold the credit"));
    }

    @Test
    public void shouldStillWaitWhileTheStreamsSentLessHaveTogetherBeenSentTooLittleToHoldTheCredit() {
        waitingStream("first", 25_000, 500);
        waitingStream("second", 24_999, 500);
        nowMillis = 500;
        waitingStream("sent enough to hold the credit", 60_000, 500);

        checkAt(1000);
        assertThat(resets, is(empty()));

        checkAt(1500);
        assertThat(resets, contains("sent enough to hold the credit"));
        checkAt(2499);
        assertThat(resets, contains("sent enough to hold the credit"));
        checkAt(2500);
        assertThat(resets, contains("sent enough to hold the credit", "first", "second"));
    }

    @Test
    public void shouldEndAWaitAlreadyGrantedOnceTheStreamsSentLessCouldTogetherHoldTheCredit() {
        waitingStream("first", 20_000, 500);
        nowMillis = 900;
        waitingStream("sent enough to hold the credit", 60_000, 500);
        checkAt(1000);
        assertThat("the first stream waits for the one that could hold the credit", resets, is(empty()));

        nowMillis = 1050;
        waitingStream("second", 20_000, 500);
        waitingStream("third", 20_000, 500);

        checkAt(1100);
        assertThat(resets, contains("first"));
    }

    /**
     * Known limitation, pinned as it is. An unread stream that could hold the credit and an unread stream sent nothing
     * both stall at 0; a stream the client reads, sent enough to hold the credit, begins waiting at 400. Timed alone
     * both unread streams are reset at 1000 and the read stream gets the credit. Here the stream sent nothing is kept,
     * and, offered the returned credit first, takes it: the read stream is reset on its own clock.
     */
    @Test
    public void shouldResetAReadStreamWhenAStreamKeptLongerTakesTheCreditAResetReturned() {
        Http3ConnectionWriteStallWatcher.Stream read = watcher.stream(() -> reset("read"));
        watcher.writing(read, 60_000);
        watcher.written(read, true);
        Http3ConnectionWriteStallWatcher.Stream kept = watcher.stream(() -> reset("kept"));
        Http3ConnectionWriteStallWatcher.Stream holder = watcher.stream(() -> {
            reset("unread holder");
            // the returned credit goes to the kept stream, which takes its write and waits again, unread
            watcher.written(kept, true);
            watcher.writing(kept, 500);
        });
        watcher.writing(holder, 60_000);
        watcher.written(holder, true);
        watcher.writing(holder, 500);
        watcher.watch(holder);
        watcher.writing(kept, 60_000);
        watcher.watch(kept);

        checkEvery100MillisUntil(400);
        watcher.writing(read, 500);
        watcher.watch(read);
        checkEvery100MillisUntil(6000);

        assertThat(resets, contains("unread holder", "read", "kept"));
        assertThat(resetAtMillis.get("unread holder"), is(1000L));
        assertThat("on its own clock, one period after it began waiting", resetAtMillis.get("read"), is(1400L));
        assertThat("one period after it took the credit", resetAtMillis.get("kept"), is(2000L));
    }

    @Test
    public void shouldResetAStreamSentLessTwoPeriodsAfterAnIdleStreamThatCouldHoldTheCreditBeganAWrite() {
        Http3ConnectionWriteStallWatcher.Stream idle = watcher.stream(() -> reset("idle"));
        watcher.writing(idle, 60_000);
        watcher.written(idle, true);
        waitingStream("waiter", 0, 500);

        checkEvery100MillisUntil(900);
        watcher.writing(idle, 500);
        watcher.watch(idle);
        checkEvery100MillisUntil(10_000);

        assertThat("one period after its write began waiting, as when timed alone", resetAtMillis.get("idle"), is(1900L));
        assertThat("no data moved after 0", resetAtMillis.get("waiter"), is(2900L));
    }

    @Test
    public void shouldKeepAStreamSentLessUpToTwoPeriodsMoreForEachIdleStreamThatCouldHoldTheCreditAndBeginsAWrite() {
        List<Http3ConnectionWriteStallWatcher.Stream> idle = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String name = "idle " + i;
            Http3ConnectionWriteStallWatcher.Stream stream = watcher.stream(() -> reset(name));
            watcher.writing(stream, 60_000);
            watcher.written(stream, true);
            idle.add(stream);
        }
        waitingStream("waiter", 0, 500);

        // each begins a write just before the waiter would be reset, and no data ever moves
        for (int i = 0; i < 10; i++) {
            checkEvery100MillisUntil(900 + 1900L * i);
            watcher.writing(idle.get(i), 500);
            watcher.watch(idle.get(i));
        }
        checkEvery100MillisUntil(60_000);

        for (int i = 0; i < 10; i++) {
            assertThat("one period after its write began waiting, as when timed alone", resetAtMillis.get("idle " + i), is(1900 + 1900L * i));
        }
        assertThat("one period, and 1.9 more for each of the ten", resetAtMillis.get("waiter"), is(20_000L));
    }

    private Http3ConnectionWriteStallWatcher watcher(long holderBytes) {
        return new Http3ConnectionWriteStallWatcher(TIMEOUT_MILLIS, channel.eventLoop(), () -> holderBytes, () -> TimeUnit.MILLISECONDS.toNanos(nowMillis));
    }

    /**
     * A stream QUIC took {@code takenBytes} from, now, and which has a write of {@code waitingBytes} waiting.
     */
    private Http3ConnectionWriteStallWatcher.Stream waitingStream(String name, int takenBytes, int waitingBytes) {
        Http3ConnectionWriteStallWatcher.Stream stream = watcher.stream(() -> reset(name));
        if (takenBytes > 0) {
            watcher.writing(stream, takenBytes);
            watcher.written(stream, true);
        }
        watcher.writing(stream, waitingBytes);
        watcher.watch(stream);
        return stream;
    }

    private void reset(String name) {
        resets.add(name);
        resetAtMillis.put(name, nowMillis);
    }

    private void checkAt(long millis) {
        nowMillis = millis;
        watcher.check();
    }

    /**
     * Checks at each multiple of 100 ms after now, up to and including {@code millis}, and leaves the clock there.
     */
    private void checkEvery100MillisUntil(long millis) {
        for (long next = nowMillis - nowMillis % 100 + 100; next <= millis; next += 100) {
            checkAt(next);
        }
        nowMillis = millis;
    }
}
