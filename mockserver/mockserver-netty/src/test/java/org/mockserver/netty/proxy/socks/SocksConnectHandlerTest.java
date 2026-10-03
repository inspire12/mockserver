package org.mockserver.netty.proxy.socks;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.socksx.v4.Socks4ServerEncoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.codec.HttpChunkLineLimiter;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.proxy.relay.RelayConnectHandler;
import org.mockserver.scheduler.Scheduler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;

public class SocksConnectHandlerTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger();
    private LifeCycle server;

    @Before
    public void setUp() {
        server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
    }

    @Test
    public void shouldRemoveTheHttpCodecAndChunkLineLimiterWhenASocks5TunnelStarts() {
        Socks5ConnectHandler handler = new Socks5ConnectHandler(configuration(), mockServerLogger, server, "example.com", 443);

        assertCodecSupportRemoved(handler, new EmbeddedChannel(Socks5ServerEncoder.DEFAULT));
    }

    @Test
    public void shouldRemoveTheHttpCodecAndChunkLineLimiterWhenASocks4TunnelStarts() {
        Socks4ConnectHandler handler = new Socks4ConnectHandler(configuration(), mockServerLogger, server, "example.com", 443);

        assertCodecSupportRemoved(handler, new EmbeddedChannel(Socks4ServerEncoder.INSTANCE));
    }

    private void assertCodecSupportRemoved(SocksConnectHandler<?> handler, EmbeddedChannel channel) {
        try {
            HttpChunkLineLimiter chunkLineLimiter = new HttpChunkLineLimiter(mockServerLogger);
            channel.pipeline().addLast(chunkLineLimiter.beforeCodec(), new HttpServerCodec(), chunkLineLimiter.afterCodec(), new HttpObjectAggregator(1024), handler);
            assertThat(channel.pipeline().names(), hasItems("HttpChunkLineLimiter$BeforeCodec#0", "HttpServerCodec#0", "HttpChunkLineLimiter$AfterCodec#0"));

            handler.removeCodecSupport(channel.pipeline().context(handler));

            assertThat(channel.pipeline().get(HttpServerCodec.class), is(nullValue()));
            assertThat(channel.pipeline().get(HttpObjectAggregator.class), is(nullValue()));
            assertThat(channel.pipeline().get(RelayConnectHandler.class), is(nullValue()));
            assertThat(String.valueOf(channel.pipeline().names()), channel.pipeline().names(), everyItem(not(containsString("HttpChunkLineLimiter"))));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
