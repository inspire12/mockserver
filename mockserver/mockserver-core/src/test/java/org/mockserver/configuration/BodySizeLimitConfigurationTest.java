package org.mockserver.configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * {@code maxRequestBodySize} and {@code maxResponseBodySize} are never read as less than 1. An aggregator refuses
 * every body at a limit of zero and cannot be built with a negative one, while the bounds on bodies that are not
 * aggregated (a streamed response, an HTTP/3 request, a raw Snappy block) read zero or less as "no limit", so a
 * value below 1 would otherwise remove those bounds.
 * <p>
 * Mutates the process-wide {@link ConfigurationProperties} store, so it is registered in the sequential
 * phase of {@code mockserver-core/pom.xml}.
 */
public class BodySizeLimitConfigurationTest {

    private static final String REQUEST_KEY = "mockserver.maxRequestBodySize";
    private static final String RESPONSE_KEY = "mockserver.maxResponseBodySize";

    @Before
    @After
    public void resetProperties() throws Exception {
        for (String key : new String[]{REQUEST_KEY, RESPONSE_KEY}) {
            System.clearProperty(key);
            clearCacheEntry(key);
            clearProgrammaticallySetKey(key);
        }
    }

    @Test
    public void shouldKeepTheDefaults() {
        assertThat(ConfigurationProperties.maxRequestBodySize(), is(10 * 1024 * 1024));
        assertThat(ConfigurationProperties.maxResponseBodySize(), is(50 * 1024 * 1024));
        assertThat(Configuration.configuration().maxRequestBodySize(), is(10 * 1024 * 1024));
        assertThat(Configuration.configuration().maxResponseBodySize(), is(50 * 1024 * 1024));
    }

    @Test
    public void shouldReadZeroAsOneByteFromTheStaticStore() {
        ConfigurationProperties.maxRequestBodySize(0);
        ConfigurationProperties.maxResponseBodySize(0);

        assertThat(ConfigurationProperties.maxRequestBodySize(), is(1));
        assertThat(ConfigurationProperties.maxResponseBodySize(), is(1));
        assertThat(Configuration.configuration().maxRequestBodySize(), is(1));
        assertThat(Configuration.configuration().maxResponseBodySize(), is(1));
    }

    @Test
    public void shouldReadANegativeValueAsOneByteFromTheStaticStore() {
        ConfigurationProperties.maxRequestBodySize(-1);
        ConfigurationProperties.maxResponseBodySize(Integer.MIN_VALUE);

        assertThat(ConfigurationProperties.maxRequestBodySize(), is(1));
        assertThat(ConfigurationProperties.maxResponseBodySize(), is(1));
    }

    @Test
    public void shouldReadZeroAsOneByteFromARawSystemProperty() throws Exception {
        System.setProperty(REQUEST_KEY, "0");
        System.setProperty(RESPONSE_KEY, "0");
        clearCacheEntry(REQUEST_KEY);
        clearCacheEntry(RESPONSE_KEY);

        assertThat(ConfigurationProperties.maxRequestBodySize(), is(1));
        assertThat(ConfigurationProperties.maxResponseBodySize(), is(1));
    }

    @Test
    public void shouldReadZeroOrLessAsOneByteFromAConfigurationInstance() {
        assertThat(Configuration.configuration().maxRequestBodySize(0).maxRequestBodySize(), is(1));
        assertThat(Configuration.configuration().maxResponseBodySize(0).maxResponseBodySize(), is(1));
        assertThat(Configuration.configuration().maxRequestBodySize(-5).maxRequestBodySize(), is(1));
        assertThat(Configuration.configuration().maxResponseBodySize(-5).maxResponseBodySize(), is(1));
    }

    @Test
    public void shouldKeepAPositiveValue() {
        ConfigurationProperties.maxRequestBodySize(2);
        ConfigurationProperties.maxResponseBodySize(3);

        assertThat(ConfigurationProperties.maxRequestBodySize(), is(2));
        assertThat(ConfigurationProperties.maxResponseBodySize(), is(3));
        assertThat(Configuration.configuration().maxRequestBodySize(4).maxRequestBodySize(), is(4));
        assertThat(Configuration.configuration().maxResponseBodySize(5).maxResponseBodySize(), is(5));
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
