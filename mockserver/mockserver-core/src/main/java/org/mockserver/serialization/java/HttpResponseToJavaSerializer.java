package org.mockserver.serialization.java;

import org.mockserver.model.*;

import java.util.List;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.serialization.java.ExpectationToJavaSerializer.INDENT_SIZE;

/**
 * @author jamesdbloom
 */
public class HttpResponseToJavaSerializer implements ToJavaSerializer<HttpResponse> {

    @Override
    public String serialize(int numberOfSpacesToIndent, HttpResponse httpResponse) {
        return JavaCode.toString(output -> write(numberOfSpacesToIndent, httpResponse, output));
    }

    /**
     * As {@link #serialize(int, HttpResponse)}, writing the code to {@code output}, the body as it is escaped.
     */
    void write(int numberOfSpacesToIndent, HttpResponse httpResponse, JavaCode output) {
        if (httpResponse != null) {
            appendNewLineAndIndent(numberOfSpacesToIndent * INDENT_SIZE, output).append("response()");
            if (httpResponse.getStatusCode() != null) {
                appendNewLineAndIndent((numberOfSpacesToIndent + 1) * INDENT_SIZE, output).append(".withStatusCode(").append(httpResponse.getStatusCode()).append(")");
            }
            if (httpResponse.getReasonPhrase() != null) {
                appendNewLineAndIndent((numberOfSpacesToIndent + 1) * INDENT_SIZE, output).append(".withReasonPhrase(\"").appendEscaped(httpResponse.getReasonPhrase()).append("\")");
            }
            outputHeaders(numberOfSpacesToIndent + 1, output, httpResponse.getHeaderList());
            outputCookies(numberOfSpacesToIndent + 1, output, httpResponse.getCookieList());
            if (isNotBlank(httpResponse.getBodyAsString())) {
                if (httpResponse.getBody() instanceof BinaryBody) {
                    appendNewLineAndIndent((numberOfSpacesToIndent + 1) * INDENT_SIZE, output);
                    BinaryBody body = (BinaryBody) httpResponse.getBody();
                    output.append(".withBody(new Base64Converter().base64StringToBytes(\"").appendBase64(body.getRawBytes()).append("\"))");
                } else {
                    appendNewLineAndIndent((numberOfSpacesToIndent + 1) * INDENT_SIZE, output).append(".withBody(\"").appendEscaped(httpResponse.getBodyAsString()).append("\")");
                }
            }
            if (isNotBlank(httpResponse.getGenerateFromSchema())) {
                appendNewLineAndIndent((numberOfSpacesToIndent + 1) * INDENT_SIZE, output).append(".withGenerateFromSchema(\"").appendEscaped(httpResponse.getGenerateFromSchema()).append("\")");
            }
            if (httpResponse.getDelay() != null) {
                appendNewLineAndIndent((numberOfSpacesToIndent + 1) * INDENT_SIZE, output).append(".withDelay(").append(new DelayToJavaSerializer().serialize(0, httpResponse.getDelay())).append(")");
            }
            if (httpResponse.getConnectionOptions() != null) {
                appendNewLineAndIndent((numberOfSpacesToIndent + 1) * INDENT_SIZE, output).append(".withConnectionOptions(");
                output.append(new ConnectionOptionsToJavaSerializer().serialize(numberOfSpacesToIndent + 2, httpResponse.getConnectionOptions()));
                appendNewLineAndIndent((numberOfSpacesToIndent + 1) * INDENT_SIZE, output).append(")");
            }
        }
    }

    private void outputCookies(int numberOfSpacesToIndent, JavaCode output, List<Cookie> cookies) {
        if (cookies.size() > 0) {
            appendNewLineAndIndent(numberOfSpacesToIndent * INDENT_SIZE, output).append(".withCookies(");
            appendObject(numberOfSpacesToIndent + 1, output, new CookieToJavaSerializer(), cookies);
            appendNewLineAndIndent(numberOfSpacesToIndent * INDENT_SIZE, output).append(")");
        }
    }

    private void outputHeaders(int numberOfSpacesToIndent, JavaCode output, List<Header> headers) {
        if (headers.size() > 0) {
            appendNewLineAndIndent(numberOfSpacesToIndent * INDENT_SIZE, output).append(".withHeaders(");
            appendObject(numberOfSpacesToIndent + 1, output, new HeaderToJavaSerializer(), headers);
            appendNewLineAndIndent(numberOfSpacesToIndent * INDENT_SIZE, output).append(")");
        }
    }

    private <T extends ObjectWithReflectiveEqualsHashCodeToString> void appendObject(int numberOfSpacesToIndent, JavaCode output, MultiValueToJavaSerializer<T> toJavaSerializer, List<T> objects) {
        output.append(toJavaSerializer.serializeAsJava(numberOfSpacesToIndent, objects));
    }

    private JavaCode appendNewLineAndIndent(int numberOfSpacesToIndent, JavaCode output) {
        return output.newLineAndIndent(numberOfSpacesToIndent);
    }
}
