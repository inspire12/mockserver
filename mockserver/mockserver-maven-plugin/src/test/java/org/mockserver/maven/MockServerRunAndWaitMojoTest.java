package org.mockserver.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.netty.dns.DnsStartupException;
import org.mockserver.socket.PortFactory;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * @author jamesdbloom
 */
public class MockServerRunAndWaitMojoTest {

    @Mock
    private CompletableFuture<Object> objectSettableFuture;
    @Mock
    private InstanceHolder mockInstanceHolder;
    @InjectMocks
    private MockServerRunAndWaitMojo mockServerRunAndWaitMojo = new MockServerRunAndWaitMojo();

    @Before
    public void setupMocks() {
        openMocks(this);

        MockServerAbstractMojo.instanceHolder = mockInstanceHolder;
    }

    @Test
    public void shouldRunMockServerWithNullTimeout() throws Exception {
        // given
        mockServerRunAndWaitMojo.serverPort = "1,2";
        mockServerRunAndWaitMojo.logLevel = "WARN";
        mockServerRunAndWaitMojo.pipeLogToConsole = true;
        mockServerRunAndWaitMojo.timeout = null;
        mockServerRunAndWaitMojo.initializationClass = "org.mockserver.maven.ExampleInitializationClass";

        // when
        mockServerRunAndWaitMojo.execute();

        // then
        verify(mockInstanceHolder).start(eq(new Integer[]{1, 2}), eq(-1), eq(""), eq("WARN"), any(ExampleInitializationClass.class), eq(""));
        verify(objectSettableFuture).get();
    }

    @Test
    public void shouldRunMockServerAndWaitIndefinitelyAndHandleInterruptedException() throws Exception {
        // given
        mockServerRunAndWaitMojo.serverPort = "1";
        mockServerRunAndWaitMojo.timeout = 0;
        doThrow(new InterruptedException("TEST EXCEPTION")).when(objectSettableFuture).get();

        // when
        mockServerRunAndWaitMojo.execute();

        // then
        assertThat("the interrupt is kept for the caller", Thread.interrupted(), is(true));
    }

    @Test
    public void shouldRunMockServerAndWaitForFixedPeriod() throws Exception {
        // given
        mockServerRunAndWaitMojo.serverPort = "1,2";
        mockServerRunAndWaitMojo.timeout = 2;
        mockServerRunAndWaitMojo.initializationClass = "org.mockserver.maven.ExampleInitializationClass";

        // when
        mockServerRunAndWaitMojo.execute();

        // then
        verify(mockInstanceHolder).start(eq(new Integer[]{1, 2}), eq(-1), eq(""), eq("INFO"), any(ExampleInitializationClass.class), eq(""));
        verify(objectSettableFuture).get(2, TimeUnit.SECONDS);
    }

    @Test
    public void shouldSkipStoppingMockServer() throws Exception {
        // given
        mockServerRunAndWaitMojo.skip = true;

        // when
        mockServerRunAndWaitMojo.execute();

        // then
        verifyNoMoreInteractions(mockInstanceHolder);
    }

    @Test
    public void shouldFailTheBuildWhenMockServerDoesNotStart() throws Exception {
        // given
        mockServerRunAndWaitMojo.serverPort = "1";
        mockServerRunAndWaitMojo.timeout = 0;
        RuntimeException refused = new RuntimeException("Exception while binding MockServer to port 1");
        doThrow(refused).when(mockInstanceHolder).start(any(), any(), any(), any(), any(), any());

        // when
        MojoExecutionException failed = assertThrows(MojoExecutionException.class, () -> mockServerRunAndWaitMojo.execute());

        // then
        assertThat(failed.getMessage(), is("MockServer did not start: Exception while binding MockServer to port 1"));
        assertThat(failed.getCause(), sameInstance(refused));
        verify(objectSettableFuture, never()).get();
    }

    @Test
    public void shouldFailTheBuildWhenTheServerPortIsInUse() throws Exception {
        MockServerAbstractMojo.instanceHolder = new InstanceHolder();
        try (ServerSocket otherApplication = new ServerSocket(PortFactory.findFreePort())) {
            mockServerRunAndWaitMojo.serverPort = String.valueOf(otherApplication.getLocalPort());
            mockServerRunAndWaitMojo.timeout = 1;

            MojoExecutionException failed = assertThrows(MojoExecutionException.class, () -> mockServerRunAndWaitMojo.execute());

            assertThat(failed.getMessage(), containsString("MockServer did not start"));
        } finally {
            MockServerAbstractMojo.instanceHolder.stop();
        }
    }

    @Test
    public void shouldFailTheBuildWhenTheDnsPortIsInUse() throws Exception {
        MockServerAbstractMojo.instanceHolder = new InstanceHolder();
        boolean dnsEnabled = ConfigurationProperties.dnsEnabled();
        int dnsPort = ConfigurationProperties.dnsPort();
        try (DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET)) {
            otherApplication.bind(new InetSocketAddress("127.0.0.1", 0));
            ConfigurationProperties.dnsEnabled(true);
            ConfigurationProperties.dnsPort(((InetSocketAddress) otherApplication.getLocalAddress()).getPort());
            mockServerRunAndWaitMojo.serverPort = String.valueOf(PortFactory.findFreePort());
            mockServerRunAndWaitMojo.timeout = 1;

            MojoExecutionException failed = assertThrows(MojoExecutionException.class, () -> mockServerRunAndWaitMojo.execute());

            assertThat(failed.getCause(), instanceOf(DnsStartupException.class));
            assertThat(failed.getMessage(), containsString("so MockServer cannot start"));
        } finally {
            ConfigurationProperties.dnsEnabled(dnsEnabled);
            ConfigurationProperties.dnsPort(dnsPort);
            MockServerAbstractMojo.instanceHolder.stop();
        }
    }
}
