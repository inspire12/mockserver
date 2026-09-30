package org.mockserver.matchers;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.FullHttpRequestToMockServerHttpRequest;
import org.mockserver.mappers.FullHttpResponseToMockServerHttpResponse;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Body;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;
import org.mockserver.model.RequestDefinition;

import java.util.Collections;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.JsonPathBody.jsonPath;
import static org.mockserver.model.RegexBody.regex;
import static org.mockserver.model.StringBody.exact;
import static org.mockserver.model.StringBody.subString;

/**
 * Bodies sent with no Content-Type, decoded exactly as the server decodes them, must keep matching the
 * string, JSON and binary matchers they always matched - whether they stay a string (valid UTF-8) or are
 * kept as binary (not valid UTF-8) so they can be forwarded byte-identical.
 */
public class NoContentTypeBodyMatchingTest {

    private final Configuration configuration = configuration();
    private final MockServerLogger mockServerLogger = new MockServerLogger(NoContentTypeBodyMatchingTest.class);

    private HttpRequest receivedWithoutContentType(byte[] body) {
        DefaultFullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/some_path", Unpooled.wrappedBuffer(body));
        return new FullHttpRequestToMockServerHttpRequest(configuration, mockServerLogger, false, null, 1080)
            .mapFullHttpRequestToMockServerRequest(nettyRequest, Collections.emptyList(), null, null, Protocol.HTTP_1_1);
    }

    private boolean matches(RequestDefinition expected, HttpRequest actual) {
        HttpRequestPropertiesMatcher matcher = new HttpRequestPropertiesMatcher(configuration, mockServerLogger);
        matcher.update(new Expectation(expected));
        return matcher.matches(null, actual);
    }

    @Test
    public void shouldMatchUtf8JsonAndTextSentWithoutContentType() {
        HttpRequest jsonRequest = receivedWithoutContentType("{\"name\":\"şarəs\",\"id\":1}".getBytes(UTF_8));
        HttpRequest textRequest = receivedWithoutContentType("some şarəs text".getBytes(UTF_8));

        assertThat(jsonRequest.getBody().getType(), is(Body.Type.STRING));
        assertThat(matches(request().withBody(json("{\"id\":1}")), jsonRequest), is(true));
        assertThat(matches(request().withBody(json("{\"name\":\"şarəs\",\"id\":1}")), jsonRequest), is(true));
        assertThat(matches(request().withBody(jsonPath("$[?(@.name == 'şarəs')]")), jsonRequest), is(true));
        assertThat(matches(request().withBody(json("{\"id\":2}")), jsonRequest), is(false));

        assertThat(textRequest.getBody().getType(), is(Body.Type.STRING));
        assertThat(matches(request().withBody(exact("some şarəs text")), textRequest), is(true));
        assertThat(matches(request().withBody(subString("şarəs")), textRequest), is(true));
        assertThat(matches(request().withBody(regex("some .* text")), textRequest), is(true));
        assertThat(matches(request().withBody(exact("other text")), textRequest), is(false));
    }

    @Test
    public void shouldMatchNonUtf8BodySentWithoutContentTypeAgainstItsLenientUtf8Text() {
        // Latin-1 JSON/text with no Content-Type is not valid UTF-8, so it is kept as binary; string and
        // JSON matchers still read it as the lenient UTF-8 text they always did (é becomes U+FFFD)
        HttpRequest jsonRequest = receivedWithoutContentType("{\"name\":\"José\",\"id\":1}".getBytes(ISO_8859_1));
        HttpRequest textRequest = receivedWithoutContentType("café au lait".getBytes(ISO_8859_1));

        assertThat(jsonRequest.getBody().getType(), is(Body.Type.BINARY));
        assertThat(matches(request().withBody(json("{\"id\":1}")), jsonRequest), is(true));
        assertThat(matches(request().withBody(json("{\"id\":2}")), jsonRequest), is(false));

        assertThat(textRequest.getBody().getType(), is(Body.Type.BINARY));
        assertThat(matches(request().withBody(subString("au lait")), textRequest), is(true));
        assertThat(matches(request().withBody(regex("caf. au lait")), textRequest), is(true));
        assertThat(matches(request().withBody(exact("caf\uFFFD au lait")), textRequest), is(true));
        assertThat(matches(request().withBody(subString("espresso")), textRequest), is(false));
    }

    @Test
    public void shouldDecodeNonUtf8BodySentWithoutContentTypeOncePerMatchingScan() {
        HttpRequest request = receivedWithoutContentType("café au lait".getBytes(ISO_8859_1));

        assertThat(request.openParsedBodyCache(), is(true));
        String first;
        try {
            // each candidate expectation gets its own BodySource; the scan's cache shares one decode
            first = BodyMatching.of(request).getBodyAsString();
            assertThat(BodyMatching.of(request).getBodyAsString(), sameInstance(first));
        } finally {
            request.closeParsedBodyCache();
        }

        assertThat(first, is("caf\uFFFD au lait"));
        assertThat(BodyMatching.of(request).getBodyAsString(), is(first));
        assertThat(BodyMatching.of(request).getBodyAsString(), not(sameInstance(first)));
    }

    @Test
    public void shouldReadNonUtf8BodySentWithoutContentTypeAsLenientUtf8OnTheControlPlane() {
        HttpRequest controlPlaneRequest = receivedWithoutContentType("{\"path\":\"/José\"}".getBytes(ISO_8859_1));

        assertThat(controlPlaneRequest.getBody().getType(), is(Body.Type.BINARY));
        assertThat(controlPlaneRequest.getBodyAsJsonOrXmlString(), is("{\"path\":\"/Jos\uFFFD\"}"));
        assertThat(controlPlaneRequest.getBodyAsText(), is("{\"path\":\"/Jos\uFFFD\"}"));
    }

    @Test
    public void shouldMatchForwardedResponseWithNonUtf8BodyAndNoContentTypeAgainstItsLenientUtf8Text() {
        HttpResponse upstreamResponse = new FullHttpResponseToMockServerHttpResponse(mockServerLogger)
            .mapFullHttpResponseToMockServerResponse(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.wrappedBuffer("{\"name\":\"José\",\"id\":1}".getBytes(ISO_8859_1))));

        assertThat(upstreamResponse.getBody().getType(), is(Body.Type.BINARY));
        assertThat(new HttpResponseMatcher(configuration, mockServerLogger, response().withBody(json("{\"id\":1}"))).matches(upstreamResponse), is(true));
        assertThat(new HttpResponseMatcher(configuration, mockServerLogger, response().withBody(json("{\"id\":2}"))).matches(upstreamResponse), is(false));
    }

    @Test
    public void shouldMatchBinaryMatcherAgainstExactBytesSentWithoutContentType() {
        byte[] bytes = new byte[]{(byte) 0xFF, (byte) 0xD8, 0x00, 0x7F, (byte) 0x80, 'a'};
        HttpRequest binaryRequest = receivedWithoutContentType(bytes);

        assertThat(binaryRequest.getBody().getType(), is(Body.Type.BINARY));
        assertThat(binaryRequest.getBodyAsRawBytes(), is(bytes));
        assertThat(matches(request().withBody(binary(bytes)), binaryRequest), is(true));
        assertThat(matches(request().withBody(binary(new byte[]{(byte) 0xFF, (byte) 0xD8})), binaryRequest), is(false));
    }
}
