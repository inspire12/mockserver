package org.mockserver.clientandserver;

import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
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

public class ClientAndServerListenerTest {

    @Test
    public void shouldNotifyListener() throws Exception {
        ThreadGroup group = new ThreadGroup("client-and-server-listener");

        LeftBehind.in(group, () -> {
            // given
            List<Expectation> expectations = new CopyOnWriteArrayList<>();

            try (ClientAndServer clientAndServer = new ClientAndServer()) {
                // when
                clientAndServer
                    .registerListener(expectations::addAll)
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
            }
        });

        // a server left running here would run for the rest of the test JVM
        assertThat(LeftBehind.threadsStillAlive(group), is(empty()));
    }

}
