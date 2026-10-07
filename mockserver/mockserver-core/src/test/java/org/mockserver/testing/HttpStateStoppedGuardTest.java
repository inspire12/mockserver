package org.mockserver.testing;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * Build-time guard: a core test that constructs an {@code HttpState} must stop it.
 *
 * <h2>Why</h2>
 * <p>Only {@code HttpState.stop()} ends the event-log thread an {@code HttpState} starts, and that thread keeps the
 * state, its event log and its expectations reachable, so every one a test drops unstopped stays in the test JVM
 * until the fork ends.
 *
 * <h2>Limits</h2>
 * <p>A textual check of {@code src/test/java}. Each {@code new HttpState(} must be assigned to a name, and the file
 * must call {@code name.stop()} (or {@code this.name.stop()}), or add the name to a collection it stops with {@code forEach(HttpState::stop)}.
 * Whether that call runs in an {@code @After} or a {@code finally} is not checked, and a name reused for another
 * object is not told apart. A construction that is not assigned (returned, passed on, or dropped) is reported unless
 * it is listed in {@link #ALLOWED} with a reason.
 */
public class HttpStateStoppedGuardTest {

    private static final Path TEST_ROOT = Paths.get("src", "test", "java");

    private static final Pattern CONSTRUCTION = Pattern.compile("\\bnew\\s+(?:org\\.mockserver\\.mock\\.)?HttpState\\s*\\(");

    private static final Pattern ASSIGNED_TO = Pattern.compile("(?:this\\s*\\.\\s*)?(\\w+)\\s*=\\s*$");

    // a name may be qualified with this., but not with any other receiver
    private static final String RECEIVER = "(?<![\\w.])(?:this\\s*\\.\\s*)?";

    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    /**
     * Constructions that are not assigned to a name and are meant, keyed by file name, with how many the file has.
     */
    private static final Map<String, Allowed> ALLOWED = Map.of(
        "AbandonedHttpStateIsCollectedTest.java", new Allowed(1,
            "drops an HttpState without stop() on purpose, to show what is left once its event log has stopped"),
        "ClusterPeerClientLifecycleTest.java", new Allowed(1,
            "returned by a helper; each test stops the HttpState it gets from it"),
        "HttpStateFailedConstructionTest.java", new Allowed(1,
            "the constructor throws, so there is nothing to stop"),
        "HttpStateReadinessTest.java", new Allowed(1,
            "constructs on another thread into an AtomicReference, whose HttpState the @After method stops")
    );

    @Test
    public void shouldStopEveryHttpStateACoreTestConstructs() throws IOException {
        assertThat("expected to find src/test/java at " + TEST_ROOT.toAbsolutePath(), Files.isDirectory(TEST_ROOT), is(true));
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(TEST_ROOT)) {
            sources = walk.filter(path -> path.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }

        int constructing = 0;
        Map<String, List<String>> offendersByFile = new TreeMap<>();
        for (Path source : sources) {
            String fileName = source.getFileName().toString();
            if (fileName.equals(HttpStateStoppedGuardTest.class.getSimpleName() + ".java")) {
                continue;
            }
            String content = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            if (!CONSTRUCTION.matcher(content).find()) {
                continue;
            }
            constructing++;
            List<String> offences = offences(content);
            Allowed allowed = ALLOWED.get(fileName);
            long unassigned = offences.stream().filter(offence -> offence.startsWith(UNASSIGNED)).count();
            if (allowed != null && unassigned == allowed.count) {
                offences.removeIf(offence -> offence.startsWith(UNASSIGNED));
            }
            if (!offences.isEmpty()) {
                offendersByFile.put(TEST_ROOT.relativize(source).toString(), offences);
            }
        }

        assertThat("must find the core tests that construct an HttpState", constructing, greaterThan(40));
        assertThat("a core test constructs an HttpState it never stops; the thread of its event log keeps it, and"
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

    private static final String UNASSIGNED = "not assigned to a name: ";

    static List<String> offences(String source) {
        String code = COMMENT.matcher(source).replaceAll("");
        List<String> offences = new ArrayList<>();
        Matcher construction = CONSTRUCTION.matcher(code);
        while (construction.find()) {
            String statement = statementBefore(code, construction.start());
            Matcher assigned = ASSIGNED_TO.matcher(statement);
            if (!assigned.find()) {
                offences.add(UNASSIGNED + (statement.trim() + " new HttpState(").trim());
            } else if (!stopped(code, assigned.group(1))) {
                offences.add(assigned.group(1) + " is never stopped");
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

    private static boolean stopped(String code, String name) {
        String quoted = Pattern.quote(name);
        if (Pattern.compile(RECEIVER + quoted + "\\s*\\.\\s*stop\\s*\\(\\s*\\)").matcher(code).find()) {
            return true;
        }
        Matcher addedTo = Pattern.compile("(\\w+)\\s*\\.\\s*add\\s*\\(\\s*" + quoted + "\\s*\\)").matcher(code);
        while (addedTo.find()) {
            if (Pattern.compile(RECEIVER + Pattern.quote(addedTo.group(1)) + "\\s*\\.\\s*forEach\\s*\\(\\s*HttpState::stop\\s*\\)").matcher(code).find()) {
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
