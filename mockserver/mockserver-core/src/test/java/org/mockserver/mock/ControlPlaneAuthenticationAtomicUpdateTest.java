package org.mockserver.mock;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.authentication.AuthenticationHandler;
import org.mockserver.authentication.AuthenticationResult;
import org.mockserver.authentication.authorization.ControlPlaneRole;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.model.ConfigurationDTO;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;

/**
 * A {@code PUT /mockserver/configuration} that switches control-plane authentication from one mechanism
 * to another must never let a request through unauthenticated while it is being applied.
 *
 * <p>{@code ConfigurationDTO.applyTo} writes {@code controlPlaneTLSMutualAuthenticationRequired} before
 * {@code controlPlaneJWTAuthenticationRequired} and {@code controlPlaneOidcAuthenticationRequired}, so a
 * gate reading the fields one by one saw "nothing required" between the two writes. The tests pause, or
 * widen, exactly that gap and check the gate from other threads.
 */
public class ControlPlaneAuthenticationAtomicUpdateTest {

    private static String jwkSource;

    private final List<Scheduler> schedulers = new ArrayList<>();

    @BeforeClass
    public static void createJwkSource() throws Exception {
        // a loadable key set, so the JWT and OIDC handlers build and reject on the missing token
        File jwks = File.createTempFile("control-plane-jwks", ".json");
        jwks.deleteOnExit();
        Files.write(jwks.toPath(), new JWKSet(new RSAKeyGenerator(2048).keyID("test").generate().toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8));
        jwkSource = jwks.getAbsolutePath();
    }

    private static GatedConfiguration mtlsRequired() {
        GatedConfiguration configuration = new GatedConfiguration();
        configuration
            .controlPlaneTLSMutualAuthenticationRequired(true)
            .controlPlaneJWTAuthenticationRequired(false)
            .controlPlaneJWTAuthenticationJWKSource(jwkSource)
            .controlPlaneOidcAuthenticationRequired(false)
            .controlPlaneOidcJwksUri(jwkSource)
            .controlPlaneOidcAudience("mockserver");
        return configuration;
    }

    @After
    public void shutdownSchedulers() {
        schedulers.forEach(Scheduler::shutdown);
    }

    private HttpState httpState(Configuration configuration) {
        MockServerLogger mockServerLogger = new MockServerLogger();
        Scheduler scheduler = new Scheduler(configuration, mockServerLogger);
        schedulers.add(scheduler);
        return new HttpState(configuration, mockServerLogger, scheduler);
    }

    private static boolean allowedWithoutCredentials(HttpState httpState) {
        return httpState.evaluateControlPlaneAuthentication(request().withMethod("PUT").withPath("/mockserver/retrieve")).isAllowed();
    }

    @Test
    public void shouldNotAllowAnUnauthenticatedRequestWhileSwitchingFromMtlsToJwt() throws Exception {
        assertNeverOpenMidSwitch("jwt", new ConfigurationDTO()
            .setControlPlaneTLSMutualAuthenticationRequired(false)
            .setControlPlaneJWTAuthenticationRequired(true));
    }

    @Test
    public void shouldNotAllowAnUnauthenticatedRequestWhileSwitchingFromMtlsToOidc() throws Exception {
        assertNeverOpenMidSwitch("oidc", new ConfigurationDTO()
            .setControlPlaneTLSMutualAuthenticationRequired(false)
            .setControlPlaneOidcAuthenticationRequired(true));
    }

