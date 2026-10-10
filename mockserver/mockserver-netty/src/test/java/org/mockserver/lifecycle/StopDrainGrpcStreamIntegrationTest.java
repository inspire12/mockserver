package org.mockserver.lifecycle;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.CallOptions;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import org.junit.After;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.model.Delay;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.GrpcStreamResponse.grpcStreamResponse;
import static org.mockserver.model.HttpRequest.request;

/**
 * A gRPC server stream is written straight to the channel, and its connection is left open across
 * {@code stop()}: once the stream has ended {@code stop()} must not wait out {@code stopDrainMillis}, and a
 * stream still being written is waited for. Over HTTP/2 (a real grpc-java client) each stream has its own child
 * channel, which closes with the stream; over HTTP/1.1 the connection outlives the stream, so only the end of
 * the stream (its trailers) can release the drain.
 */
public class StopDrainGrpcStreamIntegrationTest {

    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String STREAM_METHOD = "ListGreetings";
    private static final String GREETING_DESCRIPTOR = "../mockserver-core/src/test/resources/grpc/greeting.dsc";

    private static final long DRAIN_MILLIS = 15_000L;
    // far below the drain budget, and loose enough for a slow CI agent to stop an idle server
    private static final long PROMPT_STOP_MILLIS = 5_000L;

    private MockServer mockServer;
    private ManagedChannel channel;
    private Descriptors.Descriptor requestType;
    private Descriptors.Descriptor responseType;
    private MethodDescriptor<DynamicMessage, DynamicMessage> listGreetings;

