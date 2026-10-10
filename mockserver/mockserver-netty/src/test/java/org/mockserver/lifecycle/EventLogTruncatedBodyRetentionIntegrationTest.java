package org.mockserver.lifecycle;

import com.google.common.collect.Multimap;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.matchers.Times;
import org.mockserver.model.ClearType;
import org.mockserver.netty.MockServer;
import org.slf4j.event.Level;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * With maxLoggedBodyBytes set, nothing a retained log entry reaches (its messages, its message arguments, the
 * expectation it names) may hold a whole 1 MiB body once the expectations themselves are gone. A server without
 * the setting, given the same traffic, is the control that shows the walk finds such bodies.
 */
public class EventLogTruncatedBodyRetentionIntegrationTest {

    private static final int BODY_BYTES = 1024 * 1024;
    private static final int MAX_LOGGED_BODY_BYTES = 4096;
    private static final int LARGE = 64 * 1024;
    private static final int EXCHANGES = 3;
    private static final String BODY = "b".repeat(BODY_BYTES);

    private static MockServer backend;
    private static MockServer truncating;
    private static MockServer whole;

    @BeforeClass
    public static void startServers() throws Exception {
        backend = new MockServer(configuration().logLevel(Level.WARN));
        new MockServerClient("localhost", backend.getLocalPort())
            .when(request().withPath("/backend.*"))
            .respond(response().withHeader("content-type", "text/plain").withBody(BODY));
        truncating = new MockServer(configuration().logLevel(Level.INFO).maxLoggedBodyBytes(MAX_LOGGED_BODY_BYTES));
        whole = new MockServer(configuration().logLevel(Level.INFO));
        for (MockServer server : new MockServer[]{truncating, whole}) {
            sendTraffic(server);
        }
    }

    @AfterClass
    public static void stopServers() {
        for (MockServer server : new MockServer[]{truncating, whole, backend}) {
            if (server != null) {
                server.stop();
            }
        }
    }

