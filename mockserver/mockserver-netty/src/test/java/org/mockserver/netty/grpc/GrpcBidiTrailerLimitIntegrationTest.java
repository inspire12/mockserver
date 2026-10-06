package org.mockserver.netty.grpc;

import com.google.protobuf.Descriptors;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.client.MockServerClient;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcBidiRule;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;
import org.mockserver.netty.integration.NettyBufferLeaks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Trailers on a true gRPC bidi stream over HTTP/2 end the request, and are limited by {@code maxHeaderSize} as any
 * request's are. Trailers within the limit end the response with its configured {@code grpc-status}. A bidi stream
 * has its own pipeline, so trailers over the limit reach the stream's handler as an error: the stream ends with
 * {@code grpc-status} INTERNAL and one WARN names the trailers. Either way the connection's other streams carry on.
 * gRPC clients end a request on a DATA frame, so only other clients send request trailers.
 */
@RunWith(Parameterized.class)
public class GrpcBidiTrailerLimitIntegrationTest {

    // above the 8,192 bytes Netty limits a header list to when it is not told otherwise
    private static final int LIMIT = 32 * 1024;
    private static final int FIELD_OVERHEAD = 32;
    private static final String TRAILER = "x-trailer";
    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String CHAT = "/" + SERVICE + "/Chat";
    private static final Path DESCRIPTOR = Paths.get("../mockserver-core/src/test/resources/grpc/greeting.dsc");
    private static final String TRAILERS_OVER_THE_LIMIT = "because the request's trailers are larger than maxHeaderSize";

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static EventLoopGroup clientGroup;
    private static Descriptors.MethodDescriptor chatMethod;
    private static GrpcJsonMessageConverter converter;
    private static int leaksBefore;

    @Parameterized.Parameters(name = "tls={0}")
    public static Object[] routes() {
        return new Object[]{false, true};
    }

    @Parameterized.Parameter
    public boolean tls;

