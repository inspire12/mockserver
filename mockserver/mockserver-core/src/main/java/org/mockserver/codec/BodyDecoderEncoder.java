package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.mockserver.model.*;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.mockserver.model.JsonBody.DEFAULT_MATCH_TYPE;

@SuppressWarnings("rawtypes")
public class BodyDecoderEncoder {

    public ByteBuf bodyToByteBuf(Body body, String contentTypeHeader) {
        byte[] bytes = bodyToBytes(body, contentTypeHeader);
        if (bytes != null) {
            return Unpooled.wrappedBuffer(bytes);
        } else {
            return Unpooled.EMPTY_BUFFER;
        }
    }

    public ByteBuf[] bodyToByteBuf(Body body, String contentTypeHeader, int chunkSize) {
        byte[] bytes = bodyToBytes(body, contentTypeHeader);
        if (bytes == null) {
            return new ByteBuf[]{Unpooled.EMPTY_BUFFER};
        }
        byte[][] chunks = split(bytes, chunkSize);
        ByteBuf[] byteBufs = new ByteBuf[chunks.length];
        for (int i = 0; i < chunks.length; i++) {
            if (chunks[i] != null) {
                byteBufs[i] = Unpooled.wrappedBuffer(chunks[i]);
            } else {
                byteBufs[i] = Unpooled.EMPTY_BUFFER;
            }
        }
        return byteBufs;
    }

    public static byte[][] split(byte[] array, int chunkSize) {
        if (array == null) {
            return new byte[][]{new byte[0]};
        }
        if (chunkSize < array.length) {
            int numOfChunks = (array.length + chunkSize - 1) / chunkSize;
            byte[][] output = new byte[numOfChunks][];

            for (int i = 0; i < numOfChunks; ++i) {
                int start = i * chunkSize;
                int length = Math.min(array.length - start, chunkSize);

                byte[] temp = new byte[length];
                System.arraycopy(array, start, temp, 0, length);
                output[i] = temp;
            }
            return output;
        } else {
            return new byte[][]{array};
        }
    }

    byte[] bodyToBytes(Body body, String contentTypeHeader) {
        if (body != null) {
            if (body instanceof BinaryBody) {
                return body.getRawBytes();
            }
            // Not getValue(): a response is logged before it is written, so its log entry may already be
            // retained and released; a caching read would re-attach the decoded String to it, uncounted.
            Object value = body.getValueWithoutCaching();
            if (value instanceof String) {
                Charset contentTypeCharset = MediaType.parse(contentTypeHeader).getCharsetOrDefault();
                Charset bodyCharset = body.getCharset(contentTypeCharset);
                Charset wireCharset = bodyCharset != null ? bodyCharset : MediaType.DEFAULT_TEXT_HTTP_CHARACTER_SET;
                // When the body carries its OWN declared charset (e.g. withBody(json, JSON_UTF_8) or
                // withBody(string, charset)), StringBody/JsonBody/XmlBody already materialised rawBytes
                // in exactly that charset, and the wire charset always resolves to that same declared
                // charset (a declared charset wins over the header - see getCharset). So the model bytes
                // are byte-identical to value.getBytes(wireCharset): reuse them and skip a second
                // whole-body encode and its allocation. This is a pure charset-object comparison, no
                // per-byte scan, so it costs the hot path nothing. It deliberately does NOT fire when the
                // body has no declared charset (declaredCharset == null) - the withBody(String) shape,
                // whose rawBytes use the ISO-8859-1 default and can differ from a UTF-8 wire - forcing the
                // correct re-encode there and never emitting a lossy body.
                Charset declaredCharset = body.getCharset(null);
                byte[] materialised = body.getRawBytes();
                if (materialised != null && declaredCharset != null && declaredCharset.equals(wireCharset)) {
                    return materialised;
                }
                return BodyTextEncoder.encode((String) value, wireCharset);
            } else {
                return body.getRawBytes();
            }
        }
        return null;
    }

    public BodyWithContentType byteBufToBody(ByteBuf content, String contentTypeHeader) {
        if (content != null && content.readableBytes() > 0) {
            byte[] bodyBytes = new byte[content.readableBytes()];
            content.readBytes(bodyBytes);
            return bytesToBody(bodyBytes, contentTypeHeader);
        }
        return null;
    }

    public BodyWithContentType bytesToBody(byte[] bodyBytes, String contentTypeHeader) {
        if (bodyBytes.length > 0) {
            MediaType mediaType = MediaType.parse(contentTypeHeader);
            if (mediaType.isJson()) {
                return new JsonBody(
                    new String(bodyBytes, mediaType.getCharsetOrDefault()),
                    bodyBytes,
                    mediaType,
                    DEFAULT_MATCH_TYPE
                );
            } else if (mediaType.isXml()) {
                return new XmlBody(
                    new String(bodyBytes, mediaType.getCharsetOrDefault()),
                    bodyBytes,
                    mediaType
                );
            } else if (isBlank(contentTypeHeader)) {
                // No Content-Type: keep a String view only when the bytes are valid UTF-8, so it
                // re-encodes to exactly the bytes received. Anything else stays binary; decoding it
                // would turn each malformed byte into U+FFFD (3 bytes), corrupting forwarded bodies.
                String text = decodeIfValidUtf8(bodyBytes);
                if (text != null) {
                    return new StringBody(text, bodyBytes, false, null);
                }
                return new BinaryBody(bodyBytes, null);
            } else if (mediaType.isString()) {
                return new StringBody(
                    new String(bodyBytes, mediaType.getCharsetOrDefault()),
                    bodyBytes,
                    false,
                    mediaType
                );
            } else {
                return new BinaryBody(bodyBytes, mediaType);
            }
        }
        return null;
    }

    /**
     * The bytes decoded as UTF-8, or {@code null} when they are not valid UTF-8 and so could not be
     * re-encoded to the same bytes.
     */
    public static String decodeIfValidUtf8(byte[] bytes) {
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        // the lenient decode replaces every malformed sequence with U+FFFD, so its absence proves the
        // bytes are valid; a U+FFFD that was genuinely encoded in the input needs the strict check
        if (decoded.indexOf('\uFFFD') < 0) {
            return decoded;
        }
        return isValidUtf8(bytes) ? decoded : null;
    }

    private static boolean isValidUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        // validate through a small reused buffer rather than a second copy the size of the body
        CharBuffer out = CharBuffer.allocate(8192);
        CoderResult result;
        do {
            out.clear();
            result = decoder.decode(in, out, true);
            if (result.isError()) {
                return false;
            }
        } while (result.isOverflow());
        out.clear();
        return !decoder.flush(out).isError();
    }
}
