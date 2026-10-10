package org.mockserver.verify;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.HttpResponseMatcher;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.VerificationSequenceSerializer;
import org.mockserver.serialization.VerificationSerializer;

import java.util.Arrays;
import java.util.Collection;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpResponse.response;

/**
 * Every request body matcher type must constrain a response verification sent over the REST API: each
 * case is checked against a response that should match AND one that should not, so a matcher that is
 * dropped during deserialisation (and therefore matches everything) fails the negative assertion.
 */
@RunWith(Parameterized.class)
public class ResponseVerificationBodyMatcherTypesTest {

    private static final String XSD = "<?xml version=\\\"1.0\\\" encoding=\\\"UTF-8\\\"?>" +
        "<xs:schema xmlns:xs=\\\"http://www.w3.org/2001/XMLSchema\\\">" +
        "<xs:element name=\\\"order\\\"><xs:complexType><xs:sequence>" +
        "<xs:element name=\\\"id\\\" type=\\\"xs:int\\\"/>" +
        "</xs:sequence></xs:complexType></xs:element></xs:schema>";

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
            {"REGEX", "{\"type\":\"REGEX\",\"regex\":\"order-[0-9]+\"}", "order-42", "zzz"},
            {"REGEX not", "{\"type\":\"REGEX\",\"regex\":\"zzz.*\",\"not\":true}", "order-42", "zzz-nothing"},
            {"JSON_PATH", "{\"type\":\"JSON_PATH\",\"jsonPath\":\"$.status\"}", "{\"status\":\"ok\"}", "{\"other\":\"ok\"}"},
            {"JSON_SCHEMA", "{\"type\":\"JSON_SCHEMA\",\"jsonSchema\":{\"type\":\"object\",\"required\":[\"id\"]}}", "{\"id\":1}", "{\"name\":\"x\"}"},
            {"XPATH", "{\"type\":\"XPATH\",\"xpath\":\"/order/id[.='42']\"}", "<order><id>42</id></order>", "<order><id>7</id></order>"},
            {"XML_SCHEMA", "{\"type\":\"XML_SCHEMA\",\"xmlSchema\":\"" + XSD + "\"}", "<order><id>42</id></order>", "<order><name>x</name></order>"},
            {"PARAMETERS", "{\"type\":\"PARAMETERS\",\"parameters\":{\"a\":[\"1\"]}}", "a=1&b=2", "a=2&b=2"},
            {"FUZZY", "{\"type\":\"FUZZY\",\"fuzzy\":\"hello world\",\"threshold\":0.9}", "hello world", "completely different"},
            {"ALL_OF", "{\"type\":\"ALL_OF\",\"bodyAllOf\":[{\"type\":\"REGEX\",\"regex\":\"order-.*\"},{\"type\":\"STRING\",\"string\":\"42\",\"subString\":true}]}", "order-42", "order-7"},
            {"STRING subString", "{\"type\":\"STRING\",\"string\":\"needle\",\"subString\":true}", "hay needle stack", "hay stack"},
            {"JSON STRICT", "{\"type\":\"JSON\",\"json\":{\"a\":1},\"matchType\":\"STRICT\"}", "{\"a\":1}", "{\"a\":1,\"b\":2}"},
            {"JSON", "{\"type\":\"JSON\",\"json\":{\"a\":1}}", "{\"a\":1,\"b\":2}", "{\"a\":2}"},
            {"STRING", "{\"type\":\"STRING\",\"string\":\"exact\"}", "exact", "not exact"},
            {"STRING not", "{\"type\":\"STRING\",\"string\":\"exact\",\"not\":true}", "not exact", "exact"},
            {"plain string", "\"exact\"", "exact", "not exact"},
        });
    }

    private final Configuration configuration = configuration();
    private final MockServerLogger mockServerLogger = new MockServerLogger(configuration, ResponseVerificationBodyMatcherTypesTest.class);

    @Parameterized.Parameter
    public String name;
    @Parameterized.Parameter(1)
    public String bodyJson;
    @Parameterized.Parameter(2)
    public String matchingBody;
    @Parameterized.Parameter(3)
    public String nonMatchingBody;

    @Test
    public void verificationResponseBodyShouldMatchOnlyMatchingResponses() {
        Verification verification = new VerificationSerializer(mockServerLogger).deserialize(
            "{\"httpResponse\":{\"body\":" + bodyJson + "},\"times\":{\"atLeast\":1}}"
        );

        assertMatchesOnlyTheMatchingBody(verification.getHttpResponse());
    }

    @Test
    public void verificationResponseBodyShouldSurviveAClientRoundTrip() {
        VerificationSerializer serializer = new VerificationSerializer(mockServerLogger);
        Verification fromJson = serializer.deserialize("{\"httpResponse\":{\"body\":" + bodyJson + "}}");

        // the Java client serialises its Verification before sending it; the server deserialises it again
        Verification roundTripped = serializer.deserialize(serializer.serialize(fromJson));

        assertThat(roundTripped.getHttpResponse(), is(fromJson.getHttpResponse()));
        assertMatchesOnlyTheMatchingBody(roundTripped.getHttpResponse());
    }

    @Test
    public void verificationSequenceResponseBodyShouldMatchOnlyMatchingResponses() {
        VerificationSequence verificationSequence = new VerificationSequenceSerializer(mockServerLogger).deserialize(
            "{\"httpResponses\":[{\"body\":" + bodyJson + "}]}"
        );

        assertMatchesOnlyTheMatchingBody(verificationSequence.getHttpResponses().get(0));
    }

    private void assertMatchesOnlyTheMatchingBody(HttpResponse template) {
        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger, template);
        assertThat(name + " should match " + matchingBody, matcher.matches(response().withBody(matchingBody)), is(true));
        assertThat(name + " should not match " + nonMatchingBody, matcher.matches(response().withBody(nonMatchingBody)), is(false));
    }
}