    private static void sendTraffic(MockServer server) throws Exception {
        MockServerClient client = new MockServerClient("localhost", server.getLocalPort());
        HttpClient direct = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
        HttpClient viaProxy = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.of(new InetSocketAddress("localhost", server.getLocalPort()))).build();
        String local = "http://localhost:" + server.getLocalPort();
        for (int i = 0; i < EXCHANGES; i++) {
            client.when(request().withPath("/mocked/" + i), Times.once())
                .respond(response().withHeader("content-type", "text/plain").withBody(BODY));
            client.when(request().withPath("/backend-forwarded/" + i), Times.once())
                .forward(forward().withHost("localhost").withPort(backend.getLocalPort()));
            client.when(request().withPath("/closest/" + i).withBody(BODY + "-other"))
                .respond(response().withHeader("content-type", "text/plain").withBody(BODY));
            assertThat(post(direct, local + "/mocked/" + i), is(200));
            assertThat(post(direct, local + "/backend-forwarded/" + i), is(200));
            assertThat(post(viaProxy, "http://localhost:" + backend.getLocalPort() + "/backend-proxy/" + i), is(200));
            assertThat(post(direct, local + "/closest/" + i), is(404));
        }
        // the expectations no longer hold their bodies: only the log can
        client.clear(request(), ClearType.EXPECTATIONS);
        for (int i = 0; i < EXCHANGES; i++) {
            assertThat(post(direct, local + "/unmatched/" + i), is(404));
        }
    }

    private static int post(HttpClient client, String uri) throws Exception {
        return client.send(
            java.net.http.HttpRequest.newBuilder(URI.create(uri))
                .header("content-type", "text/plain")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(BODY))
                .build(),
            java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    public void shouldRetainNoWholeBodyWhenMaxLoggedBodyBytesIsSet() throws Exception {
        Retained retained = walk(truncating);

        assertThat(retained.largeObjectPaths, is(empty()));
        assertThat(retained.payloadBytes, lessThan(retained.estimatedHeapSize));
    }

    @Test
    public void shouldFindWholeBodiesWithoutMaxLoggedBodyBytes() throws Exception {
        Retained retained = walk(whole);

        // the request bodies of the unmatched requests at least, so the walk does reach bodies
        assertThat(retained.largeObjectPaths.size(), greaterThanOrEqualTo(EXCHANGES));
        assertThat(retained.payloadBytes, greaterThanOrEqualTo((long) EXCHANGES * BODY_BYTES));
    }

    private static Retained walk(MockServer server) throws Exception {
        CompletableFuture<List<LogEntry>> entries = new CompletableFuture<>();
        server.httpState.getMockServerLog().retrieveLogEntriesInReverseForUI(null, entry -> true, Function.identity(),
            stream -> entries.complete(stream.collect(Collectors.toList())));
        Retained retained = new Retained();
        GraphWalk walk = new GraphWalk();
        for (LogEntry entry : entries.get(30, TimeUnit.SECONDS)) {
            retained.estimatedHeapSize += entry.estimatedHeapSize();
            walk.from(entry, entry.getType() + " " + entry.getMessageFormat());
        }
        retained.payloadBytes = walk.payloadBytes;
        retained.largeObjectPaths = walk.largeObjectPaths;
        System.out.println("retained by " + (server == truncating ? "maxLoggedBodyBytes=" + MAX_LOGGED_BODY_BYTES : "maxLoggedBodyBytes=0")
            + ": " + retained.payloadBytes + " payload bytes, weigher " + retained.estimatedHeapSize + ", large " + retained.largeObjectPaths);
        return retained;
    }

    private static final class Retained {
        private long payloadBytes;
        private long estimatedHeapSize;
        private List<String> largeObjectPaths;
    }

    /**
     * Counts the bytes of every String and primitive array reachable, once each, from model objects. Service objects
     * (loggers, configuration, Netty, threads) are not followed, and JDK types other than strings, arrays and
     * collections are not looked into.
     */
    private static final class GraphWalk {
        private static final int MAX_OBJECTS = 2_000_000;
        private final Map<Object, Boolean> seen = new IdentityHashMap<>();
        private final List<String> largeObjectPaths = new ArrayList<>();
        private long payloadBytes;

        void from(Object root, String rootName) throws IllegalAccessException {
            Deque<Object[]> pending = new ArrayDeque<>();
            pending.push(new Object[]{root, rootName});
            while (!pending.isEmpty()) {
                Object[] next = pending.pop();
                Object object = next[0];
                String path = (String) next[1];
                if (object == null || object instanceof Class || seen.put(object, Boolean.TRUE) != null || !followed(object)) {
                    continue;
                }
                assertThat("objects walked", seen.size(), lessThan(MAX_OBJECTS));
                if (object instanceof String) {
                    payload(((String) object).length(), path);
                } else if (object.getClass().isArray()) {
                    if (object.getClass().getComponentType().isPrimitive()) {
                        payload(Array.getLength(object), path);
                    } else {
                        for (int i = 0; i < Array.getLength(object); i++) {
                            pending.push(new Object[]{Array.get(object, i), path + "[" + i + "]"});
                        }
                    }
                } else if (object instanceof Map) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
                        pending.push(new Object[]{entry.getKey(), path + ".key"});
                        pending.push(new Object[]{entry.getValue(), path + "[" + entry.getKey() + "]"});
                    }
                } else if (object instanceof Multimap) {
                    pending.push(new Object[]{((Multimap<?, ?>) object).asMap(), path});
                } else if (object instanceof Collection) {
                    int i = 0;
                    for (Object element : (Collection<?>) object) {
                        pending.push(new Object[]{element, path + "[" + i++ + "]"});
                    }
                } else if (object instanceof Optional) {
                    pending.push(new Object[]{((Optional<?>) object).orElse(null), path});
                } else if (object instanceof AtomicReference) {
                    pending.push(new Object[]{((AtomicReference<?>) object).get(), path});
                } else if (!(object instanceof Enum)) {
                    for (Class<?> type = object.getClass(); type != null && !isJdk(type); type = type.getSuperclass()) {
                        for (Field field : type.getDeclaredFields()) {
                            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                                continue;
                            }
                            field.setAccessible(true);
                            pending.push(new Object[]{field.get(object), path + "." + field.getName()});
                        }
                    }
                }
            }
        }

        private void payload(long bytes, String path) {
            payloadBytes += bytes;
            if (bytes > LARGE) {
                largeObjectPaths.add(path + " (" + bytes + ")");
            }
        }

        private static boolean followed(Object object) {
            String name = object.getClass().getName();
            return !(object instanceof Configuration
                || object instanceof org.mockserver.logging.MockServerLogger
                || object instanceof Thread
                || object instanceof ClassLoader
                || name.startsWith("io.netty.")
                || name.startsWith("org.mockserver.log.MockServerEventLog")
                || name.startsWith("org.mockserver.mock.HttpState")
                || name.startsWith("org.mockserver.scheduler."));
        }

        private static boolean isJdk(Class<?> type) {
            String name = type.getName();
            return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.");
        }
    }
}