    @BeforeClass
    public static void startServer() throws Exception {
        leaksBefore = NettyBufferLeaks.recorded();
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(DESCRIPTOR);
        chatMethod = store.getMethod(SERVICE, "Chat");
        converter = store.getConverter();
        // the test JVM defaults to ERROR, which would drop the WARN entries this class asserts on
        mockServer = new MockServer(configuration().grpcBidiStreamingEnabled(true).maxHeaderSize(LIMIT).logLevel("WARN"), 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        mockServerClient.uploadGrpcDescriptor(Files.readAllBytes(DESCRIPTOR));
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
        // a reset keeps the loaded descriptors
        mockServerClient.reset();
        mockServerClient.when(request().withPath(CHAT)).respondWithGrpcBidi(
            GrpcBidiResponse.grpcBidiResponse()
                .withStatusName("OK")
                .withRule(GrpcBidiRule.grpcBidiRule(".*Alice.*").withResponse("{\"greeting\": \"Hello Alice\"}"))
        );
    }

    @Test
    public void shouldEndTheStreamWithItsStatusForTrailersJustUnderMaxHeaderSize() throws Exception {
        endsWithItsStatusForTrailersOfSize(LIMIT - 1);
    }

    @Test
    public void shouldEndTheStreamWithItsStatusForTrailersExactlyMaxHeaderSize() throws Exception {
        endsWithItsStatusForTrailersOfSize(LIMIT);
    }

    /**
     * Trailers end the request as END_STREAM on a DATA frame would: the response ends with its configured status.
     */
    private void endsWithItsStatusForTrailersOfSize(int size) throws Exception {
        try (Http2TestClient client = connect()) {
            Http2TestClient.Exchange other = startChat(client);
            Http2TestClient.Exchange chat = startChat(client);

            chat.trailers(trailersOfSize(size));

            assertThat(chat.trailer(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
            assertThat(greetings(chat), contains("Hello Alice"));
            assertTheOtherStreamCarriesOn(client, other);
        }
        assertThat(warningsAndErrors(), is(empty()));
    }

    @Test
    public void shouldEndAServerReflectionStreamWithItsStatusForTrailersExactlyMaxHeaderSize() throws Exception {
        try (Http2TestClient client = connect()) {
            Http2TestClient.Exchange other = startChat(client);
            // a ServerReflectionRequest asking for list_services (field 7, an empty string)
            Http2TestClient.Exchange reflection = client.send(chatHeaders().path("/grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo"), false)
                .data(GrpcFrameCodec.encode(new byte[]{0x3a, 0x00}), false);

            reflection.trailers(trailersOfSize(LIMIT));

            assertThat(reflection.trailer(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
            assertThat("the services were listed", new String(reflection.receivedByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1), containsString(SERVICE));
            assertTheOtherStreamCarriesOn(client, other);
        }
        assertThat(warningsAndErrors(), is(empty()));
    }

    @Test
    public void shouldEndTheStreamWithInternalForTrailersOneByteOverMaxHeaderSize() throws Exception {
        try (Http2TestClient client = connect()) {
            Http2TestClient.Exchange other = startChat(client);
            Http2TestClient.Exchange chat = startChat(client);

            chat.trailers(trailersOfSize(LIMIT + 1));

            assertThat(chat.trailer(GrpcStatusMapper.GRPC_STATUS_HEADER), is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.INTERNAL.getCode())));
            assertThat("nothing more on the ended stream", greetings(chat), contains("Hello Alice"));
            assertTheOtherStreamCarriesOn(client, other);
        }
        assertThat(warningsAndErrors(), contains(containsString(TRAILERS_OVER_THE_LIMIT)));
    }

    private void assertTheOtherStreamCarriesOn(Http2TestClient client, Http2TestClient.Exchange other) throws Exception {
        other.data(message("Alice again"), true);
        assertThat("the other stream carries on", other.trailer(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
        assertThat(greetings(other), contains("Hello Alice", "Hello Alice"));
        assertThat("the connection carries on", client.send(chatHeaders(), false).data(message("Alice"), true).trailer(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
        assertThat(client.isOpen(), is(true));
    }

    /**
     * Opens a bidi stream and waits for the answer to its first message, so the stream is known to be on the bidi path.
     */
    private Http2TestClient.Exchange startChat(Http2TestClient client) throws Exception {
        Http2TestClient.Exchange chat = client.send(chatHeaders(), false).data(message("Alice"), false);
        assertThat(chat.status(), is(200));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (greetings(chat).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat("answered while the request is still open", greetings(chat), contains("Hello Alice"));
        return chat;
    }

    private Http2TestClient connect() throws Exception {
        return tls ? Http2TestClient.tls(clientGroup, mockServer.getLocalPort()) : Http2TestClient.h2c(clientGroup, mockServer.getLocalPort());
    }

    private Http2Headers chatHeaders() {
        return new DefaultHttp2Headers()
            .method("POST")
            .scheme(tls ? "https" : "http")
            .authority("127.0.0.1:" + mockServer.getLocalPort())
            .path(CHAT)
            .set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .set("te", "trailers");
    }

    private static byte[] message(String name) {
        return GrpcFrameCodec.encode(converter.toProtobuf("{\"name\": \"" + name + "\"}", chatMethod.getInputType()));
    }

    private static List<String> greetings(Http2TestClient.Exchange exchange) {
        byte[] received = exchange.receivedByteArray();
        return GrpcFrameCodec.decode(received).stream()
            .map(message -> converter.toJson(message, chatMethod.getOutputType()))
            .map(json -> json.replaceAll("(?s).*\"greeting\":\\s*\"([^\"]*)\".*", "$1"))
            .collect(Collectors.toList());
    }

    /**
     * Trailers whose header list is exactly {@code size} bytes as RFC 9113 counts it.
     */
    private static Http2Headers trailersOfSize(int size) {
        return new DefaultHttp2Headers().add(TRAILER, "a".repeat(size - TRAILER.length() - FIELD_OVERHEAD));
    }

    /**
     * What MockServer logged since the last clear, other than the requests it received and the responses it returned.
     */
    private static List<String> warningsAndErrors() {
        return Arrays.stream(mockServerClient.retrieveLogMessagesArray(null))
            // an empty log is returned as one empty message
            .filter(message -> !message.isEmpty())
            .map(message -> message.substring(message.indexOf(" - ") + " - ".length()))
            .filter(message -> !message.startsWith("received request") && !message.startsWith("returning "))
            .collect(Collectors.toList());
    }
}
