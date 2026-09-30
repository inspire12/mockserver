package org.mockserver.scheduler;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Delay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.scheduler.Scheduler.rejectable;

/**
 * WebSocket reply sets ({@link Scheduler#scheduleReplySet}) are admitted or refused whole against their own
 * {@code maxPendingDelayedResponses} budget, and an admitted set's frames run strictly in order.
 */
public class SchedulerReplySetTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    @Test(timeout = 10_000)
    public void shouldRunEveryFrameOfASetInOrderIncludingEqualDelays() throws Exception {
        Scheduler scheduler = new Scheduler(configuration(), mockServerLogger, false);
        try {
            int sets = 50;
            List<List<Integer>> received = new ArrayList<>();
            CountDownLatch finished = new CountDownLatch(sets);
            for (int set = 0; set < sets; set++) {
                List<Integer> frames = Collections.synchronizedList(new ArrayList<>());
                received.add(frames);
                List<Runnable> writes = new ArrayList<>();
                for (int frame = 0; frame < 20; frame++) {
                    int index = frame;
                    writes.add(() -> frames.add(index));
                }
                long[] delays = new long[20];
                for (int frame = 0; frame < 20; frame++) {
                    // undelayed first frames, then runs of equal delays that a pool would otherwise race
                    delays[frame] = frame < 2 ? 0 : 20 + (frame / 5) * 10;
                }
                boolean admitted = scheduler.scheduleReplySet(writes, delays, () -> false, () -> {
                }, finished::countDown);
                assertThat(admitted, is(true));
            }

            assertThat(finished.await(5, TimeUnit.SECONDS), is(true));
            for (List<Integer> frames : received) {
                assertThat(frames, contains(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19));
            }
            assertThat(scheduler.getPendingWebSocketReplyFrameCount(), is(0));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldRefuseAWholeSetWhenTheBudgetIsFullAndRunNoneOfItsFrames() {
        Scheduler scheduler = new Scheduler(configuration().maxPendingDelayedResponses(2), mockServerLogger, false);
        try {
            AtomicInteger firstRan = new AtomicInteger();
            AtomicInteger secondRan = new AtomicInteger();
            AtomicInteger refused = new AtomicInteger();

            // admitted while fewer than the limit are pending, even though the set is larger than the limit
            assertThat(scheduler.scheduleReplySet(writes(4, firstRan), delays(0, 30_000, 30_000, 30_000), () -> false, refused::incrementAndGet, () -> {
            }), is(true));
            assertThat("the undelayed frame ran at once", firstRan.get(), is(1));
            assertThat(scheduler.getPendingWebSocketReplyFrameCount(), is(3));

            assertThat(scheduler.scheduleReplySet(writes(4, secondRan), delays(0, 10, 20, 30_000), () -> false, refused::incrementAndGet, () -> {
            }), is(false));
            assertThat("no frame of a refused set runs, not even the undelayed one", secondRan.get(), is(0));
            assertThat(refused.get(), is(1));
            assertThat(scheduler.getPendingWebSocketReplyFrameCount(), is(3));
            assertThat(scheduler.getOverloadRejectionCount(Scheduler.OverloadReason.WEBSOCKET_REPLIES), is(1L));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldAlwaysAdmitAnUndelayedSet() {
        Scheduler scheduler = new Scheduler(configuration().maxPendingDelayedResponses(1), mockServerLogger, false);
        try {
            AtomicInteger ran = new AtomicInteger();
            scheduler.scheduleReplySet(writes(1, new AtomicInteger()), delays(30_000), () -> false, () -> {
            }, () -> {
            });

            assertThat(scheduler.scheduleReplySet(writes(2, ran), delays(0, 0), () -> false, () -> {
            }, () -> {
            }), is(true));
            assertThat(ran.get(), is(2));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldKeepItsOwnBudgetApartFromDelayedResponses() {
        Scheduler scheduler = new Scheduler(configuration().maxPendingDelayedResponses(1), mockServerLogger, false);
        try {
            AtomicInteger responsesRefused = new AtomicInteger();
            scheduler.schedule(rejectable(() -> {
            }, responsesRefused::incrementAndGet), false, Delay.seconds(30));
            scheduler.submitAsync(Scheduler.sheddable(() -> {
            }), Delay.seconds(30));

            assertThat("full response and side-action budgets do not refuse a reply set",
                scheduler.scheduleReplySet(writes(2, new AtomicInteger()), delays(30_000, 30_000), () -> false, () -> {
                }, () -> {
                }), is(true));

            scheduler.schedule(rejectable(() -> {
            }, responsesRefused::incrementAndGet), false, Delay.seconds(30));
            assertThat("and a reply backlog does not take a response's slot", responsesRefused.get(), is(1));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldDropTheRestOfAStoppedSetAndReleaseItsFrames() throws Exception {
        Scheduler scheduler = new Scheduler(configuration(), mockServerLogger, false);
        try {
            AtomicInteger ran = new AtomicInteger();
            AtomicBoolean stopped = new AtomicBoolean();
            CountDownLatch finished = new CountDownLatch(1);
            List<Runnable> writes = new ArrayList<>();
            writes.add(() -> {
                ran.incrementAndGet();
                stopped.set(true);
            });
            writes.add(ran::incrementAndGet);
            writes.add(ran::incrementAndGet);

            scheduler.scheduleReplySet(writes, delays(10, 20, 30_000), stopped::get, () -> {
            }, finished::countDown);

            assertThat(finished.await(5, TimeUnit.SECONDS), is(true));
            assertThat(ran.get(), is(1));
            assertThat(scheduler.getPendingWebSocketReplyFrameCount(), is(0));
            assertThat(scheduler.getPendingDelayedTaskCount(), is(0));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldLeaveOneQueuedTaskPerCancelledReplacement() {
        Scheduler scheduler = new Scheduler(configuration(), mockServerLogger, false);
        try {
            Scheduler.PendingTask previous = null;
            for (int i = 0; i < 1_000; i++) {
                Scheduler.PendingTask next = scheduler.scheduleCancellable(() -> {
                }, 60_000);
                if (previous != null) {
                    previous.cancel();
                }
                previous = next;
            }
            assertThat("cancelled tasks leave the queue at once", scheduler.getQueuedTaskCount(), is(1));
            assertThat(scheduler.getPendingDelayedTaskCount(), is(1));

            previous.cancel();
            assertThat(scheduler.getQueuedTaskCount(), is(0));
            assertThat(scheduler.getPendingDelayedTaskCount(), is(0));
        } finally {
            scheduler.shutdown();
        }
    }

    private static List<Runnable> writes(int count, AtomicInteger ran) {
        List<Runnable> writes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            writes.add(ran::incrementAndGet);
        }
        return writes;
    }

    private static long[] delays(long... delays) {
        return delays;
    }
}
