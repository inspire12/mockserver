package org.mockserver.netty.integration.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural guard that the HTTP parser limits {@code maxHeaderSize} and {@code maxInitialLineLength} are wired into
 * the Netty {@code HttpServerCodec} in the HTTP/1.1 request pipeline ({@code PortUnificationHandler.switchToHttp}) and
 * that the <em>configured</em> values, not Netty's defaults (8192 and 4096 bytes), are the ones enforced against a
 * real socket.
 * <p>
 * An over-limit request is refused: {@code 431} for a header section over {@code maxHeaderSize}, {@code 414} for a
 * request line over {@code maxInitialLineLength}, each with {@code Connection: close}, and the request is not
 * dispatched (nothing is recorded). Netty's decoder marks such a request as failed and stops parsing it; before
 * {@code HttpChunkLineLimiter} refused it, the request was served from the headers parsed so far, or as Netty's
 * synthetic {@code /bad-request}.
 * <p>
 * Each filler sits strictly between the configured limit and Netty's default, so the tests are a positive control:
 * reverting the wiring to the defaults lets the whole request through, it matches the expectation, and the
 * over-limit assertions turn red.
 * <p>
 * The third parser limit, {@code maxChunkSize}, is deliberately <strong>not</strong> covered here: in
 * Netty's {@code HttpObjectDecoder} it is only ever applied as {@code Math.min(bytesAvailable,
 * maxChunkSize)} to bound how many body bytes are emitted per {@code HttpContent} — an oversized wire
 * chunk is <em>split</em> into several {@code HttpContent} messages, never rejected, and the downstream
 * {@code HttpObjectAggregator} reassembles the full body regardless. There is therefore no
 * client-observable effect to assert, so a {@code maxChunkSize} case would be vacuous.
 *
 * @author jamesdbloom
 */
public class HttpParserLimitsIntegrationTest {

