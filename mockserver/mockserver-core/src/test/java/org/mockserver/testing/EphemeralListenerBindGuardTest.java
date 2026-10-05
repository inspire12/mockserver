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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;

/**
 * Build-time guard: test code must not bind a dual-stack socket to port 0.
 *
 * <h2>Why</h2>
 * <p>On macOS a default (dual-stack) socket bound to port 0, or to {@code 0.0.0.0} port 0, can be given a port
 * another process already listens on at 127.0.0.1; connections a test then makes to {@code 127.0.0.1:port} reach
 * that process. A socket bound to {@code 127.0.0.1}, or one of the IPv4 family, never is. So in every module's test
 * sources, and the main sources of the test-support modules, each {@code .bind(0)}, {@code new ServerSocket(0)},
 * {@code new InetSocketAddress(0)}, {@code new InetSocketAddress("0.0.0.0", 0)}, {@code new DatagramSocket(0)},
 * {@code new DatagramSocket()} and {@code createServerSocket(0)} must be on an IPv4 socket or be listed in
 * {@link #ALLOWED} with a reason. Bind {@code 127.0.0.1} and connect to {@code 127.0.0.1}; take a port for
 * MockServer to bind from {@code PortFactory.findFreePort()}.
 *
 * <h2>Limits</h2>
 * <p>A textual check. A bind counts as IPv4 when its statement names an IPv4 family or channel factory, or when it
 * is called on a variable whose nearest preceding assignment does; that assignment is found by the variable's name
 * anywhere earlier in the file, so one in another method can exempt a dual-stack socket of the same name. A socket
 * made IPv4 any other way needs an {@link #ALLOWED} entry.
 * <p>Spellings of the same bind that are not seen: a port of 0 written as a constant, a variable or {@code 0x0};
 * {@code bind(null)}; a bootstrap's {@code .localAddress(0)} followed by {@code .bind()}; Netty's
 * {@code bind("0.0.0.0", 0)}; {@code new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0)},
 * {@code new InetSocketAddress("::", 0)} and {@code new InetSocketAddress((InetAddress) null, 0)}; and servers of
 * other libraries started on port 0 (Jetty's {@code new Server(0)}, WireMock's {@code dynamicPort()}).
 */
public class EphemeralListenerBindGuardTest {

    static final Path MODULES_ROOT = Paths.get("..");

    private static final Set<String> TEST_SUPPORT_MODULES = Set.of("mockserver-testing", "mockserver-integration-testing");

    // a constructor may be written with its package, as in new java.net.ServerSocket(0)
    private static final String QUALIFIER = "(?:\\w++\\.)*+";

    // a backlog, and a null address, still leave the socket on every address
    private static final String BACKLOG_AND_NULL_ADDRESS = "(?:,[^,()]*(?:,\\s*null\\s*)?)?";

    private static final Pattern EPHEMERAL_WILDCARD_BIND = Pattern.compile(
        "\\.bind\\(\\s*0\\s*\\)"
            + "|new\\s+" + QUALIFIER + "InetSocketAddress\\(\\s*(?:\"0\\.0\\.0\\.0\"\\s*,\\s*)?0\\s*\\)"
            + "|new\\s+" + QUALIFIER + "DatagramSocket\\(\\s*0?\\s*\\)"
            + "|(?:new\\s+" + QUALIFIER + "ServerSocket|createServerSocket)\\(\\s*0\\s*" + BACKLOG_AND_NULL_ADDRESS + "\\)");

    private static final Pattern IPV4 = Pattern.compile("ProtocolFamily\\.INET\\b|\\bIpv4DatagramChannelFactory\\b");

    // the variable a bind is called on: "variable" before .bind(0), "variable.bind(" before new InetSocketAddress(0)
    private static final Pattern RECEIVER = Pattern.compile("(?<![\\w.])(\\w+)\\s*(?:\\.\\s*bind\\(\\s*)?$");

    private static final Pattern STATEMENT_END = Pattern.compile("[;{]");

