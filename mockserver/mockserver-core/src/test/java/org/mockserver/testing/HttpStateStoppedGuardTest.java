package org.mockserver.testing;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;

/**
 * Build-time guard: a test, in any module, that constructs an {@code HttpState} or a {@code MockServerEventLog} must
 * stop it.
 *
 * <h2>Why</h2>
 * <p>Only {@code HttpState.stop()} (or {@code MockServerEventLog.stop()} for an event log built directly) ends the
 * event-log thread, and that thread keeps the state, its event log and its expectations reachable, so every one a
 * test drops unstopped stays in the test JVM until the fork ends.
 *
 * <h2>Limits</h2>
 * <p>A textual check of every module's {@code src/test/java}, and of the main sources of the test-support modules, as
 * {@link EphemeralListenerBindGuardTest#testSources()} lists them. Each {@code new HttpState(} or
 * {@code new MockServerEventLog(} must be assigned to a name, and the file must call {@code name.stop()} (or
 * {@code this.name.stop()}), or add the name to a collection it stops with {@code forEach(HttpState::stop)} (or
 * {@code forEach(MockServerEventLog::stop)}).
 * Whether that call runs in an {@code @After} or a {@code finally} is not checked, and a name reused for another
 * object is not told apart. A construction that is not assigned (returned, passed on, or dropped) is reported unless
 * it is listed in {@link #ALLOWED} with a reason.
 */
public class HttpStateStoppedGuardTest {

    private static final List<String> STOPPABLE_TYPES = List.of("HttpState", "MockServerEventLog");

    private static final Pattern CONSTRUCTION = construction("HttpState");

    private static final Pattern EVENT_LOG_CONSTRUCTION = construction("MockServerEventLog");

    private static Pattern construction(String type) {
        return Pattern.compile("\\bnew\\s+(?:org\\.mockserver\\.(?:mock|log)\\.)?" + type + "\\s*\\(");
    }

    private static final Pattern ASSIGNED_TO = Pattern.compile("(?:this\\s*\\.\\s*)?(\\w+)\\s*=\\s*$");

    // a name may be qualified with this., but not with any other receiver
    private static final String RECEIVER = "(?<![\\w.])(?:this\\s*\\.\\s*)?";

    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    /**
     * Constructions that are not assigned to a name and are meant, keyed by {@code module/File.java}, with how many the
     * file has.
     */
    private static final Map<String, Allowed> ALLOWED = Map.ofEntries(
        Map.entry("mockserver-core/AbandonedHttpStateIsCollectedTest.java", new Allowed(1,
            "drops an HttpState without stop() on purpose, to show what is left once its event log has stopped")),
        Map.entry("mockserver-core/ClusterPeerClientLifecycleTest.java", new Allowed(1,
            "returned by a helper; each test stops the HttpState it gets from it")),
        Map.entry("mockserver-core/HttpStateFailedConstructionTest.java", new Allowed(1,
            "the constructor throws, so there is nothing to stop")),
        Map.entry("mockserver-core/HttpStateReadinessTest.java", new Allowed(1,
            "constructs on another thread into an AtomicReference, whose HttpState the @After method stops")),
        Map.entry("mockserver-core/MockServerEventLogDerivedFormReleaseTest.java", new Allowed(1,
            "returned by a helper; each test stops the event log it gets from it in a finally block")),
        Map.entry("mockserver-core/MockServerEventLogInFlightBytesTest.java", new Allowed(1,
            "returned by a helper; each test stops the event log it gets from it in a finally block")),
        Map.entry("mockserver-core/MockServerEventLogVerifyIncompleteLogCauseTest.java", new Allowed(1,
            "returned by a helper into the log field, which the @After method stops")),
        Map.entry("mockserver-core/MetricsTest.java", new Allowed(1,
            "returned by a helper; each test stops the event log it gets from it in a finally block")),
        Map.entry("mockserver-benchmark/EventLogQueryDropProof.java", new Allowed(1,
            "returned by a helper; each scenario stops the event log it gets from it in a finally block")),
        Map.entry("mockserver-netty/DashboardWebSocketHandlerTest.java", new Allowed(8,
            "each is passed to track(), which adds it to trackedHttpStates; the @After method stops every one")),
        Map.entry("mockserver-war/MockServerServletTest.java", new Allowed(1,
            "spied, and the spy injected into the servlet, whose destroy() in the @After method stops it")),
        Map.entry("mockserver-proxy-war/ProxyServletTest.java", new Allowed(1,
            "spied, and the spy injected into the servlet, whose destroy() in the @After method stops it"))
    );

