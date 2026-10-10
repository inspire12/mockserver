package org.mockserver.springtest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.junit4.SpringRunner;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

@RunWith(SpringRunner.class)
@MockServerTest
public class MockServerDnsPortAnnotationTest {

    @MockServerDnsPort
    private Integer mockServerDnsPort;

    @Test
    public void shouldInjectTheDnsPortOfTheListenersServer() {
        assertThat(mockServerDnsPort, is(MockServerPropertyCustomizer.getOrCreateClientAndServer().getDnsPort()));
    }
}
