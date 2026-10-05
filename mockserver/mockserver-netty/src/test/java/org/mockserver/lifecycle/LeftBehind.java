package org.mockserver.lifecycle;

import com.sun.management.UnixOperatingSystemMXBean;
import org.junit.Assume;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * What starting and stopping left behind in this JVM. Threads are looked for in a thread group of the
 * test's own, which a thread joins when it is started from that group, so the threads of other tests are not
 * seen. Open file descriptors can only be counted for the whole process.
 */
public final class LeftBehind {

    public static final long DEADLINE_SECONDS = 30;

    private static final long BODY_DEADLINE_SECONDS = 120;

    private static final Pattern JDK_HTTP_CLIENT_SELECTOR = Pattern.compile("HttpClient-\\d+-SelectorManager");

    // longer than the two seconds Netty waits before it ends the event loops of a group shut down gracefully
    private static final int SETTLED_AFTER_UNCHANGED_SAMPLES = 25;
    private static final long SAMPLE_MILLIS = 100;
    private static final long COLLECT_GARBAGE_EVERY_MILLIS = 1000;

    private LeftBehind() {
    }

    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    /**
     * Runs {@code body} on a thread of {@code group} and rethrows what it threw.
     */
    public static void in(ThreadGroup group, Body body) throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = new Thread(group, () -> {
            try {
                body.run();
            } catch (Throwable throwable) {
                thrown.set(throwable);
            }
        }, group.getName());
        thread.start();
        thread.join(TimeUnit.SECONDS.toMillis(BODY_DEADLINE_SECONDS));
        assertThat("finished within " + BODY_DEADLINE_SECONDS + "s", thread.isAlive(), is(false));
        if (thrown.get() instanceof Error) {
            throw (Error) thrown.get();
        }
        if (thrown.get() != null) {
            throw (Exception) thrown.get();
        }
    }

    /**
     * @return the threads of {@code group} still alive once the deadline has passed, none if all ended sooner
     */
    public static List<String> threadsStillAlive(ThreadGroup group) throws InterruptedException {
        return stillAlive(group, thread -> true, false);
    }

    /**
     * The JDK ends an HttpClient's selector thread only after the client has been garbage collected.
     *
     * @return the JDK HttpClient selector threads of {@code group} still alive once the deadline has passed
     */
    public static List<String> jdkHttpClientThreadsStillAlive(ThreadGroup group) throws InterruptedException {
        return stillAlive(group, thread -> JDK_HTTP_CLIENT_SELECTOR.matcher(thread.getName()).matches(), true);
    }

    private static List<String> stillAlive(ThreadGroup group, Predicate<Thread> counted, boolean collectGarbage) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        long collectAt = System.nanoTime();
        while (true) {
            if (collectGarbage && System.nanoTime() >= collectAt) {
                System.gc();
                collectAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COLLECT_GARBAGE_EVERY_MILLIS);
            }
            Thread[] threads = new Thread[group.activeCount() + 16];
            int count = group.enumerate(threads, true);
            List<String> alive = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                if (threads[i].isAlive() && counted.test(threads[i])) {
                    alive.add(threads[i].getName());
                }
            }
            if (alive.isEmpty() || System.nanoTime() >= deadline) {
                return alive;
            }
            Thread.sleep(SAMPLE_MILLIS);
        }
    }

    /**
     * Descriptors that earlier tests in this JVM are still closing must not be counted in a baseline, and a
     * count that never stops changing is no baseline at all: that fails the test here, since a comparison
     * with it could pass or fail for reasons that are not the test's.
     *
     * @return the open file descriptors once their number has stopped changing, or -1 where they cannot be counted
     */
    public static long settledDescriptors() throws InterruptedException {
        if (!descriptorsCountable()) {
            return -1;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        long last = openDescriptors();
        long lowest = last;
        long highest = last;
        int unchanged = 0;
        while (unchanged < SETTLED_AFTER_UNCHANGED_SAMPLES && System.nanoTime() < deadline) {
            Thread.sleep(SAMPLE_MILLIS);
            long now = openDescriptors();
            unchanged = now == last ? unchanged + 1 : 0;
            last = now;
            lowest = Math.min(lowest, now);
            highest = Math.max(highest, now);
        }
        assertThat("the open file descriptors of this JVM kept changing for " + DEADLINE_SECONDS + "s (between " + lowest + " and " + highest
            + "), so there is no baseline to compare with: something else in this JVM is still opening or closing them", unchanged, is(SETTLED_AFTER_UNCHANGED_SAMPLES));
        return last;
    }

    /**
     * Fails unless fewer descriptors than {@code startsOrStops} are open above {@code baseline} within the
     * deadline: one left behind by each start or stop fails, a few opened once by the JVM do not. The test is
     * skipped where descriptors cannot be counted.
     */
    public static void assertDescriptorsGivenBack(long baseline, int startsOrStops) throws InterruptedException {
        Assume.assumeTrue("open file descriptors cannot be counted on this platform", descriptorsCountable());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        long leftBehind = openDescriptors() - baseline;
        while (leftBehind >= startsOrStops && System.nanoTime() < deadline) {
            Thread.sleep(SAMPLE_MILLIS);
            leftBehind = openDescriptors() - baseline;
        }
        assertThat("file descriptors left open by " + startsOrStops + " starts or stops", leftBehind, is(lessThan((long) startsOrStops)));
    }

    private static boolean descriptorsCountable() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof UnixOperatingSystemMXBean;
    }

    private static long openDescriptors() {
        OperatingSystemMXBean operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        return ((UnixOperatingSystemMXBean) operatingSystem).getOpenFileDescriptorCount();
    }
}
