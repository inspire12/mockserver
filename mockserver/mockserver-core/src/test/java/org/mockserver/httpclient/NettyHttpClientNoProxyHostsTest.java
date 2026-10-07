package org.mockserver.httpclient;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.Type.HTTP;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;

/**
 * Whether a request goes through forwardHttpProxy, which forwardProxyBlockPrivateNetworks uses to decide whether the
 * Host header, sent to that proxy as the URI, is checked too: not for a destination on noProxyHosts.
 */
public class NettyHttpClientNoProxyHostsTest {

    private static NettyHttpClient clientThroughHttpProxy(String noProxyHosts) {
        return new NettyHttpClient(configuration().noProxyHosts(noProxyHosts), new MockServerLogger(), (io.netty.channel.EventLoopGroup) null, Collections.singletonList(proxyConfiguration(HTTP, "127.0.0.1:1")), true);
    }

    private static HttpRequest withHost(String host) {
        return request().withHeader("Host", host);
    }

    @Test
    public void shouldNotSendHostHeaderDestinationOnTheListThroughHttpProxy() {
        NettyHttpClient client = clientThroughHttpProxy("localhost,*.internal.test,10.0.0.1");

        assertThat(client.sendsThroughHttpProxy(withHost("localhost:8080"), null), is(false));
        assertThat(client.sendsThroughHttpProxy(withHost("api.internal.test"), null), is(false));
        assertThat(client.sendsThroughHttpProxy(withHost("10.0.0.1:80"), null), is(false));
        assertThat(client.sendsThroughHttpProxy(withHost("example.com"), null), is(true));
    }

    @Test
    public void shouldNotSendRemoteAddressOnTheListThroughHttpProxy() {
        NettyHttpClient client = clientThroughHttpProxy("*.internal.test");

        assertThat(client.sendsThroughHttpProxy(withHost("example.com"), InetSocketAddress.createUnresolved("api.internal.test", 80)), is(false));
        assertThat(client.sendsThroughHttpProxy(withHost("api.internal.test"), InetSocketAddress.createUnresolved("example.com", 80)), is(true));
    }

    @Test
    public void shouldNotMatchIpAddressEntryToNamedDestinationThatCarriesThatAddress() throws Exception {
        NettyHttpClient client = clientThroughHttpProxy("10.0.0.1");
        InetSocketAddress named = new InetSocketAddress(InetAddress.getByAddress("api.internal.test", new byte[]{10, 0, 0, 1}), 80);

        assertThat(client.sendsThroughHttpProxy(withHost("api.internal.test"), named), is(true));
    }

    @Test
    public void shouldSendNothingSecureThroughHttpProxy() {
        assertThat(clientThroughHttpProxy("").sendsThroughHttpProxy(withHost("example.com").withSecure(true), null), is(false));
        assertThat(clientThroughHttpProxy("").sendsThroughHttpProxy(withHost("example.com"), null), is(true));
    }

    @Test
    public void shouldTreatRequestNamingNoDestinationAsNotOnTheList() {
        assertThat(clientThroughHttpProxy("localhost").sendsThroughHttpProxy(request(), null), is(true));
    }
}
