package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.MediaType;
import org.mockserver.model.SegmentedBytes;
import org.mockserver.model.StringBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A body built from segmented bytes is written by wrapping its segments, or by writing them in turn,
 * never by joining them into one array first.
 */
public class SegmentedBodyWriteTest {

    private static final int LARGE = 4 << 20;

    private static StringBody body(String text, MediaType contentType) throws IOException {
        SegmentedBytes bytes = new SegmentedBytes();
        try (Writer writer = bytes.writer(contentType.getCharset())) {
            writer.write(text);
        }
        return StringBody.fromSegmentedBytes(bytes, contentType);
    }

    private static String largeText() {
        char[] text = new char[LARGE];
        for (int i = 0; i < text.length; i++) {
            text[i] = (char) ('a' + i % 26);
        }
        return new String(text);
    }

    private static long allocatedBy(Runnable runnable) {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat("this JVM measures the bytes a thread allocates", threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled(), is(true));
        long before = threads.getCurrentThreadAllocatedBytes();
        runnable.run();
        return threads.getCurrentThreadAllocatedBytes() - before;
    }

    @Test
    public void shouldWrapTheSegmentsAsTheNettyBodyWithoutACopy() throws IOException {
        String text = largeText();
        StringBody body = body(text, MediaType.JSON_UTF_8);
        BodyDecoderEncoder encoder = new BodyDecoderEncoder();
        encoder.bodyToByteBuf(body, null).release();

        ByteBuf[] wire = new ByteBuf[1];
        long allocated = allocatedBy(() -> wire[0] = encoder.bodyToByteBuf(body, "text/plain; charset=iso-8859-1"));
        try {
            byte[] bytes = new byte[wire[0].readableBytes()];
            wire[0].getBytes(wire[0].readerIndex(), bytes);
            assertThat(Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8)), is(true));
            assertThat("allocated " + allocated, allocated, lessThan(LARGE / 20L));
        } finally {
            wire[0].release();
        }
    }

    @Test
    public void shouldWriteNoBytesForAnEmptyBody() throws IOException {
        ByteBuf wire = new BodyDecoderEncoder().bodyToByteBuf(body("", MediaType.JSON_UTF_8), null);
        assertThat(wire.readableBytes(), is(0));
        wire.release();
    }

    @Test
    public void shouldSplitTheSegmentsIntoChunksOfTheChunkSize() throws IOException {
        String text = "é😀" + largeText().substring(0, 5000);
        ByteBuf[] chunks = new BodyDecoderEncoder().bodyToByteBuf(body(text, MediaType.PLAIN_TEXT_UTF_8), null, 1000);
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (ByteBuf chunk : chunks) {
            assertThat(chunk.readableBytes() <= 1000, is(true));
            byte[] bytes = new byte[chunk.readableBytes()];
            chunk.readBytes(bytes);
            joined.write(bytes, 0, bytes.length);
            chunk.release();
        }
        assertThat(chunks.length, is(6));
        assertThat(Arrays.equals(joined.toByteArray(), text.getBytes(StandardCharsets.UTF_8)), is(true));
    }

    @Test
    public void shouldWriteTheSegmentsToTheServletResponseWithoutACopy() throws IOException {
        String text = largeText();
        StringBody body = body(text, MediaType.JSON_UTF_8);
        BodyServletDecoderEncoder encoder = new BodyServletDecoderEncoder(new MockServerLogger());
        CountingOutputStream warmUp = new CountingOutputStream();
        encoder.bodyToServletResponse(servletResponse(warmUp), body, null);

        CountingOutputStream out = new CountingOutputStream();
        HttpServletResponse response = servletResponse(out);
        long allocated = allocatedBy(() -> encoder.bodyToServletResponse(response, body, "text/plain; charset=iso-8859-1"));

        assertThat(out.count, is((long) text.length()));
        assertThat(out.writes, greaterThan(1));
        assertThat(out.closed, is(true));
        assertThat(out.hash, is(hash(text.getBytes(StandardCharsets.UTF_8))));
        assertThat("allocated " + allocated, allocated, lessThan(LARGE / 20L));
    }

    @Test
    public void shouldLogAndThrowWhenTheServletResponseCannotBeWritten() throws IOException {
        MockServerLogger logger = mock(MockServerLogger.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenThrow(new IOException("connection reset"));
        // longer than the first segment, so the size reported is the whole body's
        StringBody body = body(largeText().substring(0, 3000), MediaType.PLAIN_TEXT_UTF_8);

        RuntimeException failure = assertThrows(RuntimeException.class, () -> new BodyServletDecoderEncoder(logger).bodyToServletResponse(response, body, null));

        assertThat(failure.getMessage(), is("IOException while writing 3000 bytes to HttpServletResponse output stream"));
        assertThat(failure.getCause().getMessage(), is("connection reset"));
        verify(logger).logEvent(any(LogEntry.class));
    }

    @Test
    public void shouldDescribeTheFirstBytesWhenAnArrayCannotBeWritten() throws IOException {
        MockServerLogger logger = mock(MockServerLogger.class);
        org.mockito.ArgumentCaptor<LogEntry> logged = org.mockito.ArgumentCaptor.forClass(LogEntry.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenThrow(new IOException("connection reset"));
        byte[] bytes = largeText().substring(0, 300).getBytes(StandardCharsets.UTF_8);

        RuntimeException failure = assertThrows(RuntimeException.class, () -> new org.mockserver.streams.IOStreamUtils(logger).writeToOutputStream(bytes, response));

        assertThat(failure.getMessage(), is("IOException while writing 300 bytes to HttpServletResponse output stream"));
        verify(logger).logEvent(logged.capture());
        assertThat(String.valueOf(logged.getValue().getArguments()[0]), containsString(largeText().substring(0, 100) + "...(300 bytes)"));
    }

    private static HttpServletResponse servletResponse(ServletOutputStream out) throws IOException {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(out);
        return response;
    }

    private static long hash(byte[] bytes) {
        long hash = 17;
        for (byte b : bytes) {
            hash = hash * 31 + b;
        }
        return hash;
    }

    private static final class CountingOutputStream extends ServletOutputStream {
        private long count;
        private int writes;
        private long hash = 17;
        private boolean closed;

        @Override
        public void write(int b) {
            count++;
            writes++;
            hash = hash * 31 + (byte) b;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            count += length;
            writes++;
            for (int i = offset; i < offset + length; i++) {
                hash = hash * 31 + bytes[i];
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
        }
    }
}
