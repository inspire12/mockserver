package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * An {@link HttpState} registers its request sender as the process-wide sender of load scenarios and drift alerts.
 * A sender it replaced, or one installed once it has stopped, must not be left there: it would keep the stopped
 * server behind it.
 * <p>
 * Runs in the sequential phase: it uses the load-scenario and drift-alert singletons.
 */
public class HttpStateRequestSenderTest {

    @Test
    public void shouldNotKeepARequestSenderReplacedBeforeTheServerStopped() throws InterruptedException {
        HttpState httpState = new HttpState(configuration(), new MockServerLogger(), null);
        AtomicReference<Function<HttpRequest, CompletableFuture<HttpResponse>>> replaced = new AtomicReference<>(new NeverResponds());
        WeakReference<?> replacedSender = new WeakReference<>(replaced.get());
        try {
            httpState.installRequestSender(replaced.get());
            httpState.installRequestSender(new NeverResponds());
        } finally {
            httpState.stop();
        }

        replaced.set(null);

        assertCollected("a request sender replaced before its server stopped", replacedSender);
    }

    @Test
    public void shouldNotKeepARequestSenderInstalledWhileTheServerStops() throws InterruptedException {
        assertCollected("a request sender installed once its server had stopped", installedOnceStopped());
    }

    private static WeakReference<?> installedOnceStopped() {
        HttpState httpState = new HttpState(configuration(), new MockServerLogger(), null);
        try {
            httpState.installRequestSender(new NeverResponds());
        } finally {
            httpState.stop();
        }
        // a connection whose first request arrives while its server stops installs the sender again
        Function<HttpRequest, CompletableFuture<HttpResponse>> late = new NeverResponds();
        httpState.installRequestSender(late);
        return new WeakReference<>(late);
    }

    private static void assertCollected(String what, WeakReference<?> reference) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (reference.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(100);
        }
        assertThat(what + " is still reachable after 30s of garbage collection", reference.get(), is(nullValue()));
    }

    private static final class NeverResponds implements Function<HttpRequest, CompletableFuture<HttpResponse>> {
        @Override
        public CompletableFuture<HttpResponse> apply(HttpRequest httpRequest) {
            return new CompletableFuture<>();
        }
    }
}
