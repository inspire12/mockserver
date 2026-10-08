package org.mockserver.lifecycle;

import org.junit.Test;
import org.junit.runner.Description;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the listener the way surefire's JUnit Platform provider does: a test class, and each parameter set inside
 * it, start and finish as {@code testStarted} and {@code testFinished}; test methods are not reported.
 */
public class ClearInlineMocksAfterEachTestClassTest {

    @Test
    public void aMockThatRecordedItselfIsReleasedOnceItsClassHasFinishedAndNotBefore() throws Exception {
        ClearInlineMocksAfterEachTestClass listener = new ClearInlineMocksAfterEachTestClass();
        Description testClass = Description.createTestDescription(getClass().getName(), "SomeTest");
        Description parameterSet = Description.createTestDescription(getClass().getName(), "[0]");

        listener.testStarted(testClass);
        Recorder selfRecording = mock(Recorder.class);
        when(selfRecording.size()).thenReturn(7);
        selfRecording.record(selfRecording);
        WeakReference<Object> created = new WeakReference<>(selfRecording);
        listener.testStarted(parameterSet);
        listener.testFinished(parameterSet);
        assertThat("the mock is still stubbed once a parameter set of its class has finished", selfRecording.size(), is(7));
        selfRecording = null;
        listener.testFinished(testClass);

        // Mockito keeps the latest invocation on this thread until another mock is used on it
        mock(Runnable.class).run();
        assertCollected(created);
    }

    // a class, not an interface, so it is an inline mock, which only works while Mockito keeps its state
    static class Recorder {
        int size() {
            return 0;
        }

        void record(Object recorded) {
        }
    }

    private static void assertCollected(WeakReference<?> reference) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (reference.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(100);
        }
        assertThat("a mock whose recorded invocation refers to it is still reachable after its class finished", reference.get(), is(nullValue()));
    }
}
