package org.mockserver.configuration;

import org.junit.Test;
import org.mockserver.serialization.model.ConfigurationDTO;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

/**
 * Layer A of the configuration-reachability guard: a generic, per-call-site bytecode check that no
 * enforcement site reads a configuration value from the static {@link ConfigurationProperties} store
 * in a way that is unreachable from a {@link Configuration} instance.
 *
 * <h2>The defect class this guards against</h2>
 * <p>{@code PUT /mockserver/configuration} calls {@code ConfigurationDTO.applyTo(configuration)},
 * which writes ONLY the {@link Configuration} instance and never the static
 * {@link ConfigurationProperties} store. {@code Configuration.foo()} falls back TO the static store
 * when its own field is unset, so the data flow is strictly one-directional:
 *
 * <pre>system property / env var / property file -&gt; static store -&gt; Configuration instance</pre>
 *
 * <p>Instance -&gt; static is never wired. Therefore any property whose ENFORCEMENT site reads only
 * the static store is silently unreachable from the instance, from the DTO and from the REST API —
 * while still round-tripping perfectly through {@code ConfigurationDTOTest} and appearing fully
 * configurable in the documentation. This has previously affected security controls such as
 * {@code wasmEnabled} and {@code redactSecretsInLog}.
 *
 * <h2>The rule</h2>
 * <p>Every static-store read {@code ConfigurationProperties.x()} of a name {@code x} that is ALSO a
 * {@link ConfigurationDTO} property must sit in a method that ALSO contains an instance read
 * {@code configuration.x()} of the same name — i.e. the sanctioned fallback:
 *
 * <pre>configuration != null ? configuration.x() : ConfigurationProperties.x()</pre>
 *
 * <p>...unless that {@code Class#method} appears in {@link #ALLOWED_STATIC_ONLY_CALL_SITES} with a
 * mandatory reason string.
 *
 * <h2>Why per-call-site, and why descriptor-qualified</h2>
 * <p>Granularity is load-bearing in two directions:
 * <ul>
 *   <li>A per-PROPERTY rule (does this property have a fallback anywhere?) would miss
 *       {@code wasmEnabled} and {@code sloTrackingEnabled}, whose instance reads live in
 *       {@code HttpState} while enforcement happened statically elsewhere.</li>
 *   <li>Keying by bare method NAME lets overloads sanction each other: an overload containing a
 *       correct fallback would mask a sibling overload that reads only the static store. Keying by
 *       name + descriptor found two real violations that a name-only key hid
 *       ({@code MockServerLogger#writeToSystemOut} and {@code WasmRuntime#<init>}).</li>
 * </ul>
 * Detection is therefore descriptor-qualified; the allowlist is keyed by {@code Class#method}
 * (covering all overloads) so it stays readable and does not churn on signature changes.
 *
 * <h2>Where this guard runs, and why</h2>
 * <p>The guard scans compiled {@code .class} output in the {@code target/classes} of every module directory
 * under {@code mockserver/} (except {@link #UNSHIPPED_MODULES_EXCLUDED_FROM_SCAN}).
 * That makes its coverage a function of what has been BUILT: a module that has not yet been compiled is
 * silently invisible and the scan passes having proven nothing about it. Because
 * {@code mockserver-junit-rule}, {@code mockserver-junit-jupiter} and every other module downstream of
 * {@code mockserver-netty} in the reactor are not yet compiled during netty's OWN test phase (and under
 * {@code -T} the schedule is by dependency graph, not list order), running this in netty's test phase
 * would quietly scan only netty + its upstreams — exactly the reactor-order-dependent scope this guard
 * now refuses to accept.
 *
 * <p>So this test does NOT run in netty's default test phase (it is excluded there in
 * {@code mockserver-netty/pom.xml}). It runs as a standalone, fail-closed verification over the
 * FULLY-built reactor, via the dedicated surefire execution {@code configuration-callsite-guard},
 * invoked after {@code clean install} in {@code scripts/buildkite_quick_build.sh}:
 *
 * <pre>./mvnw -pl mockserver-netty surefire:test@configuration-callsite-guard</pre>
 *
 * At that point every module's {@code target/classes} exists on disk regardless of build parallelism.
 * {@link #expectedScannedModules()} asserts every module that SHOULD be covered actually was, so an
 * incomplete tree fails loudly instead of narrowing the guard's scope in silence.
 *
 * <p>{@code mockserver-maven-plugin} ships but is outside the reactor, so the quick build never builds
 * it. {@code .buildkite/scripts/steps/maven-plugin-build.sh} runs the guard again after the plugin's
 * {@code verify}, naming it in {@link #EXTRA_EXPECTED_MODULES_PROPERTY} so a missing plugin build fails.
 *
 * <h2>Maintenance</h2>
 * <p>This guard needs zero per-property and zero per-module maintenance: it discovers the property set,
 * the call sites AND the set of modules to cover automatically. If it fails on a new call site, the
 * correct first response is to make the site consult the {@link Configuration} instance — NOT to add an
 * allowlist entry. Allowlist only genuinely instance-unreachable bootstrap code, and say why.
 *
 * <p>The same scan also enforces the snapshot rule: control-plane authentication and server TLS values are
 * read through {@link ControlPlaneAuthenticationSettings} and {@link ServerTlsSettings}, never field by field
 * (see {@link #shouldReadSnapshottedSettingsOnlyThroughTheirSnapshots()}).
 *
 * <p>Both rules match a read by the class the call is compiled against, which is the receiver's static type.
 * {@link ConfigurationOwners} therefore counts every scanned subclass of the two configuration classes as an
 * owner, and the scan fails on a subclass not in {@link #ALLOWED_CONFIGURATION_SUBCLASSES}, on a subclass that
 * redeclares one of their methods, on either class gaining a supertype other than {@code Object}, and on either
 * class or any subclass implementing an interface (a read through a supertype would name it as the owner). Each allow-list also fails on an entry whose site no longer
 * makes the read it permits.
 */
public class ConfigurationCallSiteGuardTest {

    private static final String CONFIGURATION_PROPERTIES = "org/mockserver/configuration/ConfigurationProperties";
    private static final String CONFIGURATION = "org/mockserver/configuration/Configuration";

    /**
     * Comma-separated non-reactor modules the run must also have scanned, e.g. {@code
     * -Dguard.extraExpectedModules=mockserver-maven-plugin}. Surefire passes Maven user properties to
     * the test JVM.
     */
    private static final String EXTRA_EXPECTED_MODULES_PROPERTY = "guard.extraExpectedModules";

    /**
     * Reactor modules that appear in {@code mockserver/pom.xml}'s {@code <modules>} list but which the
     * coverage check must NOT require to have been scanned, each with a mandatory reason. This is the
     * ONLY sanctioned way for an expected module to be absent from the scanned set — it exists so that
     * "a module was not scanned" is always either a deliberate, reasoned exemption here or a loud
     * failure, never silence.
     *
     * <p>It is deliberately empty: every module carrying {@code src/main/java} is derived automatically
     * (see {@link #expectedScannedModules()}), and modules with no main sources — the {@code
     * *-no-dependencies} shade artifacts and {@code mockserver-bom} — are already excluded there because
     * they produce no scannable {@code target/classes}. Add an entry only for a module that genuinely
     * produces main classes yet cannot be present when the guard runs, and say why.
     */
    private static final Map<String, String> MODULES_WITHOUT_SCANNABLE_MAIN_CLASSES = new TreeMap<>();

    /**
     * Directories under {@code mockserver/} whose {@code target/classes} is never scanned, each with a
     * mandatory reason. CI never builds them before the guard runs, so scanning them only when a developer
     * happens to have built one would make the verdict depend on local state.
     * {@link #shouldExemptOnlyUnshippedNonReactorModulesFromScan()} asserts each entry is outside the reactor
     * (so no release build includes it), sets {@code maven.deploy.skip} in its project-level properties, and
     * is named by no release or image-build path ({@link #RELEASE_PATHS}).
     */
    private static final Map<String, String> UNSHIPPED_MODULES_EXCLUDED_FROM_SCAN = new TreeMap<>();

    static {
        UNSHIPPED_MODULES_EXCLUDED_FROM_SCAN.put("mockserver-benchmark",
            "on-demand JMH module outside the reactor and not part of any release; its benchmarks deliberately read the "
                + "static store (e.g. PerRequestConfigResolutionBenchmark measures ConfigurationProperties."
                + "maxLoggedBodyBytes()) and are not server enforcement sites");
    }

    /**
     * Classes that DEFINE the static-store fallback rather than enforcing a value. Their getters are
     * the {@code field != null ? field : ConfigurationProperties.x()} implementation itself, so a
     * static read inside them is the mechanism under test, not a violation of it.
     */
    private static final Set<String> FALLBACK_DEFINITION_CLASSES = new HashSet<>(java.util.Arrays.asList(
        "org.mockserver.configuration.Configuration",
        "org.mockserver.configuration.ConfigurationProperties",
        // ClientConfiguration is the client-side analogue of Configuration: its getters are
        // themselves the sanctioned fallback into the static store, exactly as Configuration's are.
        "org.mockserver.configuration.ClientConfiguration"
    ));