    @Test
    public void shouldStopEveryHttpStateAndEventLogATestConstructs() throws IOException {
        List<Path> sources = EphemeralListenerBindGuardTest.testSources();
        Collections.sort(sources);

        Map<String, Integer> constructingByModule = new TreeMap<>();
        int coreEventLogConstructingFiles = 0;
        Map<String, List<String>> offendersByFile = new TreeMap<>();
        for (Path source : sources) {
            String fileName = source.getFileName().toString();
            if (fileName.equals(HttpStateStoppedGuardTest.class.getSimpleName() + ".java")) {
                continue;
            }
            String content = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            boolean constructsHttpState = CONSTRUCTION.matcher(content).find();
            boolean constructsEventLog = EVENT_LOG_CONSTRUCTION.matcher(content).find();
            if (!constructsHttpState && !constructsEventLog) {
                continue;
            }
            String module = EphemeralListenerBindGuardTest.module(source);
            if (constructsHttpState) {
                constructingByModule.merge(module, 1, Integer::sum);
            }
            if (constructsEventLog && module.equals("mockserver-core")) {
                coreEventLogConstructingFiles++;
            }
            List<String> offences = offences(content);
            Allowed allowed = ALLOWED.get(module + "/" + fileName);
            long unassigned = offences.stream().filter(offence -> offence.startsWith(UNASSIGNED)).count();
            if (allowed != null && unassigned == allowed.count) {
                offences.removeIf(offence -> offence.startsWith(UNASSIGNED));
            }
            if (!offences.isEmpty()) {
                offendersByFile.put(EphemeralListenerBindGuardTest.MODULES_ROOT.relativize(source).toString(), offences);
            }
        }

        assertThat("must find the tests that construct an HttpState in core, netty, war and proxy-war: " + constructingByModule,
            constructingByModule.keySet(), hasItems("mockserver-core", "mockserver-netty", "mockserver-war", "mockserver-proxy-war"));
        assertThat("must find the core tests that construct an HttpState", constructingByModule.get("mockserver-core"), greaterThan(40));
        assertThat("must find the netty tests that construct an HttpState", constructingByModule.get("mockserver-netty"), greaterThan(40));
        assertThat("must find the core tests that construct a MockServerEventLog", coreEventLogConstructingFiles, greaterThan(15));
        assertThat("a test constructs an HttpState or a MockServerEventLog it never stops; the thread of its event log keeps it, and"
                + " everything it holds, for the rest of the test JVM. Stop it in an @After method or a finally block:\n"
                + describe(offendersByFile),
            offendersByFile.keySet(), is(empty()));
    }

    @Test
    public void shouldReportAnHttpStateThatIsNeverStopped() {
        assertThat(offences("    private HttpState httpState;\n"
                + "    @Before\n    public void setUp() {\n        httpState = new HttpState(configuration, logger, scheduler);\n    }\n"),
            contains("httpState is never stopped"));
        assertThat(offences("    public void test() {\n        HttpState local = new HttpState(configuration, logger, scheduler);\n"
                + "        other.stop();\n    }\n"),
            contains("local is never stopped"));
        assertThat(offences("    private final HttpState state = new org.mockserver.mock.HttpState(configuration(), logger, null);\n"
                + "    // state.stop();\n"),
            contains("state is never stopped"));
        assertThat(offences("    private HttpState newHttpState() {\n        return new HttpState(configuration, logger, scheduler);\n    }\n"),
            contains(UNASSIGNED + "return new HttpState("));
    }

