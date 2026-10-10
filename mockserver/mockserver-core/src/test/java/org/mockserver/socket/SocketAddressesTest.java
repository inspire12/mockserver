package org.mockserver.socket;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import java.net.InetSocketAddress;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.socket.SocketAddresses.PROXY_PROTOCOL_SOURCE;

public class SocketAddressesTest {

    @Test
    public void shouldGiveTheConnectionsPeerWhenNoProxyProtocolHeaderNamedAClient() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            assertThat(SocketAddresses.clientAddress(channel), is(sameInstance(channel.remoteAddress())));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldGiveTheClientAProxyProtocolHeaderNamed() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            InetSocketAddress source = new InetSocketAddress("10.9.8.7", 45678);
            channel.attr(PROXY_PROTOCOL_SOURCE).set(source);

            assertThat(SocketAddresses.clientAddress(channel), is(sameInstance(source)));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldGiveTheConnectionsPeerWhenTheProxyProtocolSourceIsUnset() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            channel.attr(PROXY_PROTOCOL_SOURCE).set(null);

            assertThat(SocketAddresses.clientAddress(channel), is(sameInstance(channel.remoteAddress())));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
