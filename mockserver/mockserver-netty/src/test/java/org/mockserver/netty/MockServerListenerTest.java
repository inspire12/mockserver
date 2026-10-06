package org.mockserver.netty;

import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.lifecycle.LeftBehind;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.test.Retries;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class MockServerListenerTest {

    @Test
    public void shouldNotifyListener() throws Exception {
        ThreadGroup group = new ThreadGroup("mock-server-listener");

        LeftBehind.in(group, () -> {
            // given
            List<Expectation> expectations = new CopyOnWriteArrayList<>();
            MockServer mockServer = new MockServer().registerListener(expectations::addAll);
            try (MockServerClient mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort())) {
                // when
                mockServerClient
                    .when(
                        request()
                            .withPath("/some/path")
                    )
                    .respond(
                        response()
                            .withBody("some_response_body")
                    );

                // then
                Retries.tryWaitForSuccess(() -> {
                    assertThat(expectations.size(), is(1));
                    assertThat(expectations.get(0), is(new Expectation(
                        request()
                            .withPath("/some/path"),
                        Times.unlimited(),
                        TimeToLive.unlimited(),
                        0
                    ).thenRespond(
                        response()
                            .withBody("some_response_body")
                    )));
                });
            } finally {
                mockServer.stop();
            }
        });

        // a server left running here would run for the rest of the test JVM
        assertThat(LeftBehind.threadsStillAlive(group), is(empty()));
    }

}
