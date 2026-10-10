package org.mockserver.netty;

import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.NotSslRecordException;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.mcp.McpSessionManager;
import org.mockserver.netty.mcp.McpStreamableHttpHandler;
import org.mockserver.netty.proxy.BinaryRequestProxyingHandler;
import org.mockserver.netty.proxy.connect.HttpConnectHandler;
import org.mockserver.netty.proxy.relay.DownstreamProxyRelayHandler;
import org.mockserver.netty.proxy.relay.UpstreamProxyRelayHandler;
import org.mockserver.netty.proxy.socks.Socks5ProxyHandler;
import org.mockserver.netty.unification.PortUnificationHandler;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.slf4j.event.Level;

import javax.net.ssl.SSLHandshakeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * An SSL or decoder fault's log entry keeps its stack trace and says how many bytes were not a TLS record, not which:
 * Netty's JDK TLS handler puts a hex dump of every byte read into the exception's message, and so into the entry of
 * each handler that attaches the exception. An exception whose messages are short is attached as it is.
 */
public class SslFaultLogEntryBoundTest {

    private static final int BYTES_READ = 60_000;

    private final List<LogEntry> logged = new ArrayList<>();
    private final MockServerLogger mockServerLogger = new MockServerLogger(SslFaultLogEntryBoundTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return true;
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry);
        }
    };

    private Map<String, Supplier<ChannelHandler>> handlers() {
        HttpState httpState = mock(HttpState.class, RETURNS_DEEP_STUBS);
        when(httpState.getMockServerLogger()).thenReturn(mockServerLogger);
        LifeCycle server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        Map<String, Supplier<ChannelHandler>> handlers = new LinkedHashMap<>();
        handlers.put("HttpRequestHandler", () -> new HttpRequestHandler(mock(Configuration.class), server, httpState, mock(HttpActionHandler.class)));
        handlers.put("CallbackWebSocketServerHandler", () -> new CallbackWebSocketServerHandler(httpState));
        handlers.put("DashboardWebSocketHandler", () -> new DashboardWebSocketHandler(httpState, false, false));
        handlers.put("McpStreamableHttpHandler", () -> new McpStreamableHttpHandler(httpState, server, mock(McpSessionManager.class)));
        handlers.put("Socks5ProxyHandler", () -> new Socks5ProxyHandler(configuration(), mockServerLogger, server));
        handlers.put("PortUnificationHandler", () -> portUnificationHandler(httpState, server));
        handlers.put("BinaryRequestProxyingHandler", () -> new BinaryRequestProxyingHandler(mock(Configuration.class), mockServerLogger, mock(Scheduler.class), mock(NettyHttpClient.class), httpState));
        handlers.put("HttpConnectHandler", () -> new HttpConnectHandler(configuration(), server, mockServerLogger, "backend.example.com", 443));
        handlers.put("UpstreamProxyRelayHandler", () -> new UpstreamProxyRelayHandler(mockServerLogger, new EmbeddedChannel(), new EmbeddedChannel(), "backend.example.com", 443, 1024));
        handlers.put("DownstreamProxyRelayHandler", () -> new DownstreamProxyRelayHandler(mockServerLogger, new EmbeddedChannel()));
        return handlers;
    }

    @Test
    public void shouldLogBytesThatAreNotATlsRecordAsACountWithTheStackTrace() {
        handlers().forEach((name, handler) -> {
            NotSslRecordException notTls = new NotSslRecordException("not an SSL/TLS record: " + "41".repeat(BYTES_READ));
            Throwable fault = new DecoderException(notTls);
            EmbeddedChannel channel = new EmbeddedChannel(handler.get());
            logged.clear();

            channel.pipeline().fireExceptionCaught(fault);
            channel.runPendingTasks();

            assertThat(name, logged, hasSize(1));
            assertThat(name, logged.get(0).getLogLevel(), is(Level.WARN));
            String entry = logged.get(0).getMessage(configuration());
            assertThat(name, entry, not(containsString("4141")));
            assertThat(name, entry.length(), lessThan(BYTES_READ));
            assertThat(name, logged.get(0).getThrowable().toString(), is("io.netty.handler.codec.DecoderException: io.netty.handler.ssl.NotSslRecordException: not an SSL/TLS record: " + BYTES_READ + " bytes"));
            assertThat(name, logged.get(0).getThrowable().getStackTrace(), is(fault.getStackTrace()));
            assertThat(name, logged.get(0).getThrowable().getCause().getStackTrace(), is(notTls.getStackTrace()));
            assertThat(name + " closes the connection", channel.isOpen(), is(false));
            channel.finishAndReleaseAll();
        });
    }

    @Test
    public void shouldBoundAFailedTlsHandshakesEntryAtThePortItArrivedOnAndAttachNoStackTrace() {
        HttpState httpState = mock(HttpState.class, RETURNS_DEEP_STUBS);
        when(httpState.getMockServerLogger()).thenReturn(mockServerLogger);
        // the client distrusts MockServer's CA, or the handshake failed for another reason
        for (String alert : new String[]{"Received fatal alert: certificate_unknown ", "Received fatal alert: handshake_failure "}) {
            SSLHandshakeException handshake = new SSLHandshakeException(alert + "x".repeat(BYTES_READ));
            Throwable fault = new DecoderException(handshake);
            EmbeddedChannel channel = new EmbeddedChannel(portUnificationHandler(httpState, mock(LifeCycle.class)));
            logged.clear();

            channel.pipeline().fireExceptionCaught(fault);
            channel.runPendingTasks();

            assertThat(alert, logged, hasSize(1));
            assertThat(alert, logged.get(0).getLogLevel(), is(Level.WARN));
            String entry = logged.get(0).getMessage(configuration());
            assertThat(alert, entry, containsString(alert));
            assertThat(alert, entry.length(), lessThan(1_500));
            assertThat(alert, logged.get(0).getThrowable(), is(nullValue()));
            assertThat(alert + " closes the connection", channel.isOpen(), is(false));
            channel.finishAndReleaseAll();
        }
    }

    private static PortUnificationHandler portUnificationHandler(HttpState httpState, LifeCycle server) {
        return new PortUnificationHandler(mock(Configuration.class), server, httpState, mock(HttpActionHandler.class), mock(NettySslContextFactory.class), null);
    }

    @Test
    public void shouldAttachAFaultWithShortMessagesAsItIs() {
        handlers().forEach((name, handler) -> {
            Throwable fault = new DecoderException("bad frame");
            EmbeddedChannel channel = new EmbeddedChannel(handler.get());
            logged.clear();

            channel.pipeline().fireExceptionCaught(fault);
            channel.runPendingTasks();

            assertThat(name, logged, hasSize(1));
            assertThat(name, logged.get(0).getThrowable(), is(sameInstance(fault)));
            channel.finishAndReleaseAll();
        });
    }
}
