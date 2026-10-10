package org.mockserver.configuration;

import org.junit.Test;
import org.mockserver.serialization.model.ConfigurationDTO;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;

/**
 * {@code PUT /mockserver/configuration} writes a {@link Configuration} on the control-plane thread
 * (under {@code synchronized (configuration)}) while request handling and control-plane enforcement
 * read it on worker threads without that lock. Only a {@code volatile} (or {@code final}) field is
 * guaranteed to publish the write to those readers, so a plain field could leave a worker enforcing
 * the old value of, for example, {@code controlPlaneTLSMutualAuthenticationRequired}.
 */
public class ConfigurationFieldPublicationTest {

    @Test
    public void everyMutableConfigurationFieldMustBeVolatile() {
        List<String> notSafelyPublished = new ArrayList<>();
        for (Field field : Configuration.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.isSynthetic() || Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                continue;
            }
            if (!Modifier.isVolatile(modifiers)) {
                notSafelyPublished.add(field.getName());
            }
        }
        assertThat("Configuration fields that a runtime PUT can change must be volatile so worker threads see the change",
            notSafelyPublished, empty());
    }

    @Test
    public void thePutMutableFieldsMustBeCoveredByTheVolatileCheck() {
        // Guards the check above against vacuity: the fields ConfigurationDTO.applyTo writes are
        // Configuration's own fields of the same name, including the security-relevant ones.
        Set<String> configurationFields = new TreeSet<>();
        for (Field field : Configuration.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && Modifier.isVolatile(field.getModifiers())) {
                configurationFields.add(field.getName());
            }
        }
        Set<String> putMutable = new TreeSet<>();
        for (Field field : ConfigurationDTO.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && configurationFields.contains(field.getName())) {
                putMutable.add(field.getName());
            }
        }
        assertThat(putMutable.size(), greaterThan(150));
        assertThat(putMutable, hasItems(Arrays.asList(
            "controlPlaneTLSMutualAuthenticationRequired",
            "controlPlaneTLSMutualAuthenticationCAChain",
            "controlPlaneJWTAuthenticationRequired",
            "controlPlaneJWTAuthenticationJWKSource",
            "controlPlaneOidcAuthenticationRequired",
            "controlPlaneOidcIssuer",
            "controlPlaneAuthorizationEnabled",
            "redactSecretsInLog",
            "enableCORSForAPI",
            "enableCORSForAllResponses",
            "tlsMutualAuthenticationRequired",
            "forwardProxyTLSX509CertificatesTrustManagerType",
            "maxLogEntries",
            "maxEventLogSizeInBytes"
        ).toArray(new String[0])));
    }
}