    @After
    public void tearDown() throws Exception {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(10, TimeUnit.SECONDS);
        }
        if (mockServer != null && mockServer.isRunning()) {
            mockServer.stop();
        }
    }

    @Test
    public void stopIsPromptAfterGrpcServerStreamOnKeptAliveHttp2Connection() throws Exception {
        startServer(configuration());

        assertThat(readWholeStream(), contains("one", "two", "three"));
        assertThat("the HTTP/2 connection is still open", channel.getState(false), is(ConnectivityState.READY));

        assertStopIsPrompt();
    }

    @Test
    public void stopIsPromptAfterGrpcServerStreamWithGrpcBidiStreamingEnabled() throws Exception {
        startServer(configuration().grpcBidiStreamingEnabled(true));

        assertThat(readWholeStream(), contains("one", "two", "three"));
        assertThat("the HTTP/2 connection is still open", channel.getState(false), is(ConnectivityState.READY));

        assertStopIsPrompt();
    }

    @Test
    public void stopIsPromptAfterGrpcServerStreamOnKeepAliveHttp1Connection() throws Exception {
        MockServerClient mockServerClient = startServer(configuration());
        mockServerClient
            .when(request().withPath("/" + SERVICE + "/" + STREAM_METHOD))
            .respondWithGrpcStream(grpcStreamResponse()
                .withStatusName("OK")
                .withMessage("{\"greeting\":\"one\"}")
                .withMessage("{\"greeting\":\"last\"}"));

        try (Socket socket = new Socket("localhost", mockServer.getLocalPort())) {
            socket.setSoTimeout(15_000);
            byte[] message = helloRequest("World").toByteArray();
            byte[] frame = ByteBuffer.allocate(5 + message.length).put((byte) 0).putInt(message.length).put(message).array();
            OutputStream out = socket.getOutputStream();
            out.write(("POST /" + SERVICE + "/" + STREAM_METHOD + " HTTP/1.1\r\nHost: localhost:" + mockServer.getLocalPort()
                + "\r\nContent-Type: application/grpc\r\nTE: trailers\r\nContent-Length: " + frame.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.write(frame);
            out.flush();

            String stream = readUntil(socket.getInputStream(), "grpc-status: 0\r\n\r\n");
            assertThat(stream, startsWith("HTTP/1.1 200 "));
            assertThat(stream, containsString("last"));
            assertThat(stream, containsString("\r\n0\r\ngrpc-status: 0\r\n\r\n"));

            assertStopIsPrompt();
        }
    }

    @Test
    public void stopWaitsForGrpcServerStreamStillBeingWritten() throws Exception {
        long lastMessageDelayMillis = 1_500L;
        MockServerClient mockServerClient = startServer(configuration());
        mockServerClient
            .when(request().withPath("/" + SERVICE + "/" + STREAM_METHOD))
            .respondWithGrpcStream(grpcStreamResponse()
                .withStatusName("OK")
                .withMessage("{\"greeting\":\"first\"}")
                .withMessage("{\"greeting\":\"last\"}", new Delay(TimeUnit.MILLISECONDS, lastMessageDelayMillis)));

        List<String> received = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch firstMessage = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        ClientCalls.asyncServerStreamingCall(
            channel.newCall(listGreetings, CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS)),
            helloRequest("World"),
            new StreamObserver<DynamicMessage>() {
                @Override
                public void onNext(DynamicMessage message) {
                    received.add(greeting(message));
                    firstMessage.countDown();
                }

                @Override
                public void onError(Throwable throwable) {
                    error.set(throwable);
                    completed.countDown();
                }

                @Override
                public void onCompleted() {
                    completed.countDown();
                }
            });
        assertThat("the first message arrives", firstMessage.await(15, TimeUnit.SECONDS), is(true));

        long stopStart = System.currentTimeMillis();
        CompletableFuture<String> stopped = mockServer.stopAsync();
        assertThat("the stream ends", completed.await(DRAIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
        stopped.get(DRAIN_MILLIS * 2, TimeUnit.MILLISECONDS);
        long stopMillis = System.currentTimeMillis() - stopStart;

        assertThat("the stream ends with grpc-status OK", error.get(), is(nullValue()));
        assertThat("the stream is written whole before the server stops", received, contains("first", "last"));
        assertThat("stop waited for the last message, took " + stopMillis + "ms", stopMillis, is(greaterThanOrEqualTo(lastMessageDelayMillis / 2)));
        assertThat("stop did not wait out the drain budget, took " + stopMillis + "ms", stopMillis, is(lessThan(DRAIN_MILLIS / 2)));
    }

    private MockServerClient startServer(Configuration configuration) throws Exception {
        byte[] descriptorBytes = Files.readAllBytes(Paths.get(GREETING_DESCRIPTOR));
        Descriptors.MethodDescriptor method = serviceMethod(descriptorBytes);
        requestType = method.getInputType();
        responseType = method.getOutputType();
        listGreetings = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
            .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, STREAM_METHOD))
            .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(requestType)))
            .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(responseType)))
            .build();

        mockServer = new MockServer(configuration.stopDrainMillis(DRAIN_MILLIS), 0);
        MockServerClient mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        mockServerClient.uploadGrpcDescriptor(descriptorBytes);
        channel = NettyChannelBuilder.forAddress("localhost", mockServer.getLocalPort()).usePlaintext().build();
        return mockServerClient;
    }

    private List<String> readWholeStream() {
        new MockServerClient("localhost", mockServer.getLocalPort())
            .when(request().withPath("/" + SERVICE + "/" + STREAM_METHOD))
            .respondWithGrpcStream(grpcStreamResponse()
                .withStatusName("OK")
                .withMessage("{\"greeting\":\"one\"}")
                .withMessage("{\"greeting\":\"two\"}")
                .withMessage("{\"greeting\":\"three\"}"));

        List<String> received = new ArrayList<>();
        Iterator<DynamicMessage> replies = ClientCalls.blockingServerStreamingCall(
            channel, listGreetings, CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS), helloRequest("World"));
        while (replies.hasNext()) {
            received.add(greeting(replies.next()));
        }
        return received;
    }

    private void assertStopIsPrompt() {
        long start = System.currentTimeMillis();
        mockServer.stop();
        long stopMillis = System.currentTimeMillis() - start;
        assertThat("stop must not wait for a gRPC stream that has already ended, took " + stopMillis + "ms",
            stopMillis, is(lessThan(PROMPT_STOP_MILLIS)));
        assertThat(mockServer.getRequestsInFlight(), is(0));
    }

    private static String readUntil(InputStream inputStream, String terminator) throws IOException {
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        while (!read.toString(StandardCharsets.ISO_8859_1).endsWith(terminator)) {
            int next = inputStream.read();
            if (next == -1) {
                throw new IOException("connection closed before \"" + terminator + "\", having read: " + read.toString(StandardCharsets.ISO_8859_1));
            }
            read.write(next);
        }
        return read.toString(StandardCharsets.ISO_8859_1);
    }

    private DynamicMessage helloRequest(String name) {
        return DynamicMessage.newBuilder(requestType).setField(requestType.findFieldByName("name"), name).build();
    }

    private String greeting(DynamicMessage message) {
        return (String) message.getField(responseType.findFieldByName("greeting"));
    }

    private static Descriptors.MethodDescriptor serviceMethod(byte[] descriptorBytes) throws Exception {
        for (DescriptorProtos.FileDescriptorProto file : DescriptorProtos.FileDescriptorSet.parseFrom(descriptorBytes).getFileList()) {
            for (Descriptors.ServiceDescriptor service : Descriptors.FileDescriptor.buildFrom(file, new Descriptors.FileDescriptor[0]).getServices()) {
                if (service.getFullName().equals(SERVICE)) {
                    return service.findMethodByName(STREAM_METHOD);
                }
            }
        }
        throw new IllegalStateException("service not found in descriptors: " + SERVICE);
    }
}
