package org.mockserver.netty.integration.mock;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersEncoder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersEncoder;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * An HTTP/2 request's header list is limited by {@code maxHeaderSize}, as an HTTP/1.1 request's header section is,
 * over cleartext HTTP/2 and over TLS: the limit is what MockServer advertises as {@code SETTINGS_MAX_HEADER_LIST_SIZE},
 * a list of exactly that size is served, one byte over is answered {@code 431} and its stream reset while the
 * connection carries on, and a header block more than a quarter over closes the connection. The size is the one
 * RFC 9113 section 6.5.2 defines: each field's name and value plus 32 bytes, the pseudo-header fields included.
 */
public class Http2HeaderListLimitIntegrationTest {

    // above the 8,192 bytes Netty limits a header list to when it is not told otherwise
    private static final int LIMIT = 32 * 1024;
    private static final int DEFAULT_LIMIT = 256 * 1024;
    private static final String FILLER = "x-filler";
    private static final int FIELD_OVERHEAD = 32;

    private static MockServer limited;
    private static MockServerClient limitedClient;
    private static MockServer defaults;
    private static MockServerClient defaultsClient;
    private static EventLoopGroup clientGroup;

    @BeforeClass
    public static void startServers() {
        // the test JVM defaults to ERROR, which would drop the WARN entries this class asserts on
        limited = new MockServer(configuration().maxHeaderSize(LIMIT).logLevel("WARN"), 0);
        limitedClient = new MockServerClient("localhost", limited.getLocalPort());
        defaults = new MockServer(configuration().logLevel("WARN"), 0);
        defaultsClient = new MockServerClient("localhost", defaults.getLocalPort());
        clientGroup = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(limitedClient);
        stopQuietly(limited);
        stopQuietly(defaultsClient);
        stopQuietly(defaults);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Before
    public void resetServers() {
        limitedClient.reset();
        limitedClient.when(request().withPath("/limit")).respond(response().withBody("served"));
        defaultsClient.reset();
        defaultsClient.when(request().withPath("/limit")).respond(response().withBody("served"));
    }

    @Test
    public void shouldAdvertiseMaxHeaderSizeAsTheHeaderListLimit() throws Exception {
        for (boolean tls : new boolean[]{false, true}) {
            try (Http2TestClient client = connect(limited, tls)) {
                assertThat(transport(tls), client.serverSettings().maxHeaderListSize(), is((long) LIMIT));
            }
            try (Http2TestClient client = connect(defaults, tls)) {
                assertThat(transport(tls), client.serverSettings().maxHeaderListSize(), is((long) DEFAULT_LIMIT));
            }
        }
    }

    @Test
    public void shouldServeAHeaderListOfExactlyMaxHeaderSize() throws Exception {
        for (boolean tls : new boolean[]{false, true}) {
            try (Http2TestClient client = connect(limited, tls)) {
                Http2Headers headers = headersOfSize(limited, tls, LIMIT);

                Http2TestClient.Exchange exchange = client.send(headers, true);

                assertThat(transport(tls), exchange.status(), is(200));
                assertThat(transport(tls), exchange.body(), is("served"));
            }
        }
        assertThat(limitedClient.retrieveRecordedRequests(request().withPath("/limit")).length, is(2));
        assertThat(limitedClient.retrieveRecordedRequests(request().withPath("/limit"))[0].getFirstHeader(FILLER).length(), greaterThan(LIMIT - 256));
    }

    @Test
    public void shouldAnswerAHeaderListOneByteOverWith431AndKeepTheConnection() throws Exception {
        for (boolean tls : new boolean[]{false, true}) {
            limitedClient.reset();
            limitedClient.when(request().withPath("/limit")).respond(response().withBody("served"));
            try (Http2TestClient client = connect(limited, tls)) {
                Http2Headers overLimit = headersOfSize(limited, tls, HttpMethod.POST, LIMIT + 1);

                // left open, so the reset that follows the 431 is not for a stream both sides have ended
                Http2TestClient.Exchange refused = client.send(overLimit, false);

                assertThat(transport(tls), refused.status(), is(431));
                assertThat(transport(tls), refused.resetErrorCode(), is(Http2Error.PROTOCOL_ERROR.code()));
                assertThat("never dispatched", limitedClient.retrieveRecordedRequests(request().withPath("/limit")), emptyArray());

                Http2TestClient.Exchange next = client.send(headersOfSize(limited, tls, LIMIT), true);
                assertThat("the connection carries on", next.status(), is(200));
                assertThat(next.body(), is("served"));
                assertThat(client.isOpen(), is(true));
            }
            assertThat(transport(tls), warnings(limitedClient, "because its header list is larger than maxHeaderSize"), is(1L));
        }
    }

    @Test
    public void shouldCloseTheConnectionForAHeaderBlockMoreThanAQuarterOver() throws Exception {
        for (boolean tls : new boolean[]{false, true}) {
            limitedClient.reset();
            try (Http2TestClient client = connect(limited, tls)) {
                // 'a' takes 5 bits in HPACK's Huffman code, so this block is about 1.9 times the limit
                Http2Headers farOver = headersOfSize(limited, tls, 3 * LIMIT);
                assertThat(encodedBytes(farOver), greaterThan(LIMIT + LIMIT / 4));

                client.send(farOver, true);

                assertThat(transport(tls), client.goAwayErrorCode(), is(Http2Error.PROTOCOL_ERROR.code()));
                assertThat(transport(tls), client.closedWithin(10), is(true));
            }
            assertThat("never dispatched", limitedClient.retrieveRecordedRequests(request().withPath("/limit")), emptyArray());
            assertThat(transport(tls), warnings(limitedClient, "because a request's header block is more than a quarter larger than maxHeaderSize"), is(1L));
        }
    }

    @Test
    public void shouldServeAHeaderListOverNettysDefaultLimitByDefault() throws Exception {
        for (boolean tls : new boolean[]{false, true}) {
            try (Http2TestClient client = connect(defaults, tls)) {
                Http2TestClient.Exchange exchange = client.send(headersOfSize(defaults, tls, 100 * 1024), true);

                assertThat(transport(tls), exchange.status(), is(200));
                assertThat(transport(tls), exchange.body(), is("served"));
            }
            try (Http2TestClient client = connect(defaults, tls)) {
                Http2TestClient.Exchange refused = client.send(headersOfSize(defaults, tls, HttpMethod.POST, DEFAULT_LIMIT + 1), false);

                assertThat(transport(tls), refused.status(), is(431));
            }
        }
    }

    /**
     * One 4 KiB field, sent once and then referred to 20,000 times from HPACK's dynamic table at a byte a time:
     * about 23 KB on the wire and 80 MB decoded, over 300 times the default limit. What decoding it allocates is
     * measured in {@code Http2RequestHeaderLimitTest}, away from this JVM's buffer leak tracking.
     */
    @Test
    public void shouldRefuseAHeaderBlockThatDecodesFarOverTheLimit() throws Exception {
        int references = 20_000;
        String value = "a".repeat(4000);
        long decodedBytes = (long) references * (FILLER.length() + value.length() + FIELD_OVERHEAD);
        for (boolean tls : new boolean[]{false, true}) {
            defaultsClient.reset();
            defaultsClient.when(request().withPath("/limit")).respond(response().withBody("served"));
            try (Http2TestClient client = connect(defaults, tls)) {
                Http2Headers bomb = pseudoHeaders(defaults, tls, HttpMethod.POST);
                for (int i = 0; i < references; i++) {
                    bomb.add(FILLER, value);
                }
                assertThat("small on the wire", encodedBytes(bomb), lessThan(32 * 1024));
                assertThat("far over the limit decoded", decodedBytes, greaterThan(300L * DEFAULT_LIMIT));

                Http2TestClient.Exchange refused = client.send(bomb, false);

                assertThat(transport(tls), refused.status(), is(431));
                assertThat(transport(tls), refused.resetErrorCode(), is(Http2Error.PROTOCOL_ERROR.code()));
                assertThat("the connection carries on", client.send(pseudoHeaders(defaults, tls), true).status(), is(200));
            }
            assertThat("only the request after it was dispatched", defaultsClient.retrieveRecordedRequests(request().withPath("/limit")).length, is(1));
            assertThat(transport(tls), warnings(defaultsClient, "because its header list is larger than maxHeaderSize"), is(1L));
        }
    }

    private static Http2TestClient connect(MockServer mockServer, boolean tls) throws Exception {
        return tls ? Http2TestClient.tls(clientGroup, mockServer.getLocalPort()) : Http2TestClient.h2c(clientGroup, mockServer.getLocalPort());
    }

    private static String transport(boolean tls) {
        return tls ? "over TLS" : "over cleartext";
    }

    private static Http2Headers pseudoHeaders(MockServer mockServer, boolean tls) {
        return pseudoHeaders(mockServer, tls, HttpMethod.GET);
    }

    private static Http2Headers pseudoHeaders(MockServer mockServer, boolean tls, HttpMethod method) {
        return new DefaultHttp2Headers()
            .method(method.asciiName())
            .scheme(tls ? "https" : "http")
            .authority("localhost:" + mockServer.getLocalPort())
            .path("/limit");
    }

    /**
     * A request whose header list is exactly {@code size} bytes as RFC 9113 counts it, made up with one filler field.
     */
    private static Http2Headers headersOfSize(MockServer mockServer, boolean tls, int size) {
        return headersOfSize(mockServer, tls, HttpMethod.GET, size);
    }

    private static Http2Headers headersOfSize(MockServer mockServer, boolean tls, HttpMethod method, int size) {
        Http2Headers headers = pseudoHeaders(mockServer, tls, method);
        long fillerValueLength = size - headerListSize(headers) - FILLER.length() - FIELD_OVERHEAD;
        headers.add(FILLER, "a".repeat((int) fillerValueLength));
        assertThat(headerListSize(headers), is((long) size));
        return headers;
    }

    private static long headerListSize(Http2Headers headers) {
        long size = 0;
        for (Map.Entry<CharSequence, CharSequence> field : headers) {
            size += field.getKey().length() + field.getValue().length() + FIELD_OVERHEAD;
        }
        return size;
    }

    private static int encodedBytes(Http2Headers headers) throws Exception {
        ByteBuf block = Unpooled.buffer();
        try {
            new DefaultHttp2HeadersEncoder(Http2HeadersEncoder.NEVER_SENSITIVE, true).encodeHeaders(1, headers, block);
            return block.readableBytes();
        } finally {
            block.release();
        }
    }

    private static long warnings(MockServerClient client, String text) {
        return Arrays.stream(client.retrieveLogMessagesArray(null)).filter(message -> message.contains(text)).count();
    }
}
