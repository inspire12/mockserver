package org.mockserver.httpclient.netty;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.Test;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.slf4j.event.Level;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.BinaryMessage.bytes;

/**
 * A binary request over TLS to an upstream that answers with bytes that are not a TLS record is logged with the count
 * of those bytes, not a hex dump of them, in the entry's text and in the exception it attaches. The forward client's
 * TLS handler is the JDK's here, as it is without the OpenSSL native: OpenSSL's message holds no dump.
 */
public class NettyHttpClientBinaryFaultLogEntryBoundTest {

    private static final int BYTES_SENT = 20_000;

    private final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private final MockServerLogger mockServerLogger = new MockServerLogger(NettyHttpClientBinaryFaultLogEntryBoundTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return true;
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry);
        }
    };

    @Test(timeout = 60_000)
    public void shouldLogABinaryRequestToAnUpstreamThatIsNotTlsWithoutItsBytes() throws Exception {
        EventLoopGroup clientEventLoopGroup = new NioEventLoopGroup(1, new Scheduler.SchedulerThreadFactory(NettyHttpClientBinaryFaultLogEntryBoundTest.class.getSimpleName() + "-client"));
        try (ServerSocket upstream = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            Thread answer = new Thread(() -> {
                try (Socket socket = upstream.accept()) {
                    socket.setSoTimeout(30_000);
                    socket.getInputStream().read();
                    byte[] notTls = new byte[BYTES_SENT];
                    Arrays.fill(notTls, (byte) 'A');
                    socket.getOutputStream().write(notTls);
                    socket.getOutputStream().flush();
                    while (socket.getInputStream().read() != -1) {
                        continue;
                    }
                } catch (Exception closed) {
                    // the client has gone
                }
            });
            answer.setDaemon(true);
            answer.start();
            NettySslContextFactory jdkTls = new NettySslContextFactory(configuration(), mockServerLogger, false) {
                @Override
                public SslContext createClientSslContext(boolean forwardProxyClient, boolean enableHttp2, String host) {
                    try {
                        return SslContextBuilder.forClient().sslProvider(SslProvider.JDK).trustManager(InsecureTrustManagerFactory.INSTANCE).build();
                    } catch (javax.net.ssl.SSLException e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
            NettyHttpClient client = new NettyHttpClient(configuration(), mockServerLogger, clientEventLoopGroup, null, true, jdkTls);

            assertThrows(ExecutionException.class, () -> client
                .sendRequest(bytes("binary request".getBytes()), true, new InetSocketAddress("127.0.0.1", upstream.getLocalPort()), 5_000L)
                .get(30, TimeUnit.SECONDS));

            List<LogEntry> warnings = binaryRequestWarnings();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (warnings.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(50);
                warnings = binaryRequestWarnings();
            }
            assertThat(warnings, hasSize(1));
            LogEntry warning = warnings.get(0);
            StringBuilder rendered = new StringBuilder(warning.getMessage(configuration())).append('\n');
            for (Throwable throwable = warning.getThrowable(); throwable != null; throwable = throwable.getCause()) {
                rendered.append(throwable).append('\n');
            }
            assertThat(rendered.toString(), not(containsString("4141")));
            // as many bytes as the TLS handler had read when it gave up
            assertThat(rendered.toString(), java.util.regex.Pattern.compile("not an SSL/TLS record: [1-9][0-9]* bytes").matcher(rendered).find(), org.hamcrest.Matchers.is(true));
            assertThat(rendered.length(), lessThan(2_000));
        } finally {
            clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        }
    }

    private List<LogEntry> binaryRequestWarnings() {
        return logged.stream()
            .filter(entry -> entry.getLogLevel() == Level.WARN && entry.getMessageFormat() != null && entry.getMessageFormat().startsWith("exception while sending binary request"))
            .collect(Collectors.toList());
    }
}
