package org.mockserver.configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * An unrecognised binaryMessageFraming is read as RAW and reported once, not on every binary connection and
 * every configuration request that reads it. Mutates a system property, so it runs in the sequential phase.
 */
public class BinaryMessageFramingConfigurationTest {

    private static final String KEY = "mockserver.binaryMessageFraming";

    private final AtomicReference<String> reported = new AtomicReference<>();
    private final List<String> reports = new ArrayList<>();

    @Before
    @After
    public void resetProperty() throws Exception {
        System.clearProperty(KEY);
        clearCacheEntry(KEY);
        clearProgrammaticallySetKey(KEY);
    }

    private BinaryMessageFraming read(String value) {
        return ConfigurationProperties.binaryMessageFraming(value, reported, reports::add);
    }

    @Test
    public void shouldReadAnUnrecognisedValueAsRawAndReportItOnce() {
        assertThat(read("MYSQL"), is(BinaryMessageFraming.RAW));
        assertThat(read("MYSQL"), is(BinaryMessageFraming.RAW));
        assertThat(read("MYSQL"), is(BinaryMessageFraming.RAW));

        assertThat(reports, contains("MYSQL"));
    }

    @Test
    public void shouldReportAnotherUnrecognisedValueWhenTheValueChanges() {
        read("MYSQL");
        read("KAFKA");
        read("KAFKA");

        assertThat(reports, contains("MYSQL", "KAFKA"));
    }

    @Test
    public void shouldReadTheSupportedValuesInAnyCaseWithoutReportingThem() {
        assertThat(read("postgresql"), is(BinaryMessageFraming.POSTGRESQL));
        assertThat(read(" Raw "), is(BinaryMessageFraming.RAW));

        assertThat(reports, is(empty()));
    }

    @Test
    public void shouldFallBackToRawForAnUnrecognisedSystemProperty() throws Exception {
        System.setProperty(KEY, "MYSQL");
        clearCacheEntry(KEY);

        assertThat(ConfigurationProperties.binaryMessageFraming(), is(BinaryMessageFraming.RAW));
        assertThat("an unset instance value falls back to the store", new Configuration().binaryMessageFraming(), is(BinaryMessageFraming.RAW));

        System.setProperty(KEY, "postgresql");
        clearCacheEntry(KEY);

        assertThat(ConfigurationProperties.binaryMessageFraming(), is(BinaryMessageFraming.POSTGRESQL));
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
