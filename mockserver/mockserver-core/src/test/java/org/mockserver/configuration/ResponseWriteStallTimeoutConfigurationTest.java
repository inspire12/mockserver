package org.mockserver.configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.ConfigurationDTO;

import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * Resolution of {@code mockserver.responseWriteStallTimeoutMillis}: the default, the raw {@code -Dmockserver.<name>}
 * form, the programmatic setter, the negative clamp, the {@link Configuration} instance route and the
 * {@link ConfigurationDTO} round trip that the runtime configuration {@code PUT} uses.
 * <p>
 * Mutates the process-wide {@link ConfigurationProperties} store, so it is registered in the sequential phase of
 * {@code mockserver-core/pom.xml}; each reset clears the property cache as well.
 */
public class ResponseWriteStallTimeoutConfigurationTest {

    private static final String KEY = "mockserver.responseWriteStallTimeoutMillis";

    @Before
    @After
    public void resetProperties() throws Exception {
        System.clearProperty(KEY);
        clearCacheEntry(KEY);
        clearProgrammaticallySetKey(KEY);
    }

    @Test
    public void shouldDefaultToOneMinute() {
        assertThat(ConfigurationProperties.responseWriteStallTimeoutMillis(), is(60_000L));
        assertThat(Configuration.configuration().responseWriteStallTimeoutMillis(), is(60_000L));
    }

    @Test
    public void shouldResolveRawSystemProperty() throws Exception {
        System.setProperty(KEY, "1234");
        clearCacheEntry(KEY);

        assertThat(ConfigurationProperties.responseWriteStallTimeoutMillis(), is(1234L));
        assertThat(Configuration.configuration().responseWriteStallTimeoutMillis(), is(1234L));
    }

    @Test
    public void shouldReturnProgrammaticallyOverriddenValueAndAllowDisabling() {
        ConfigurationProperties.responseWriteStallTimeoutMillis(0);

        assertThat(ConfigurationProperties.responseWriteStallTimeoutMillis(), is(0L));
    }

    @Test
    public void shouldClampNegativeValuesToDisabled() {
        ConfigurationProperties.responseWriteStallTimeoutMillis(-1);

        assertThat(ConfigurationProperties.responseWriteStallTimeoutMillis(), is(0L));
        assertThat(Configuration.configuration().responseWriteStallTimeoutMillis(-5L).responseWriteStallTimeoutMillis(), is(0L));
    }

    @Test
    public void shouldPreferInstanceValueOverStaticStore() {
        ConfigurationProperties.responseWriteStallTimeoutMillis(1_000);
        Configuration configuration = Configuration.configuration();

        assertThat(configuration.responseWriteStallTimeoutMillis(), is(1_000L));

        configuration.responseWriteStallTimeoutMillis(2_000L);

        assertThat(configuration.responseWriteStallTimeoutMillis(), is(2_000L));
    }

    @Test
    public void shouldApplyValueFromConfigurationDtoAsTheRuntimePutDoes() throws Exception {
        Configuration target = Configuration.configuration().responseWriteStallTimeoutMillis(5_000L);

        ConfigurationDTO dto = ObjectMapperFactory.createObjectMapper().readValue("{\"responseWriteStallTimeoutMillis\": 7000}", ConfigurationDTO.class);
        dto.applyTo(target);

        assertThat(target.responseWriteStallTimeoutMillis(), is(7_000L));
        assertThat(new ConfigurationDTO(target).getResponseWriteStallTimeoutMillis(), is(7_000L));
    }

    @Test
    public void shouldLeaveValueUnchangedWhenAPutOmitsIt() {
        Configuration target = Configuration.configuration().responseWriteStallTimeoutMillis(5_000L);

        new ConfigurationDTO().applyTo(target);

        assertThat(target.responseWriteStallTimeoutMillis(), is(5_000L));
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
