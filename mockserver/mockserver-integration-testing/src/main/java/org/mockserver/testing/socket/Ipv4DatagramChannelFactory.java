package org.mockserver.testing.socket;

import io.netty.channel.ChannelFactory;
import io.netty.channel.socket.SocketProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;

/**
 * IPv4-only {@link NioDatagramChannel}s for test UDP sockets that talk over {@code 127.0.0.1}; use
 * {@code .channelFactory(Ipv4DatagramChannelFactory.INSTANCE)} instead of {@code .channel(NioDatagramChannel.class)}.
 * On macOS a dual-stack socket bound to port 0 can share its port with another process's IPv4 socket, which
 * then receives the replies. See docs/code/http3.md, "Test UDP sockets (macOS port shadowing)".
 */
public final class Ipv4DatagramChannelFactory implements ChannelFactory<NioDatagramChannel> {

    public static final Ipv4DatagramChannelFactory INSTANCE = new Ipv4DatagramChannelFactory();

    private Ipv4DatagramChannelFactory() {
    }

    @Override
    public NioDatagramChannel newChannel() {
        return new NioDatagramChannel(SocketProtocolFamily.INET);
    }
}
