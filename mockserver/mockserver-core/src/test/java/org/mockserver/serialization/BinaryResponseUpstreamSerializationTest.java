package org.mockserver.serialization;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.BinaryResponse;
import org.mockserver.serialization.java.ExpectationToJavaSerializer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.BinaryRequestDefinition.binaryRequest;
import static org.mockserver.model.BinaryResponse.binaryResponse;

/**
 * A binary response's {@code upstream} survives the JSON a client sends and the server reads, is checked against the
 * schema, and is written in generated Java.
 */
public class BinaryResponseUpstreamSerializationTest {

    private final ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

    private static Expectation expectation(BinaryResponse binaryResponse) {
        return new Expectation(binaryRequest(new byte[]{'Q', 1})).withId("binary-expectation").thenRespondWithBinary(binaryResponse);
    }

    @Test
    public void shouldRoundTripEachUpstream() {
        for (BinaryResponse.Upstream upstream : BinaryResponse.Upstream.values()) {
            Expectation expectation = expectation(binaryResponse(new byte[]{'Z'}).withUpstream(upstream));

            String json = serializer.serialize(expectation);

            assertThat(json, containsString("\"upstream\" : \"" + upstream.name() + "\""));
            assertThat(serializer.deserialize(json).getBinaryResponse().getUpstream(), is(upstream));
            assertThat(serializer.deserialize(json), is(expectation));
        }
    }

    @Test
    public void shouldLeaveOutAnUpstreamThatIsNotSet() {
        String json = serializer.serialize(expectation(binaryResponse(new byte[]{'Z'})));

        assertThat(json, not(containsString("upstream")));
        assertThat(serializer.deserialize(json).getBinaryResponse().getUpstream(), is(nullValue()));
    }

    @Test
    public void shouldRefuseAnUpstreamTheSchemaDoesNotName() {
        String json = "{ \"id\": \"binary-expectation\", \"binaryRequest\": { \"binaryData\": \"UQE=\" }, \"binaryResponse\": { \"binaryData\": \"Wg==\", \"upstream\": \"SOMETIMES\" } }";

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> serializer.deserialize(json));

        assertThat(refused.getMessage(), containsString("schema validation errors"));
        assertThat(refused.getMessage(), containsString("$.binaryResponse.upstream"));
    }

    @Test
    public void shouldWriteTheUpstreamInGeneratedJava() {
        String java = new ExpectationToJavaSerializer().serialize(0, expectation(binaryResponse(new byte[]{'Z'}).withUpstream(BinaryResponse.Upstream.FORWARD_AND_REPLACE)));

        assertThat(java, containsString(".withUpstream(org.mockserver.model.BinaryResponse.Upstream.FORWARD_AND_REPLACE)"));
    }

    @Test
    public void shouldTellBinaryResponsesApartByUpstream() {
        BinaryResponse answerAndForward = binaryResponse(new byte[]{'Z'}).withUpstream(BinaryResponse.Upstream.ANSWER_AND_FORWARD);
        BinaryResponse forwardAndReplace = binaryResponse(new byte[]{'Z'}).withUpstream(BinaryResponse.Upstream.FORWARD_AND_REPLACE);

        assertThat(answerAndForward.equals(forwardAndReplace), is(false));
        assertThat(answerAndForward.equals(binaryResponse(new byte[]{'Z'}).withUpstream(BinaryResponse.Upstream.ANSWER_AND_FORWARD)), is(true));
        assertThat(answerAndForward.hashCode() == forwardAndReplace.hashCode(), is(false));
    }
}
