package org.mockserver.codec;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.LlmConversationMatcher;
import org.mockserver.mock.breakpoint.BreakpointMatcher;
import org.mockserver.mock.breakpoint.BreakpointPhase;
import org.mockserver.model.Body;
import org.mockserver.model.BodyWithContentType;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;
import org.mockserver.openapi.OpenAPIRequestValidator;

import java.util.EnumSet;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A Latin-1 body sent with no Content-Type is kept as binary so it can be forwarded byte-identical;
 * the readers that interpret an inbound or forwarded body as text must still see the lenient UTF-8
 * text they always did, not base64.
 */
public class NoContentTypeBodyTextReadersTest {

    private static BodyWithContentType<?> receivedWithoutContentType(String latin1Text) {
        BodyWithContentType<?> body = new BodyDecoderEncoder().bytesToBody(latin1Text.getBytes(ISO_8859_1), null);
        assertThat(body.getType(), is(Body.Type.BINARY));
        return body;
    }

    @Test
    public void shouldMatchLlmConversationInNonUtf8BodyWithoutContentType() {
        HttpRequest llmRequest = request("/v1/messages")
            .withBody(receivedWithoutContentType("{\"messages\":[{\"role\":\"user\",\"content\":\"un café, hello\"}]}"));

        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withLatestMessageContains("hello");

        assertThat(matcher.matches(llmRequest), is(true));
    }

    @Test
    public void shouldMatchBreakpointResponseConditionOnNonUtf8BodyWithoutContentType() {
        HttpResponse forwardedResponse = response().withStatusCode(200)
            .withBody(receivedWithoutContentType("{\"status\":\"déjà vu\",\"code\":\"E42\"}"));

        BreakpointMatcher breakpoint = new BreakpointMatcher("breakpoint-1", request().withPath("/any"),
            EnumSet.of(BreakpointPhase.RESPONSE), null, null, null, null, null, "\"code\":\"E42\"");

        assertThat(breakpoint.responseConditionMatches(forwardedResponse), is(true));
    }

    @Test
    public void shouldKeepBase64ForUserBinaryBodyThatDeclaresAContentType() {
        // a user-authored binary(bytes, PNG) body with no Content-Type header is still an image: exports
        // and readers keep its base64 form; only a body received with no content type at all is read as text
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', (byte) 0xFF, 0x00};
        HttpResponse userResponse = response().withBody(org.mockserver.model.BinaryBody.binary(png, org.mockserver.model.MediaType.PNG));
        String postman = new org.mockserver.serialization.ExpectationExportSerializer(new MockServerLogger())
            .serializeAsPostmanCollection(java.util.Collections.singletonList(
                new org.mockserver.mock.Expectation(request("/image")).thenRespond(userResponse)));

        assertThat(userResponse.getBodyAsText(), is(java.util.Base64.getEncoder().encodeToString(png)));
        assertThat(postman.contains(java.util.Base64.getEncoder().encodeToString(png)), is(true));
        assertThat(response().withBody(receivedWithoutContentType("caf\u00e9")).getBodyAsText(), is("caf\uFFFD"));
    }

    @Test
    public void shouldValidateNonUtf8JsonBodyWithoutContentTypeAgainstOpenApi() {
        HttpRequest postPet = request("/pets")
            .withMethod("POST")
            .withBody(receivedWithoutContentType("{\"id\": 1, \"name\": \"Café\"}"));

        assertThat(OpenAPIRequestValidator.validate("org/mockserver/openapi/openapi_petstore_example.json", postPet, new MockServerLogger()), is(empty()));
    }
}
