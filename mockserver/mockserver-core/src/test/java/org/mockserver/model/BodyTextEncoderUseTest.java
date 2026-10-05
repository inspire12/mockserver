package org.mockserver.model;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.mockserver.codec.BodyDecoderEncoder;
import org.mockserver.matchers.MatchType;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockserver.model.HttpRequest.request;

/**
 * Every place that turns a body's text into bytes must go through {@link BodyTextEncoder}: with
 * {@link String#getBytes(Charset)} a text of more than 715 million characters fails on Java 17.
 * Text that long cannot be made in a unit test, and for shorter text both give the same bytes, so
 * the encoder is replaced here and each place must hand back the bytes the replacement returns.
 */
public class BodyTextEncoderUseTest {

    private static final byte[] FROM_THE_ENCODER = {1, 2, 3};

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private MockedStatic<BodyTextEncoder> encoder;

    @Before
    public void replaceTheEncoder() {
        // replaces it for this thread only
        encoder = mockStatic(BodyTextEncoder.class);
    }

    @After
    public void restoreTheEncoder() {
        encoder.close();
    }

    private void encoderReturns(String text, Charset charset, byte[] bytes) {
        encoder.when(() -> BodyTextEncoder.encode(text, charset)).thenReturn(bytes);
    }

    @Test
    public void stringBodyShouldEncodeWithTheEncoder() {
        encoderReturns("some text", StandardCharsets.UTF_8, FROM_THE_ENCODER);

        assertThat(new StringBody("some text", MediaType.JSON_UTF_8).getRawBytes(), sameInstance(FROM_THE_ENCODER));
    }

    @Test
    public void jsonBodyShouldEncodeWithTheEncoder() {
        encoderReturns("{\"some\": \"json\"}", StandardCharsets.UTF_16, FROM_THE_ENCODER);

        assertThat(new JsonBody("{\"some\": \"json\"}", StandardCharsets.UTF_16, MatchType.STRICT).getRawBytes(), sameInstance(FROM_THE_ENCODER));
    }

    @Test
    public void xmlBodyShouldEncodeWithTheEncoder() {
        encoderReturns("<some>xml</some>", StandardCharsets.UTF_16, FROM_THE_ENCODER);

        assertThat(new XmlBody("<some>xml</some>", StandardCharsets.UTF_16).getRawBytes(), sameInstance(FROM_THE_ENCODER));
    }

    @Test
    public void fileBodyShouldEncodeWithTheEncoder() throws Exception {
        File file = temporaryFolder.newFile("body.txt");
        Files.write(file.toPath(), "some file".getBytes(StandardCharsets.UTF_8));
        encoderReturns("some file", StandardCharsets.UTF_16, FROM_THE_ENCODER);

        assertThat(new FileBody(file.getAbsolutePath(), MediaType.PLAIN_TEXT_UTF_8.withCharset(StandardCharsets.UTF_16)).getRawBytes(), sameInstance(FROM_THE_ENCODER));
    }

    @Test
    public void bodyWithoutItsOwnBytesShouldEncodeWithTheEncoder() {
        encoder.when(() -> BodyTextEncoder.encode(anyString(), eq(StandardCharsets.UTF_8))).thenReturn(FROM_THE_ENCODER);

        assertThat(new RegexBody("some.*regex").getRawBytes(), sameInstance(FROM_THE_ENCODER));
    }

    @Test
    public void requestShouldReEncodeItsBodyWithTheEncoder() {
        encoderReturns("some text", StandardCharsets.ISO_8859_1, "some text".getBytes(StandardCharsets.ISO_8859_1));
        encoderReturns("some text", StandardCharsets.UTF_16, "from the encoder".getBytes(StandardCharsets.UTF_8));
        HttpRequest request = request()
            .withHeader("content-type", "application/json; charset=utf-16")
            .withBody(new StringBody("some text"));

        assertThat(request.getBodyAsJsonOrXmlString(), is("from the encoder"));
    }

    @Test
    public void responseWriteShouldEncodeWithTheEncoder() {
        // the body declares no charset, so its own bytes are not the bytes for a UTF-8 response
        encoderReturns("some text", StandardCharsets.ISO_8859_1, "some text".getBytes(StandardCharsets.ISO_8859_1));
        encoderReturns("some text", StandardCharsets.UTF_8, FROM_THE_ENCODER);

        ByteBuf written = new BodyDecoderEncoder().bodyToByteBuf(new StringBody("some text"), "text/plain; charset=utf-8");

        try {
            assertThat(ByteBufUtil.getBytes(written), is(FROM_THE_ENCODER));
        } finally {
            written.release();
        }
    }
}