    /**
     * Call sites permitted to read the static store WITHOUT an instance fallback, each with a
     * mandatory reason. Add an entry ONLY when no {@link Configuration} instance can exist at that
     * point (bootstrap, static initialisation, CLI argument parsing before a server is built).
     *
     * <p>If a site merely does not HAVE a Configuration to hand but could be given one, that is an
     * instance-unreachable bug and belongs in a fix, not here.
     */
    private static final Map<String, String> ALLOWED_STATIC_ONLY_CALL_SITES = new TreeMap<>();

    static {
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.cli.Main#main",
            "CLI entry point: reads disableSystemOut to configure logging before any Configuration instance is constructed");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.cli.Main#lambda$main$0",
            "CLI argument-parse error handler on the Main#main bootstrap path, before any Configuration exists");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.cli.Main#startServer",
            "CLI bootstrap: reads logLevel while building the server, before the Configuration instance is available");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.cli.Main$RunCommand#run",
            "picocli command body on the CLI bootstrap path, before any Configuration instance is constructed");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.logging.MockServerLogger#configureLogger",
            "static logging bootstrap invoked from ConfigurationProperties itself; runs before and independently of any Configuration instance");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.logging.MockServerLogger#isEnabled",
            "static overload with no instance in scope; the instance-aware equivalent is the differently-named "
                + "isEnabledForInstance(Level), which consults the Configuration field and is what logEvent uses");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.logging.MockServerLogger#writeToSystemOut",
            "legacy 2-arg overload retained for source compatibility; superseded by "
                + "writeToSystemOut(Logger, LogEntry, Configuration), which logEvent calls whenever a Configuration is held");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.wasm.WasmRuntime#<init>",
            "legacy single-arg constructor retained for source compatibility; superseded by "
                + "WasmRuntime(byte[], Configuration), which prefers the instance value");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.closurecallback.websocketregistry.LocalCallbackRegistry#<clinit>",
            "static class-initializer default for a wholly static registry; the live instance value is pushed in "
                + "by the HttpState constructor via setMaxWebSocketExpectations(configuration.maxWebSocketExpectations())");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.socket.tls.KeyAndCertificateFactory#writeCertificateAuthorityToDisk",
            "interface default method with no instance state in scope; the sole in-tree implementation "
                + "BCKeyAndCertificateFactory overrides it to use its Configuration field, and custom factory "
                + "suppliers are handed the Configuration too, so this body is unreachable in the shipped server");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.mock.audit.AuditStore#maxFromConfig",
            "static class-initializer default for the audit singleton; capacity is a volatile field, not final, "
                + "and HttpState.applyConfigurationUpdate pushes configuration.controlPlaneAuditMaxEntries() into "
                + "AuditStore.setMaxSize(int) on every PUT /mockserver/configuration, so the instance value does "
                + "take effect");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.testing.integration.mock.AbstractBasicMockingIntegrationTest#shouldRetrieveRecordedLogMessages",
            "mockserver-integration-testing ships test-support code in src/main so downstream suites can reuse it; "
                + "this is an assertion in a test body, not a server enforcement site");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.client.MockServerClient#stop",
            "client code: MockServerClient holds only a ClientConfiguration, never a server Configuration, so no "
                + "Configuration instance can exist here. stopDrainMillis is a server property; the client reads it "
                + "best-effort from its OWN JVM's static store to size how long to wait for a remote shutdown, and it "
                + "is inherently unreachable from a server-side PUT /mockserver/configuration");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.client.MockServerClient#lambda$stop$3",
            "client code: the ClientStop thread body inside MockServerClient.stop(boolean). MockServerClient holds "
                + "only a ClientConfiguration, never a server Configuration, so no Configuration instance can exist "
                + "here. stopDrainMillis is a server property read best-effort from the client's OWN JVM to size the "
                + "stop-wait deadline, and is inherently unreachable from a server-side PUT /mockserver/configuration");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.junit.MockServerRule#applyDevModeDefault",
            "JUnit-4 rule test-harness bootstrap that runs BEFORE ClientAndServer.startClientAndServer(...) builds "
                + "any server, so no Configuration instance exists yet. It deliberately SEEDS the JVM-global static "
                + "store (ConfigurationProperties.devMode(true)) so a later-constructed ClientAndServer inherits the "
                + "dev-mode store sizes, then reads devMode/maxLogEntries/maxExpectations back from that same static "
                + "store ONLY to log the effective sizes it just seeded. These are process-global defaults read at "
                + "server-construction/store-sizing time, not per-instance server settings, so they are inherently "
                + "unreachable from a running server's PUT /mockserver/configuration and consulting a Configuration "
                + "instance here would be wrong (there is none, and the value being set is global by design)");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.junit.jupiter.MockServerExtension#applyDevModeDefault",
            "JUnit-5 extension analogue of MockServerRule#applyDevModeDefault: identical bootstrap that runs before "
                + "any server/Configuration exists, deliberately seeds the JVM-global dev-mode store sizes into the "
                + "static store, then reads devMode/maxLogEntries/maxExpectations back solely to log them. Global "
                + "pre-construction defaults, inherently unreachable from a server-side PUT /mockserver/configuration");
    }

    /**
     * Main classes permitted to extend {@link Configuration} or {@link ConfigurationProperties}, by dotted name,
     * each with a mandatory reason. Empty: none exists. Reads through a listed subclass are still checked like
     * reads through the class it extends, and a listed subclass may not redeclare any of that class's methods or
     * implement an interface (a read through the interface would name it as the owner).
     */
    private static final Map<String, String> ALLOWED_CONFIGURATION_SUBCLASSES = new TreeMap<>();

    /**
     * Instance-unreachable defects that exist TODAY and are not yet fixed. This is a ratchet, NOT an
     * excuse list: each entry is a real bug where a value settable over
     * {@code PUT /mockserver/configuration} is silently ignored.
     *
     * <p>The guard asserts this set is EXACTLY the set of currently-violating non-allowlisted sites, in
     * both directions:
     * <ul>
     *   <li>a NEW violation is not covered here, so it fails the build immediately;</li>
     *   <li>FIXING one of these makes its entry stale, which also fails the build — forcing the entry to
     *       be deleted so the defect can never silently regress afterwards.</li>
     * </ul>
     * The correct way to discharge an entry is to fix the call site and delete the line.
     *
     * <p><b>This map is now EMPTY, and must stay that way.</b> It previously carried 20 enforcement
     * sites created by one event: 27 properties that existed ONLY on {@link ConfigurationProperties} —
     * with no {@link Configuration} accessor and no {@link ConfigurationDTO} field — were wired through
     * to the instance/DTO/REST routes, which brought them into {@link #restReachableProperties()} for
     * the first time and so exposed their long-standing static-only enforcement sites to this guard.
     * All 20 have since been fixed to consult the {@link Configuration} instance, and their entries
     * deleted, so the guard now enforces them directly.
     *
     * <p>Do NOT add entries for new work. A newly-detected violation should be FIXED by making the site
     * consult the {@link Configuration} instance; if the site genuinely cannot reach one (bootstrap,
     * static initialisation), it belongs in {@link #ALLOWED_STATIC_ONLY_CALL_SITES} with a real reason.
     */
    private static final Map<String, String> KNOWN_INSTANCE_UNREACHABLE_DEFECTS = new TreeMap<>();

    /** The main bytecode of every scanned module, read once, with the call owners derived from it. */
    private static final class BuiltTree {
        final List<byte[]> classes = new ArrayList<>();
        ConfigurationOwners owners;
    }

    /**
     * Reads every scanned module's classes. Both scanning tests go through here, so neither can pass having
     * scanned part of the tree, with a supertype above either configuration class, or with an unlisted or
     * method-redeclaring subclass of either in main code.
     */
    private static BuiltTree scanBuiltTree() throws IOException {
        List<Path> moduleClassRoots = moduleClassRoots();
        assertEveryExpectedModuleWasScanned(moduleClassRoots);
        BuiltTree tree = new BuiltTree();
        for (Path root : moduleClassRoots) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path classFile : files.filter(f -> f.toString().endsWith(".class")).collect(Collectors.toList())) {
                    tree.classes.add(Files.readAllBytes(classFile));
                }
            }
        }
        tree.owners = ConfigurationOwners.of(tree.classes);
        List<String> ownerSupertypeViolations = tree.owners.ownerSupertypeViolations();
        assertThat("Configuration and ConfigurationProperties must extend only Object, and neither they nor any subclass "
                + "of them may implement an interface: a read through a receiver typed as a supertype names that "
                + "supertype as its owner, so neither scanning test would see it. Extend ConfigurationOwners to treat "
                + "the supertypes as owners before relaxing this: " + ownerSupertypeViolations,
            ownerSupertypeViolations, is(empty()));
        Set<String> unlistedSubclasses = tree.owners.subclasses();
        unlistedSubclasses.removeAll(ALLOWED_CONFIGURATION_SUBCLASSES.keySet());
        assertThat("these main classes extend Configuration or ConfigurationProperties. A subclass can override a "
                + "getter or the snapshot publication, so enforcement code handed one no longer reads what a PUT "
                + "/mockserver/configuration wrote. Compose a Configuration instead; only add to "
                + "ALLOWED_CONFIGURATION_SUBCLASSES, with a reason, a subclass that redeclares no method and "
                + "implements no interface: "
                + unlistedSubclasses,
            unlistedSubclasses, is(empty()));
        assertThat("these ALLOWED_CONFIGURATION_SUBCLASSES entries redeclare a method of the class they extend (a "
                + "getter, setter, applyAtomically or a snapshot accessor), so code handed one no longer reads what a "
                + "PUT /mockserver/configuration wrote. Compose a Configuration instead: " + tree.owners.redeclared,
            tree.owners.redeclared.keySet(), is(empty()));
        Set<String> staleSubclasses = new TreeSet<>(ALLOWED_CONFIGURATION_SUBCLASSES.keySet());
        staleSubclasses.removeAll(tree.owners.subclasses());
        assertThat("these ALLOWED_CONFIGURATION_SUBCLASSES entries name no scanned subclass — delete them: "
            + staleSubclasses, staleSubclasses, is(empty()));
        return tree;
    }

    private static void assertEveryExpectedModuleWasScanned(List<Path> moduleClassRoots) throws IOException {
        // sanity: the guard must be scanning real, representative bytecode so it cannot pass vacuously
        assertThat("guard must scan compiled module output — run this over a fully-built reactor",
            moduleClassRoots.size(), greaterThan(1));
        Set<String> scannedModules = new TreeSet<>();
        for (Path root : moduleClassRoots) {
            try (Stream<Path> files = Files.walk(root)) {
                if (files.anyMatch(f -> f.toString().endsWith(".class"))) {
                    scannedModules.add(root.getParent().getParent().getFileName().toString());
                }
            }
        }

        // COVERAGE SELF-VERIFICATION (see the "Where this guard runs, and why" javadoc).
        //
        // The guard scans compiled .class output, so a module it should cover but that has not been
        // built yet is silently invisible: the scan simply finds nothing there and passes having
        // proven nothing about that module. That is exactly how this guard was scoped by Maven reactor
        // ORDER rather than by intent — every module downstream of mockserver-netty in the reactor is
        // uncompiled at netty's own test phase, so running here would quietly skip all of them.
        //
        // Defeat that by deriving the set of modules the guard is SUPPOSED to cover from an
        // authoritative source — the reactor's own <modules> list, restricted to modules that produce
        // main classes — and asserting every one of them was actually scanned. A module that is
        // expected but absent from the build output is a LOUD failure here, never silence. If a module
        // legitimately must be skipped it has to earn an explicit, reasoned exemption in
        // MODULES_WITHOUT_SCANNABLE_MAIN_CLASSES, not disappear quietly.
        Set<String> expectedModules = expectedScannedModules();
        assertThat("could not derive the expected module set from the reactor pom — a parse failure here would "
                + "let the coverage check pass vacuously", expectedModules.size(), greaterThan(10));
        Set<String> missingModules = new TreeSet<>(expectedModules);
        missingModules.removeAll(scannedModules);
        assertThat("these reactor modules declare main sources but no .class file was found under their "
                + "<module>/target/classes, "
                + "so the guard could not scan them and its coverage is INCOMPLETE. This is the defect the guard "
                + "guards against in itself: a scan whose scope is set by what happens to be built, not by intent. "
                + "It almost always means the guard ran before these modules were compiled — e.g. in "
                + "mockserver-netty's own test phase, where every module downstream of netty in the reactor is not "
                + "yet built. Run it over a FULLY-built reactor (the post-install `surefire:test@configuration-"
                + "callsite-guard` step), or add a justified exemption to MODULES_WITHOUT_SCANNABLE_MAIN_CLASSES. "
                + "A module named in -D" + EXTRA_EXPECTED_MODULES_PROPERTY + " must have been built (and not be in "
                + "UNSHIPPED_MODULES_EXCLUDED_FROM_SCAN). Missing: "
                + missingModules,
            missingModules, is(empty()));
        // core and netty are the load-bearing minimum; keep an explicit tripwire in case the derivation
        // above ever regresses to an over-permissive empty/near-empty expected set.
        assertThat("mockserver-core must be scanned", scannedModules, hasItem("mockserver-core"));
        assertThat("mockserver-netty must be scanned", scannedModules, hasItem("mockserver-netty"));
    }

    @Test
    public void shouldNotReadStaticConfigurationStoreWithoutInstanceFallback() throws Exception {
        Set<String> restReachableProperties = restReachableProperties();
        assertThat("guard must cover a large property set", restReachableProperties.size(), greaterThan(100));

        BuiltTree tree = scanBuiltTree();
        CallSiteIndex index = new CallSiteIndex();
        for (byte[] classBytes : tree.classes) {
            index.scan(classBytes, tree.owners);
        }

        assertThat("guard must find configuration call sites — a scan that indexes nothing would pass vacuously",
            index.staticReads.size(), greaterThan(10));

        List<String> violations = new ArrayList<>();
        Set<String> observedKnownDefects = new TreeSet<>();
        Set<String> observedAllowedSites = new TreeSet<>();
        for (Map.Entry<String, Set<String>> entry : index.staticReads.entrySet()) {
            String descriptorQualifiedMethod = entry.getKey();
            String allowlistKey = descriptorQualifiedMethod.substring(0, descriptorQualifiedMethod.indexOf('('));
            Set<String> instanceReadsInSameMethod =
                index.instanceReads.getOrDefault(descriptorQualifiedMethod, java.util.Collections.emptySet());
            List<String> unreachable = entry.getValue().stream()
                .filter(restReachableProperties::contains)
                .filter(name -> !instanceReadsInSameMethod.contains(name))
                .sorted()
                .collect(Collectors.toList());
            if (unreachable.isEmpty()) {
                continue;
            }
            if (ALLOWED_STATIC_ONLY_CALL_SITES.containsKey(allowlistKey)) {
                observedAllowedSites.add(allowlistKey);
                continue;
            }
            if (KNOWN_INSTANCE_UNREACHABLE_DEFECTS.containsKey(allowlistKey)) {
                observedKnownDefects.add(allowlistKey);
                continue;
            }
            violations.add(descriptorQualifiedMethod + " reads only the static store for " + unreachable);
        }

        // ratchet: a known defect that no longer violates has been FIXED — delete its entry so the fix
        // is locked in and can never silently regress
        Set<String> staleKnownDefects = new TreeSet<>(KNOWN_INSTANCE_UNREACHABLE_DEFECTS.keySet());
        staleKnownDefects.removeAll(observedKnownDefects);
        assertThat("these call sites are recorded in KNOWN_INSTANCE_UNREACHABLE_DEFECTS but no longer read the "
                + "static store without an instance fallback — they appear to have been FIXED. Delete their "
                + "entries so the guard starts enforcing them: " + staleKnownDefects,
            staleKnownDefects, is(empty()));

        Set<String> staleAllowedSites = new TreeSet<>(ALLOWED_STATIC_ONLY_CALL_SITES.keySet());
        staleAllowedSites.removeAll(observedAllowedSites);
        assertThat("these ALLOWED_STATIC_ONLY_CALL_SITES entries name a method that no longer exists or no longer "
                + "reads the static store without an instance fallback, so each is a standing licence for whatever "
                + "next takes that name. Delete them, or re-key them to the new site: " + staleAllowedSites,
            staleAllowedSites, is(empty()));

        assertThat("Configuration values read from the static ConfigurationProperties store with no "
                + "instance fallback — these are UNREACHABLE from PUT /mockserver/configuration even though "
                + "they round-trip through ConfigurationDTO. Fix by consulting the Configuration instance "
                + "(configuration != null ? configuration.x() : ConfigurationProperties.x()); only add to "
                + "ALLOWED_STATIC_ONLY_CALL_SITES, with a reason, if no Configuration instance can exist "
                + "at that point:\n  " + String.join("\n  ", violations) + "\n",
            violations, is(empty()));
    }

    /**
     * Snapshot rule: a value held by {@link ControlPlaneAuthenticationSettings} or {@link ServerTlsSettings}
     * must be read from the snapshot, never through its {@link Configuration} getter or the static store.
     * A {@code PUT} writes the fields one at a time and republishes the snapshots once at the end, so a
     * field-by-field read can see a mix of old and new values (mTLS switched off before JWT is switched on
     * reads as "no authentication required"). The guarded getters are derived from the snapshot classes,
     * so a newly snapshotted field is covered without editing this test.
     */
    @Test
    public void shouldReadSnapshottedSettingsOnlyThroughTheirSnapshots() throws Exception {
        Set<String> guardedGetters = snapshottedGetters(SNAPSHOT_CLASSES);
        BuiltTree tree = scanBuiltTree();

        Map<String, Set<String>> reads = new TreeMap<>();
        for (byte[] classBytes : tree.classes) {
            reads.putAll(directSnapshottedReads(classBytes, guardedGetters, tree.owners));
        }
        assertThat("guard must find direct reads of snapshotted getters (the definition classes alone have dozens) — "
            + "a scan that indexes nothing would pass vacuously", reads.size(), greaterThan(10));

        SnapshotVerdict verdict = judgeSnapshottedReads(reads, ALLOWED_DIRECT_SNAPSHOTTED_READS, SNAPSHOT_DEFINITION_CLASSES.keySet());

        assertThat("these methods read a control-plane authentication or server TLS value through a Configuration "
                + "getter or the static store instead of its snapshot, so a multi-field PUT /mockserver/configuration "
                + "can be seen half-applied. Read ControlPlaneAuthenticationSettings.of(configuration) or "
                + "ServerTlsSettings.of(configuration) once and decide from it; only add to "
                + "ALLOWED_DIRECT_SNAPSHOTTED_READS, with a reason, a site whose decision cannot weaken "
                + "authentication or TLS:\n  " + String.join("\n  ", verdict.violations) + "\n",
            verdict.violations, is(empty()));
        assertThat("these ALLOWED_DIRECT_SNAPSHOTTED_READS or SNAPSHOT_DEFINITION_CLASSES entries permit a read that "
                + "no longer happens: the site is gone or renamed, or it stopped reading the getter. A stale entry "
                + "is a standing licence for whatever next takes that name, so delete it, trim its getters, or re-key "
                + "it to the new site:\n  "
                + String.join("\n  ", verdict.stale) + "\n",
            verdict.stale, is(empty()));
        assertThat("these ALLOWED_DIRECT_SNAPSHOTTED_READS entries are keyed by Class#method but cover more than one "
                + "overload that reads a snapshotted getter, so one overload's reason licenses the other. Key each by "
                + "its descriptor, as directSnapshottedReads reports it:\n  "
                + String.join("\n  ", verdict.ambiguous) + "\n",
            verdict.ambiguous, is(empty()));
    }

    @Test
    public void shouldGuardEveryValueTheSnapshotsExpose() {
        Set<String> guardedGetters = snapshottedGetters(SNAPSHOT_CLASSES);
        for (Class<?> snapshotClass : SNAPSHOT_CLASSES) {
            for (Method method : publicNoArgGetters(snapshotClass)) {
                if (!SNAPSHOT_DERIVED_ACCESSORS.containsKey(method.getName())) {
                    assertThat(snapshotClass.getSimpleName() + "." + method.getName() + "() has no Configuration getter "
                            + "of the same name, so direct reads of the value cannot be guarded. Name it after the "
                            + "Configuration getter, or list it in SNAPSHOT_DERIVED_ACCESSORS if it is derived",
                        guardedGetters, hasItem(method.getName()));
                }
            }
        }
        for (String name : java.util.Arrays.asList("controlPlaneTLSMutualAuthenticationRequired", "controlPlaneJWTAuthenticationRequired",
            "controlPlaneOidcAuthenticationRequired", "controlPlaneAuthorizationEnabled", "controlPlaneScopeMapping",
            "tlsMutualAuthenticationRequired", "tlsMutualAuthenticationCertificateChain", "tlsProtocols", "x509CertificatePath")) {
            assertThat(guardedGetters, hasItem(name));
        }

        for (Map.Entry<String, AllowedRead> allowed : ALLOWED_DIRECT_SNAPSHOTTED_READS.entrySet()) {
            assertThat(allowed.getKey() + " needs a reason", allowed.getValue().reason.trim().isEmpty(), is(false));
            for (String getter : allowed.getValue().getters) {
                assertThat(allowed.getKey() + " permits " + getter + ", which is not a snapshotted getter",
                    guardedGetters, hasItem(getter));
            }
        }

        Set<String> extended = snapshottedGetters(java.util.Arrays.asList(ServerTlsSettings.class, ExtendedSnapshotFixture.class));
        assertThat("a getter added to a snapshot class must join the guarded set without editing this test",
            extended, hasItem("maxExpectations"));
        assertThat(extended.contains("notAConfigurationGetter"), is(false));
    }

    @Test
    public void shouldDetectEveryFormOfDirectSnapshottedRead() throws IOException {
        Map<String, Set<String>> byMethod = readsByMethodName(DirectReadFixture.class,
            ConfigurationOwners.of(java.util.Collections.emptyList()));
        assertThat(byMethod.get("viaGetter"), is(new TreeSet<>(java.util.Collections.singleton("tlsMutualAuthenticationRequired"))));
        assertThat(byMethod.get("viaMethodReference"), is(new TreeSet<>(java.util.Collections.singleton("controlPlaneJWTAuthenticationRequired"))));
        assertThat(byMethod.get("viaStaticStore"), is(new TreeSet<>(java.util.Collections.singleton("controlPlaneOidcIssuer"))));
        assertThat("a read through the snapshot is the sanctioned form", byMethod.containsKey("viaSnapshot"), is(false));
        assertThat("an unguarded getter is not a snapshotted read", byMethod.containsKey("viaUnguardedGetter"), is(false));
    }

    /**
     * javac names the receiver's static type as the owner of a call, so a read through a subclass of
     * {@link Configuration} or {@link ConfigurationProperties} does not mention either class.
     */
    @Test
    public void shouldDetectReadsThroughSubclassesOfTheConfigurationClasses() throws IOException {
        ConfigurationOwners owners = ConfigurationOwners.of(java.util.Arrays.asList(classBytes(SubclassedConfigurationFixture.class),
            classBytes(TwiceSubclassedConfigurationFixture.class), classBytes(SubclassedStoreFixture.class),
            classBytes(SubclassReadFixture.class)));
        assertThat(owners.subclasses(), is(new TreeSet<>(java.util.Arrays.asList(SubclassedConfigurationFixture.class.getName(),
            TwiceSubclassedConfigurationFixture.class.getName(), SubclassedStoreFixture.class.getName()))));

        Map<String, Set<String>> byMethod = readsByMethodName(SubclassReadFixture.class, owners);
        assertThat(byMethod.get("viaSubclassGetter"), is(new TreeSet<>(java.util.Collections.singleton("tlsMutualAuthenticationRequired"))));
        assertThat(byMethod.get("viaSubclassMethodReference"), is(new TreeSet<>(java.util.Collections.singleton("controlPlaneJWTAuthenticationRequired"))));
        assertThat(byMethod.get("viaSubclassStaticStore"), is(new TreeSet<>(java.util.Collections.singleton("controlPlaneOidcIssuer"))));
        Map<String, Set<String>> byExactOwner = readsByMethodName(SubclassReadFixture.class, ConfigurationOwners.of(java.util.Collections.emptyList()));
        assertThat("the fixture must compile to calls owned by the subclasses, or it proves nothing",
            byExactOwner.containsKey("viaSubclassGetter") || byExactOwner.containsKey("viaSubclassStaticStore"), is(false));

        CallSiteIndex index = new CallSiteIndex();
        index.scan(classBytes(SubclassReadFixture.class), owners);
        String fixture = SubclassReadFixture.class.getName();
        assertThat(index.staticReads.get(fixture + "#viaSubclassStaticStore()Ljava/lang/String;"),
            is(new TreeSet<>(java.util.Collections.singleton("controlPlaneOidcIssuer"))));
        assertThat(index.instanceReads.get(fixture + "#viaSubclassGetter(L" + Type.getInternalName(TwiceSubclassedConfigurationFixture.class) + ";)Z"),
            is(new TreeSet<>(java.util.Collections.singleton("tlsMutualAuthenticationRequired"))));
    }

    /**
     * A read through an interface or superclass of a configuration class names that supertype as its owner, which
     * no owner set matches, so the scan requires both classes and their subclasses to have none beyond {@code Object}
     * and the subclass chain; a subclass may not redeclare their methods.
     */
    @Test
    public void shouldRejectSupertypesOfTheConfigurationClassesAndRedeclaringSubclasses() throws IOException {
        ConfigurationOwners real = ConfigurationOwners.of(java.util.Arrays.asList(classBytes(Configuration.class),
            classBytes(ConfigurationProperties.class)));
        assertThat(real.ownerSupertypeViolations(), is(empty()));

        String implementing = Type.getInternalName(InterfaceImplementingConfigurationFixture.class);
        String view = Type.getInternalName(TlsViewFixture.class);
        ConfigurationOwners withInterface = ConfigurationOwners.of(java.util.Arrays.asList(
            classBytes(InterfaceImplementingConfigurationFixture.class), classBytes(ConfigurationProperties.class)), implementing, CONFIGURATION_PROPERTIES);
        assertThat(withInterface.ownerSupertypeViolations(),
            is(java.util.Collections.singletonList(implementing + " has supertypes [java/lang/Object, " + view + "]")));
        assertThat("a read through the interface is invisible to the owner set, which is why the interface is forbidden",
            readsByMethodName(SupertypeReadFixture.class, withInterface).containsKey("viaInterface"), is(false));
        assertThat("the fixture must compile to a call owned by the interface, or it proves nothing",
            readsByMethodName(SupertypeReadFixture.class, ConfigurationOwners.of(java.util.Collections.emptyList(), view, CONFIGURATION_PROPERTIES))
                .get("viaInterface"), is(new TreeSet<>(java.util.Collections.singleton("tlsMutualAuthenticationRequired"))));

        String subclassed = Type.getInternalName(SubclassedConfigurationFixture.class);
        ConfigurationOwners withSuperclass = ConfigurationOwners.of(java.util.Collections.singletonList(
            classBytes(SubclassedConfigurationFixture.class)), subclassed, CONFIGURATION_PROPERTIES);
        assertThat(withSuperclass.ownerSupertypeViolations(), is(java.util.Arrays.asList(
            subclassed + " has supertypes [" + CONFIGURATION + "]", CONFIGURATION_PROPERTIES + " was not scanned")));

        String implementingSubclass = Type.getInternalName(InterfaceImplementingSubclassFixture.class);
        ConfigurationOwners withSubclassInterface = ConfigurationOwners.of(java.util.Arrays.asList(classBytes(Configuration.class),
            classBytes(ConfigurationProperties.class), classBytes(InterfaceImplementingSubclassFixture.class)));
        assertThat("a subclass inherits the getter rather than redeclaring it, so only its interface list reveals it",
            withSubclassInterface.redeclared.keySet(), is(empty()));
        assertThat(withSubclassInterface.ownerSupertypeViolations(),
            is(java.util.Collections.singletonList(implementingSubclass + " implements [" + view + "]")));

        ConfigurationOwners redeclaring = ConfigurationOwners.of(java.util.Arrays.asList(classBytes(Configuration.class),
            classBytes(ConfigurationProperties.class), classBytes(OverridingConfigurationFixture.class),
            classBytes(HidingStoreFixture.class), classBytes(SubclassedConfigurationFixture.class)));
        Map<String, Set<String>> expected = new TreeMap<>();
        expected.put(OverridingConfigurationFixture.class.getName(), java.util.Collections.singleton("tlsMutualAuthenticationRequired()Ljava/lang/Boolean;"));
        expected.put(HidingStoreFixture.class.getName(), java.util.Collections.singleton("controlPlaneOidcIssuer()Ljava/lang/String;"));
        assertThat(redeclaring.redeclared, is(expected));
    }

    @Test
    public void shouldReportStaleAndAmbiguousAllowListEntries() {
        Map<String, Set<String>> reads = new TreeMap<>();
        reads.put("a.Site#read()V", new TreeSet<>(java.util.Arrays.asList("x", "y")));
        reads.put("a.Overloaded#read()V", new TreeSet<>(java.util.Collections.singleton("x")));
        reads.put("a.Overloaded#read(I)V", new TreeSet<>(java.util.Collections.singleton("x")));
        reads.put("a.Keyed#<init>()V", new TreeSet<>(java.util.Collections.singleton("x")));
        reads.put("a.Keyed#<init>(I)V", new TreeSet<>(java.util.Collections.singleton("x")));
        reads.put("a.Whole#one()V", new TreeSet<>(java.util.Collections.singleton("x")));
        reads.put("a.Whole#two()V", new TreeSet<>(java.util.Collections.singleton("y")));
        reads.put("a.Definition$Nested#any()V", new TreeSet<>(java.util.Collections.singleton("x")));

        Map<String, AllowedRead> allowList = new TreeMap<>();
        allowList.put("a.Site#read", new AllowedRead("reason", "x"));
        allowList.put("a.Overloaded#read", new AllowedRead("reason", "x"));
        allowList.put("a.Keyed#<init>()V", new AllowedRead("reason", "x"));
        allowList.put("a.Whole", new AllowedRead("reason", "x", "y"));
        SnapshotVerdict clean = judgeSnapshottedReads(reads, allowList, java.util.Collections.singleton("a.Definition"));
        assertThat(clean.violations, is(java.util.Arrays.asList("a.Keyed#<init>(I)V reads [x]", "a.Site#read()V reads [y]")));
        assertThat(clean.stale, is(empty()));
        assertThat(clean.ambiguous, is(java.util.Collections.singletonList("a.Overloaded#read covers [a.Overloaded#read()V, a.Overloaded#read(I)V]")));

        allowList.put("a.Site#read", new AllowedRead("reason", "x", "y", "z"));
        allowList.put("a.Renamed#read", new AllowedRead("reason", "x"));
        allowList.put("a.Whole", new AllowedRead("reason", "x", "y", "z"));
        SnapshotVerdict stale = judgeSnapshottedReads(reads, allowList, new TreeSet<>(java.util.Arrays.asList("a.Definition", "a.GoneDefinition")));
        assertThat(stale.stale, is(java.util.Arrays.asList("a.Renamed#read no longer reads [x]", "a.Site#read no longer reads [z]",
            "a.Whole no longer reads [z]", "a.GoneDefinition (definition class) reads no snapshotted getter")));
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        try (java.io.InputStream in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            return in.readAllBytes();
        }
    }

    private static Map<String, Set<String>> readsByMethodName(Class<?> fixture, ConfigurationOwners owners) throws IOException {
        Map<String, Set<String>> byMethod = new TreeMap<>();
        directSnapshottedReads(classBytes(fixture), snapshottedGetters(SNAPSHOT_CLASSES), owners)
            .forEach((method, names) -> byMethod.put(method.substring(fixture.getName().length() + 1, method.indexOf('(')), names));
        return byMethod;
    }

    private static final List<Class<?>> SNAPSHOT_CLASSES =
        java.util.Arrays.asList(ControlPlaneAuthenticationSettings.class, ServerTlsSettings.class);

    /** Snapshot accessors computed from other snapshotted values rather than holding one of their own. */
    private static final Map<String, String> SNAPSHOT_DERIVED_ACCESSORS = new TreeMap<>();

    static {
        SNAPSHOT_DERIVED_ACCESSORS.put("authenticationRequired", "OR of the three control-plane *Required values");
        SNAPSHOT_DERIVED_ACCESSORS.put("signature", "cache key built from the snapshotted authentication values");
    }

    /**
     * Classes, keyed by top-level name, that define, publish, copy or serialise the snapshotted values, so a
     * direct read inside them is the mechanism itself rather than a decision made from a half-applied update.
     * An entry that makes no direct read is stale, so a class that needs no exemption is not listed.
     */
    private static final Map<String, String> SNAPSHOT_DEFINITION_CLASSES = new TreeMap<>();

    static {
        SNAPSHOT_DEFINITION_CLASSES.put("org.mockserver.configuration.Configuration",
            "defines the getters and builds both snapshots from its fields");
        SNAPSHOT_DEFINITION_CLASSES.put("org.mockserver.configuration.ClientConfiguration",
            "client-side configuration: copies values from the static store for the client, enforces nothing on the server");
        SNAPSHOT_DEFINITION_CLASSES.put("org.mockserver.serialization.model.ConfigurationDTO",
            "serialises and applies the configuration; applyTo runs inside an AtomicConfigurationUpdate");
    }

    private static final String[] CERTIFICATE_PATHS = {"certificateAuthorityCertificate", "certificateAuthorityPrivateKey",
        "directoryToSaveDynamicSSLCertificate", "dynamicallyCreateCertificateAuthorityCertificate", "privateKeyPath",
        "x509CertificatePath", "preventCertificateDynamicUpdate"};

    /**
     * Sites allowed to read named snapshotted values through a {@link Configuration} getter or the static store,
     * keyed by {@code Class#method}, by {@code Class#method(descriptor)return} (required when more than one
     * overload reads a snapshotted getter), or by {@code Class} (every method of that class, not its nested
     * classes). A site belongs here only when a mixed read cannot weaken authentication or TLS: a warning
     * or log message, the outbound client context, protocol routing, or certificate material the server context
     * build re-reads from the snapshot. Each entry permits only the getters it lists, and must still read every
     * one of them.
     */
    private static final Map<String, AllowedRead> ALLOWED_DIRECT_SNAPSHOTTED_READS = new TreeMap<>();

    static {
        allow("org.mockserver.mock.HttpState#warnIfLoweringTlsPosture",
            "compares the current values with an incoming PUT to log a warning; decides nothing",
            "tlsMutualAuthenticationRequired", "tlsMutualAuthenticationCertificateChain", "privateKeyPath",
            "x509CertificatePath", "certificateAuthorityCertificate", "certificateAuthorityPrivateKey");
        allow("org.mockserver.socket.tls.bouncycastle.BCKeyAndCertificateFactory",
            "the certificate factory resolves, generates and writes back the CA and leaf paths; the server context "
                + "build reads them after the build and discards them if an update completed meanwhile",
            CERTIFICATE_PATHS);
        allow("org.mockserver.socket.tls.KeyAndCertificateFactory#writeCertificateAuthorityToDisk",
            "interface default overridden by BCKeyAndCertificateFactory, the only in-tree implementation",
            "directoryToSaveDynamicSSLCertificate");
        allow("org.mockserver.socket.tls.CertificateConfigurationValidator#validate",
            "checks the certificate files before a build; a mixed read can only fail a build, which is retried",
            "certificateAuthorityCertificate", "certificateAuthorityPrivateKey", "privateKeyPath", "x509CertificatePath");
        allow("org.mockserver.socket.tls.NettySslContextFactory#createServerSslContext",
            "exception message after a failed build", "privateKeyPath", "x509CertificatePath", "certificateAuthorityCertificate");
        allow("org.mockserver.socket.tls.NettySslContextFactory#fixedServerCertificateRecheckDue",
            "throttled on-disk rotation check of a fixed leaf; it can only trigger or skip a rebuild, which reads the snapshot",
            "privateKeyPath", "x509CertificatePath");
        allow("org.mockserver.socket.tls.NettySslContextFactory#fixedServerCertificateChangedOnDisk",
            "throttled on-disk rotation check of a fixed leaf; it can only trigger or skip a rebuild, which reads the snapshot",
            "privateKeyPath", "x509CertificatePath");
        allow("org.mockserver.socket.tls.NettySslContextFactory#recordFixedServerCertificateState",
            "records the fixed leaf's file state for the rotation check above", "privateKeyPath", "x509CertificatePath");
        allow("org.mockserver.socket.tls.NettySslContextFactory#usingBundledDefaultCertificateAuthority",
            "startup warning that the bundled CA is in use", "dynamicallyCreateCertificateAuthorityCertificate",
            "privateKeyPath", "x509CertificatePath", "certificateAuthorityCertificate", "certificateAuthorityPrivateKey");
        allow("org.mockserver.socket.tls.NettySslContextFactory#warnIfInsecureTlsProfileConfigured",
            "startup warning about TLSv1 / TLSv1.1", "tlsProtocols", "tlsAllowInsecureProtocols");
        allow("org.mockserver.socket.tls.NettySslContextFactory$ClientTlsInputs#<init>",
            "the outbound client context's inputs, read once; the client context is built only from them and cached "
                + "under their signature, and the server context is built from ServerTlsSettings",
            "tlsMutualAuthenticationCertificateChain", "tlsProtocols", "tlsAllowInsecureProtocols", "http2Enabled",
            "certificateAuthorityCertificate", "certificateAuthorityPrivateKey",
            "dynamicallyCreateCertificateAuthorityCertificate", "directoryToSaveDynamicSSLCertificate");
        allow("org.mockserver.netty.unification.PortUnificationHandler#decode",
            "chooses the HTTP/2 or HTTP/1.1 pipeline; a mismatch with the context's ALPN fails the connection, "
                + "it skips no authentication", "http2Enabled");
        allow("org.mockserver.netty.unification.PortUnificationHandler#exceptionCaught",
            "log message", "x509CertificatePath", "certificateAuthorityCertificate");
        allow("org.mockserver.netty.proxy.relay.RelayConnectHandler$RelayTlsDetectionHandler#decode",
            "chooses h2c or HTTP/1.1 for a relayed cleartext connection, matching PortUnificationHandler#decode",
            "http2Enabled");
        allow("org.mockserver.socket.tls.ProxySetupInfo#isUsingDefaultCa",
            "describes the CA for --proxy-setup output", "certificateAuthorityCertificate",
            "dynamicallyCreateCertificateAuthorityCertificate");
        allow("org.mockserver.state.StateBackendFactory#isClusteredWithDynamicCertificateAuthority",
            "startup warning for a clustered server with a per-node dynamic CA",
            "dynamicallyCreateCertificateAuthorityCertificate");
        allow("org.mockserver.echo.tls.UniqueCertificateChainSSLContextBuilder$UniqueCertificateChainX509KeyManager#<init>",
            "test-support echo server: saves the values it overrides on its own Configuration to build a unique "
                + "client chain, and restores them", "dynamicallyCreateCertificateAuthorityCertificate",
            "directoryToSaveDynamicSSLCertificate", "privateKeyPath", "x509CertificatePath");
    }

    private static void allow(String site, String reason, String... getters) {
        ALLOWED_DIRECT_SNAPSHOTTED_READS.put(site, new AllowedRead(reason, getters));
    }

    private static final class AllowedRead {
        final String reason;
        final Set<String> getters;

        AllowedRead(String reason, String... getters) {
            this.reason = reason;
            this.getters = new TreeSet<>(java.util.Arrays.asList(getters));
        }
    }

    static final class SnapshotVerdict {
        final List<String> violations = new ArrayList<>();
        final List<String> stale = new ArrayList<>();
        final List<String> ambiguous = new ArrayList<>();
    }

    /**
     * Checks the direct reads against the allow-list in both directions: a read no entry permits is a violation,
     * and an entry (or definition class) whose permitted reads no longer all happen is stale.
     */
    static SnapshotVerdict judgeSnapshottedReads(Map<String, Set<String>> reads, Map<String, AllowedRead> allowList, Set<String> definitionClasses) {
        SnapshotVerdict verdict = new SnapshotVerdict();
        Map<String, Set<String>> permittedReadsSeen = new HashMap<>();
        Map<String, Set<String>> sitesByKey = new HashMap<>();
        Set<String> definitionClassesSeen = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : reads.entrySet()) {
            String method = entry.getKey();
            String className = method.substring(0, method.indexOf('#'));
            String topLevelClassName = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
            if (definitionClasses.contains(topLevelClassName)) {
                definitionClassesSeen.add(topLevelClassName);
                continue;
            }
            Set<String> disallowed = new TreeSet<>(entry.getValue());
            for (String key : java.util.Arrays.asList(method, method.substring(0, method.indexOf('(')), className)) {
                AllowedRead allowed = allowList.get(key);
                if (allowed != null) {
                    Set<String> permittedReads = new TreeSet<>(entry.getValue());
                    permittedReads.retainAll(allowed.getters);
                    permittedReadsSeen.computeIfAbsent(key, k -> new TreeSet<>()).addAll(permittedReads);
                    sitesByKey.computeIfAbsent(key, k -> new TreeSet<>()).add(method);
                    disallowed.removeAll(allowed.getters);
                    break;
                }
            }
            if (!disallowed.isEmpty()) {
                verdict.violations.add(method + " reads " + disallowed);
            }
        }
        for (Map.Entry<String, AllowedRead> allowed : allowList.entrySet()) {
            String key = allowed.getKey();
            Set<String> unread = new TreeSet<>(allowed.getValue().getters);
            unread.removeAll(permittedReadsSeen.getOrDefault(key, java.util.Collections.emptySet()));
            if (!unread.isEmpty()) {
                verdict.stale.add(key + " no longer reads " + unread);
            }
            Set<String> sites = sitesByKey.getOrDefault(key, java.util.Collections.emptySet());
            if (key.contains("#") && !key.contains("(") && sites.size() > 1) {
                verdict.ambiguous.add(key + " covers " + sites);
            }
        }
        for (String definitionClass : new TreeSet<>(definitionClasses)) {
            if (!definitionClassesSeen.contains(definitionClass)) {
                verdict.stale.add(definitionClass + " (definition class) reads no snapshotted getter");
            }
        }
        return verdict;
    }

    /**
     * The classes a configuration read can name as its owner: {@link Configuration} and
     * {@link ConfigurationProperties}, plus every scanned class whose superclass chain reaches one of them.
     */
    static final class ConfigurationOwners {
        final String instanceRoot;
        final String staticRoot;
        final Set<String> instance = new TreeSet<>();
        final Set<String> staticStore = new TreeSet<>();
        /** Each scanned class's superclass and interfaces, by internal name. */
        private final Map<String, List<String>> supertypes = new HashMap<>();
        /** Dotted subclass name to the methods ({@code name+descriptor}) it redeclares from its root. */
        final Map<String, Set<String>> redeclared = new TreeMap<>();

        private ConfigurationOwners(String instanceRoot, String staticRoot) {
            this.instanceRoot = instanceRoot;
            this.staticRoot = staticRoot;
        }

        static ConfigurationOwners of(java.util.Collection<byte[]> classes) {
            return of(classes, CONFIGURATION, CONFIGURATION_PROPERTIES);
        }

        static ConfigurationOwners of(java.util.Collection<byte[]> classes, String instanceRoot, String staticRoot) {
            Map<String, String> superNames = new HashMap<>();
            Map<String, Set<String>> declaredMethods = new HashMap<>();
            ConfigurationOwners owners = new ConfigurationOwners(instanceRoot, staticRoot);
            for (byte[] classBytes : classes) {
                ClassReader reader = new ClassReader(classBytes);
                superNames.put(reader.getClassName(), reader.getSuperName());
                List<String> supertypes = new ArrayList<>();
                supertypes.add(reader.getSuperName());
                supertypes.addAll(java.util.Arrays.asList(reader.getInterfaces()));
                owners.supertypes.put(reader.getClassName(), supertypes);
                Set<String> methods = new TreeSet<>();
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        if ((access & Opcodes.ACC_PRIVATE) == 0 && !name.startsWith("<")) {
                            methods.add(name + descriptor);
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                declaredMethods.put(reader.getClassName(), methods);
            }
            owners.instance.add(instanceRoot);
            owners.staticStore.add(staticRoot);
            for (String name : superNames.keySet()) {
                Set<String> visited = new HashSet<>();
                for (String ancestor = superNames.get(name); ancestor != null && visited.add(ancestor); ancestor = superNames.get(ancestor)) {
                    if (instanceRoot.equals(ancestor) || staticRoot.equals(ancestor)) {
                        (instanceRoot.equals(ancestor) ? owners.instance : owners.staticStore).add(name);
                        Set<String> overlap = new TreeSet<>(declaredMethods.get(name));
                        overlap.retainAll(declaredMethods.getOrDefault(ancestor, java.util.Collections.emptySet()));
                        if (!overlap.isEmpty()) {
                            owners.redeclared.put(name.replace('/', '.'), overlap);
                        }
                    }
                }
            }
            return owners;
        }

        /**
         * Ways a read could be compiled against a supertype of an owner, which no owner set would match: a root that
         * extends anything but {@code Object} or was not scanned at all, or any owner that implements an interface.
         */
        List<String> ownerSupertypeViolations() {
            List<String> violations = new ArrayList<>();
            for (String root : java.util.Arrays.asList(instanceRoot, staticRoot)) {
                List<String> rootSupertypes = supertypes.get(root);
                if (rootSupertypes == null) {
                    violations.add(root + " was not scanned");
                } else if (!rootSupertypes.equals(java.util.Collections.singletonList("java/lang/Object"))) {
                    violations.add(root + " has supertypes " + rootSupertypes);
                }
            }
            for (String subclass : new TreeSet<>(Stream.concat(instance.stream(), staticStore.stream()).collect(Collectors.toSet()))) {
                List<String> subclassSupertypes = supertypes.get(subclass);
                if (!subclass.equals(instanceRoot) && !subclass.equals(staticRoot) && subclassSupertypes.size() > 1) {
                    violations.add(subclass + " implements " + subclassSupertypes.subList(1, subclassSupertypes.size()));
                }
            }
            return violations;
        }

        /** Dotted names of the owners other than the two roots themselves. */
        Set<String> subclasses() {
            Set<String> subclasses = new TreeSet<>();
            Stream.concat(instance.stream(), staticStore.stream())
                .filter(name -> !instanceRoot.equals(name) && !staticRoot.equals(name))
                .forEach(name -> subclasses.add(name.replace('/', '.')));
            return subclasses;
        }
    }

    /**
     * The guarded getter names: every public no-arg accessor of the snapshot classes that {@link Configuration}
     * also exposes as a public no-arg getter.
     */
    static Set<String> snapshottedGetters(List<Class<?>> snapshotClasses) {
        Set<String> configurationGetters = publicNoArgGetters(Configuration.class).stream()
            .map(Method::getName)
            .collect(Collectors.toSet());
        Set<String> guarded = new TreeSet<>();
        for (Class<?> snapshotClass : snapshotClasses) {
            for (Method method : publicNoArgGetters(snapshotClass)) {
                if (configurationGetters.contains(method.getName())) {
                    guarded.add(method.getName());
                }
            }
        }
        return guarded;
    }

    private static List<Method> publicNoArgGetters(Class<?> type) {
        List<Method> getters = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (!method.isSynthetic() && java.lang.reflect.Modifier.isPublic(modifiers) && !java.lang.reflect.Modifier.isStatic(modifiers)
                && method.getParameterCount() == 0 && !void.class.equals(method.getReturnType())) {
                getters.add(method);
            }
        }
        return getters;
    }

    /**
     * Descriptor-qualified {@code fqcn#name(desc)ret} of each method in {@code classBytes} that reads a guarded
     * getter through a {@link Configuration} (a call or a method reference) or through the static store, mapped
     * to the names it reads. {@code owners} names the classes such a read can be compiled against.
     */
    static Map<String, Set<String>> directSnapshottedReads(byte[] classBytes, Set<String> guardedGetters, ConfigurationOwners owners) {
        Map<String, Set<String>> reads = new TreeMap<>();
        ClassReader reader = new ClassReader(classBytes);
        String className = reader.getClassName().replace('/', '.');
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                String key = className + "#" + name + descriptor;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String calledName, String calledDescriptor, boolean isInterface) {
                        record(owner, calledName, calledDescriptor);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String indyDescriptor, org.objectweb.asm.Handle bootstrap, Object... bootstrapArguments) {
                        for (Object argument : bootstrapArguments) {
                            if (argument instanceof org.objectweb.asm.Handle) {
                                org.objectweb.asm.Handle handle = (org.objectweb.asm.Handle) argument;
                                record(handle.getOwner(), handle.getName(), handle.getDesc());
                            }
                        }
                    }

                    private void record(String owner, String calledName, String calledDescriptor) {
                        if ((owners.instance.contains(owner) || owners.staticStore.contains(owner))
                            && guardedGetters.contains(calledName)
                            && Type.getArgumentTypes(calledDescriptor).length == 0) {
                            reads.computeIfAbsent(key, k -> new TreeSet<>()).add(calledName);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return reads;
    }

    /** Stands in for a snapshot class that gained a field, to show the guarded set follows the snapshots. */
    @SuppressWarnings("unused")
    public static final class ExtendedSnapshotFixture {
        public Integer maxExpectations() {
            return null;
        }

        public String notAConfigurationGetter() {
            return null;
        }
    }

    /** One method per form of read the detector must (or must not) report. */
    @SuppressWarnings("unused")
    static final class DirectReadFixture {
        boolean viaGetter(Configuration configuration) {
            return Boolean.TRUE.equals(configuration.tlsMutualAuthenticationRequired());
        }

        java.util.function.Supplier<Boolean> viaMethodReference(Configuration configuration) {
            return configuration::controlPlaneJWTAuthenticationRequired;
        }

        String viaStaticStore() {
            return ConfigurationProperties.controlPlaneOidcIssuer();
        }

        boolean viaSnapshot(Configuration configuration) {
            return Boolean.TRUE.equals(ServerTlsSettings.of(configuration).tlsMutualAuthenticationRequired());
        }

        Integer viaUnguardedGetter(Configuration configuration) {
            return configuration.maxExpectations();
        }
    }

    static class SubclassedConfigurationFixture extends Configuration {
    }

    static final class TwiceSubclassedConfigurationFixture extends SubclassedConfigurationFixture {
    }

    static final class SubclassedStoreFixture extends ConfigurationProperties {
    }

    /** The reads of {@link DirectReadFixture}, each made through a subclass. */
    @SuppressWarnings("unused")
    static final class SubclassReadFixture {
        boolean viaSubclassGetter(TwiceSubclassedConfigurationFixture configuration) {
            return Boolean.TRUE.equals(configuration.tlsMutualAuthenticationRequired());
        }

        java.util.function.Supplier<Boolean> viaSubclassMethodReference(SubclassedConfigurationFixture configuration) {
            return configuration::controlPlaneJWTAuthenticationRequired;
        }

        String viaSubclassStaticStore() {
            return SubclassedStoreFixture.controlPlaneOidcIssuer();
        }
    }

    interface TlsViewFixture {
        Boolean tlsMutualAuthenticationRequired();
    }

    static final class InterfaceImplementingConfigurationFixture implements TlsViewFixture {
        @Override
        public Boolean tlsMutualAuthenticationRequired() {
            return null;
        }
    }

    static final class InterfaceImplementingSubclassFixture extends Configuration implements TlsViewFixture {
    }

    @SuppressWarnings("unused")
    static final class SupertypeReadFixture {
        boolean viaInterface(InterfaceImplementingConfigurationFixture configuration) {
            return Boolean.TRUE.equals(((TlsViewFixture) configuration).tlsMutualAuthenticationRequired());
        }
    }

    static final class OverridingConfigurationFixture extends Configuration {
        @Override
        public Boolean tlsMutualAuthenticationRequired() {
            return Boolean.FALSE;
        }
    }

    static final class HidingStoreFixture extends ConfigurationProperties {
        public static String controlPlaneOidcIssuer() {
            return null;
        }
    }

    /** Repository paths that build or publish released artifacts; a trailing {@code *} matches a name prefix. */
    private static final List<String> RELEASE_PATHS = java.util.Arrays.asList("scripts/release", ".buildkite/release*", "docker");

    @Test
    public void shouldExemptOnlyUnshippedNonReactorModulesFromScan() throws Exception {
        Path root = mockserverRoot();
        Set<String> reactorModules = reactorModules();
        assertThat("could not derive the reactor module set", reactorModules.size(), greaterThan(10));
        List<Path> releaseFiles = releasePathFiles(root.getParent());
        assertThat("could not find the release and image-build files", releaseFiles.size(), greaterThan(10));
        for (String module : UNSHIPPED_MODULES_EXCLUDED_FROM_SCAN.keySet()) {
            Path pom = root.resolve(module).resolve("pom.xml");
            assertThat("exempt module " + module + " no longer exists — delete its entry", Files.isRegularFile(pom), is(true));
            assertThat("exempt module " + module + " is a reactor module, so CI builds it and the guard must scan it",
                reactorModules.contains(module), is(false));
            assertThat("exempt module " + module + " must set <maven.deploy.skip>true</maven.deploy.skip> in its "
                    + "project-level <properties> (not in a profile or CDATA), or the guard must scan it",
                projectLevelProperties(Files.readString(pom, StandardCharsets.UTF_8))
                    .contains("<maven.deploy.skip>true</maven.deploy.skip>"), is(true));
            byte[] name = module.getBytes(StandardCharsets.UTF_8);
            List<String> referencing = new ArrayList<>();
            for (Path file : releaseFiles) {
                if (indexOf(Files.readAllBytes(file), name) >= 0) {
                    referencing.add(root.getParent().relativize(file).toString());
                }
            }
            assertThat("exempt module " + module + " is named by a release or image-build file, so it may ship and the "
                    + "guard must scan it: " + referencing,
                referencing, is(empty()));
        }
    }

    /** The project-level {@code <properties>} content, or "" if absent or the pom uses CDATA. */
    private static String projectLevelProperties(String pom) {
        String stripped = pom.replaceAll("(?s)<!--.*?-->", "");
        if (stripped.contains("<![CDATA[")) {
            return "";
        }
        stripped = stripped.replaceAll("(?s)<(profiles|build|reporting)>.*?</\\1>", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?s)<properties>(.*?)</properties>").matcher(stripped);
        StringBuilder properties = new StringBuilder();
        while (matcher.find()) {
            properties.append(matcher.group(1));
        }
        return properties.toString();
    }

    private static List<Path> releasePathFiles(Path repoRoot) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String releasePath : RELEASE_PATHS) {
            List<Path> roots = new ArrayList<>();
            if (releasePath.endsWith("*")) {
                Path parent = repoRoot.resolve(releasePath).getParent();
                String prefix = Paths.get(releasePath).getFileName().toString().replace("*", "");
                try (Stream<Path> children = Files.list(parent)) {
                    children.filter(c -> c.getFileName().toString().startsWith(prefix)).forEach(roots::add);
                }
            } else {
                roots.add(repoRoot.resolve(releasePath));
            }
            for (Path releaseRoot : roots) {
                assertThat("release path " + releaseRoot + " does not exist — update RELEASE_PATHS", Files.exists(releaseRoot), is(true));
                try (Stream<Path> walk = Files.walk(releaseRoot)) {
                    walk.filter(Files::isRegularFile).forEach(files::add);
                }
            }
        }
        return files;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * A wildcard static import of the store makes call sites invisible to review (they read as bare
     * {@code maxRequestBodySize()} rather than {@code ConfigurationProperties.maxRequestBodySize()}),
     * so main source must not use one. {@code fileExists} is a stateless path-checking utility that
     * carries no configuration value, and Configuration itself imports it.
     */
    @Test
    public void shouldNotStaticallyImportConfigurationPropertiesInMainSource() throws Exception {
        Path mockserverRoot = mockserverRoot();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> modules = Files.list(mockserverRoot)) {
            for (Path module : modules.filter(Files::isDirectory).collect(Collectors.toList())) {
                Path mainJava = module.resolve("src/main/java");
                if (!Files.isDirectory(mainJava)) {
                    continue;
                }
                try (Stream<Path> sources = Files.walk(mainJava)) {
                    for (Path source : sources.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                            String trimmed = line.trim();
                            if (trimmed.startsWith("import static org.mockserver.configuration.ConfigurationProperties.")) {
                                String imported = trimmed
                                    .substring("import static org.mockserver.configuration.ConfigurationProperties.".length())
                                    .replace(";", "").trim();
                                if (!ALLOWED_STATIC_IMPORTS.containsKey(imported)) {
                                    offenders.add(mockserverRoot.relativize(source) + " -> " + imported);
                                }
                            }
                        }
                    }
                }
            }
        }
        assertThat("static imports of ConfigurationProperties in main source hide static-store reads from "
                + "review and from the call-site guard above; read through a Configuration instance instead: "
                + offenders,
            offenders, is(empty()));
    }

    /**
     * Static imports of {@link ConfigurationProperties} members permitted in main source, with reasons.
     */
    private static final Map<String, String> ALLOWED_STATIC_IMPORTS = new TreeMap<>();

    static {
        ALLOWED_STATIC_IMPORTS.put("fileExists",
            "stateless filesystem predicate, carries no configuration value; used by Configuration itself");
        ALLOWED_STATIC_IMPORTS.put("maxFutureTimeout",
            "mockserver-integration-testing is test-support code shipped in src/main so downstream test suites "
                + "can reuse it; it drives test await timeouts, not server behaviour");
    }

    private static final class CallSiteIndex {
        /** descriptor-qualified {@code fqcn#name(desc)ret} -> property names read from the static store */
        final Map<String, Set<String>> staticReads = new TreeMap<>();
        /** descriptor-qualified {@code fqcn#name(desc)ret} -> property names read from a Configuration instance */
        final Map<String, Set<String>> instanceReads = new HashMap<>();

        void scan(byte[] classBytes, ConfigurationOwners owners) {
            ClassReader reader = new ClassReader(classBytes);
            String className = reader.getClassName().replace('/', '.');
            if (FALLBACK_DEFINITION_CLASSES.contains(className)) {
                return;
            }
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    String key = className + "#" + name + descriptor;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String calledName, String calledDescriptor, boolean isInterface) {
                            if (!isNoArgReader(calledDescriptor)) {
                                return;
                            }
                            if (opcode == Opcodes.INVOKESTATIC && owners.staticStore.contains(owner)) {
                                staticReads.computeIfAbsent(key, k -> new TreeSet<>()).add(calledName);
                            } else if (opcode == Opcodes.INVOKEVIRTUAL && owners.instance.contains(owner)) {
                                instanceReads.computeIfAbsent(key, k -> new TreeSet<>()).add(calledName);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        }

        private static boolean isNoArgReader(String descriptor) {
            Type type = Type.getMethodType(descriptor);
            return type.getArgumentTypes().length == 0 && !Type.VOID_TYPE.equals(type.getReturnType());
        }
    }

    /**
     * Property names that a client can actually set over {@code PUT /mockserver/configuration}: the
     * intersection of {@link ConfigurationDTO}'s fields with {@link Configuration}'s getter/fluent-setter
     * pairs. Deriving this reflectively means the guard cannot go stale as properties are added.
     */
    private static Set<String> restReachableProperties() {
        Set<String> dtoFields = new HashSet<>();
        for (Field field : ConfigurationDTO.class.getDeclaredFields()) {
            if (!field.isSynthetic() && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                dtoFields.add(field.getName());
            }
        }
        Set<String> setters = new HashSet<>();
        Set<String> getters = new HashSet<>();
        for (Method method : Configuration.class.getDeclaredMethods()) {
            if (method.isSynthetic() || !java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            if (method.getParameterCount() == 1 && Configuration.class.equals(method.getReturnType())) {
                setters.add(method.getName());
            } else if (method.getParameterCount() == 0 && !void.class.equals(method.getReturnType())) {
                getters.add(method.getName());
            }
        }
        getters.retainAll(setters);
        getters.retainAll(dtoFields);
        return getters;
    }

    /**
     * Every {@code <module>/target/classes} directory present under the {@code mockserver/} reactor root,
     * except those in {@link #UNSHIPPED_MODULES_EXCLUDED_FROM_SCAN}.
     */
    private static List<Path> moduleClassRoots() throws IOException {
        try (Stream<Path> modules = Files.list(mockserverRoot())) {
            return modules
                .filter(module -> !UNSHIPPED_MODULES_EXCLUDED_FROM_SCAN.containsKey(module.getFileName().toString()))
                .map(module -> module.resolve("target/classes"))
                .filter(Files::isDirectory)
                .sorted()
                .collect(Collectors.toList());
        }
    }

    /**
     * The set of reactor modules the guard is SUPPOSED to scan, derived authoritatively from
     * {@code mockserver/pom.xml}'s {@code <modules>} list rather than from whatever is on disk (which is
     * precisely how the guard's scope silently tracked Maven reactor order before).
     *
     * <p>A module qualifies when it is listed in the reactor AND carries {@code src/main/java} (so it
     * produces scannable {@code target/classes}) AND is not exempted in
     * {@link #MODULES_WITHOUT_SCANNABLE_MAIN_CLASSES}. This automatically excludes {@code mockserver-bom}
     * and the {@code *-no-dependencies} shade modules (no main sources) while automatically INCLUDING any
     * newly-added module that defines configuration call sites — no per-module maintenance.
     *
     * <p>Every module named in {@link #EXTRA_EXPECTED_MODULES_PROPERTY} is added unconditionally, so a
     * misspelt, unbuilt or scan-exempt name fails the coverage check rather than being dropped.
     */
    private static Set<String> expectedScannedModules() throws IOException {
        Path root = mockserverRoot();
        Set<String> expected = new TreeSet<>();
        for (String module : reactorModules()) {
            if (MODULES_WITHOUT_SCANNABLE_MAIN_CLASSES.containsKey(module)) {
                continue;
            }
            if (Files.isDirectory(root.resolve(module).resolve("src/main/java"))) {
                expected.add(module);
            }
        }
        for (String module : System.getProperty(EXTRA_EXPECTED_MODULES_PROPERTY, "").split(",")) {
            if (!module.trim().isEmpty()) {
                expected.add(module.trim());
            }
        }
        return expected;
    }

    /** Every {@code <module>} listed in {@code mockserver/pom.xml}'s {@code <modules>} block. */
    private static Set<String> reactorModules() throws IOException {
        Path root = mockserverRoot();
        String pom = Files.readString(root.resolve("pom.xml"), StandardCharsets.UTF_8);
        int start = pom.indexOf("<modules>");
        int end = pom.indexOf("</modules>");
        if (start < 0 || end < 0 || end < start) {
            throw new IllegalStateException("could not locate the <modules> block in " + root.resolve("pom.xml"));
        }
        // strip XML comments first: the <modules> block carries a commented-out
        // <module>../examples/java</module> (examples/java is built standalone, not as a reactor
        // module), and matching inside it would invent a bogus expected module that can never be scanned.
        String modulesBlock = pom.substring(start, end).replaceAll("(?s)<!--.*?-->", "");
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("<module>\\s*([^<]+?)\\s*</module>").matcher(modulesBlock);
        Set<String> modules = new TreeSet<>();
        while (matcher.find()) {
            modules.add(matcher.group(1).trim());
        }
        return modules;
    }

    /**
     * Locate the {@code mockserver/} reactor root from THIS test class's own output directory, so the
     * guard works regardless of the working directory the build runs from.
     *
     * <p>Deliberately anchored on the test classes rather than on {@link Configuration}: when this module
     * is built alone ({@code -pl mockserver-netty}) core is resolved as a jar from the local Maven
     * repository, whose layout also contains a {@code mockserver-core} directory — anchoring there
     * silently resolved into {@code ~/.m2} and scanned nothing. This module's own test output is always
     * exploded and always inside the working tree.
     */
    private static Path mockserverRoot() {
        URL location = ConfigurationCallSiteGuardTest.class.getProtectionDomain().getCodeSource().getLocation();
        Path testClasses = Paths.get(location.getPath());
        // <mockserver>/mockserver-netty/target/test-classes -> <mockserver>
        Path root = testClasses.getParent().getParent().getParent();
        if (!Files.isDirectory(root.resolve("mockserver-core/src/main/java"))) {
            throw new IllegalStateException("could not locate the mockserver reactor root from " + testClasses
                + " (resolved " + root + ")");
        }
        return root;
    }
}
