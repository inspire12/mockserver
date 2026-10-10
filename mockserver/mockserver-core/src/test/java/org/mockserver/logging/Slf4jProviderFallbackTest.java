package org.mockserver.logging;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.core.Is.is;
import static org.mockserver.logging.Slf4jProviderFallback.BUNDLED_PROVIDER;
import static org.mockserver.logging.Slf4jProviderFallback.PROVIDER_PROPERTY;
import static org.mockserver.logging.Slf4jProviderFallback.VERBOSITY_PROPERTY;

public class Slf4jProviderFallbackTest {

    private final Map<String, String> properties = new HashMap<>();

    private boolean select(boolean providerRegistered, boolean bundledProviderPresent) {
        return Slf4jProviderFallback.select(properties::get, () -> providerRegistered, className -> bundledProviderPresent && className.equals(BUNDLED_PROVIDER), properties::put);
    }

    @Test
    public void shouldSelectBundledProviderWhenNoneRegisteredAndNoneChosen() {
        assertThat(select(false, true), is(true));

        assertThat(properties, hasEntry(PROVIDER_PROPERTY, BUNDLED_PROVIDER));
        assertThat(properties, hasEntry(VERBOSITY_PROPERTY, "WARN"));
    }

    @Test
    public void shouldNotReplaceARegisteredProvider() {
        assertThat(select(true, true), is(false));

        assertThat(properties, is(anEmptyMap()));
    }

    @Test
    public void shouldNotReplaceAnExplicitlyChosenProvider() {
        properties.put(PROVIDER_PROPERTY, "org.example.UserProvider");

        assertThat(select(false, true), is(false));

        assertThat(properties.get(PROVIDER_PROPERTY), is("org.example.UserProvider"));
        assertThat(properties.containsKey(VERBOSITY_PROPERTY), is(false));
    }

    @Test
    public void shouldTreatABlankProviderPropertyAsUnset() {
        properties.put(PROVIDER_PROPERTY, " ");

        assertThat(select(false, true), is(true));

        assertThat(properties, hasEntry(PROVIDER_PROPERTY, BUNDLED_PROVIDER));
    }

    @Test
    public void shouldDoNothingWithoutTheBundledProvider() {
        assertThat(select(false, false), is(false));

        assertThat(properties, is(anEmptyMap()));
    }

    @Test
    public void shouldKeepAnExplicitVerbosity() {
        properties.put(VERBOSITY_PROPERTY, "DEBUG");

        assertThat(select(false, true), is(true));

        assertThat(properties, hasEntry(VERBOSITY_PROPERTY, "DEBUG"));
    }

    @Test
    public void shouldNotChangeAnythingInThisJvmWhereTheBundledProviderIsAbsent() {
        String before = System.getProperty(PROVIDER_PROPERTY);

        Slf4jProviderFallback.selectBundledProviderIfNoneRegistered();

        assertThat(System.getProperty(PROVIDER_PROPERTY), is(before));
    }
}
