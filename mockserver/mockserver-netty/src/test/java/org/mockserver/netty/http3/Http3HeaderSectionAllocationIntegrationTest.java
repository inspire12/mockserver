package org.mockserver.netty.http3;

import com.sun.management.ThreadMXBean;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.netty.MockServer;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * What MockServer allocates to refuse an HTTP/3 header section that is small on the wire and far over
 * {@code maxHeaderSize} once decoded: fields the QPACK static table holds whole, so a byte each sent and 64 bytes each
 * decoded. Netty counts each field against the limit as it decodes it and stops keeping them once the limit is passed,
 * so the cost of a field past the limit is a small fraction of the cost of keeping it.
 * <p>
 * Measured on a real QUIC connection, as the heap allocated by every thread but the client's while the section is
 * sent and refused. Buffer leak tracking is off for this class alone: it records a stack trace for every byte read,
 * which would be all that this measured. {@code Http3HeaderListLimitIntegrationTest} refuses the same kind of
 * section with tracking on. Needs the QUIC native library, and fails without it.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3HeaderSectionAllocationIntegrationTest {

    private static final String CLIENT_THREADS = "http3-allocation-test-client";
    private static final String FIELD_NAME = "accept-encoding";
    private static final String FIELD_VALUE = "gzip, deflate, br";
    private static final int DECODED_FIELD_BYTES = FIELD_NAME.length() + FIELD_VALUE.length() + 32;
    // at least what it costs to keep one field: an entry of its header map
    private static final int KEPT_FIELD_HEAP_BYTES = 32;
    private static final int SMALLEST_OBJECT_BYTES = 16;
    private static final int FEWER_FIELDS = 50_000;
    private static final int MORE_FIELDS = 250_000;
    private static final int MEASUREMENTS = 3;

    private static ResourceLeakDetector.Level leakDetectionLevel;
    private static MockServer mockServer;
    private static NioEventLoopGroup clientGroup;

    @BeforeClass
    public static void startServer() {
        leakDetectionLevel = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
        mockServer = startWithHttp3(configuration().http3MaxIdleTimeout(30000L).logLevel("WARN"));
        clientGroup = new NioEventLoopGroup(2, new DefaultThreadFactory(CLIENT_THREADS));
    }

    @AfterClass
    public static void stopServer() {
        try {
            stopQuietly(mockServer);
            if (clientGroup != null) {
                clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
            }
        } finally {
            ResourceLeakDetector.setLevel(leakDetectionLevel);
        }
    }

    @Test
    public void shouldRefuseAHeaderSectionFarOverTheLimitWithoutKeepingItsFields() throws Exception {
        long limit = mockServer.getConfiguration().maxHeaderSize();
        assertThat("small enough on the wire to be read", (long) MORE_FIELDS, lessThan(limit));
        assertThat("far over the limit decoded", (long) MORE_FIELDS * DECODED_FIELD_BYTES, greaterThan(50 * limit));
        // first, so that loading the classes involved is not measured
        allocatedToRefuse(FEWER_FIELDS);
        allocatedToRefuse(MORE_FIELDS);

        long fewer = leastAllocatedToRefuse(FEWER_FIELDS);
        long more = leastAllocatedToRefuse(MORE_FIELDS);

        String measured = "allocated " + fewer + " bytes for " + FEWER_FIELDS + " fields and " + more + " bytes for " + MORE_FIELDS;
        assertThat(measured + ": the fields kept up to the limit are in what was measured", fewer, greaterThan(limit / DECODED_FIELD_BYTES * KEPT_FIELD_HEAP_BYTES));
        // the fields kept are the same number in both, so the difference is the cost of fields past the limit
        long perFieldPastTheLimit = (more - fewer) / (MORE_FIELDS - FEWER_FIELDS);
        assertThat(measured + ": no object is allocated for a field past the limit", perFieldPastTheLimit, lessThan(SMALLEST_OBJECT_BYTES / 2L));
        assertThat(measured + ": within twice the limit itself", more, lessThan(2 * limit));
    }

    /**
     * The least of several measurements: whatever else the JVM's other threads allocate meanwhile only adds.
     */
    private static long leastAllocatedToRefuse(int fields) throws Exception {
        long least = Long.MAX_VALUE;
        for (int i = 0; i < MEASUREMENTS; i++) {
            least = Math.min(least, allocatedToRefuse(fields));
        }
        return least;
    }

    /**
     * @return the heap allocated by every thread other than the client's and this one, from sending a header section
     * of {@code fields} static table references until MockServer has closed the connection for it
     */
    private static long allocatedToRefuse(int fields) throws Exception {
        try (Http3TestClient connection = Http3TestClient.open(clientGroup, mockServer)) {
            connection.serverSettings();
            Http3Headers bomb = new DefaultHttp3Headers()
                .method(HttpMethod.GET.asciiName())
                .scheme("https")
                .authority("127.0.0.1:" + mockServer.getHttp3Port())
                .path("/limit");
            for (int i = 0; i < fields; i++) {
                bomb.add(FIELD_NAME, FIELD_VALUE);
            }

            Map<Long, Long> before = allocatedByOtherThreads();
            connection.send(bomb);
            QuicConnectionCloseEvent closed = connection.closedByServer();
            Map<Long, Long> after = allocatedByOtherThreads();

            assertThat(closed.error(), is(Http3ErrorCode.H3_EXCESSIVE_LOAD.code()));
            // the decoded size was refused, not the frame's length, so the whole section was decoded
            assertThat(new String(closed.reason(), StandardCharsets.US_ASCII), startsWith("Header size exceeded max allowed size"));
            long allocated = 0;
            for (Map.Entry<Long, Long> thread : after.entrySet()) {
                allocated += thread.getValue() - before.getOrDefault(thread.getKey(), 0L);
            }
            return allocated;
        }
    }

    private static Map<Long, Long> allocatedByOtherThreads() {
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        Map<Long, Long> allocated = new HashMap<>();
        for (ThreadInfo thread : threads.getThreadInfo(threads.getAllThreadIds())) {
            if (thread != null && thread.getThreadId() != Thread.currentThread().getId() && !thread.getThreadName().startsWith(CLIENT_THREADS)) {
                allocated.put(thread.getThreadId(), threads.getThreadAllocatedBytes(thread.getThreadId()));
            }
        }
        return allocated;
    }
}
