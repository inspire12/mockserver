package org.mockserver.configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.serialization.model.ConfigurationDTO;

import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * Resolution of {@code mockserver.inboundConnectionIdleTimeoutMillis} and {@code mockserver.maxInboundConnections}:
 * defaults, the raw {@code -Dmockserver.<name>} form, the programmatic setters, the negative clamp, the
 * {@link Configuration} instance route and the {@link ConfigurationDTO} round trip.
 * <p>
 * Mutates the process-wide {@link ConfigurationProperties} store, so it is registered in the sequential
 * phase of {@code mockserver-core/pom.xml}; each reset clears the property cache as well (see
 * {@link WatchInitializationJsonPollPeriodMillisTest} for why a raw {@link System#clearProperty} is not enough).
 */
public class InboundConnectionConfigurationTest {

    private static final String IDLE_KEY = "mockserver.inboundConnectionIdleTimeoutMillis";
    private static final String MAX_KEY = "mockserver.maxInboundConnections";

    @Before
    @After
    public void resetProperties() throws Exception {
        for (String key : new String[]{IDLE_KEY, MAX_KEY}) {
            System.clearProperty(key);
            clearCacheEntry(key);
            clearProgrammaticallySetKey(key);
        }
    }

    @Test
    public void shouldDefaultToFiveMinuteIdleTimeoutAndNoConnectionLimit() {
        assertThat(ConfigurationProperties.inboundConnectionIdleTimeoutMillis(), is(300_000L));
        assertThat(ConfigurationProperties.maxInboundConnections(), is(0));
        assertThat(Configuration.configuration().inboundConnectionIdleTimeoutMillis(), is(300_000L));
        assertThat(Configuration.configuration().maxInboundConnections(), is(0));
    }

    @Test
    public void shouldResolveRawSystemProperties() throws Exception {
        System.setProperty(IDLE_KEY, "1234");
        System.setProperty(MAX_KEY, "56");
        clearCacheEntry(IDLE_KEY);
        clearCacheEntry(MAX_KEY);

        assertThat(ConfigurationProperties.inboundConnectionIdleTimeoutMillis(), is(1234L));
        assertThat(ConfigurationProperties.maxInboundConnections(), is(56));
    }

    @Test
    public void shouldReturnProgrammaticallyOverriddenValues() {
        ConfigurationProperties.inboundConnectionIdleTimeoutMillis(0);
        ConfigurationProperties.maxInboundConnections(10_000);

        assertThat(ConfigurationProperties.inboundConnectionIdleTimeoutMillis(), is(0L));
        assertThat(ConfigurationProperties.maxInboundConnections(), is(10_000));
    }

    @Test
    public void shouldClampNegativeValuesToDisabled() {
        ConfigurationProperties.inboundConnectionIdleTimeoutMillis(-1);
        ConfigurationProperties.maxInboundConnections(-1);

        assertThat(ConfigurationProperties.inboundConnectionIdleTimeoutMillis(), is(0L));
        assertThat(ConfigurationProperties.maxInboundConnections(), is(0));
        assertThat(Configuration.configuration().inboundConnectionIdleTimeoutMillis(-5L).inboundConnectionIdleTimeoutMillis(), is(0L));
        assertThat(Configuration.configuration().maxInboundConnections(-5).maxInboundConnections(), is(0));
    }

    @Test
    public void shouldPreferInstanceValueOverStaticStore() {
        ConfigurationProperties.inboundConnectionIdleTimeoutMillis(1_000);
        ConfigurationProperties.maxInboundConnections(100);
        Configuration configuration = Configuration.configuration();

        assertThat(configuration.inboundConnectionIdleTimeoutMillis(), is(1_000L));
        assertThat(configuration.maxInboundConnections(), is(100));

        configuration.inboundConnectionIdleTimeoutMillis(2_000L).maxInboundConnections(200);

        assertThat(configuration.inboundConnectionIdleTimeoutMillis(), is(2_000L));
        assertThat(configuration.maxInboundConnections(), is(200));
    }

    @Test
    public void shouldApplyValuesFromConfigurationDto() {
        Configuration target = Configuration.configuration();

        new ConfigurationDTO()
            .setInboundConnectionIdleTimeoutMillis(7_000L)
            .setMaxInboundConnections(70)
            .applyTo(target);

        assertThat(target.inboundConnectionIdleTimeoutMillis(), is(7_000L));
        assertThat(target.maxInboundConnections(), is(70));
    }

    @SuppressWarnings("unchecked")
    private static void clearCacheEntry(String key) throws Exception {
        java.lang.reflect.Field cacheField = ConfigurationProperties.class.getDeclaredField("propertyCache");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(null);
        if (cache instanceof Map) {
            ((Map<String, String>) cache).remove(key);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearProgrammaticallySetKey(String key) throws Exception {
        java.lang.reflect.Field keysField = ConfigurationProperties.class.getDeclaredField("programmaticallySetKeys");
        keysField.setAccessible(true);
        Object keys = keysField.get(null);
        if (keys instanceof Set) {
            ((Set<String>) keys).remove(key);
        }
    }
}
