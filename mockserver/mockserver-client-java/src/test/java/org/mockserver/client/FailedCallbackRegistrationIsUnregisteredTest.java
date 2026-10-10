package org.mockserver.client;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.mockserver.closurecallback.websocketregistry.LocalCallbackRegistry;
import org.mockserver.configuration.ClientConfiguration;
import org.mockserver.mock.action.ExpectationCallback;
import org.mockserver.mock.action.ExpectationForwardAndResponseCallback;
import org.mockserver.mock.action.ExpectationForwardCallback;
import org.mockserver.mock.action.ExpectationResponseCallback;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.ClientConfiguration.clientConfiguration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A callback is put in the process-wide, bounded {@link LocalCallbackRegistry} before its WebSocket is registered.
 * When the registration fails no expectation will ever use it, so it must be taken out again: left there it takes
 * a slot, and once the registry is full it evicts the callback of a live expectation.
 * <p>
 * The registry is static, and this test counts its entries, so it must not run alongside a test that registers
 * callbacks (this module's unit tests run one class at a time).
 */
public class FailedCallbackRegistrationIsUnregisteredTest {

    private static final ClientConfiguration REGISTRATION_TIMES_OUT = clientConfiguration().maxFutureTimeoutInMillis(500L);

    private MockServerClient client;

    @Before
    public void createClientForAPortNothingListensOn() throws Exception {
        client = new MockServerClient(REGISTRATION_TIMES_OUT, "127.0.0.1", portNothingListensOn());
    }

    @After
    public void stopClient() {
        client.stop();
    }

    @Test
    public void shouldUnregisterAResponseCallbackWhoseWebSocketWasNotRegistered() {
        ExpectationResponseCallback callback = request -> response();
        int entriesBefore = registryEntries();

        assertNotRegistered(() -> client.when(request("/called-back")).respond(callback));

        assertThat(registryEntries(), is(entriesBefore));
        assertThat(registryHolds(callback), is(false));
    }

    @Test
    public void shouldUnregisterAForwardCallbackWhoseWebSocketWasNotRegistered() {
        ExpectationForwardCallback callback = request -> request;
        int entriesBefore = registryEntries();

        assertNotRegistered(() -> client.when(request("/forwarded")).forward(callback));

        assertThat(registryEntries(), is(entriesBefore));
        assertThat(registryHolds(callback), is(false));
    }

    @Test
    public void shouldUnregisterBothCallbacksOfAForwardWhoseWebSocketWasNotRegistered() {
        ExpectationForwardCallback forwardCallback = request -> request;
        ExpectationForwardAndResponseCallback responseCallback = (request, response) -> response;
        int entriesBefore = registryEntries();

        assertNotRegistered(() -> client.when(request("/forwarded")).forward(forwardCallback, responseCallback));

        assertThat(registryEntries(), is(entriesBefore));
        assertThat(registryHolds(forwardCallback), is(false));
        assertThat(registryHolds(responseCallback), is(false));
    }

    private static void assertNotRegistered(ThrowingRunnable registration) {
        ClientException notRegistered = assertThrows(ClientException.class, registration);
        assertThat(notRegistered.getMessage(), containsString("Unable to retrieve client registration id"));
    }

    private static int registryEntries() {
        return registries().stream().mapToInt(Map::size).sum();
    }

    private static boolean registryHolds(ExpectationCallback<?> callback) {
        for (Map<String, ? extends ExpectationCallback<?>> registry : registries()) {
            synchronized (registry) {
                if (registry.values().stream().anyMatch(registered -> registered == callback)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Map<String, ? extends ExpectationCallback<?>>> registries() {
        return Arrays.asList(
            LocalCallbackRegistry.responseCallbackRegistry(),
            LocalCallbackRegistry.forwardCallbackRegistry(),
            LocalCallbackRegistry.forwardAndResponseCallbackRegistry()
        );
    }

    private static int portNothingListensOn() throws Exception {
        try (ServerSocket released = new ServerSocket()) {
            released.bind(new InetSocketAddress("127.0.0.1", 0));
            return released.getLocalPort();
        }
    }
}
