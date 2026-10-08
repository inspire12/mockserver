package org.mockserver.netty.integration.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.mock.Expectation;
import org.mockserver.serialization.java.ExpectationToJavaSerializer;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.is;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpOverrideForwardedRequest.forwardOverriddenRequest;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.test.Retries.tryWaitForSuccess;

/**
 * The Java code generated for an expectation with more than one action ({@code format=JAVA}) compiles, runs and
 * recreates that expectation, primary action included.
 */
public class GeneratedJavaCodeMultiActionExpectationIntegrationTest {

    private static final String CLIENT_CONSTRUCTION = "new MockServerClient(\"localhost\", 1080)";

    private ClientAndServer mockServer;

    @Before
    public void startServer() {
        mockServer = startClientAndServer();
    }

    @After
    public void stopServer() {
        stopQuietly(mockServer);
    }

    @Test
    public void shouldRecreateAnExpectationWithAPrimaryResponseAndASecondaryForwardFromItsGeneratedJavaCode() throws Exception {
        // given
        Expectation expectation = new Expectation(request().withPath("/primary"))
            .thenRespond(response().withStatusCode(202).withBody("primary answer").withPrimary(true))
            .thenForward(forwardOverriddenRequest(
                request().withPath("/forwarded").withHeader(HOST.toString(), "127.0.0.1:" + mockServer.getLocalPort())
            ));
        String generatedCode = new ExpectationToJavaSerializer().serialize(2, expectation);
        assertThat(generatedCode.contains(CLIENT_CONSTRUCTION), is(true));

        // when - the generated code is compiled and run against this server
        runGeneratedCode(generatedCode.replace(CLIENT_CONSTRUCTION, "client"), mockServer);

        // then - the server holds both actions, the response marked primary
        Expectation[] active = mockServer.retrieveActiveExpectations(request().withPath("/primary"));
        assertThat(active, arrayWithSize(1));
        assertThat(active[0].getHttpResponse().getStatusCode(), is(202));
        assertThat(active[0].getHttpResponse().isPrimary(), is(true));
        assertThat(active[0].getHttpOverrideForwardedRequest().getRequestOverride().getPath().getValue(), is("/forwarded"));
        assertThat(active[0].getHttpOverrideForwardedRequest().isPrimary(), is(false));

        // and - a matching request is answered by the primary action and also runs the secondary one
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        java.net.http.HttpResponse<String> answer = httpClient.send(
            java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mockServer.getLocalPort() + "/primary"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build(),
            BodyHandlers.ofString()
        );
        assertThat(answer.statusCode(), is(202));
        assertThat(answer.body(), is("primary answer"));
        tryWaitForSuccess(() -> assertThat(mockServer.retrieveRecordedRequests(request().withPath("/forwarded")), arrayWithSize(1)), 200, 100, MILLISECONDS);
    }

    private static void runGeneratedCode(String statement, MockServerClient client) throws Exception {
        String source = "" +
            "import org.mockserver.client.MockServerClient;\n" +
            "import org.mockserver.matchers.Times;\n" +
            "import org.mockserver.matchers.TimeToLive;\n" +
            "import org.mockserver.model.*;\n" +
            "import static org.mockserver.model.HttpRequest.request;\n" +
            "import static org.mockserver.model.HttpResponse.response;\n" +
            "import static org.mockserver.model.HttpOverrideForwardedRequest.forwardOverriddenRequest;\n" +
            "\n" +
            "public class GeneratedExpectation implements java.util.function.Consumer<MockServerClient> {\n" +
            "    public void accept(MockServerClient client) {\n" +
            statement + "\n" +
            "    }\n" +
            "}\n";
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, ByteArrayOutputStream> classes = new HashMap<>();
        JavaFileManager fileManager = new ForwardingJavaFileManager<StandardJavaFileManager>(compiler.getStandardFileManager(diagnostics, null, null)) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        return classes.computeIfAbsent(className, name -> new ByteArrayOutputStream());
                    }
                };
            }
        };
        JavaFileObject sourceFile = new SimpleJavaFileObject(URI.create("string:///GeneratedExpectation.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        List<String> options = List.of("-classpath", System.getProperty("java.class.path"));
        boolean compiled = compiler.getTask(null, fileManager, diagnostics, options, null, Collections.singletonList(sourceFile)).call();
        String errors = diagnostics.getDiagnostics().stream().map(Object::toString).collect(Collectors.joining("\n"));
        assertThat("generated code:\n" + statement + "\ncompiler output:\n" + errors, compiled, is(true));

        ClassLoader classLoader = new ClassLoader(GeneratedJavaCodeMultiActionExpectationIntegrationTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                ByteArrayOutputStream bytes = classes.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] classBytes = bytes.toByteArray();
                return defineClass(name, classBytes, 0, classBytes.length);
            }
        };
        @SuppressWarnings("unchecked")
        java.util.function.Consumer<MockServerClient> generated = (java.util.function.Consumer<MockServerClient>) classLoader
            .loadClass("GeneratedExpectation")
            .getDeclaredConstructor()
            .newInstance();
        generated.accept(client);
    }
}
