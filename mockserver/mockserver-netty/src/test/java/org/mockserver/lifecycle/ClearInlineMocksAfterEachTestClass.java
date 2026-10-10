package org.mockserver.lifecycle;

import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;
import org.mockito.Mockito;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Clears Mockito's inline mocks once each top-level test class has finished. A mock in a real pipeline records
 * invocations whose arguments reach the mock itself, so Mockito's map of inline mocks would keep it, and the channel
 * and server behind it, for the rest of the fork.
 * <p>
 * Registered as a surefire listener in this module's pom. Surefire's JUnit Platform provider reports each test class,
 * and each parameter set inside one, to a JUnit 4 listener as {@code testStarted} and {@code testFinished}, and does
 * not report test methods, so the outermost of those is a class. This relies on test classes running one at a time.
 */
public class ClearInlineMocksAfterEachTestClass extends RunListener {

    private final AtomicInteger openClasses = new AtomicInteger();

    @Override
    public void testStarted(Description description) {
        openClasses.incrementAndGet();
    }

    @Override
    public void testFinished(Description description) {
        if (openClasses.decrementAndGet() == 0) {
            Mockito.framework().clearInlineMocks();
        }
    }
}