    private static final int MAX_HEADER_SIZE = 1024;
    // strictly between the configured limit (1024) and Netty's default header limit (8192): large
    // enough to push the marker header past the configured cap, small enough to fit under the default
    private static final int FILLER_LENGTH = 2048;
    private static final int MAX_INITIAL_LINE_LENGTH = 250;
    // query-string filler for the request line: pushes the initial line past the configured 250-byte
    // cap while staying under Netty's 4096-byte default so the default (positive control) still parses it
    private static final int INITIAL_LINE_FILLER_LENGTH = 1024;
    private static final long READ_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);

    private MockServer mockServer;
    private MockServerClient mockServerClient;

    @Before
    public void startServer() {
        Configuration configuration = configuration()
            .useNativeTransport(false)
            .maxHeaderSize(MAX_HEADER_SIZE)
            .maxInitialLineLength(MAX_INITIAL_LINE_LENGTH);
        mockServer = new MockServer(configuration, 0);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        // only matches when the marker header survives parsing
        mockServerClient
            .when(request().withPath("/limits").withHeader(header("X-Marker", "present")))
            .respond(response().withBody("marker-seen"));
    }

    @After
    public void stopServer() {
        if (mockServerClient != null) {
            mockServerClient.close();
        }
        if (mockServer != null && mockServer.isRunning()) {
            mockServer.stop();
        }
    }

    @Test
    public void shouldParseMarkerHeaderWhenTotalHeadersAreUnderTheConfiguredMaxHeaderSize() throws Exception {
        // marker header well within the 1024-byte limit -> parsed -> expectation matches
        String rawRequest = "GET /limits HTTP/1.1\r\n"
            + "Host: localhost:" + mockServer.getLocalPort() + "\r\n"
            + "Connection: close\r\n"
            + "X-Marker: present\r\n"
            + "\r\n";

        String response = sendRawRequestAndReadResponse(rawRequest);

        assertThat("a marker header under maxHeaderSize must be parsed and match the expectation, "
                + "actual response:\n" + response,
            response, containsString("200"));
        assertThat(response, containsString("marker-seen"));
    }

    @Test
    public void shouldRefuseAHeaderSectionOverTheConfiguredMaxHeaderSize() throws Exception {
        // a ~2KB filler header ahead of the marker pushes the header section past the 1024-byte cap
        String rawRequest = "GET /limits HTTP/1.1\r\n"
            + "Host: localhost:" + mockServer.getLocalPort() + "\r\n"
            + "Connection: close\r\n"
            + "X-Filler: " + repeat('a', FILLER_LENGTH) + "\r\n"
            + "X-Marker: present\r\n"
            + "\r\n";

        String response = sendRawRequestAndReadResponse(rawRequest);

        // with the configured limit not enforced (Netty's 8192 default) the request would match: "marker-seen"
        assertThat("an over-limit header section must be refused with 431, actual response:\n" + response,
            response, startsWith("HTTP/1.1 431 Request Header Fields Too Large\r\n"));
        assertThat(response.toLowerCase(), containsString("connection: close\r\n"));
        assertNothingDispatched();
    }

    @Test
    public void shouldAcceptAHeaderSectionOfExactlyMaxHeaderSizeWhenItsLastLineEndArrivesSplitAndRefuseOneByteMore() throws Exception {
        String host = "Host: localhost:" + mockServer.getLocalPort();
        // the header lines without their line ends: "X-Filler: " is 10 bytes, the other two 17 each
        int fillerAtLimit = MAX_HEADER_SIZE - host.length() - 17 - 17 - 10;
        for (int filler : new int[]{fillerAtLimit, fillerAtLimit + 1}) {
            String head = "GET /limits HTTP/1.1\r\n" + host + "\r\nConnection: close\r\nX-Marker: present\r\nX-Filler: " + repeat('a', filler) + "\r\n\r\n";
            int afterLastCr = head.length() - 3;

            String response = sendRawRequestInTwoReadsAndReadResponse(head.substring(0, afterLastCr), head.substring(afterLastCr));

            if (filler == fillerAtLimit) {
                assertThat("a header section of exactly maxHeaderSize is accepted, actual response:\n" + response, response, startsWith("HTTP/1.1 200 OK\r\n"));
                assertThat(response, containsString("marker-seen"));
            } else {
                assertThat("one byte more is refused, actual response:\n" + response, response, startsWith("HTTP/1.1 431 Request Header Fields Too Large\r\n"));
            }
        }
    }

    @Test
    public void shouldMatchRequestWhoseInitialLineIsUnderTheConfiguredMaxInitialLineLength() throws Exception {
        // request line (with a short query string) well within the 250-byte limit -> parsed -> matches
        String rawRequest = "GET /limits?ok=1 HTTP/1.1\r\n"
            + "Host: localhost:" + mockServer.getLocalPort() + "\r\n"
            + "Connection: close\r\n"
            + "X-Marker: present\r\n"
            + "\r\n";

        String response = sendRawRequestAndReadResponse(rawRequest);

        assertThat("a request line under maxInitialLineLength must be parsed and match the expectation, "
                + "actual response:\n" + response,
            response, containsString("200"));
        assertThat(response, containsString("marker-seen"));
    }

    @Test
    public void shouldRefuseARequestLineOverTheConfiguredMaxInitialLineLength() throws Exception {
        // a ~1KB filler query string pushes the request line past the 250-byte cap
        String rawRequest = "GET /limits?filler=" + repeat('a', INITIAL_LINE_FILLER_LENGTH) + " HTTP/1.1\r\n"
            + "Host: localhost:" + mockServer.getLocalPort() + "\r\n"
            + "Connection: close\r\n"
            + "X-Marker: present\r\n"
            + "\r\n";

        String response = sendRawRequestAndReadResponse(rawRequest);

        // with the configured limit not enforced (Netty's 4096 default) the request would match: "marker-seen"
        assertThat("an over-limit request line must be refused with 414, actual response:\n" + response,
            response, startsWith("HTTP/1.1 414 Request-URI Too Long\r\n"));
        assertThat(response.toLowerCase(), containsString("connection: close\r\n"));
        assertNothingDispatched();
    }

    private void assertNothingDispatched() {
        assertThat(mockServerClient.retrieveRecordedRequests(request()), emptyArray());
    }

    private String sendRawRequestAndReadResponse(String rawRequest) throws IOException, InterruptedException {
        return sendRawRequestInTwoReadsAndReadResponse(rawRequest, "");
    }

    /**
     * Writes {@code second} long enough after {@code first} that MockServer reads them apart.
     */
    private String sendRawRequestInTwoReadsAndReadResponse(String first, String second) throws IOException, InterruptedException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", mockServer.getLocalPort()), 2_000);
            socket.setSoTimeout((int) READ_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();
            out.write(first.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            if (!second.isEmpty()) {
                Thread.sleep(300);
                out.write(second.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
                char[] buffer = new char[1024];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    response.append(buffer, 0, read);
                }
            } catch (SocketTimeoutException timedOut) {
                // return whatever was received so the assertion message shows the actual response
            }
            return response.toString();
        }
    }

    private static String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }
}