    /**
     * Dual-stack binds of port 0 that are meant, keyed by {@code module/File.java}, with how many the file has.
     */
    private static final List<Allowed> ALLOWED = List.of(
        allowed("mockserver-netty/MainTest.java", 1,
            "holds a port on every address so MockServer's own bind of it fails; nothing connects to it"),
        allowed("mockserver-netty/AbstractExtendedNettyMockingIntegrationTest.java", 1,
            "holds a port on every address so MockServer's own bind of it fails; nothing connects to it"),
        allowed("mockserver-netty/LoopbackPortConflictIntegrationTest.java", 1,
            "reads the position of the dual-stack allocator, which is what the test then contests")
    );

    @Test
    public void shouldNotBindADualStackSocketToPortZeroInTestCode() throws IOException {
        List<Path> sources = testSources();
        Set<String> modules = sources.stream().map(EphemeralListenerBindGuardTest::module).collect(Collectors.toCollection(TreeSet::new));
        assertThat("must scan the core, netty and test-support sources: " + modules, modules, hasItems("mockserver-core", "mockserver-netty", "mockserver-integration-testing"));
        assertThat("must scan a representative source tree", sources.size(), greaterThan(1000));

        Map<String, List<String>> offendersByFile = new TreeMap<>();
        int binds = 0;
        for (Path source : sources) {
            String code = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            binds += count(EPHEMERAL_WILDCARD_BIND, blank(code, false));
            for (String offender : dualStackEphemeralBinds(code)) {
                offendersByFile.computeIfAbsent(module(source) + "/" + source.getFileName(), key -> new ArrayList<>())
                    .add(MODULES_ROOT.relativize(source) + ":" + offender);
            }
        }
        assertThat("must find the port 0 binds to check", binds, greaterThan(10));

        List<String> problems = new ArrayList<>();
        for (Allowed allowed : ALLOWED) {
            List<String> found = offendersByFile.remove(allowed.file);
            int actual = found == null ? 0 : found.size();
            if (actual != allowed.count) {
                problems.add("ALLOWED expects " + allowed.count + " in " + allowed.file + " (" + allowed.reason + ") but found " + actual
                    + (found == null ? "" : ":\n  " + String.join("\n  ", found)));
            }
        }
        offendersByFile.values().forEach(problems::addAll);
        assertThat("test code binding a dual-stack socket to port 0, which on macOS can be given a port another process "
                + "listens on at 127.0.0.1 - bind 127.0.0.1 (and connect to 127.0.0.1), use an IPv4 channel, take the port "
                + "from PortFactory.findFreePort(), or add a reasoned entry to ALLOWED:\n" + String.join("\n", problems),
            problems, is(empty()));
    }

    @Test
    public void shouldFlagADualStackBindOfPortZero() {
        assertThat(dualStackEphemeralBinds("ServerSocket server = new ServerSocket(0);"), contains("1 new ServerSocket(0)"));
        assertThat(dualStackEphemeralBinds("a();\nServerSocket server = new ServerSocket( 0, 50 );"), contains("2 new ServerSocket( 0, 50 )"));
        assertThat(dualStackEphemeralBinds("return (SSLServerSocket) factory.createServerSocket(0);"), contains("1 createServerSocket(0)"));
        assertThat("written with its package", dualStackEphemeralBinds("return new java.net.ServerSocket(0);"), contains("1 new java.net.ServerSocket(0)"));
        assertThat(dualStackEphemeralBinds("channel.bind(new java.net.InetSocketAddress(0));"), contains("1 new java.net.InetSocketAddress(0)"));
        assertThat("a null address is every address", dualStackEphemeralBinds("ServerSocket server = new ServerSocket(0, 50, null);"), contains("1 new ServerSocket(0, 50, null)"));
        assertThat(dualStackEphemeralBinds("try (DatagramSocket socket = new DatagramSocket(0)) {\n}"), contains("1 new DatagramSocket(0)"));
        assertThat("no argument is port 0", dualStackEphemeralBinds("DatagramSocket socket = new java.net.DatagramSocket();"), contains("1 new java.net.DatagramSocket()"));
        assertThat(dualStackEphemeralBinds("channel = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)\n"
            + ".childHandler(new ChannelInitializer<Channel>() {\n protected void initChannel(Channel ch) {\n ch.pipeline().addLast(handler);\n }\n })\n"
            + ".bind(0).sync().channel();"), contains("7 .bind(0)"));
        assertThat(dualStackEphemeralBinds("channel = bootstrap.bind(new InetSocketAddress(0)).sync().channel();"), contains("1 new InetSocketAddress(0)"));
        assertThat(dualStackEphemeralBinds("socket.bind(new InetSocketAddress(\"0.0.0.0\", 0));"), contains("1 new InetSocketAddress(\"0.0.0.0\", 0)"));
        assertThat(dualStackEphemeralBinds("try (DatagramChannel channel = DatagramChannel.open()) {\n channel.bind(new InetSocketAddress(0));\n}"), contains("2 new InetSocketAddress(0)"));
        assertThat("the nearest assignment decides", dualStackEphemeralBinds("ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.INET);\n"
            + "channel = ServerSocketChannel.open();\nchannel.bind(new InetSocketAddress(0));"), contains("3 new InetSocketAddress(0)"));
    }

