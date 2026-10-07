package org.mockserver.logging;

import org.slf4j.LoggerFactory;
import org.slf4j.spi.SLF4JServiceProvider;

import java.util.ServiceLoader;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Lets MockServer log when nothing else gives SLF4J a provider. The mockserver-netty-no-dependencies jar
 * bundles the slf4j-jdk14 provider relocated and without a {@code META-INF/services} registration, so it
 * never competes with an application's own provider (issue #2772). When no provider is registered and the
 * {@value #PROVIDER_PROPERTY} system property is unset, this sets that property to the bundled provider.
 * <p>
 * This sets JVM-wide system properties from library code, so it does so only in that case: it never
 * replaces an explicit setting or a registered provider. It has no effect once SLF4J has initialised.
 */
public final class Slf4jProviderFallback {

    static final String PROVIDER_PROPERTY = "slf4j.provider";
    static final String VERBOSITY_PROPERTY = "slf4j.internal.verbosity";
    static final String BUNDLED_PROVIDER = "shaded_package.org.slf4j.jul.JULServiceProvider";

    private Slf4jProviderFallback() {
    }

    public static void selectBundledProviderIfNoneRegistered() {
        try {
            ClassLoader classLoader = LoggerFactory.class.getClassLoader();
            select(
                System::getProperty,
                () -> ServiceLoader.load(SLF4JServiceProvider.class, classLoader).stream().findAny().isPresent(),
                className -> isLoadable(className, classLoader),
                System::setProperty
            );
        } catch (Throwable throwable) {
            // choosing a log provider must never stop MockServer starting; SLF4J then behaves as it would anyway
        }
    }

    static boolean select(UnaryOperator<String> getProperty, BooleanSupplier providerRegistered, Predicate<String> classLoadable, BiConsumer<String, String> setProperty) {
        String explicitProvider = getProperty.apply(PROVIDER_PROPERTY);
        if (explicitProvider != null && !explicitProvider.isBlank()) {
            return false;
        }
        if (!classLoadable.test(BUNDLED_PROVIDER) || providerRegistered.getAsBoolean()) {
            return false;
        }
        setProperty.accept(PROVIDER_PROPERTY, BUNDLED_PROVIDER);
        // SLF4J reports an explicitly chosen provider at INFO on stderr; that notice is ours, not the user's
        if (getProperty.apply(VERBOSITY_PROPERTY) == null) {
            setProperty.accept(VERBOSITY_PROPERTY, "WARN");
        }
        return true;
    }

    private static boolean isLoadable(String className, ClassLoader classLoader) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
