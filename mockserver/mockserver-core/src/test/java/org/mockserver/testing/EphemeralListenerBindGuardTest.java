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
 * sources, and the main sources of the test-support modules and the benchmarks, each bind of port 0 on every address
 * must be on an IPv4 socket or be listed in {@link #ALLOWED} with a reason: {@code .bind(0)}, {@code .bind(null)},
 * a bootstrap's {@code .localAddress(0)}, Netty's {@code bind("0.0.0.0", 0)}, {@code new ServerSocket(0)},
 * {@code createServerSocket(0)}, {@code new InetSocketAddress(0)} or with a wildcard host ({@code "0.0.0.0"},
 * {@code "::"}, a {@code null} or wildcard {@code InetAddress}), {@code new DatagramSocket(0)},
 * {@code new DatagramSocket()}, Jetty's {@code new Server(0)} and WireMock's {@code dynamicPort()}. The port may be
 * written as {@code 0}, {@code 0x0} or a name holding 0. Bind {@code 127.0.0.1} and connect to {@code 127.0.0.1};
 * take a port for MockServer to bind from {@code PortFactory.findFreePort()}.
 *
 * <h2>Limits</h2>
 * <p>A textual check. A name holds 0 when its nearest earlier assignment in the enclosing method is 0, or it is a
 * {@code final} field of 0; a constant of another class, a field that is not final, an expression, and an address
 * held in a variable are not followed. A bind counts as IPv4 when its statement names an IPv4 family or channel
 * factory, or when it is called on a variable whose nearest earlier assignment in the enclosing method, or as a
 * field, does. "Method" is the outermost block inside a top-level class, so in a nested class it is the whole class.
 * Servers of other libraries than Jetty and WireMock are not seen. {@code .bind(null)} on a socket made IPv4 another
 * way, or one that is never connected to, is still flagged and needs an {@link #ALLOWED} entry.
 */
public class EphemeralListenerBindGuardTest {

    static final Path MODULES_ROOT = Paths.get("..");

    // modules whose main sources are scanned too: test support, and benchmarks that run a client and server together
    private static final Set<String> MAIN_SOURCE_MODULES = Set.of("mockserver-testing", "mockserver-integration-testing", "mockserver-benchmark");

    // a constructor may be written with its package, as in new java.net.ServerSocket(0)
    private static final String QUALIFIER = "(?:\\w++\\.)*+";

    private static final String ZERO = "(?:0[xX]0++|0[bB]0++|0++)(?![\\w.])";

    // a port of 0, or a name that holdsZero decides
    private static final String PORT = "(?:" + ZERO + "|(?!null\\b)(?<port>[A-Za-z_$][\\w$]*+))\\s*";

    private static final String WILDCARD_HOST = "\"(?:0\\.0\\.0\\.0|::|::0|0:0:0:0:0:0:0:0|\\[::])\"";

    private static final String WILDCARD_ADDRESS = "(?:(?:\\(\\s*" + QUALIFIER + "InetAddress\\s*\\)\\s*)?null|"
        + "(?:java\\.net\\.)?InetAddress\\.getByName\\(\\s*" + WILDCARD_HOST + "\\s*\\))\\s*";

    private static final List<Pattern> EPHEMERAL_WILDCARD_BINDS = Stream.of(
        "\\.bind\\(\\s*" + PORT + "\\)",
        "\\.bind\\(\\s*(?:\\(\\s*" + QUALIFIER + "(?:Inet)?SocketAddress\\s*\\)\\s*)?null\\s*(?:,[^,()]*+)?\\)",
        "\\.(?:bind|localAddress)\\(\\s*" + WILDCARD_HOST + "\\s*,\\s*" + PORT + "\\)",
        "\\.localAddress\\(\\s*" + PORT + "\\)",
        "new\\s+" + QUALIFIER + "InetSocketAddress\\(\\s*(?:(?:" + WILDCARD_HOST + "\\s*|" + WILDCARD_ADDRESS + "),\\s*)?" + PORT + "\\)",
        "new\\s+" + QUALIFIER + "DatagramSocket\\(\\s*(?:" + PORT + ")?\\)",
        // a backlog, and a null or wildcard address, still leave the socket on every address
        "(?:new\\s+" + QUALIFIER + "ServerSocket|createServerSocket)\\(\\s*" + PORT + "(?:,[^,()]*+(?:,\\s*" + WILDCARD_ADDRESS + ")?)?\\)",
        "new\\s+" + QUALIFIER + "Server\\(\\s*" + PORT + "\\)",
        "\\.dynamic(?:Https)?Port\\(\\s*\\)"
    ).map(Pattern::compile).collect(Collectors.toList());

    private static final Pattern IPV4 = Pattern.compile("ProtocolFamily\\.INET\\b|\\bIpv4DatagramChannelFactory\\b");

    // a WireMock server bound to loopback; its bindAddress may follow dynamicPort() in the statement
    private static final Pattern LOOPBACK_BIND_ADDRESS = Pattern.compile("\\.bindAddress\\(\\s*\"127\\.0\\.0\\.1\"\\s*\\)");

    private static final Pattern FINAL = Pattern.compile("\\bfinal\\b");

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
            "reads the position of the dual-stack allocator, which is what the test then contests"),
        allowed("mockserver-netty/Http3ConnectUdpHandlerTest.java", 1,
            "binds a socket of the default family to learn that family; nothing connects to it")
    );

    @Test
    public void shouldNotBindADualStackSocketToPortZeroInTestCode() throws IOException {
        List<Path> sources = testSources();
        Set<String> modules = sources.stream().map(EphemeralListenerBindGuardTest::module).collect(Collectors.toCollection(TreeSet::new));
        assertThat("must scan the core, netty, test-support and benchmark sources: " + modules, modules,
            hasItems("mockserver-core", "mockserver-netty", "mockserver-integration-testing", "mockserver-benchmark"));
        assertThat("must scan a representative source tree", sources.size(), greaterThan(1000));

        Map<String, List<String>> offendersByFile = new TreeMap<>();
        int binds = 0;
        for (Path source : sources) {
            String code = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            binds += ephemeralBinds(code, false).size();
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

    @Test
    public void shouldFlagTheOtherSpellingsOfADualStackBindOfPortZero() {
        assertThat(dualStackEphemeralBinds("bootstrap.localAddress(0);\nchannel = bootstrap.bind().sync().channel();"), contains("1 .localAddress(0)"));
        assertThat(dualStackEphemeralBinds("channel = bootstrap.localAddress(\"0.0.0.0\", 0).bind().sync().channel();"), contains("1 .localAddress(\"0.0.0.0\", 0)"));
        assertThat(dualStackEphemeralBinds("channel = bootstrap.bind(\"0.0.0.0\", 0).sync().channel();"), contains("1 .bind(\"0.0.0.0\", 0)"));
        assertThat(dualStackEphemeralBinds("channel = bootstrap.bind(\"::\", 0).sync().channel();"), contains("1 .bind(\"::\", 0)"));
        assertThat(dualStackEphemeralBinds("server.bind(null);"), contains("1 .bind(null)"));
        assertThat(dualStackEphemeralBinds("server.bind((SocketAddress) null, 50);"), contains("1 .bind((SocketAddress) null, 50)"));
        assertThat(dualStackEphemeralBinds("socket.bind(new InetSocketAddress(InetAddress.getByName(\"0.0.0.0\"), 0));"),
            contains("1 new InetSocketAddress(InetAddress.getByName(\"0.0.0.0\"), 0)"));
        assertThat(dualStackEphemeralBinds("socket.bind(new InetSocketAddress(\"::\", 0));"), contains("1 new InetSocketAddress(\"::\", 0)"));
        assertThat(dualStackEphemeralBinds("socket.bind(new InetSocketAddress((InetAddress) null, 0));"), contains("1 new InetSocketAddress((InetAddress) null, 0)"));
        assertThat(dualStackEphemeralBinds("ServerSocket server = new ServerSocket(0x0);"), contains("1 new ServerSocket(0x0)"));
        assertThat(dualStackEphemeralBinds("ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName(\"::\"));"),
            contains("1 new ServerSocket(0, 50, InetAddress.getByName(\"::\"))"));
        assertThat(dualStackEphemeralBinds("int port = 0;\nchannel = bootstrap.bind(port).sync().channel();"), contains("2 .bind(port)"));
        assertThat("a constant", dualStackEphemeralBinds("class A {\n void start() throws IOException {\n  new ServerSocket(PORT);\n }\n private static final int PORT = 0;\n}"),
            contains("3 new ServerSocket(PORT)"));
        assertThat("Jetty", dualStackEphemeralBinds("Server server = new org.eclipse.jetty.server.Server(0);"), contains("1 new org.eclipse.jetty.server.Server(0)"));
        assertThat("WireMock", dualStackEphemeralBinds("WireMockServer server = new WireMockServer(options().dynamicPort());"), contains("1 .dynamicPort()"));
        assertThat("an IPv4 family after the bind is another socket's", dualStackEphemeralBinds("use(new ServerSocket(0), DatagramChannel.open(StandardProtocolFamily.INET));"),
            contains("1 new ServerSocket(0)"));
        assertThat("an IPv4 assignment in another method does not decide", dualStackEphemeralBinds("class A {\n"
                + " void a() throws IOException {\n  DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET);\n }\n"
                + " void b(DatagramChannel channel) throws IOException {\n  channel.bind(new InetSocketAddress(0));\n }\n}"),
            contains("6 new InetSocketAddress(0)"));
    }

    @Test
    public void shouldNotFlagTheOtherSpellingsOnALoopbackOrIpv4SocketOrANonZeroPort() {
        assertThat(dualStackEphemeralBinds("channel = bootstrap.localAddress(\"127.0.0.1\", 0).bind().sync().channel();\nbootstrap.localAddress(port);"), is(empty()));
        assertThat(dualStackEphemeralBinds("Bootstrap bootstrap = new Bootstrap().channelFactory(Ipv4DatagramChannelFactory.INSTANCE);\nbootstrap.localAddress(0);"), is(empty()));
        assertThat(dualStackEphemeralBinds("socket.bind(new InetSocketAddress(InetAddress.getByName(\"::1\"), 0));\nsocket.bind(new InetSocketAddress(address, 0));"), is(empty()));
        assertThat(dualStackEphemeralBinds("ServerSocket server = new ServerSocket(0x10);\nnew MockServer(0);\nserver.bind(address);"), is(empty()));
        assertThat("reassigned before the bind", dualStackEphemeralBinds("int port = 0;\nport = PortFactory.findFreePort();\nnew ServerSocket(port);"), is(empty()));
        assertThat("a field that is not final", dualStackEphemeralBinds("class A {\n private int port = 0;\n void a() {\n  port = PortFactory.findFreePort();\n }\n"
            + " void b() throws IOException {\n  new ServerSocket(port);\n }\n}"), is(empty()));
        assertThat("a zero in another method", dualStackEphemeralBinds("class A {\n void a() {\n  int port = 0;\n }\n"
            + " void b(int port) throws IOException {\n  new ServerSocket(port);\n }\n}"), is(empty()));
        assertThat("an IPv4 field", dualStackEphemeralBinds("class A {\n private final DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET);\n"
            + " void b() throws IOException {\n  channel.bind(new InetSocketAddress(0));\n }\n}"), is(empty()));
        assertThat(dualStackEphemeralBinds("WireMockServer server = new WireMockServer(options().dynamicPort().bindAddress(\"127.0.0.1\"));"), is(empty()));
    }

    /**
     * Each bind of port 0 in {@code source} that is not on an IPv4 socket, as {@code "<line> <matched text>"}.
     */
    static List<String> dualStackEphemeralBinds(String source) {
        return ephemeralBinds(source, true);
    }

    /**
     * Each bind of port 0 on every address in {@code source}, in order, as {@code "<line> <matched text>"}; with
     * {@code dualStackOnly} only those not on an IPv4 socket.
     */
    private static List<String> ephemeralBinds(String source, boolean dualStackOnly) {
        String code = blank(source, false);
        String structure = blank(source, true);
        int[] depth = braceDepths(structure);
        TreeMap<Integer, String> binds = new TreeMap<>();
        for (Pattern spelling : EPHEMERAL_WILDCARD_BINDS) {
            Matcher bind = spelling.matcher(code);
            while (bind.find()) {
                boolean insideLiteral = structure.charAt(bind.start()) != code.charAt(bind.start());
                String port = spelling.pattern().contains("?<port>") ? bind.group("port") : null;
                if (insideLiteral || (port != null && !holdsZero(structure, depth, port, bind.start()))) {
                    continue;
                }
                if (!dualStackOnly || !isIpv4(code, structure, depth, statementStart(structure, bind.start()), bind.start(), bind.end())) {
                    binds.put(bind.start(), lineOf(code, bind.start()) + " " + bind.group());
                }
            }
        }
        return new ArrayList<>(binds.values());
    }

    private static boolean isIpv4(String code, String structure, int[] depth, int statementStart, int bindStart, int bindEnd) {
        Matcher statementEnd = STATEMENT_END.matcher(structure);
        if (IPV4.matcher(code.substring(statementStart, bindEnd)).find()
            || LOOPBACK_BIND_ADDRESS.matcher(code.substring(statementStart, statementEnd.find(bindEnd) ? statementEnd.start() : structure.length())).find()) {
            return true;
        }
        Matcher receiver = RECEIVER.matcher(code.substring(statementStart, bindStart));
        if (!receiver.find()) {
            return false;
        }
        int assignment = nearestAssignment(structure, depth, receiver.group(1), statementStart);
        if (assignment < 0) {
            return false;
        }
        int assigned = structure.indexOf('=', assignment) + 1;
        Matcher end = STATEMENT_END.matcher(structure);
        return IPV4.matcher(code.substring(assigned, end.find(assigned) ? end.start() : structure.length())).find();
    }

    /**
     * Whether {@code name}, written as the port of a bind at {@code offset}, holds 0: its nearest earlier assignment
     * in the enclosing method is 0, or, with none there, it is a {@code final} field of 0.
     */
    private static boolean holdsZero(String structure, int[] depth, String name, int offset) {
        int assignment = nearestAssignment(structure, depth, name, offset);
        if (assignment >= methodStart(structure, depth, offset)) {
            return isZeroAssignment(structure, assignment);
        }
        Matcher field = assignmentOf(name).matcher(structure);
        while (field.find()) {
            if (depth[field.start()] <= 1 && isZeroAssignment(structure, field.start())
                && FINAL.matcher(structure.substring(statementStart(structure, field.start()), field.start())).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isZeroAssignment(String structure, int assignment) {
        Matcher value = Pattern.compile("=\\s*" + ZERO + "\\s*[;,)]").matcher(structure);
        return value.region(structure.indexOf('=', assignment), structure.length()).lookingAt();
    }

    /**
     * The start of the nearest assignment of {@code name} before {@code offset} that is in the enclosing method or
     * at field level, or -1.
     */
    private static int nearestAssignment(String structure, int[] depth, String name, int offset) {
        int methodStart = methodStart(structure, depth, offset);
        Matcher assignment = assignmentOf(name).matcher(structure);
        assignment.region(0, offset);
        int nearest = -1;
        while (assignment.find()) {
            if (assignment.start() >= methodStart || depth[assignment.start()] <= 1) {
                nearest = assignment.start();
            }
        }
        return nearest;
    }

    private static Pattern assignmentOf(String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=(?!=)");
    }

    /**
     * Just after the brace that opens the block at depth 2 holding {@code offset}, the body of a top-level class's
     * method; 0 when {@code offset} is not in one.
     */
    private static int methodStart(String structure, int[] depth, int offset) {
        if (depth[offset] < 2) {
            return 0;
        }
        for (int i = offset - 1; i >= 0; i--) {
            if (structure.charAt(i) == '{' && depth[i] == 1) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * For each offset, how many braces are open before it.
     */
    private static int[] braceDepths(String structure) {
        int[] depth = new int[structure.length() + 1];
        for (int i = 0; i < structure.length(); i++) {
            char c = structure.charAt(i);
            depth[i + 1] = depth[i] + (c == '{' ? 1 : c == '}' ? -1 : 0);
        }
        return depth;
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

    static List<Path> testSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(MODULES_ROOT)) {
            for (Path module : modules.filter(Files::isDirectory).collect(Collectors.toList())) {
                List<Path> roots = new ArrayList<>(List.of(module.resolve(Paths.get("src", "test", "java"))));
                if (MAIN_SOURCE_MODULES.contains(module.getFileName().toString())) {
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
