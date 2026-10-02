package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;
import org.mockserver.configuration.AtomicConfigurationUpdate;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.MockServerUnificationInitializer;
import org.mockserver.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The two {@code 426 Upgrade Required} answers to a plain-text request when mTLS is required: the
 * connection-level one in {@link PortUnificationHandler} (data-plane and control-plane mTLS both required)
 * and the data-plane one in {@code HttpRequestHandler} (data-plane mTLS required). Both read the
 * {@code ServerTlsSettings} and {@code ControlPlaneAuthenticationSettings} snapshots, so a multi-field
 * update is seen only once it has been applied in full.
 */
public class PlainTextUpgradeRequiredSnapshotTest {

    private static final String UPGRADE_REQUIRED = "HTTP/1.1 426";

    @Test
    public void shouldAnswerUpgradeRequiredWhenDataAndControlPlaneMutualTlsAreRequired() {
        Configuration configuration = configuration()
            .tlsMutualAuthenticationRequired(true)
            .controlPlaneTLSMutualAuthenticationRequired(true);

        assertThat(plainTextExchange(configuration), containsString(UPGRADE_REQUIRED));
    }

    @Test
    public void shouldAnswerUpgradeRequiredWhenOnlyDataPlaneMutualTlsIsRequired() {
        Configuration configuration = configuration().tlsMutualAuthenticationRequired(true);

        assertThat(plainTextExchange(configuration), containsString(UPGRADE_REQUIRED));
    }

    @Test
    public void shouldNotAnswerUpgradeRequiredWhenMutualTlsIsNotRequired() {
        assertThat(plainTextExchange(configuration()), not(containsString(UPGRADE_REQUIRED)));
    }

    @Test
    public void shouldIgnoreConnectionLevelMutualTlsUntilTheUpdateIsApplied() {
        Configuration configuration = configuration();
        AtomicReference<String> duringUpdate = new AtomicReference<>();

        AtomicConfigurationUpdate.apply(configuration, () -> {
            configuration.tlsMutualAuthenticationRequired(true);
            configuration.controlPlaneTLSMutualAuthenticationRequired(true);
            duringUpdate.set(plainTextExchange(configuration));
        });

        assertThat(duringUpdate.get(), not(containsString(UPGRADE_REQUIRED)));
        assertThat(plainTextExchange(configuration), containsString(UPGRADE_REQUIRED));
    }

    @Test
    public void shouldIgnoreDataPlaneMutualTlsUntilTheUpdateIsApplied() {
        Configuration configuration = configuration();
        AtomicReference<String> duringUpdate = new AtomicReference<>();

        AtomicConfigurationUpdate.apply(configuration, () -> {
            configuration.tlsMutualAuthenticationRequired(true);
            duringUpdate.set(plainTextExchange(configuration));
        });

        assertThat(duringUpdate.get(), not(containsString(UPGRADE_REQUIRED)));
        assertThat(plainTextExchange(configuration), containsString(UPGRADE_REQUIRED));
    }

    @Test
    public void shouldKeepAnsweringUpgradeRequiredUntilDisablingIsApplied() {
        Configuration configuration = configuration()
            .tlsMutualAuthenticationRequired(true)
            .controlPlaneTLSMutualAuthenticationRequired(true);
        AtomicReference<String> duringUpdate = new AtomicReference<>();

        AtomicConfigurationUpdate.apply(configuration, () -> {
            configuration.tlsMutualAuthenticationRequired(false);
            configuration.controlPlaneTLSMutualAuthenticationRequired(false);
            duringUpdate.set(plainTextExchange(configuration));
        });

        assertThat(duringUpdate.get(), containsString(UPGRADE_REQUIRED));
        assertThat(plainTextExchange(configuration), not(containsString(UPGRADE_REQUIRED)));
    }

    /** One plain-text HTTP/1.1 data-plane request on a fresh connection; returns everything written back. */
    private static String plainTextExchange(Configuration configuration) {
        HttpState httpState = new HttpState(configuration, new MockServerLogger(), mock(Scheduler.class));
        MockServerUnificationInitializer initializer = new MockServerUnificationInitializer(
            configuration,
            mock(LifeCycle.class),
            httpState,
            mock(HttpActionHandler.class),
            null
        );
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            channel.pipeline().addLast(initializer);
            channel.writeInbound(Unpooled.wrappedBuffer(
                "GET /somePath HTTP/1.1\r\nHost: some.random.host\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
            channel.runPendingTasks();
            StringBuilder written = new StringBuilder();
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                written.append(outbound instanceof ByteBuf ? ((ByteBuf) outbound).toString(StandardCharsets.UTF_8) : String.valueOf(outbound));
                ReferenceCountUtil.release(outbound);
            }
            return written.toString();
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
