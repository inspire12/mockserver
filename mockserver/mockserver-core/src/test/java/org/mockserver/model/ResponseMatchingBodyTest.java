package org.mockserver.model;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.HttpResponseMatcher;
import org.mockserver.mock.Expectation;
import org.mockserver.serialization.VerificationSequenceSerializer;
import org.mockserver.serialization.VerificationSerializer;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationSequence;

import java.util.Collections;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonPathBody.jsonPath;
import static org.mockserver.model.Not.not;
import static org.mockserver.model.RegexBody.regex;
import static org.mockserver.model.StringBody.subString;
import static org.mockserver.verify.Verification.verification;

public class ResponseMatchingBodyTest {

    private final Configuration configuration = configuration();
    private final MockServerLogger mockServerLogger = new MockServerLogger(configuration, ResponseMatchingBodyTest.class);

    @Test
    public void javaApiResponseBodyMatcherShouldConstrainTheMatch() {
        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger,
            response().withBodyMatching(regex("order-[0-9]+")));

        assertThat(matcher.matches(response().withBody("order-42")), is(true));
        assertThat(matcher.matches(response().withBody("zzz-nothing")), is(false));
    }

    @Test
    public void bodyWithContentTypeShouldBeSetDirectly() {
        StringBody subStringBody = subString("needle");

        assertThat(response().withBodyMatching(subStringBody).getBody(), is(subStringBody));
        assertThat(response().withBody("x").withBodyMatching(null).getBody(), nullValue());
    }

    @Test
    public void javaClientVerificationShouldReachTheServerWithItsBodyMatcher() {
        VerificationSerializer serializer = new VerificationSerializer(mockServerLogger);
        Verification sent = verification().withResponse(response().withStatusCode(200).withBodyMatching(not(jsonPath("$.error"))));

        String json = serializer.serialize(sent);
        Verification received = serializer.deserialize(json);

        assertThat(json, containsString("\"type\" : \"JSON_PATH\""));
        assertThat(received.getHttpResponse(), is(sent.getHttpResponse()));
        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger, received.getHttpResponse());
        assertThat(matcher.matches(response().withStatusCode(200).withBody("{\"status\":\"ok\"}")), is(true));
        assertThat(matcher.matches(response().withStatusCode(200).withBody("{\"error\":\"boom\"}")), is(false));
    }

    @Test
    public void javaClientVerificationSequenceShouldReachTheServerWithItsBodyMatchers() {
        VerificationSequenceSerializer serializer = new VerificationSequenceSerializer(mockServerLogger);
        VerificationSequence sent = new VerificationSequence().withResponses(
            response().withBodyMatching(regex("first-.*")),
            response().withBodyMatching(jsonPath("$.second"))
        );

        VerificationSequence received = serializer.deserialize(serializer.serialize(sent));

        assertThat(received.getHttpResponses(), is(sent.getHttpResponses()));
        HttpResponseMatcher second = new HttpResponseMatcher(configuration, mockServerLogger, received.getHttpResponses().get(1));
        assertThat(second.matches(response().withBody("{\"second\":true}")), is(true));
        assertThat(second.matches(response().withBody("{\"first\":true}")), is(false));
    }

    @Test
    public void optionalBodyMatcherShouldMatchAResponseWithoutABody() {
        Verification verification = new VerificationSerializer(mockServerLogger).deserialize(
            "{\"httpResponse\":{\"body\":{\"type\":\"REGEX\",\"regex\":\"order-[0-9]+\",\"optional\":true}}}"
        );

        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger, verification.getHttpResponse());
        assertThat(verification.getHttpResponse().getBody(), instanceOf(ResponseMatchingBody.class));
        assertThat(matcher.matches(response().withStatusCode(204)), is(true));
        assertThat(matcher.matches(response().withBody("order-42")), is(true));
        assertThat(matcher.matches(response().withBody("zzz-nothing")), is(false));
    }

    @Test
    public void optionalSetOnTheResponseBodyShouldApplyToTheWrappedMatcher() {
        HttpResponse template = response().withBodyMatching(regex("order-[0-9]+"));
        template.getBody().withOptional(true);

        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger, template);
        assertThat(template.getBody().getOptional(), is(true));
        assertThat(matcher.matches(response().withStatusCode(204)), is(true));
        assertThat(matcher.matches(response().withBody("zzz-nothing")), is(false));
    }

    @Test
    public void responseShouldPrintItsBodyMatcher() {
        assertThat(response().withBodyMatching(regex("order-[0-9]+")).toString(), is("{" + NEW_LINE +
            "  \"body\" : {" + NEW_LINE +
            "    \"type\" : \"REGEX\"," + NEW_LINE +
            "    \"regex\" : \"order-[0-9]+\"" + NEW_LINE +
            "  }" + NEW_LINE +
            "}"));
    }

    @Test
    public void expectationShouldRejectABodyMatcherAsTheResponseToReturn() {
        HttpResponse httpResponse = response().withBodyMatching(regex("order-[0-9]+"));

        IllegalArgumentException single = assertThrows(IllegalArgumentException.class,
            () -> new Expectation(request()).thenRespond(httpResponse));
        IllegalArgumentException list = assertThrows(IllegalArgumentException.class,
            () -> new Expectation(request()).thenRespond(Collections.singletonList(httpResponse)));

        assertThat(single.getMessage(), is("a REGEX body can only match a response in a verification, it cannot be returned as a response"));
        assertThat(list.getMessage(), is(single.getMessage()));
    }

    @Test
    public void shouldRejectWrappingABodyThatCanBeAResponseBody() {
        assertThrows(IllegalArgumentException.class, () -> new ResponseMatchingBody(subString("x")));
    }
}