    @Test
    public void shouldNotFlagALoopbackOrIpv4Bind() {
        assertThat(dualStackEphemeralBinds("ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName(\"127.0.0.1\"));"), is(empty()));
        assertThat(dualStackEphemeralBinds("channel = bootstrap.bind(new InetSocketAddress(\"127.0.0.1\", 0)).sync().channel();"), is(empty()));
        assertThat(dualStackEphemeralBinds("channel = new Bootstrap().group(group).channelFactory(Ipv4DatagramChannelFactory.INSTANCE)\n"
            + ".handler(new ChannelInitializer<Channel>() {\n protected void initChannel(Channel ch) {\n ch.pipeline().addLast(handler);\n }\n })\n"
            + ".bind(0).sync().channel();"), is(empty()));
        assertThat(dualStackEphemeralBinds("return ServerSocketChannel.open(StandardProtocolFamily.INET).bind(new InetSocketAddress(0));"), is(empty()));
        assertThat(dualStackEphemeralBinds("try (DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET)) {\n channel.bind(new InetSocketAddress(0));\n}"), is(empty()));
        assertThat(dualStackEphemeralBinds("DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET);\nsockets[i] = channel;\n"
            + "channel.bind(new InetSocketAddress(\"0.0.0.0\", 0));"), is(empty()));
        assertThat(dualStackEphemeralBinds("// new ServerSocket(0) was dual-stack\n/* .bind(0) */ String text = \"new ServerSocket(0)\";"), is(empty()));
        assertThat("a text block is one literal", dualStackEphemeralBinds("String json = \"\"\"\n {\"url\": \"http://host\"} \\\" new ServerSocket(0)\n \"\"\";\nServerSocket server = new ServerSocket(0);"),
            contains("4 new ServerSocket(0)"));
        assertThat(dualStackEphemeralBinds("ServerSocket server = new ServerSocket(port);\nchannel.bind(new InetSocketAddress(port));"), is(empty()));
        assertThat(dualStackEphemeralBinds("DatagramSocket socket = new DatagramSocket(port);\nsocket = new DatagramSocket(0, InetAddress.getByName(\"127.0.0.1\"));"), is(empty()));
    }

    /**
     * Each bind of port 0 in {@code source} that is not on an IPv4 socket, as {@code "<line> <matched text>"}.
     */
    static List<String> dualStackEphemeralBinds(String source) {
        String code = blank(source, false);
        String structure = blank(source, true);
        List<String> offenders = new ArrayList<>();
        Matcher bind = EPHEMERAL_WILDCARD_BIND.matcher(code);
        while (bind.find()) {
            boolean insideLiteral = structure.charAt(bind.start()) != code.charAt(bind.start());
            if (!insideLiteral && !isIpv4(code, structure, statementStart(structure, bind.start()), bind.start(), bind.end())) {
                offenders.add(lineOf(code, bind.start()) + " " + bind.group());
            }
        }
        return offenders;
    }