    @Test
    public void shouldAcceptAnHttpStateThatIsStopped() {
        assertThat(offences("    @Before\n    public void setUp() {\n        httpState = new HttpState(configuration, logger, scheduler);\n    }\n"
                + "    @After\n    public void tearDown() {\n        httpState.stop();\n    }\n"),
            is(empty()));
        assertThat(offences("    private HttpState newHttpState() {\n        HttpState httpState = new HttpState(configuration, logger, scheduler);\n"
                + "        httpStates.add(httpState);\n        return httpState;\n    }\n"
                + "    @After\n    public void stopHttpStates() {\n        httpStates.forEach(HttpState::stop);\n    }\n"),
            is(empty()));
        assertThat(offences("    @Before\n    public void setUp() {\n        this.httpState = new HttpState(configuration, logger, scheduler);\n    }\n"
                + "    @After\n    public void tearDown() {\n        this.httpState.stop();\n    }\n"),
            is(empty()));
    }

    @Test
    public void shouldReportAMockServerEventLogThatIsNeverStopped() {
        assertThat(offences("    @Before\n    public void setUp() {\n"
                + "        eventLog = new MockServerEventLog(configuration, logger, scheduler, true);\n    }\n"),
            contains("eventLog is never stopped"));
        assertThat(offences("    private MockServerEventLog eventLog(Configuration configuration) {\n"
                + "        return new MockServerEventLog(configuration, logger, scheduler, false);\n    }\n"),
            contains(UNASSIGNED + "return new MockServerEventLog("));
        assertThat("a collection stopped as HttpStates does not stop event logs",
            offences("        MockServerEventLog log = new MockServerEventLog(configuration, logger, scheduler, true);\n"
                + "        logs.add(log);\n    @After\n    public void stop() {\n        logs.forEach(HttpState::stop);\n    }\n"),
            contains("log is never stopped"));
    }

    @Test
    public void shouldAcceptAMockServerEventLogThatIsStopped() {
        assertThat(offences("    @Before\n    public void setUp() {\n"
                + "        eventLog = new MockServerEventLog(configuration, logger, scheduler, true);\n    }\n"
                + "    @After\n    public void tearDown() {\n        eventLog.stop();\n    }\n"),
            is(empty()));
        assertThat(offences("        MockServerEventLog log = new MockServerEventLog(configuration, logger, scheduler, true);\n"
                + "        logs.add(log);\n    @After\n    public void stop() {\n        logs.forEach(MockServerEventLog::stop);\n    }\n"),
            is(empty()));
    }

    private static final String UNASSIGNED = "not assigned to a name: ";

    static List<String> offences(String source) {
        String code = COMMENT.matcher(source).replaceAll("");
        List<String> offences = new ArrayList<>();
        for (String type : STOPPABLE_TYPES) {
            Matcher construction = construction(type).matcher(code);
            while (construction.find()) {
                String statement = statementBefore(code, construction.start());
                Matcher assigned = ASSIGNED_TO.matcher(statement);
                if (!assigned.find()) {
                    offences.add(UNASSIGNED + (statement.trim() + " new " + type + "(").trim());
                } else if (!stopped(code, assigned.group(1), type)) {
                    offences.add(assigned.group(1) + " is never stopped");
                }
            }
        }
        return offences;
    }

    private static String statementBefore(String code, int index) {
        int start = index;
        while (start > 0 && ";{}".indexOf(code.charAt(start - 1)) < 0) {
            start--;
        }
        return code.substring(start, index);
    }

    private static boolean stopped(String code, String name, String type) {
        String quoted = Pattern.quote(name);
        if (Pattern.compile(RECEIVER + quoted + "\\s*\\.\\s*stop\\s*\\(\\s*\\)").matcher(code).find()) {
            return true;
        }
        Matcher addedTo = Pattern.compile("(\\w+)\\s*\\.\\s*add\\s*\\(\\s*" + quoted + "\\s*\\)").matcher(code);
        while (addedTo.find()) {
            if (Pattern.compile(RECEIVER + Pattern.quote(addedTo.group(1)) + "\\s*\\.\\s*forEach\\s*\\(\\s*" + type + "::stop\\s*\\)").matcher(code).find()) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Map<String, List<String>> offendersByFile) {
        StringBuilder description = new StringBuilder();
        offendersByFile.forEach((file, offences) -> description.append("  ").append(file).append(": ").append(offences).append('\n'));
        return description.toString();
    }

    private static final class Allowed {
        final int count;
        final String reason;

        Allowed(int count, String reason) {
            this.count = count;
            this.reason = reason;
        }
    }
}
