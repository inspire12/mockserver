package org.mockserver.collections;

import org.junit.Test;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

public class MostRecentRegistrationTest {

    @Test
    public void shouldUseTheMostRecentlyRegisteredAndFallBackToTheOneBeforeItWhenItIsUnregistered() {
        MostRecentRegistration<Object> registration = new MostRecentRegistration<>();
        Object older = new Object();
        Object newer = new Object();
        registration.register(older);
        registration.register(newer);
        assertThat(registration.get(), sameInstance(newer));

        registration.unregister(newer);
        assertThat(registration.get(), sameInstance(older));

        registration.unregister(older);
        assertThat(registration.get(), is(nullValue()));
    }

    @Test
    public void shouldKeepTheOneInUseWhenAnotherIsUnregistered() {
        MostRecentRegistration<Object> registration = new MostRecentRegistration<>();
        Object older = new Object();
        Object stopped = new Object();
        Object newer = new Object();
        registration.register(older);
        registration.register(stopped);
        registration.register(newer);

        registration.unregister(stopped);
        assertThat(registration.get(), sameInstance(newer));

        registration.unregister(newer);
        assertThat("the unregistered one is not fallen back to", registration.get(), sameInstance(older));
    }

    @Test
    public void shouldMakeOneRegisteredAgainTheMostRecent() {
        MostRecentRegistration<Object> registration = new MostRecentRegistration<>();
        Object first = new Object();
        Object second = new Object();
        Object third = new Object();
        registration.register(first);
        registration.register(second);
        registration.register(first);
        registration.register(third);

        registration.unregister(third);
        assertThat(registration.get(), sameInstance(first));

        registration.unregister(first);
        assertThat(registration.get(), sameInstance(second));
    }

    @Test
    public void shouldNotFallBackToOneThatWasOnlySet() {
        MostRecentRegistration<Object> registration = new MostRecentRegistration<>();
        Object registered = new Object();
        Object set = new Object();
        Object newer = new Object();
        registration.register(registered);
        registration.set(set);
        assertThat(registration.get(), sameInstance(set));

        registration.register(newer);
        registration.unregister(newer);
        assertThat(registration.get(), sameInstance(registered));

        registration.set(null);
        assertThat(registration.get(), is(nullValue()));
    }

    @Test
    public void shouldIgnoreNull() {
        MostRecentRegistration<Object> registration = new MostRecentRegistration<>();
        Object registered = new Object();
        registration.register(registered);

        registration.register(null);
        registration.unregister(null);

        assertThat(registration.get(), sameInstance(registered));
    }

    @Test
    public void shouldNotKeepOneThatWasNeverUnregisteredAndFallBackPastIt() throws InterruptedException {
        MostRecentRegistration<Object> registration = new MostRecentRegistration<>();
        Object running = new Object();
        registration.register(running);
        WeakReference<Object> neverUnregistered = registeredAndDropped(registration);
        Object newer = new Object();
        registration.register(newer);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (neverUnregistered.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(100);
        }
        assertThat("an instance never unregistered that nothing else refers to is kept", neverUnregistered.get(), is(nullValue()));

        registration.unregister(newer);
        assertThat(registration.get(), sameInstance(running));
    }

    private static WeakReference<Object> registeredAndDropped(MostRecentRegistration<Object> registration) {
        Object instance = new Object();
        registration.register(instance);
        return new WeakReference<>(instance);
    }
}
