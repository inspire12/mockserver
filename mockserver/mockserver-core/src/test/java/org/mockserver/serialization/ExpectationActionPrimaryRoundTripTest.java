package org.mockserver.serialization;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Action;
import org.mockserver.model.HttpObjectCallback;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.Provider;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.model.BinaryResponse.binaryResponse;
import static org.mockserver.model.DnsResponse.dnsResponse;
import static org.mockserver.model.GrpcBidiResponse.grpcBidiResponse;
import static org.mockserver.model.GrpcStreamResponse.grpcStreamResponse;
import static org.mockserver.model.HttpClassCallback.callback;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpForwardValidateAction.forwardValidate;
import static org.mockserver.model.HttpForwardWithFallback.forwardWithFallback;
import static org.mockserver.model.HttpLlmResponse.llmResponse;
import static org.mockserver.model.HttpOverrideForwardedRequest.forwardOverriddenRequest;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpSseResponse.sseResponse;
import static org.mockserver.model.HttpTemplate.template;
import static org.mockserver.model.HttpWebSocketResponse.webSocketResponse;

/**
 * Every action type keeps its {@code primary} flag through the JSON a client sends and the server reads, so an
 * expectation with more than one action can be created from Java: without the flag the server rejects it.
 */
@RunWith(Parameterized.class)
public class ExpectationActionPrimaryRoundTripTest {

    private final String jsonField;
    private final UnaryOperator<Expectation> withPrimaryAction;
    private final Function<Expectation, Action<?>> primaryAction;
    private final boolean secondaryIsAnError;

    public ExpectationActionPrimaryRoundTripTest(String jsonField, UnaryOperator<Expectation> withPrimaryAction, Function<Expectation, Action<?>> primaryAction) {
        this.jsonField = jsonField;
        this.withPrimaryAction = withPrimaryAction;
        this.primaryAction = primaryAction;
        this.secondaryIsAnError = !jsonField.equals("httpError");
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> actions() {
        return Arrays.asList(new Object[][]{
            {"httpResponse", op(e -> e.thenRespond(response().withStatusCode(202).withPrimary(true))), get(Expectation::getHttpResponse)},
            {"httpResponseTemplate", op(e -> e.thenRespond(template(HttpTemplate.TemplateType.VELOCITY, "{ 'statusCode': 202 }").withPrimary(true))), get(Expectation::getHttpResponseTemplate)},
            {"httpResponseClassCallback", op(e -> e.thenRespond(callback("org.example.ResponseCallback").withPrimary(true))), get(Expectation::getHttpResponseClassCallback)},
            {"httpResponseObjectCallback", op(e -> e.thenRespond(new HttpObjectCallback().withClientId("response-client").withPrimary(true))), get(Expectation::getHttpResponseObjectCallback)},
            {"httpForward", op(e -> e.thenForward(forward().withHost("localhost").withPort(1080).withPrimary(true))), get(Expectation::getHttpForward)},
            {"httpForwardTemplate", op(e -> e.thenForward(template(HttpTemplate.TemplateType.VELOCITY, "{ 'path': '/forwarded' }").withPrimary(true))), get(Expectation::getHttpForwardTemplate)},
            {"httpForwardClassCallback", op(e -> e.thenForward(callback("org.example.ForwardCallback").withPrimary(true))), get(Expectation::getHttpForwardClassCallback)},
            {"httpForwardObjectCallback", op(e -> e.thenForward(new HttpObjectCallback().withClientId("forward-client").withPrimary(true))), get(Expectation::getHttpForwardObjectCallback)},
            {"httpOverrideForwardedRequest", op(e -> e.thenForward(forwardOverriddenRequest(request().withPath("/overridden")).withPrimary(true))), get(Expectation::getHttpOverrideForwardedRequest)},
            {"httpForwardValidateAction", op(e -> e.thenForwardValidate(forwardValidate().withSpecUrlOrPayload("https://example.com/openapi.json").withHost("localhost").withPrimary(true))), get(Expectation::getHttpForwardValidateAction)},
            {"httpForwardWithFallback", op(e -> e.thenForwardWithFallback(forwardWithFallback().withForward(forward().withHost("localhost").withPort(1080)).withFallback(response().withStatusCode(503)).withPrimary(true))), get(Expectation::getHttpForwardWithFallback)},
            {"httpSseResponse", op(e -> e.thenRespondWithSse(sseResponse().withStatusCode(200).withPrimary(true))), get(Expectation::getHttpSseResponse)},
            {"httpLlmResponse", op(e -> e.thenRespondWithLlm(llmResponse().withProvider(Provider.OPENAI).withModel("gpt-4o").withPrimary(true))), get(Expectation::getHttpLlmResponse)},
            {"httpWebSocketResponse", op(e -> e.thenRespondWithWebSocket(webSocketResponse().withSubprotocol("chat").withPrimary(true))), get(Expectation::getHttpWebSocketResponse)},
            {"grpcStreamResponse", op(e -> e.thenRespondWithGrpcStream(grpcStreamResponse().withStatusName("OK").withPrimary(true))), get(Expectation::getGrpcStreamResponse)},
            {"grpcBidiResponse", op(e -> e.thenRespondWithGrpcBidi(grpcBidiResponse().withStatusName("OK").withPrimary(true))), get(Expectation::getGrpcBidiResponse)},
            {"binaryResponse", op(e -> e.thenRespondWithBinary(binaryResponse(new byte[]{1, 2, 3}).withPrimary(true))), get(Expectation::getBinaryResponse)},
            {"dnsResponse", op(e -> e.thenRespondWithDns(dnsResponse().withPrimary(true))), get(Expectation::getDnsResponse)},
            {"httpError", op(e -> e.thenError(error().withDropConnection(true).withPrimary(true))), get(Expectation::getHttpError)},
        });
    }

    private static UnaryOperator<Expectation> op(UnaryOperator<Expectation> operator) {
        return operator;
    }

    private static Function<Expectation, Action<?>> get(Function<Expectation, Action<?>> getter) {
        return getter;
    }

    @Test
    public void shouldKeepThePrimaryFlagThroughSerializationAndDeserialization() throws Exception {
        // given - the action under test marked primary, alongside a second action that is not
        Expectation expectation = withPrimaryAction.apply(new Expectation(request().withPath("/some/path")));
        if (secondaryIsAnError) {
            expectation.thenError(error().withDropConnection(true));
        } else {
            expectation.thenRespond(response().withStatusCode(200));
        }
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        // when
        String json = serializer.serialize(expectation);
        Expectation deserialized = serializer.deserialize(json);

        // then - the flag is on the wire
        JsonNode action = ObjectMapperFactory.createObjectMapper().readTree(json).get(jsonField);
        assertThat(json, action.path("primary").asBoolean(false), is(true));

        // and - the server reads it back and resolves this action as the primary one
        Action<?> primary = primaryAction.apply(deserialized);
        assertThat(primary.isPrimary(), is(true));
        assertThat(deserialized.getAction(), sameInstance(primary));
    }
}
