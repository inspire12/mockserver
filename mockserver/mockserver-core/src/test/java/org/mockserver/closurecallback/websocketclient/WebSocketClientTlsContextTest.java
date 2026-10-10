package org.mockserver.closurecallback.websocketclient;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.lang.reflect.Field;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.sameInstance;

/**
 * The three-argument WebSocketClient builds its TLS context factory once and reuses it for every secure connection.
 */
public class WebSocketClientTlsContextTest {

    @Test
    public void shouldReuseOneTlsContextFactoryAcrossConnections() throws Exception {
        NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup(1);
        try {
            WebSocketClient<?> client = new WebSocketClient<>(eventLoopGroup, "client-id", new MockServerLogger());

            Supplier<SslContext> sslContext = sslContextSupplier(client);

            assertThat(sslContext.get(), sameInstance(sslContext.get()));
        } finally {
            eventLoopGroup.shutdownGracefully(0, 0, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    @SuppressWarnings("unchecked")
    private static Supplier<SslContext> sslContextSupplier(WebSocketClient<?> client) throws Exception {
        Field field = WebSocketClient.class.getDeclaredField("sslContextSupplier");
        field.setAccessible(true);
        return (Supplier<SslContext>) field.get(client);
    }
}