    private static boolean isIpv4(String code, String structure, int statementStart, int bindStart, int bindEnd) {
        if (IPV4.matcher(structure.substring(statementStart, bindEnd)).find()) {
            return true;
        }
        Matcher receiver = RECEIVER.matcher(code.substring(statementStart, bindStart));
        if (!receiver.find()) {
            return false;
        }
        Matcher assignment = Pattern.compile("\\b" + receiver.group(1) + "\\s*=(?!=)").matcher(structure.substring(0, statementStart));
        int assigned = -1;
        while (assignment.find()) {
            assigned = assignment.end();
        }
        if (assigned < 0) {
            return false;
        }
        Matcher end = STATEMENT_END.matcher(structure);
        return IPV4.matcher(structure.substring(assigned, end.find(assigned) ? end.start() : structure.length())).find();
    }

    /**
     * The start of the statement holding {@code offset}: the nearest preceding {@code ;}, <code>{</code> or
     * <code>}</code> that is not inside brackets closed before {@code offset}.
     */
    private static int statementStart(String structure, int offset) {
        int depth = 0;
        for (int i = offset - 1; i >= 0; i--) {
            char c = structure.charAt(i);
            if (depth == 0 && (c == ';' || c == '{' || c == '}')) {
                return i + 1;
            } else if (c == ')' || c == ']' || c == '}') {
                depth++;
            } else if ((c == '(' || c == '[' || c == '{') && depth > 0) {
                depth--;
            }
        }
        return 0;
    }

    private static int count(Pattern pattern, String code) {
        int count = 0;
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    static List<Path> testSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(MODULES_ROOT)) {
            for (Path module : modules.filter(Files::isDirectory).collect(Collectors.toList())) {
                List<Path> roots = new ArrayList<>(List.of(module.resolve(Paths.get("src", "test", "java"))));
                if (TEST_SUPPORT_MODULES.contains(module.getFileName().toString())) {
                    roots.add(module.resolve(Paths.get("src", "main", "java")));
                }
                for (Path root : roots) {
                    if (Files.isDirectory(root)) {
                        try (Stream<Path> files = Files.walk(root)) {
                            files.filter(file -> file.toString().endsWith(".java")).forEach(sources::add);
                        }
                    }
                }
            }
        }
        return sources;
    }

    static String module(Path source) {
        return MODULES_ROOT.relativize(source).getName(0).toString();
    }

    /**
     * {@code source} with its comments, and with {@code literalsToo} the contents of its string, character and
     * text-block literals, replaced by spaces; line breaks and offsets are unchanged.
     */
    static String blank(String source, boolean literalsToo) {
        StringBuilder out = new StringBuilder(source);
        int i = 0;
        while (i < source.length()) {
            int end;
            boolean blank;
            int keep = 0;
            if (source.startsWith("//", i)) {
                end = source.indexOf('\n', i);
                end = end < 0 ? source.length() : end;
                blank = true;
            } else if (source.startsWith("/*", i)) {
                end = source.indexOf("*/", i + 2);
                end = end < 0 ? source.length() : end + 2;
                blank = true;
            } else if (source.startsWith("\"\"\"", i)) {
                end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? source.length() : end + 3;
                blank = literalsToo;
                keep = 3;
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                end = i + 1;
                while (end < source.length() && source.charAt(end) != source.charAt(i)) {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end + 1, source.length());
                blank = literalsToo;
                keep = 1;
            } else {
                i++;
                continue;
            }
            if (blank) {
                for (int j = i + keep; j < end - keep; j++) {
                    if (source.charAt(j) != '\n') {
                        out.setCharAt(j, ' ');
                    }
                }
            }
            i = end;
        }
        return out.toString();
    }

    static int lineOf(String code, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static Allowed allowed(String file, int count, String reason) {
        return new Allowed(file, count, reason);
    }

    private static final class Allowed {
        private final String file;
        private final int count;
        private final String reason;

        private Allowed(String file, int count, String reason) {
            this.file = file;
            this.count = count;
            this.reason = reason;
        }
    }
}
