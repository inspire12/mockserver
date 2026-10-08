package org.mockserver.configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteOrder;
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
 * An unrecognised binaryMessageFraming, or binaryMessageLengthPrefix* value, is read as its default and reported once,
 * not on every binary connection and every configuration request that reads it. Mutates a system property, so it runs in the sequential phase.
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
        assertThat(read("MONGODB"), is(BinaryMessageFraming.RAW));
        assertThat(read("MONGODB"), is(BinaryMessageFraming.RAW));
        assertThat(read("MONGODB"), is(BinaryMessageFraming.RAW));

        assertThat(reports, contains("MONGODB"));
    }

    @Test
    public void shouldReportAnotherUnrecognisedValueWhenTheValueChanges() {
        read("MONGODB");
        read("KAFKA");
        read("KAFKA");

        assertThat(reports, contains("MONGODB", "KAFKA"));
    }

    @Test
    public void shouldReadTheSupportedValuesInAnyCaseWithoutReportingThem() {
        assertThat(read("postgresql"), is(BinaryMessageFraming.POSTGRESQL));
        assertThat(read(" Raw "), is(BinaryMessageFraming.RAW));

        assertThat(reports, is(empty()));
    }

    @Test
    public void shouldFallBackToRawForAnUnrecognisedSystemProperty() throws Exception {
        System.setProperty(KEY, "MONGODB");
        clearCacheEntry(KEY);

        assertThat(ConfigurationProperties.binaryMessageFraming(), is(BinaryMessageFraming.RAW));
        assertThat("an unset instance value falls back to the store", new Configuration().binaryMessageFraming(), is(BinaryMessageFraming.RAW));

        System.setProperty(KEY, "postgresql");
        clearCacheEntry(KEY);

        assertThat(ConfigurationProperties.binaryMessageFraming(), is(BinaryMessageFraming.POSTGRESQL));
    }

    @Test
    public void shouldReadTheNewFramingsInAnyCase() {
        assertThat(read("mysql"), is(BinaryMessageFraming.MYSQL));
        assertThat(read("Redis"), is(BinaryMessageFraming.REDIS));
        assertThat(read("length_prefix"), is(BinaryMessageFraming.LENGTH_PREFIX));

        assertThat(reports, is(empty()));
    }

    @Test
    public void shouldReadAnInvalidLengthPrefixSizeAsFourAndReportItOnce() {
        assertThat(ConfigurationProperties.binaryMessageLengthPrefixBytes(" 8 ", reported, reports::add), is(8));
        assertThat(ConfigurationProperties.binaryMessageLengthPrefixBytes("1", reported, reports::add), is(1));
        assertThat(ConfigurationProperties.binaryMessageLengthPrefixBytes("2", reported, reports::add), is(2));
        assertThat(reports, is(empty()));

        assertThat(ConfigurationProperties.binaryMessageLengthPrefixBytes("3", reported, reports::add), is(4));
        assertThat(ConfigurationProperties.binaryMessageLengthPrefixBytes("3", reported, reports::add), is(4));
        assertThat(ConfigurationProperties.binaryMessageLengthPrefixBytes("four", reported, reports::add), is(4));

        assertThat(reports, contains("3", "four"));
    }

    @Test
    public void shouldReadAnInvalidLengthPrefixByteOrderAsBigEndianAndReportItOnce() {
        assertThat(ConfigurationProperties.binaryMessageLengthPrefixByteOrder("little_endian", reported, reports::add), is(ByteOrder.LITTLE_ENDIAN));
        assertThat(ConfigurationProperties.binaryMessageLengthPrefixByteOrder(" BIG_ENDIAN ", reported, reports::add), is(ByteOrder.BIG_ENDIAN));
        assertThat(reports, is(empty()));

        assertThat(ConfigurationProperties.binaryMessageLengthPrefixByteOrder("LITTLE", reported, reports::add), is(ByteOrder.BIG_ENDIAN));
        assertThat(ConfigurationProperties.binaryMessageLengthPrefixByteOrder("LITTLE", reported, reports::add), is(ByteOrder.BIG_ENDIAN));

        assertThat(reports, contains("LITTLE"));
    }

    @Test
    public void shouldReadAnInvalidLengthPrefixOffsetAsZeroAndReportItOnce() {
        assertThat(ConfigurationProperties.binaryMessageLengthPrefixOffset("5", reported, reports::add), is(5));
        assertThat(ConfigurationProperties.binaryMessageLengthPrefixOffset("0", reported, reports::add), is(0));
        assertThat(reports, is(empty()));

        assertThat(ConfigurationProperties.binaryMessageLengthPrefixOffset("-1", reported, reports::add), is(0));
        assertThat(ConfigurationProperties.binaryMessageLengthPrefixOffset("-1", reported, reports::add), is(0));
        assertThat(ConfigurationProperties.binaryMessageLengthPrefixOffset("x", reported, reports::add), is(0));

        assertThat(reports, contains("-1", "x"));
    }

    @Test
    public void shouldFallBackForInvalidLengthPrefixSystemProperties() throws Exception {
        String[] keys = {"mockserver.binaryMessageLengthPrefixBytes", "mockserver.binaryMessageLengthPrefixByteOrder", "mockserver.binaryMessageLengthPrefixOffset"};
        try {
            System.setProperty(keys[0], "16");
            System.setProperty(keys[1], "MIDDLE_ENDIAN");
            System.setProperty(keys[2], "-3");
            for (String key : keys) {
                clearCacheEntry(key);
            }

            Configuration unset = new Configuration();
            assertThat(unset.binaryMessageLengthPrefixBytes(), is(4));
            assertThat(unset.binaryMessageLengthPrefixByteOrder(), is(ByteOrder.BIG_ENDIAN));
            assertThat(unset.binaryMessageLengthPrefixOffset(), is(0));

            System.setProperty(keys[0], "2");
            System.setProperty(keys[1], "LITTLE_ENDIAN");
            System.setProperty(keys[2], "1");
            for (String key : keys) {
                clearCacheEntry(key);
            }

            assertThat(unset.binaryMessageLengthPrefixBytes(), is(2));
            assertThat(unset.binaryMessageLengthPrefixByteOrder(), is(ByteOrder.LITTLE_ENDIAN));
            assertThat(unset.binaryMessageLengthPrefixOffset(), is(1));
        } finally {
            for (String key : keys) {
                System.clearProperty(key);
                clearCacheEntry(key);
                clearProgrammaticallySetKey(key);
            }
        }
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
