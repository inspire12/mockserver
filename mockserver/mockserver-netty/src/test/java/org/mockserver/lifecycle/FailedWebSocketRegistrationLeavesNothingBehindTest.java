package org.mockserver.lifecycle;

import org.junit.Test;
import org.mockserver.client.ClientException;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ClientConfiguration;
import org.mockserver.mock.breakpoint.BreakpointPhase;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.EnumSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.ClientConfiguration.clientConfiguration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A breakpoint or callback needs a WebSocket of its own to MockServer, with event loops of its own. When that
 * WebSocket cannot be registered the caller gets an exception and nothing it could stop, so the event loops
 * must be given back then, not when the client is stopped, if ever.
 */
public class FailedWebSocketRegistrationLeavesNothingBehindTest {

    private static final int REPEATS = 5;

    private static final ClientConfiguration REGISTRATION_TIMES_OUT = clientConfiguration().maxFutureTimeoutInMillis(500L);

    @Test
    public void shouldGiveBackTheEventLoopsOfABreakpointWebSocketThatWasNotRegistered() throws Exception {
        MockServerClient client = new MockServerClient(REGISTRATION_TIMES_OUT, "127.0.0.1", portNothingListensOn());
        try {
            assertBreakpointNotRegistered(client);
            long baseline = LeftBehind.settledDescriptors();
            ThreadGroup group = new ThreadGroup("failed-breakpoint-registrations");

            LeftBehind.in(group, () -> {
                for (int i = 0; i < REPEATS; i++) {
                    assertBreakpointNotRegistered(client);
                }
            });

            // the client is still running: only the failed registrations must have given back what they started
            assertThat(LeftBehind.threadsStillAlive(group), is(empty()));
            LeftBehind.assertDescriptorsGivenBack(baseline, REPEATS);
        } finally {
            client.stop();
        }
    }

    @Test
    public void shouldGiveBackTheEventLoopsOfACallbackWebSocketThatWasNotRegistered() throws Exception {
        MockServerClient client = new MockServerClient(REGISTRATION_TIMES_OUT, "127.0.0.1", portNothingListensOn());
        try {
            assertCallbackNotRegistered(client);
            long baseline = LeftBehind.settledDescriptors();
            ThreadGroup group = new ThreadGroup("failed-callback-registrations");

            LeftBehind.in(group, () -> {
                for (int i = 0; i < REPEATS; i++) {
                    assertCallbackNotRegistered(client);
                }
            });

            assertThat(LeftBehind.threadsStillAlive(group), is(empty()));
            LeftBehind.assertDescriptorsGivenBack(baseline, REPEATS);
        } finally {
            client.stop();
        }
    }

    private static void assertBreakpointNotRegistered(MockServerClient client) {
        ClientException notRegistered = assertThrows(ClientException.class, () -> client.addBreakpoint(
            request("/paused"),
            EnumSet.of(BreakpointPhase.REQUEST),
            request -> request,
            null,
            null
        ));
        assertThat(notRegistered.getMessage(), containsString("Unable to establish breakpoint WebSocket connection"));
    }

    private static void assertCallbackNotRegistered(MockServerClient client) {
        ClientException notRegistered = assertThrows(ClientException.class, () -> client
            .when(request("/called-back"))
            .respond(request -> response()));
        assertThat(notRegistered.getMessage(), containsString("Unable to retrieve client registration id"));
    }

    private static int portNothingListensOn() throws Exception {
        try (ServerSocket released = new ServerSocket()) {
            released.bind(new InetSocketAddress("127.0.0.1", 0));
            return released.getLocalPort();
        }
    }
}
