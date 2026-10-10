package org.mockserver.httpclient;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.BinaryMessage;
import org.slf4j.event.Level;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.BinaryMessage.bytes;

/**
 * A caller that forwards binary messages in order starts the next one from the {@code onRequestSent} callback,
 * so the callback must run exactly once: when the request has been written, and when it could not be.
 */
public class NettyHttpClientBinaryRequestSentTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static EventLoopGroup group;

    @BeforeClass
    public static void startGroup() {
        group = new NioEventLoopGroup(1);
    }

    @AfterClass
    public static void stopGroup() {
        group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }

    private static NettyHttpClient client(MockServerLogger mockServerLogger) {
        return new NettyHttpClient(configuration().forwardBinaryRequestsWithoutWaitingForResponse(true), mockServerLogger, group, null, false);
    }

    private static final BinaryMessage MESSAGE = bytes("a binary message".getBytes(StandardCharsets.UTF_8));

    /** Records what the client reports through {@code onRequestSent}, and how often. */
    private static final class Reported {
        private final CountDownLatch once = new CountDownLatch(1);
        private final AtomicInteger times = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private void accept(Throwable sendFailure) {
            failure.set(sendFailure);
            times.incrementAndGet();
            once.countDown();
        }

        private Throwable await() throws InterruptedException {
            assertThat("the client reports what became of the request", once.await(30, TimeUnit.SECONDS), is(true));
            return failure.get();
        }
    }

    @Test
    public void shouldReportTheRequestSentOnceItHasBeenWrittenUpstream() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 1, LOOPBACK)) {
            Reported reported = new Reported();

            client(new MockServerLogger()).sendRequest(MESSAGE, false, new InetSocketAddress(LOOPBACK, upstream.getLocalPort()), 10000L, reported::accept);

            assertThat("no failure is reported for a request that was written", reported.await(), is(nullValue()));
            try (Socket connection = upstream.accept()) {
                connection.setSoTimeout(10000);
                InputStream received = connection.getInputStream();
                assertThat(new String(received.readNBytes("a binary message".length()), StandardCharsets.UTF_8), is("a binary message"));
            }
            assertThat(reported.times.get(), is(1));
        }
    }

    @Test
    public void shouldReportTheFailureWhenTheUpstreamCannotBeReached() throws Exception {
        // bound but not listening, so the connection is refused and no other process can take the port
        try (Socket notListening = new Socket()) {
            notListening.bind(new InetSocketAddress(LOOPBACK, 0));
            Reported reported = new Reported();

            // a connect timeout well inside the wait, so a refusal slowed down by a loaded machine is still reported
            CompletableFuture<BinaryMessage> response = client(new MockServerLogger()).sendRequest(MESSAGE, false, new InetSocketAddress(LOOPBACK, notListening.getLocalPort()), 2000L, reported::accept);

            assertThat("the failed attempt is reported, so the caller does not wait for it", reported.await(), is(notNullValue()));
            assertThrows(ExecutionException.class, () -> response.get(30, TimeUnit.SECONDS));
            assertThat(reported.times.get(), is(1));
        }
    }

    @Test
    public void shouldReportTheFailureWhenTheRequestIsNotWrittenAfterConnecting() throws Exception {
        // the debug log of the bytes about to be sent fails, after the connection is made and before the write
        MockServerLogger failsToLog = mock(MockServerLogger.class);
        when(failsToLog.isEnabledForInstance(Level.DEBUG)).thenReturn(true);
        doThrow(new IllegalStateException("cannot log")).when(failsToLog).logEvent(any(LogEntry.class));
        try (ServerSocket upstream = new ServerSocket(0, 1, LOOPBACK)) {
            Reported reported = new Reported();

            CompletableFuture<BinaryMessage> response = client(failsToLog).sendRequest(MESSAGE, false, new InetSocketAddress(LOOPBACK, upstream.getLocalPort()), 10000L, reported::accept);

            assertThat(reported.await(), is(instanceOf(IllegalStateException.class)));
            assertThrows("no response can arrive for a request that was never written", ExecutionException.class, () -> response.get(10, TimeUnit.SECONDS));
            assertThat(reported.times.get(), is(1));
            try (Socket connection = upstream.accept()) {
                connection.setSoTimeout(10000);
                assertThat("the connection nothing will be written to is closed", connection.getInputStream().read(), is(-1));
            }
        }
    }

    @Test
    public void shouldReportTheFailureWhenTheRequestCannotBeWritten() throws Exception {
        // the request is to be sent over TLS and the upstream closes instead of answering the handshake, so
        // the connection is made and the write behind the handshake fails
        try (ServerSocket upstream = new ServerSocket(0, 1, LOOPBACK)) {
            Thread closesEveryConnection = new Thread(() -> {
                try (Socket connection = upstream.accept()) {
                    connection.setSoTimeout(10000);
                    connection.getInputStream().read();
                } catch (IOException upstreamClosed) {
                    // the test has finished
                }
            }, "upstream-closing-on-handshake");
            closesEveryConnection.start();
            Reported reported = new Reported();

            client(new MockServerLogger()).sendRequest(MESSAGE, true, new InetSocketAddress(LOOPBACK, upstream.getLocalPort()), 10000L, reported::accept);

            assertThat("a request that was not written is not reported as sent", reported.await(), is(notNullValue()));
            assertThat(reported.times.get(), is(1));
            closesEveryConnection.join(10000);
        }
    }
}
