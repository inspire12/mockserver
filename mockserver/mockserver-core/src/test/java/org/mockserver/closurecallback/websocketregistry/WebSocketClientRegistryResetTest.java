package org.mockserver.closurecallback.websocketregistry;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class WebSocketClientRegistryResetTest {

    /*
     * MockServer unregisters a callback client when its channel closes; a channel on the resetting thread's
     * event loop closes, and so unregisters, while reset() is still closing the others.
     */
    @Test
    public void shouldCloseEveryClientWhoseChannelUnregistersItAsItCloses() {
        WebSocketClientRegistry registry = new WebSocketClientRegistry(Configuration.configuration(), new MockServerLogger());
        List<EmbeddedChannel> channels = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
            String clientId = "client-" + i;
            registry.registerClient(clientId, channel.pipeline().firstContext());
            channel.closeFuture().addListener(future -> registry.unregisterClient(clientId));
            channels.add(channel);
        }
        assertThat(registry.size(), is(3));

        registry.reset();

        assertThat(registry.size(), is(0));
        for (EmbeddedChannel channel : channels) {
            assertThat(channel.isOpen(), is(false));
            channel.finishAndReleaseAll();
        }
    }
}
