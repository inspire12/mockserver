package org.mockserver.mappers;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpObject;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Body;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;
import org.mockserver.serialization.HttpRequestSerializer;
import org.mockserver.serialization.HttpResponseSerializer;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.HttpResponse.response;

/**
 * A body with no Content-Type that is not valid UTF-8 must pass through MockServer byte-identical on
 * every leg: decoded from the wire, written back to the wire, and serialised for retrieval.
 */
public class NoContentTypeBinaryBodyRoundTripTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger(NoContentTypeBinaryBodyRoundTripTest.class);

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new Random(2461).nextBytes(bytes);
        return bytes;
    }

    @Test
    public void shouldWriteForwardedUpstreamResponseWithNoContentTypeByteIdentical() {
        // given - an upstream response with a binary body and no Content-Type
        byte[] upstreamBytes = randomBytes(1_000_000);
        FullHttpResponse upstream = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(upstreamBytes));

        // when - decoded as the forward client does, then written back to the client
        HttpResponse decoded = new FullHttpResponseToMockServerHttpResponse(mockServerLogger).mapFullHttpResponseToMockServerResponse(upstream);
        List<DefaultHttpObject> written = new MockServerHttpResponseToFullHttpResponse(mockServerLogger).mapMockServerResponseToNettyResponse(decoded);

        // then
        byte[] writtenBytes = contentOf(written);
        assertThat(writtenBytes.length, is(upstreamBytes.length));
        assertThat(writtenBytes, is(upstreamBytes));
        assertThat(headersOf(written).contains(CONTENT_TYPE), is(false));
        assertThat(decoded.getBody().getType(), is(Body.Type.BINARY));
    }

    @Test
    public void shouldForwardInboundRequestWithNoContentTypeByteIdentical() {
        // given - an inbound request with a binary body and no Content-Type
        byte[] requestBytes = randomBytes(250_000);
        FullHttpRequest inbound = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", Unpooled.wrappedBuffer(requestBytes));
        inbound.headers().set("Host", "localhost:1080");

        // when - decoded as the server does, then encoded for the upstream as the forward client does
        HttpRequest decoded = new FullHttpRequestToMockServerHttpRequest(configuration(), mockServerLogger, false, null, 1080)
            .mapFullHttpRequestToMockServerRequest(inbound, Collections.emptyList(), null, null, Protocol.HTTP_1_1);
        FullHttpRequest forwarded = new MockServerHttpRequestToFullHttpRequest(mockServerLogger, null).mapMockServerRequestToNettyRequest(decoded);

        // then
        byte[] forwardedBytes = ByteBufUtil.getBytes(forwarded.content());
        assertThat(forwardedBytes.length, is(requestBytes.length));
        assertThat(forwardedBytes, is(requestBytes));
        assertThat(forwarded.headers().contains(CONTENT_TYPE), is(false));
        assertThat(decoded.getBodyAsRawBytes(), is(requestBytes));
        assertThat(decoded.getBody().getType(), is(Body.Type.BINARY));
    }

    @Test
    public void shouldRetrieveRecordedRequestAndResponseWithNoContentTypeByteIdentical() {
        // given - a recorded request and response decoded from bodies with no Content-Type
        byte[] requestBytes = randomBytes(4096);
        byte[] responseBytes = randomBytes(8192);
        FullHttpRequest inbound = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", Unpooled.wrappedBuffer(requestBytes));
        HttpRequest recordedRequest = new FullHttpRequestToMockServerHttpRequest(configuration(), mockServerLogger, false, null, 1080)
            .mapFullHttpRequestToMockServerRequest(inbound, Collections.emptyList(), null, null, Protocol.HTTP_1_1);
        HttpResponse recordedResponse = new FullHttpResponseToMockServerHttpResponse(mockServerLogger)
            .mapFullHttpResponseToMockServerResponse(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(responseBytes)));

        // when - serialised as the retrieve API does and read back as a client does
        String requestJson = new HttpRequestSerializer(mockServerLogger).serialize(true, recordedRequest);
        String responseJson = new HttpResponseSerializer(mockServerLogger).serialize(recordedResponse);
        HttpRequest retrievedRequest = new HttpRequestSerializer(mockServerLogger).deserialize(requestJson);
        HttpResponse retrievedResponse = new HttpResponseSerializer(mockServerLogger).deserialize(responseJson);

        // then - the existing BINARY body shape, with no invented content type
        assertThat(retrievedRequest.getBodyAsRawBytes(), is(requestBytes));
        assertThat(retrievedResponse.getBodyAsRawBytes(), is(responseBytes));
        assertThat(requestJson, containsString("\"type\" : \"BINARY\""));
        assertThat(requestJson, not(containsString("contentType")));
    }

    @Test
    public void shouldWriteExpectationResponseWithBinaryBodyAndNoContentTypeByteIdentical() {
        // given
        byte[] responseBytes = randomBytes(100_000);

        // when
        List<DefaultHttpObject> written = new MockServerHttpResponseToFullHttpResponse(mockServerLogger)
            .mapMockServerResponseToNettyResponse(response().withBody(binary(responseBytes)));

        // then
        assertThat(contentOf(written), is(responseBytes));
        assertThat(headersOf(written).contains(CONTENT_TYPE), is(false));
    }

    private static byte[] contentOf(List<DefaultHttpObject> httpObjects) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (DefaultHttpObject httpObject : httpObjects) {
            if (httpObject instanceof HttpContent) {
                ByteBuf buf = ((HttpContent) httpObject).content();
                content.writeBytes(ByteBufUtil.getBytes(buf));
            }
        }
        return content.toByteArray();
    }

    private static HttpHeaders headersOf(List<DefaultHttpObject> httpObjects) {
        return ((HttpMessage) httpObjects.get(0)).headers();
    }
}
