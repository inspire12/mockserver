package org.mockserver.mock;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultHttpObject;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.codec.BodyDecoderEncoder;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.MockServerHttpResponseToFullHttpResponse;
import org.mockserver.model.Format;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RetrieveType;
import org.mockserver.model.SegmentedBytes;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ExpectationExportSerializer;
import org.slf4j.event.Level;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The OpenAPI, Postman and Bruno retrieves are written straight into the bytes the frontend writes.
 * A failure part-way through discards what was written and answers what the String-built export
 * answered on a failure, which the response can still do because it is built whole before it is sent.
 */
public class HttpStateRetrieveExportTest {

    private static final String OPENAPI_FAILURE = "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"MockServer Exported Expectations\",\"version\":\"1.0\"},\"paths\":{}}";
    private static final RetrieveType[] EXPORTING = {RetrieveType.REQUESTS, RetrieveType.REQUEST_RESPONSES, RetrieveType.RECORDED_EXPECTATIONS, RetrieveType.ACTIVE_EXPECTATIONS};

    private HttpState httpState;
    private ScheduledExecutorService schedulerExecutor;

    @Before
    public void setUp() {
        Configuration configuration = configuration().logLevel(Level.WARN);
        Scheduler scheduler = mock(Scheduler.class);
        schedulerExecutor = Executors.newScheduledThreadPool(2);
        when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler);
        HttpStateRetrieveGoldenTest.populate(httpState);
    }

    @After
    public void tearDown() {
        if (httpState != null) {
            httpState.stop();
        }
        schedulerExecutor.shutdownNow();
    }

    private void failPartWay() throws ReflectiveOperationException {
        ExpectationExportSerializer failing = new ExpectationExportSerializer(new MockServerLogger()) {
            @Override
            public void writeOpenApi(List<Expectation> expectations, Writer writer) throws IOException {
                writer.write("{\"openapi\":");
                throw new IOException("failed part-way");
            }

            @Override
            public void writePostmanCollection(List<Expectation> expectations, Writer writer) throws IOException {
                writer.write("{\"info\":");
                throw new IOException("failed part-way");
            }

            @Override
            public void writeBrunoCollection(List<Expectation> expectations, OutputStream out) throws IOException {
                out.write(new byte[]{'P', 'K', 3, 4});
                throw new IOException("failed part-way");
            }
        };
        Field field = HttpState.class.getDeclaredField("expectationExportSerializer");
        field.setAccessible(true);
        field.set(httpState, failing);
    }

    private static HttpStateRetrieveGoldenTest.Wire nettyWire(HttpResponse response) {
        List<DefaultHttpObject> objects = new MockServerHttpResponseToFullHttpResponse(new MockServerLogger()).mapMockServerResponseToNettyResponse(response);
        try {
            FullHttpResponse full = (FullHttpResponse) objects.get(0);
            ByteBuf content = full.content();
            byte[] bytes = new byte[content.readableBytes()];
            content.getBytes(content.readerIndex(), bytes);
            return new HttpStateRetrieveGoldenTest.Wire(full.headers().get(CONTENT_TYPE), bytes, null);
        } finally {
            objects.forEach(ReferenceCountUtil::release);
        }
    }

    @Test(timeout = 60_000)
    public void shouldAnswerTheFailureDocumentWhenOpenApiFailsPartWay() throws ReflectiveOperationException {
        failPartWay();
        for (RetrieveType type : EXPORTING) {
            HttpResponse response = httpState.retrieve(HttpStateRetrieveGoldenTest.retrieveRequest(type, Format.OPENAPI));
            HttpStateRetrieveGoldenTest.Wire wire = nettyWire(response);

            assertThat(type + " status", response.getStatusCode(), is(200));
            assertThat(type + " content type", wire.contentType, is("application/json; charset=utf-8"));
            assertThat(type + " body", new String(wire.bytes, StandardCharsets.UTF_8), is(OPENAPI_FAILURE));
        }
    }

    @Test(timeout = 60_000)
    public void shouldAnswerAnEmptyObjectWhenPostmanFailsPartWay() throws ReflectiveOperationException {
        failPartWay();
        for (RetrieveType type : EXPORTING) {
            HttpResponse response = httpState.retrieve(HttpStateRetrieveGoldenTest.retrieveRequest(type, Format.POSTMAN));
            HttpStateRetrieveGoldenTest.Wire wire = nettyWire(response);

            assertThat(type + " status", response.getStatusCode(), is(200));
            assertThat(type + " content type", wire.contentType, is("application/json; charset=utf-8"));
            assertThat(type + " body", new String(wire.bytes, StandardCharsets.UTF_8), is("{}"));
        }
    }

    @Test(timeout = 60_000)
    public void shouldAnswerNoBytesWhenBrunoFailsPartWay() throws ReflectiveOperationException {
        failPartWay();
        for (RetrieveType type : EXPORTING) {
            HttpResponse response = httpState.retrieve(HttpStateRetrieveGoldenTest.retrieveRequest(type, Format.BRUNO));
            HttpStateRetrieveGoldenTest.Wire wire = nettyWire(response);

            assertThat(type + " status", response.getStatusCode(), is(200));
            assertThat(type + " content type", wire.contentType, is("application/zip"));
            assertThat(type + " body length", wire.bytes.length, is(0));
        }
    }

    @Test(timeout = 60_000)
    public void shouldAnswerTheBrunoZipAsTheSegmentsItWasWrittenTo() {
        for (RetrieveType type : EXPORTING) {
            HttpResponse response = httpState.retrieve(HttpStateRetrieveGoldenTest.retrieveRequest(type, Format.BRUNO));

            SegmentedBytes segments = BodyDecoderEncoder.wireSegments(response.getBody());
            assertThat(type + " segments", segments, notNullValue());
            assertThat(type + " zip size", segments.size(), greaterThan(0));
            List<DefaultHttpObject> objects = new MockServerHttpResponseToFullHttpResponse(new MockServerLogger()).mapMockServerResponseToNettyResponse(response);
            try {
                ByteBuf content = ((FullHttpResponse) objects.get(0)).content();
                // one buffer per segment: the zip is wrapped as written, not joined into one array
                assertThat(type + " buffers", content.nioBufferCount(), is(segments.asByteBuffers().length));
                assertThat(type + " bytes", content.readableBytes(), is(segments.size()));
            } finally {
                objects.forEach(ReferenceCountUtil::release);
            }
        }
    }
}
