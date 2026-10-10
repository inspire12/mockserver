package org.mockserver.proxyconfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.SocketAddress;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.HttpRequest.request;

/**
 * Behaviour: the validator blocks SSRF-suspect targets when
 * {@code forwardProxyBlockPrivateNetworks=true}, and is a no-op otherwise.
 * Tests use the per-instance Configuration so they don't depend on global state.
 */
public class InetAddressValidatorTest {

    private Configuration enabled;
    private Configuration disabled;

    @Before
    public void setUp() {
        enabled = Configuration.configuration().forwardProxyBlockPrivateNetworks(true);
        disabled = Configuration.configuration().forwardProxyBlockPrivateNetworks(false);
    }

    @After
    public void tearDown() {
        // ensure system property is not lingering between test methods
        ConfigurationProperties.forwardProxyBlockPrivateNetworks(false);
    }

    @Test
    public void shouldAllowPublicLiteralAddressWhenEnabled() {
        InetAddressValidator.validateForwardTarget(enabled, "8.8.8.8");
    }

    @Test
    public void shouldBlockLoopbackLiteralWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "127.0.0.1"));
        assertThat(ex.getMessage(), containsString("loopback"));
    }

    @Test
    public void shouldBlockLoopbackHostNameWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "localhost"));
        assertThat(ex.getMessage(), containsString("loopback"));
    }

    @Test
    public void shouldBlockIpv6LoopbackWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "::1"));
        assertThat(ex.getMessage(), containsString("loopback"));
    }

    @Test
    public void shouldBlockBracketedIpv6LoopbackWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "[::1]"));
        assertThat(ex.getMessage(), containsString("loopback"));
    }

    @Test
    public void shouldBlockRfc1918TenWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "10.0.0.1"));
        assertThat(ex.getMessage(), containsString("private"));
    }

    @Test
    public void shouldBlockRfc1918OneSeventyTwoWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "172.16.0.1"));
        assertThat(ex.getMessage(), containsString("private"));
    }

    @Test
    public void shouldBlockRfc1918OneNinetyTwoWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "192.168.1.1"));
        assertThat(ex.getMessage(), containsString("private"));
    }

    @Test
    public void shouldBlockCloudMetadataEndpointWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "169.254.169.254"));
        assertThat(ex.getMessage(), containsString("metadata"));
    }

    @Test
    public void shouldBlockIpv6UniqueLocalAddressWhenEnabled() {
        // RFC 4193 ULA range (fc00::/7) is not covered by Java's isSiteLocalAddress()
        // — common Docker / Kubernetes / Tailscale IPv6 prefix. Must be blocked explicitly.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "fd00::1"));
        assertThat(ex.getMessage(), containsString("private"));
    }

    @Test
    public void shouldBlockCarrierGradeNatAddressesWhenEnabled() throws UnknownHostException {
        // RFC 6598 shared address space 100.64.0.0/10, used by carrier-grade NAT and Tailscale
        for (String address : new String[]{"100.64.0.0", "100.64.0.1", "100.100.100.100", "100.127.255.255", "::ffff:100.64.0.1", "[::ffff:100.127.0.1]"}) {
            ForwardTargetBlockedException ex = assertThrows(address, ForwardTargetBlockedException.class,
                () -> InetAddressValidator.validateForwardTarget(enabled, address));
            assertThat(ex.getMessage(), containsString("carrier-grade NAT"));
        }
        assertThrows(ForwardTargetBlockedException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, InetAddress.getByName("100.64.0.1")));
        assertThrows(ForwardTargetBlockedException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, new InetSocketAddress("100.64.0.1", 443)));
    }

    @Test
    public void shouldAllowAddressesEitherSideOfTheCarrierGradeNatRangeWhenEnabled() {
        for (String address : new String[]{"100.63.255.255", "100.128.0.0", "100.0.0.1", "100.255.255.255", "36.64.0.1", "::ffff:100.128.0.1"}) {
            InetAddressValidator.validateForwardTarget(enabled, address);
        }
    }

    @Test
    public void shouldAllowCarrierGradeNatAddressesWhenDisabled() {
        InetAddressValidator.validateForwardTarget(disabled, "100.64.0.1");
    }

    @Test
    public void shouldBlockWildcardAddressWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "0.0.0.0"));
        assertThat(ex.getMessage(), containsString("wildcard"));
    }

    @Test
    public void shouldRejectUnresolvableHostWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "no-such-host-for-mockserver-ssrf.invalid"));
        assertThat(ex.getMessage(), containsString("could not be resolved"));
    }

    @Test
    public void shouldAllowEverythingWhenDisabled() {
        // none of these should throw
        InetAddressValidator.validateForwardTarget(disabled, "127.0.0.1");
        InetAddressValidator.validateForwardTarget(disabled, "localhost");
        InetAddressValidator.validateForwardTarget(disabled, "10.0.0.1");
        InetAddressValidator.validateForwardTarget(disabled, "169.254.169.254");
        InetAddressValidator.validateForwardTarget(disabled, "0.0.0.0");
    }

    @Test
    public void shouldTreatBlankHostAsNoOp() {
        // ambiguous input should not raise — caller will surface its own error
        InetAddressValidator.validateForwardTarget(enabled, "");
        InetAddressValidator.validateForwardTarget(enabled, (String) null);
    }

    // ---- InetAddress overload (validate the SAME resolved address that is connected) ----

    @Test
    public void shouldBlockLoopbackInetAddressWhenEnabled() throws UnknownHostException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, loopback));
        assertThat(ex.getMessage(), containsString("loopback"));
    }

    @Test
    public void shouldBlockCloudMetadataInetAddressWhenEnabled() throws UnknownHostException {
        InetAddress metadata = InetAddress.getByName("169.254.169.254");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, metadata));
        assertThat(ex.getMessage(), containsString("metadata"));
    }

    @Test
    public void shouldBlockRfc1918InetAddressWhenEnabled() throws UnknownHostException {
        InetAddress priv = InetAddress.getByName("10.1.2.3");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, priv));
        assertThat(ex.getMessage(), containsString("private"));
    }

    @Test
    public void shouldAllowPublicInetAddressWhenEnabled() throws UnknownHostException {
        // literal public address so no DNS dependency; must not throw
        InetAddressValidator.validateForwardTarget(enabled, InetAddress.getByName("8.8.8.8"));
    }

    @Test
    public void shouldBeNoOpForInetAddressOverloadWhenDisabled() throws UnknownHostException {
        // even a loopback address passes when the feature is disabled
        InetAddressValidator.validateForwardTarget(disabled, InetAddress.getByName("127.0.0.1"));
    }

    @Test
    public void shouldTreatNullInetAddressAsNoOp() {
        InetAddressValidator.validateForwardTarget(enabled, (InetAddress) null);
    }

    @Test
    public void shouldBlockALoopbackSocketAddressWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, new InetSocketAddress("127.0.0.1", 5432)));
        assertThat(ex.getMessage(), containsString("loopback"));
    }

    @Test
    public void shouldBlockASocketAddressWhoseNameResolvesToABlockedAddress() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, InetSocketAddress.createUnresolved("localhost", 5432)));
        assertThat(ex.getMessage(), containsString("loopback"));
    }

    @Test
    public void shouldRejectASocketAddressWhoseNameDoesNotResolveWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, InetSocketAddress.createUnresolved("no-such-host-for-mockserver-ssrf.invalid", 5432)));
        assertThat(ex.getMessage(), containsString("could not be resolved"));
    }

    @Test
    public void shouldReturnTheResolvedAddressItCheckedSoThatItIsTheOneConnectedTo() {
        InetSocketAddress target = InetSocketAddress.createUnresolved("8.8.8.8", 5432);

        InetSocketAddress toConnectTo = InetAddressValidator.validateForwardTarget(enabled, target);

        assertThat(toConnectTo.isUnresolved(), is(false));
        assertThat(toConnectTo.getAddress().getHostAddress(), is("8.8.8.8"));
        assertThat(toConnectTo.getPort(), is(5432));
    }

    @Test
    public void shouldReturnASocketAddressAsItWasGivenWhenDisabled() {
        InetSocketAddress loopback = new InetSocketAddress("127.0.0.1", 5432);
        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("no-such-host-for-mockserver-ssrf.invalid", 5432);

        assertThat(InetAddressValidator.validateForwardTarget(disabled, loopback), is(sameInstance(loopback)));
        assertThat("and no name is looked up", InetAddressValidator.validateForwardTarget(disabled, unresolved), is(sameInstance(unresolved)));
        assertThat(InetAddressValidator.validateForwardTarget(enabled, (InetSocketAddress) null), is(nullValue()));
    }

    @Test
    public void shouldCheckTheAddressAForwardedRequestIsSentToWhenThereIsOne() {
        HttpRequest publicHost = request().withHeader("Host", "8.8.8.8");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, publicHost, InetSocketAddress.createUnresolved("127.0.0.1", 80), false));
        assertThat(ex.getMessage(), containsString("Forward to loopback address blocked: 127.0.0.1"));
        InetAddressValidator.validateForwardTarget(enabled, request().withHeader("Host", "127.0.0.1"), InetSocketAddress.createUnresolved("8.8.8.8", 80), false);
    }

    @Test
    public void shouldCheckTheSocketAddressOrHostHeaderOfAForwardedRequestWithNoAddress() {
        IllegalArgumentException byHostHeader = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, request().withHeader("Host", "localhost:1080"), null, false));
        assertThat(byHostHeader.getMessage(), containsString("Forward to loopback address blocked: localhost"));
        IllegalArgumentException bySocketAddress = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, request().withHeader("Host", "8.8.8.8").withSocketAddress("169.254.169.254", 80, SocketAddress.Scheme.HTTP), null, false));
        assertThat(bySocketAddress.getMessage(), containsString("cloud metadata"));
        InetAddressValidator.validateForwardTarget(enabled, request().withHeader("Host", "8.8.8.8:80"), null, false);
    }

    @Test
    public void shouldAlsoCheckTheHostHeaderOfARequestSentThroughForwardHttpProxy() {
        HttpRequest privateHostHeader = request().withHeader("Host", "10.0.0.1:8080");
        InetSocketAddress publicAddress = InetSocketAddress.createUnresolved("8.8.8.8", 80);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, privateHostHeader, publicAddress, true));
        assertThat(ex.getMessage(), containsString("Forward to private network blocked: 10.0.0.1"));
        InetAddressValidator.validateForwardTarget(enabled, privateHostHeader, publicAddress, false);
    }

    @Test
    public void shouldLeaveAForwardedRequestThatNamesNoDestinationToFailWhereItIsSent() {
        InetAddressValidator.validateForwardTarget(enabled, request(), null, true);
        InetAddressValidator.validateForwardTarget(enabled, request().withHeader("Host", ":::"), null, true);
    }

    @Test
    public void shouldNotCheckAForwardedRequestWhenDisabled() {
        InetAddressValidator.validateForwardTarget(disabled, request().withHeader("Host", "127.0.0.1"), InetSocketAddress.createUnresolved("127.0.0.1", 80), true);
    }
}
