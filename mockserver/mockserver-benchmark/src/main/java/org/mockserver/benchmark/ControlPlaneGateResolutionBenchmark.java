package org.mockserver.benchmark;

import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ControlPlaneAuthenticationSettings;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * The configuration reads the control-plane gate makes per request on a server with no control-plane
 * authentication, the default.
 *
 * <ul>
 *   <li>{@link #fieldByField()} — the three {@code ...Required} getters and
 *       {@code controlPlaneAuthorizationEnabled()}, each falling back to the JVM-wide default, as the gate
 *       read them before it used one snapshot;</li>
 *   <li>{@link #snapshot()} — one {@link ControlPlaneAuthenticationSettings} read, as the gate reads it now.</li>
 * </ul>
 *
 * <pre>./run.sh ControlPlaneGateResolutionBenchmark</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ControlPlaneGateResolutionBenchmark {

    private Configuration configuration;

    @Setup
    public void setup() {
        configuration = Configuration.configuration();
    }

    @Benchmark
    public boolean fieldByField() {
        boolean authenticationRequired = Boolean.TRUE.equals(configuration.controlPlaneTLSMutualAuthenticationRequired())
            || Boolean.TRUE.equals(configuration.controlPlaneJWTAuthenticationRequired())
            || Boolean.TRUE.equals(configuration.controlPlaneOidcAuthenticationRequired());
        return authenticationRequired || configuration.controlPlaneAuthorizationEnabled();
    }

    @Benchmark
    public boolean snapshot() {
        ControlPlaneAuthenticationSettings settings = ControlPlaneAuthenticationSettings.of(configuration);
        return settings.authenticationRequired() || Boolean.TRUE.equals(settings.controlPlaneAuthorizationEnabled());
    }
}
