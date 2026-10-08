package org.mockserver.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.mockserver.configuration.ConfigurationProperties;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Run the MockServer and wait for a specified timeout (or indefinitely)
 *
 * @author jamesdbloom
 */
@SuppressWarnings("deprecation")
@Mojo(name = "run", requiresProject = false)
public class MockServerRunAndWaitMojo extends MockServerAbstractMojo {

    // used to simplify waiting logic
    @SuppressWarnings("FieldMayBeFinal")
    private CompletableFuture<Object> settableFuture = new CompletableFuture<>();

    public void execute() throws MojoExecutionException {
        if (isNotBlank(logLevel)) {
            ConfigurationProperties.logLevel(logLevel);
        }
        if (skip) {
            getLog().info("Skipping plugin execution");
        } else {
            if (getLog().isInfoEnabled()) {
                getLog().info("mockserver:run about to start MockServer on: "
                        + (getServerPorts() != null ? " serverPort " + Arrays.toString(getServerPorts()) : "")
                );
            }
            try {
                getLocalMockServerInstance().start(getServerPorts(), proxyRemotePort, proxyRemoteHost, logLevel, createInitializerClass(), createInitializerJson());
            } catch (RuntimeException e) {
                // as the start goal does: a build that asked for MockServer does not carry on without it
                throw new MojoExecutionException("MockServer did not start: " + e.getMessage(), e);
            }
            try {
                if (timeout != null && timeout > 0) {
                    settableFuture.get(timeout, TimeUnit.SECONDS);
                } else {
                    settableFuture.get();
                }
            } catch (TimeoutException te) {
                // do nothing this is an expected exception when the timeout expires
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException ee) {
                getLog().error("Exception while running MockServer", ee);
            }
        }

    }
}