    private void assertNeverOpenMidSwitch(String pausePoint, ConfigurationDTO switchToTokenAuthentication) throws Exception {
        GatedConfiguration configuration = mtlsRequired();
        HttpState httpState = httpState(configuration);
        assertThat("a request without a client certificate must be rejected before the switch", allowedWithoutCredentials(httpState), is(false));

        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        configuration.onTokenAuthenticationWrite(pausePoint, () -> {
            paused.countDown();
            await(resume);
        });
        Thread put = new Thread(() -> switchToTokenAuthentication.applyTo(configuration), "configuration-put");
        put.start();
        assertThat("the PUT never reached the pause point", paused.await(10, TimeUnit.SECONDS), is(true));
        try {
            // the PUT has written mTLS=false and not yet the token mechanism's =true
            assertThat(configuration.controlPlaneTLSMutualAuthenticationRequired(), is(false));
            AtomicBoolean allowed = new AtomicBoolean();
            Thread request = new Thread(() -> allowed.set(allowedWithoutCredentials(httpState)), "control-plane-request");
            request.start();
            request.join(TimeUnit.SECONDS.toMillis(10));
            assertThat("the gate must not block on a PUT in progress", request.isAlive(), is(false));
            assertThat("a request arriving mid-PUT must not pass without credentials", allowed.get(), is(false));
        } finally {
            resume.countDown();
            put.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertThat("the token mechanism is required once the PUT completes", allowedWithoutCredentials(httpState), is(false));
    }

    @Test
    public void shouldAuthorizeFromTheOldSettingsWhilePausedBetweenTheAuthorizationFlagAndTheScopeMapping() throws Exception {
        GatedConfiguration configuration = new GatedConfiguration();
        configuration.controlPlaneAuthorizationEnabled(false).controlPlaneScopeMapping(Collections.emptyMap());
        // applyTo writes controlPlaneAuthorizationEnabled, then controlPlaneScopeMapping
        assertAuthorizedFromOldSettingsMidPut(configuration, "scopeMapping", new ConfigurationDTO()
            .setControlPlaneAuthorizationEnabled(true)
            .setControlPlaneScopeMapping(Collections.singletonMap("team", ControlPlaneRole.READ)));
    }

    @Test
    public void shouldAuthorizeFromTheOldScopeMappingWhilePausedAfterIt() throws Exception {
        GatedConfiguration configuration = new GatedConfiguration();
        configuration.controlPlaneAuthorizationEnabled(true).controlPlaneScopeMapping(Collections.singletonMap("team", ControlPlaneRole.MUTATE));
        // applyTo writes transparentProxyEnabled after controlPlaneScopeMapping
        assertAuthorizedFromOldSettingsMidPut(configuration, "afterScopeMapping", new ConfigurationDTO()
            .setControlPlaneScopeMapping(Collections.singletonMap("team", ControlPlaneRole.READ))
            .setTransparentProxyEnabled(false));
    }

    /**
     * The old settings allow a mutation by a principal with scope "team"; the new ones only grant it READ.
     */
    private void assertAuthorizedFromOldSettingsMidPut(GatedConfiguration configuration, String pausePoint, ConfigurationDTO update) throws Exception {
        HttpState httpState = httpState(configuration);
        httpState.setControlPlaneAuthenticationHandler(new AuthenticationHandler() {
            @Override
            public boolean controlPlaneRequestAuthenticated(HttpRequest request) {
                return true;
            }

            @Override
            public AuthenticationResult authenticate(HttpRequest request) {
                return AuthenticationResult.authenticated("principal", "test", Collections.emptyMap(), Collections.singleton("team"));
            }
        });
        HttpRequest mutation = request().withMethod("PUT").withPath("/mockserver/clear");
        assertThat("the old settings allow the mutation", httpState.evaluateControlPlaneAuthentication(mutation).outcome(), is(HttpState.ControlPlaneAuthOutcome.ALLOWED));

        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        configuration.onTokenAuthenticationWrite(pausePoint, () -> {
            paused.countDown();
            await(resume);
        });
        Thread put = new Thread(() -> update.applyTo(configuration), "configuration-put");
        put.start();
        assertThat("the PUT never reached the pause point", paused.await(10, TimeUnit.SECONDS), is(true));
        try {
            AtomicReference<HttpState.ControlPlaneAuthOutcome> outcome = new AtomicReference<>();
            Thread request = new Thread(() -> outcome.set(httpState.evaluateControlPlaneAuthentication(mutation).outcome()), "control-plane-request");
            request.start();
            request.join(TimeUnit.SECONDS.toMillis(10));
            assertThat("a request mid-PUT is authorized from the old settings, all of them", outcome.get(), is(HttpState.ControlPlaneAuthOutcome.ALLOWED));
        } finally {
            resume.countDown();
            put.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertThat("the new settings only grant READ", httpState.evaluateControlPlaneAuthentication(mutation).outcome(), is(HttpState.ControlPlaneAuthOutcome.FORBIDDEN));
    }

    @Test
    public void shouldNeverAllowAnUnauthenticatedRequestWhilePutsFlipBetweenMechanisms() throws Exception {
        GatedConfiguration configuration = mtlsRequired();
        HttpState httpState = httpState(configuration);
        // widen the gap between the two writes so a racing request reliably lands in it
        configuration.onTokenAuthenticationWrite("jwt", () -> LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(200)));

        AtomicBoolean flipping = new AtomicBoolean(true);
        AtomicLong requests = new AtomicLong();
        AtomicLong allowed = new AtomicLong();
        List<Thread> requesters = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread requester = new Thread(() -> {
                while (flipping.get()) {
                    requests.incrementAndGet();
                    if (allowedWithoutCredentials(httpState)) {
                        allowed.incrementAndGet();
                    }
                }
            }, "control-plane-requester-" + i);
            requester.start();
            requesters.add(requester);
        }
        try {
            // keep flipping until the requesters have raced plenty of PUTs, however the threads are scheduled
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            for (int flip = 0; (flip < 200 || requests.get() < 2_000) && System.nanoTime() < deadline; flip++) {
                boolean toJwt = flip % 2 == 0;
                new ConfigurationDTO()
                    .setControlPlaneTLSMutualAuthenticationRequired(!toJwt)
                    .setControlPlaneJWTAuthenticationRequired(toJwt)
                    .applyTo(configuration);
            }
        } finally {
            flipping.set(false);
            for (Thread requester : requesters) {
                requester.join(TimeUnit.SECONDS.toMillis(10));
            }
        }

        assertThat(requests.get(), greaterThanOrEqualTo(2_000L));
        assertThat("requests allowed without credentials while PUTs switched mechanism", allowed.get(), is(0L));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs a hook just before a chosen setter, so a test can pause or widen the window inside {@code applyTo}.
     */
    private static class GatedConfiguration extends Configuration {
        private volatile String hookOn;
        private volatile Runnable hook;

        void onTokenAuthenticationWrite(String mechanism, Runnable hook) {
            this.hook = hook;
            this.hookOn = mechanism;
        }

        private void runHook(String mechanism) {
            Runnable current = hook;
            if (current != null && mechanism.equals(hookOn)) {
                current.run();
            }
        }

        @Override
        public Configuration controlPlaneJWTAuthenticationRequired(Boolean controlPlaneJWTAuthenticationRequired) {
            runHook("jwt");
            return super.controlPlaneJWTAuthenticationRequired(controlPlaneJWTAuthenticationRequired);
        }

        @Override
        public Configuration controlPlaneOidcAuthenticationRequired(Boolean controlPlaneOidcAuthenticationRequired) {
            runHook("oidc");
            return super.controlPlaneOidcAuthenticationRequired(controlPlaneOidcAuthenticationRequired);
        }

        @Override
        public Configuration controlPlaneScopeMapping(Map<String, ControlPlaneRole> controlPlaneScopeMapping) {
            runHook("scopeMapping");
            return super.controlPlaneScopeMapping(controlPlaneScopeMapping);
        }

        @Override
        public Configuration transparentProxyEnabled(Boolean transparentProxyEnabled) {
            runHook("afterScopeMapping");
            return super.transparentProxyEnabled(transparentProxyEnabled);
        }
    }
}
