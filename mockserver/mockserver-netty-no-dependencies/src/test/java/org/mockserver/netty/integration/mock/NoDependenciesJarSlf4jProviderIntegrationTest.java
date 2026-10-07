package org.mockserver.netty.integration.mock;

import org.junit.Test;
import org.mockserver.netty.integration.NoDependenciesJarRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #2772: the shaded jar bundles an SLF4J provider for running on its own, but must never compete with an
 * embedding project's provider. Each case forks a JVM that embeds MockServer from the shaded jar.
 */
public class NoDependenciesJarSlf4jProviderIntegrationTest {

    private static final long TIMEOUT_SECONDS = 120;

    @Test
    public void shouldLogThroughTheProjectsOwnProviderWithoutAMultipleProvidersWarning() throws Exception {
        String output = runEmbedded(Arrays.asList(requiredJar("slf4jApiJar"), requiredJar("slf4jSimpleJar")), "logFirst");

        assertTrue("embedded MockServer did not serve the mock:\n" + output, output.contains("EMBEDDED RESULT 200 embedded-ok"));
        assertFalse("SLF4J warned while a project provider was present:\n" + output, output.contains("SLF4J(W)"));
        assertTrue("the application's own log line did not go through slf4j-simple:\n" + output,
            output.contains("INFO org.mockserver.netty.integration.EmbeddedMockServerMain - application log line"));
        assertTrue("MockServer's log did not go through the project's provider (slf4j-simple):\n" + output,
            output.matches("(?s).*\\] INFO org\\.mockserver\\.[^\\n]* - [0-9]+ started on port: [0-9]+.*"));
    }

    @Test
    public void shouldStillLogThroughTheBundledProviderWhenTheProjectHasNone() throws Exception {
        String output = runEmbedded(new ArrayList<>(), null);

        assertTrue("embedded MockServer did not serve the mock:\n" + output, output.contains("EMBEDDED RESULT 200 embedded-ok"));
        assertFalse("SLF4J reported a provider problem:\n" + output, output.contains("SLF4J("));
        assertTrue("MockServer logged nothing without a project provider (the bundled provider was not selected):\n" + output,
            output.matches("(?s).* INFO [0-9]+ started on port: [0-9]+.*"));
    }

    private static String requiredJar(String property) {
        String path = System.getProperty(property, "");
        assertTrue("system property " + property + " must name an existing jar, was '" + path + "'", new File(path).isFile());
        return path;
    }

    private static String runEmbedded(List<String> projectJars, String argument) throws IOException, InterruptedException {
        File testClasses = new File(System.getProperty("project.basedir", "."), "target/test-classes");
        // the project's own jars first, as Maven orders a project's direct dependencies
        List<String> classpath = new ArrayList<>(projectJars);
        classpath.add(NoDependenciesJarRunner.locateShadedJar().getAbsolutePath());
        classpath.add(testClasses.getAbsolutePath());
        List<String> command = new ArrayList<>(Arrays.asList(
            NoDependenciesJarRunner.getJavaBin(), "-Dfile.encoding=UTF-8", "-cp", String.join(File.pathSeparator, classpath),
            "org.mockserver.netty.integration.EmbeddedMockServerMain"));
        if (argument != null) {
            command.add(argument);
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(output);
            } catch (IOException ignored) {
                // the process ended; what was read is kept
            }
        }, "embedded-mockserver-output");
        reader.start();
        boolean exited = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        reader.join(TimeUnit.SECONDS.toMillis(10));
        String text;
        synchronized (output) {
            text = output.toString(StandardCharsets.UTF_8);
        }
        assertTrue("embedded MockServer did not exit within " + TIMEOUT_SECONDS + "s:\n" + text, exited);
        return text;
    }
}
