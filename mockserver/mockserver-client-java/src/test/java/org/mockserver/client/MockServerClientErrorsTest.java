package org.mockserver.client;

import org.junit.Test;
import org.mockserver.httpclient.SocketConnectionException;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.test.ClosedPort.CLOSED_PORT;

/**
 * @author jamesdbloom
 */
public class MockServerClientErrorsTest {

    @Test
    public void shouldHandleSocketErrorForReset() {
        // given
        MockServerClient mockServerClient = new MockServerClient("localhost", CLOSED_PORT);

        // when
        SocketConnectionException clientException = assertThrows(SocketConnectionException.class, mockServerClient::reset);

        // then
        // localhost may resolve to 127.0.0.1 or ::1, so assert the socket prefix + port rather
        // than a hardcoded IPv4 literal
        assertThat(clientException.getMessage(),
            allOf(startsWith("Unable to connect to socket localhost/"), endsWith(":" + CLOSED_PORT)));
    }

    @Test
    public void shouldHandleSocketErrorForClear() {
        // given
        MockServerClient mockServerClient = new MockServerClient("localhost", CLOSED_PORT);

        // when
        SocketConnectionException clientException = assertThrows(SocketConnectionException.class, () -> mockServerClient.clear(request()));

        // then
        // localhost may resolve to 127.0.0.1 or ::1, so assert the socket prefix + port rather
        // than a hardcoded IPv4 literal
        assertThat(clientException.getMessage(),
            allOf(startsWith("Unable to connect to socket localhost/"), endsWith(":" + CLOSED_PORT)));
    }

    @Test
    public void shouldHandleSocketErrorForExpectation() {
        // given
        MockServerClient mockServerClient = new MockServerClient("localhost", CLOSED_PORT);

        // when
        SocketConnectionException clientException = assertThrows(SocketConnectionException.class, () -> mockServerClient.when(request()).respond(response()));

        // then
        // localhost may resolve to 127.0.0.1 or ::1, so assert the socket prefix + port rather
        // than a hardcoded IPv4 literal
        assertThat(clientException.getMessage(),
            allOf(startsWith("Unable to connect to socket localhost/"), endsWith(":" + CLOSED_PORT)));
    }

}
