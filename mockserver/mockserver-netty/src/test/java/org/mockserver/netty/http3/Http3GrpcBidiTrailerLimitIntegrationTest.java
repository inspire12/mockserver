package org.mockserver.netty.http3;

import com.google.protobuf.Descriptors;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcBidiRule;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.NettyBufferLeaks;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A trailer section on a true gRPC bidi stream over HTTP/3 is limited by {@code maxHeaderSize} as any request's is:
 * one within the limit ends the request, and the response ends with its configured status while the connection's
 * other streams carry on; one over it closes the connection, and its other streams with it, with
 * {@code H3_EXCESSIVE_LOAD}, as for any stream, and one WARN that names the trailers. Needs the QUIC native library,
 * and fails without it.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3GrpcBidiTrailerLimitIntegrationTest {

    // above the 8,192 bytes Netty limits a field section to when it is not told otherwise
    private static final int LIMIT = 32 * 1024;
    private static final int FIELD_OVERHEAD = 32;
    private static final String TRAILER = "x-trailer";
    private static final Path DESCRIPTOR_DIR = Paths.get("../mockserver-core/src/test/resources/grpc").toAbsolutePath();
    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String CHAT = "/" + SERVICE + "/Chat";

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static NioEventLoopGroup clientGroup;
    private static Descriptors.MethodDescriptor chatMethod;
    private static GrpcJsonMessageConverter converter;
    private static int leaksBefore;

    @BeforeClass
    public static void startServer() {
        leaksBefore = NettyBufferLeaks.recorded();
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(DESCRIPTOR_DIR.resolve("greeting.dsc"));
        chatMethod = store.getMethod(SERVICE, "Chat");
        converter = store.getConverter();
        // the test JVM defaults to ERROR, which would drop the WARN entries this class asserts on
        mockServer = startWithHttp3(configuration()
            .http3MaxIdleTimeout(30000L)
            .maxHeaderSize(LIMIT)
            .grpcDescriptorDirectory(DESCRIPTOR_DIR.toString())
            .grpcBidiStreamingEnabled(true)
            .logLevel("WARN"));
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        clientGroup = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopServer() throws Exception {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
        NettyBufferLeaks.assertNoneSince(leaksBefore);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
        mockServerClient.when(request().withPath(CHAT)).respondWithGrpcBidi(
            GrpcBidiResponse.grpcBidiResponse()
                .withStatusName("OK")
                .withRule(GrpcBidiRule.grpcBidiRule(".*Alice.*").withResponse("{\"greeting\": \"Hello Alice\"}"))
        );
    }

    @Test
    public void shouldEndTheStreamNormallyForATrailerSectionJustUnderMaxHeaderSize() throws Exception {
        endsNormallyWithTrailersOfSize(LIMIT - 1);
    }

    @Test
    public void shouldEndTheStreamNormallyForATrailerSectionExactlyMaxHeaderSize() throws Exception {
        endsNormallyWithTrailersOfSize(LIMIT);
    }

    private void endsNormallyWithTrailersOfSize(int size) throws Exception {
        try (Http3TestClient connection = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange other = startChat(connection);
            Http3TestClient.Exchange chat = startChat(connection);

            chat.trailers(trailersOfSize(size));

            assertThat(chat.trailer(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
            assertThat(greetings(chat), contains("Hello Alice"));
            other.data(alice()).end();
            assertThat("the other stream carries on", other.trailer(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
            assertThat(greetings(other), contains("Hello Alice", "Hello Alice"));
        }
        assertThat(otherLogMessages(), is(empty()));
    }

    @Test
    public void shouldCloseTheConnectionForATrailerSectionOneByteOver() throws Exception {
        try (Http3TestClient connection = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange other = startChat(connection);
            Http3TestClient.Exchange chat = startChat(connection);

            chat.trailers(trailersOfSize(LIMIT + 1));

            QuicConnectionCloseEvent closed = connection.closedByServer();
            assertThat(closed.isApplicationClose(), is(true));
            assertThat(closed.error(), is(Http3ErrorCode.H3_EXCESSIVE_LOAD.code()));
            assertThrows("the other stream goes with the connection", ExecutionException.class, () -> other.trailer(GrpcStatusMapper.GRPC_STATUS_HEADER));
        }
        assertThat(otherLogMessages(), contains(containsString("because a request's trailer section is larger than maxHeaderSize")));

        try (Http3TestClient next = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange chat = startChat(next);
            chat.trailers(trailersOfSize(LIMIT));
            assertThat("the server carries on", chat.trailer(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
        }
    }

    /**
     * Opens a bidi stream and waits for the answer to its first message, so the stream is known to be on the bidi path.
     */
    private static Http3TestClient.Exchange startChat(Http3TestClient connection) throws Exception {
        Http3TestClient.Exchange chat = connection.start(new DefaultHttp3Headers()
            .method(HttpMethod.POST.asciiName())
            .scheme("https")
            .authority("127.0.0.1:" + mockServer.getHttp3Port())
            .path(CHAT)
            .set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .set("te", "trailers"));
        chat.data(alice());
        assertThat(chat.status(), is(200));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (greetings(chat).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat("answered while the request is still open", greetings(chat), contains("Hello Alice"));
        return chat;
    }

    private static byte[] alice() {
        return GrpcFrameCodec.encode(converter.toProtobuf("{\"name\": \"Alice\"}", chatMethod.getInputType()));
    }

    private static List<String> greetings(Http3TestClient.Exchange exchange) {
        return GrpcFrameCodec.decode(exchange.receivedByteArray()).stream()
            .map(message -> converter.toJson(message, chatMethod.getOutputType()))
            .map(json -> json.replaceAll("(?s).*\"greeting\":\\s*\"([^\"]*)\".*", "$1"))
            .collect(Collectors.toList());
    }

    /**
     * A trailer section of exactly {@code size} bytes as RFC 9114 counts it.
     */
    private static Http3Headers trailersOfSize(int size) {
        return new DefaultHttp3Headers().add(TRAILER, "a".repeat(size - TRAILER.length() - FIELD_OVERHEAD));
    }

    /**
     * What MockServer logged since the last reset, other than the requests it received and the responses it returned.
     */
    private static List<String> otherLogMessages() {
        return Arrays.stream(mockServerClient.retrieveLogMessagesArray(null))
            // an empty log is returned as one empty message
            .filter(message -> !message.isEmpty())
            .map(message -> message.substring(message.indexOf(" - ") + " - ".length()))
            .filter(message -> !message.startsWith("received request") && !message.startsWith("returning "))
            .collect(Collectors.toList());
    }
}
