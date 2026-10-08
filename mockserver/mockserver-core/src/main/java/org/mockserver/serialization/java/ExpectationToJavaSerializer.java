package org.mockserver.serialization.java;

import com.google.common.base.Strings;
import org.apache.commons.text.StringEscapeUtils;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Action;
import org.mockserver.model.AfterAction;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.OpenAPIDefinition;
import org.mockserver.model.RequestDefinition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.character.Character.NEW_LINE;

/**
 * @author jamesdbloom
 */
public class ExpectationToJavaSerializer implements ToJavaSerializer<Expectation> {

    public static final int INDENT_SIZE = 8;

    public String serialize(List<Expectation> expectations) {
        StringBuilder output = new StringBuilder();
        for (Expectation expectation : expectations) {
            output.append(serialize(0, expectation));
            output.append(NEW_LINE);
            output.append(NEW_LINE);
        }
        return output.toString();
    }

    /**
     * As {@link #serialize(List)}, writing the code for one expectation at a time to {@code writer}.
     */
    public void serialize(List<Expectation> expectations, Writer writer) {
        try {
            for (Expectation expectation : expectations) {
                writer.write(serialize(0, expectation));
                writer.write(NEW_LINE);
                writer.write(NEW_LINE);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String serialize(int numberOfSpacesToIndent, Expectation expectation) {
        StringBuffer output = new StringBuffer();
        if (expectation != null) {
            // when(...) takes one terminal action and has none for forward-validate
            boolean upsert = generatedActionCount(expectation) > 1 || expectation.getHttpForwardValidateAction() != null;
            int indent = upsert ? numberOfSpacesToIndent + 1 : numberOfSpacesToIndent;
            appendNewLineAndIndent(numberOfSpacesToIndent * INDENT_SIZE, output).append("new MockServerClient(\"localhost\", 1080)");
            if (upsert) {
                appendNewLineAndIndent(numberOfSpacesToIndent * INDENT_SIZE, output).append(".upsert(");
                appendNewLineAndIndent(indent * INDENT_SIZE, output).append("new org.mockserver.mock.Expectation(");
            } else {
                appendNewLineAndIndent(indent * INDENT_SIZE, output).append(".when(");
            }
            String respond = upsert ? ".thenRespond(" : ".respond(";
            String forward = upsert ? ".thenForward(" : ".forward(";
            String error = upsert ? ".thenError(" : ".error(";
            String respondWith = upsert ? ".thenRespondWith" : ".respondWith";
            RequestDefinition requestDefinition = expectation.getHttpRequest();
            if (requestDefinition instanceof HttpRequest) {
                output.append(new HttpRequestToJavaSerializer().serialize(indent + 1, (HttpRequest) requestDefinition));
            } else if (requestDefinition instanceof OpenAPIDefinition) {
                output.append(new OpenAPIMatcherToJavaSerializer().serialize(indent + 1, (OpenAPIDefinition) requestDefinition));
            }
            output.append(",");
            if (expectation.getTimes() != null) {
                output.append(new TimesToJavaSerializer().serialize(indent + 1, expectation.getTimes()));
            } else {
                appendNewLineAndIndent((indent + 1) * INDENT_SIZE, output).append("null");
            }
            output.append(",");
            if (expectation.getTimeToLive() != null) {
                output.append(new TimeToLiveToJavaSerializer().serialize(indent + 1, expectation.getTimeToLive()));
            } else {
                appendNewLineAndIndent((indent + 1) * INDENT_SIZE, output).append("null");
            }
            output.append(",");
            appendNewLineAndIndent((indent + 1) * INDENT_SIZE, output).append(expectation.getPriority());
            appendNewLineAndIndent(indent * INDENT_SIZE, output).append(")");
            if (expectation.getPercentage() != null) {
                appendNewLineAndIndent(indent * INDENT_SIZE, output).append(".withPercentage(").append(expectation.getPercentage()).append(")");
            }
            if (isNotBlank(expectation.getNamespace())) {
                appendNewLineAndIndent(indent * INDENT_SIZE, output).append(".withNamespace(\"").append(StringEscapeUtils.escapeJava(expectation.getNamespace())).append("\")");
            }
            if (isNotBlank(expectation.getScenarioName())) {
                appendNewLineAndIndent(indent * INDENT_SIZE, output).append(".withScenarioName(\"").append(StringEscapeUtils.escapeJava(expectation.getScenarioName())).append("\")");
            }
            if (isNotBlank(expectation.getScenarioState())) {
                appendNewLineAndIndent(indent * INDENT_SIZE, output).append(".withScenarioState(\"").append(StringEscapeUtils.escapeJava(expectation.getScenarioState())).append("\")");
            }
            if (isNotBlank(expectation.getNewScenarioState())) {
                appendNewLineAndIndent(indent * INDENT_SIZE, output).append(".withNewScenarioState(\"").append(StringEscapeUtils.escapeJava(expectation.getNewScenarioState())).append("\")");
            }
            if (expectation.getResponseMode() != null) {
                appendNewLineAndIndent(indent * INDENT_SIZE, output).append(".withResponseMode(org.mockserver.mock.ResponseMode.").append(expectation.getResponseMode().name()).append(")");
            }
            if (expectation.getResponseWeights() != null && !expectation.getResponseWeights().isEmpty()) {
                StringBuilder weights = new StringBuilder();
                List<Integer> responseWeights = expectation.getResponseWeights();
                for (int i = 0; i < responseWeights.size(); i++) {
                    if (i > 0) {
                        weights.append(", ");
                    }
                    weights.append(responseWeights.get(i));
                }
                appendNewLineAndIndent(indent * INDENT_SIZE, output).append(".withResponseWeights(java.util.Arrays.asList(").append(weights).append("))");
            }
            if (expectation.getSwitchAfter() != null) {
                appendNewLineAndIndent(indent * INDENT_SIZE, output).append(".withSwitchAfter(").append(expectation.getSwitchAfter()).append(")");
            }
            appendAfterActions(indent, ".withBeforeActions(", expectation.getBeforeActions(), output);
            appendAfterActions(indent, ".withAfterActions(", expectation.getAfterActions(), output);
            if (expectation.getHttpResponses() != null && !expectation.getHttpResponses().isEmpty()) {
                HttpResponseToJavaSerializer responseSerializer = new HttpResponseToJavaSerializer();
                appendNewLineAndIndent(indent * INDENT_SIZE, output).append(respond).append("java.util.Arrays.asList(");
                List<HttpResponse> responses = expectation.getHttpResponses();
                for (int i = 0; i < responses.size(); i++) {
                    appendNewLineAndIndent((indent + 1) * INDENT_SIZE, output);
                    output.append(responseSerializer.serialize(indent + 2, responses.get(i)));
                    if (i < responses.size() - 1) {
                        output.append(",");
                    }
                }
                appendNewLineAndIndent(indent * INDENT_SIZE, output).append("))");
            } else if (expectation.getHttpResponse() != null) {
                appendAction(indent, respond, new HttpResponseToJavaSerializer().serialize(indent + 1, expectation.getHttpResponse()), expectation.getHttpResponse(), output);
            }
            if (expectation.getHttpResponseTemplate() != null) {
                appendAction(indent, respond, new HttpTemplateToJavaSerializer().serialize(indent + 1, expectation.getHttpResponseTemplate()), expectation.getHttpResponseTemplate(), output);
            }
            if (expectation.getHttpResponseClassCallback() != null) {
                appendAction(indent, respond, new HttpClassCallbackToJavaSerializer().serialize(indent + 1, expectation.getHttpResponseClassCallback()), expectation.getHttpResponseClassCallback(), output);
            }
            if (expectation.getHttpResponseObjectCallback() != null) {
                appendNewLineAndIndent(indent * INDENT_SIZE, output).append("/*NOT POSSIBLE TO GENERATE CODE FOR OBJECT CALLBACK*/");
            }
            if (expectation.getHttpForward() != null) {
                appendAction(indent, forward, new HttpForwardToJavaSerializer().serialize(indent + 1, expectation.getHttpForward()), expectation.getHttpForward(), output);
            }
            if (expectation.getHttpOverrideForwardedRequest() != null) {
                appendAction(indent, forward, new HttpOverrideForwardedRequestToJavaSerializer().serialize(indent + 1, expectation.getHttpOverrideForwardedRequest()), expectation.getHttpOverrideForwardedRequest(), output);
            }
            if (expectation.getHttpForwardTemplate() != null) {
                appendAction(indent, forward, new HttpTemplateToJavaSerializer().serialize(indent + 1, expectation.getHttpForwardTemplate()), expectation.getHttpForwardTemplate(), output);
            }
            if (expectation.getHttpForwardClassCallback() != null) {
                appendAction(indent, forward, new HttpClassCallbackToJavaSerializer().serialize(indent + 1, expectation.getHttpForwardClassCallback()), expectation.getHttpForwardClassCallback(), output);
            }
            if (expectation.getHttpForwardObjectCallback() != null) {
                appendNewLineAndIndent(indent * INDENT_SIZE, output).append("/*NOT POSSIBLE TO GENERATE CODE FOR OBJECT CALLBACK*/");
            }
            if (expectation.getHttpForwardValidateAction() != null) {
                appendAction(indent, ".thenForwardValidate(", new HttpForwardValidateActionToJavaSerializer().serialize(indent + 1, expectation.getHttpForwardValidateAction()), expectation.getHttpForwardValidateAction(), output);
            }
            if (expectation.getHttpForwardWithFallback() != null) {
                appendAction(indent, upsert ? ".thenForwardWithFallback(" : ".forwardWithFallback(", new HttpForwardWithFallbackToJavaSerializer().serialize(indent + 1, expectation.getHttpForwardWithFallback()), expectation.getHttpForwardWithFallback(), output);
            }
            if (expectation.getHttpError() != null) {
                appendAction(indent, error, new HttpErrorToJavaSerializer().serialize(indent + 1, expectation.getHttpError()), expectation.getHttpError(), output);
            }
            if (expectation.getHttpSseResponse() != null) {
                appendAction(indent, respondWith + "Sse(", new HttpSseResponseToJavaSerializer().serialize(indent + 1, expectation.getHttpSseResponse()), expectation.getHttpSseResponse(), output);
            }
            if (expectation.getHttpLlmResponse() != null) {
                appendAction(indent, respondWith + "Llm(", new HttpLlmResponseToJavaSerializer().serialize(indent + 1, expectation.getHttpLlmResponse()), expectation.getHttpLlmResponse(), output);
            }
            if (expectation.getHttpWebSocketResponse() != null) {
                appendAction(indent, respondWith + "WebSocket(", new HttpWebSocketResponseToJavaSerializer().serialize(indent + 1, expectation.getHttpWebSocketResponse()), expectation.getHttpWebSocketResponse(), output);
            }
            if (expectation.getGrpcStreamResponse() != null) {
                appendAction(indent, respondWith + "GrpcStream(", new GrpcStreamResponseToJavaSerializer().serialize(indent + 1, expectation.getGrpcStreamResponse()), expectation.getGrpcStreamResponse(), output);
            }
            if (expectation.getGrpcBidiResponse() != null) {
                appendAction(indent, respondWith + "GrpcBidi(", new GrpcBidiResponseToJavaSerializer().serialize(indent + 1, expectation.getGrpcBidiResponse()), expectation.getGrpcBidiResponse(), output);
            }
            if (expectation.getBinaryResponse() != null) {
                appendAction(indent, respondWith + "Binary(", new BinaryResponseToJavaSerializer().serialize(indent + 1, expectation.getBinaryResponse()), expectation.getBinaryResponse(), output);
            }
            if (expectation.getDnsResponse() != null) {
                appendAction(indent, respondWith + "Dns(", new DnsResponseToJavaSerializer().serialize(indent + 1, expectation.getDnsResponse()), expectation.getDnsResponse(), output);
            }
            if (upsert) {
                appendNewLineAndIndent(numberOfSpacesToIndent * INDENT_SIZE, output).append(")");
            }
            output.append(";");
        }
        return output.toString();
    }

    /**
     * The number of action calls the generated code makes. With more than one, the code builds an {@link Expectation}
     * and upserts it, since {@code when(...)} takes a single terminal action.
     */
    private static int generatedActionCount(Expectation expectation) {
        int count = expectation.getHttpResponses() != null && !expectation.getHttpResponses().isEmpty() || expectation.getHttpResponse() != null ? 1 : 0;
        for (Action<?> action : Arrays.asList(
            expectation.getHttpResponseTemplate(),
            expectation.getHttpResponseClassCallback(),
            expectation.getHttpForward(),
            expectation.getHttpOverrideForwardedRequest(),
            expectation.getHttpForwardTemplate(),
            expectation.getHttpForwardClassCallback(),
            expectation.getHttpForwardValidateAction(),
            expectation.getHttpForwardWithFallback(),
            expectation.getHttpError(),
            expectation.getHttpSseResponse(),
            expectation.getHttpLlmResponse(),
            expectation.getHttpWebSocketResponse(),
            expectation.getGrpcStreamResponse(),
            expectation.getGrpcBidiResponse(),
            expectation.getBinaryResponse(),
            expectation.getDnsResponse()
        )) {
            if (action != null) {
                count++;
            }
        }
        return count;
    }

    private void appendAfterActions(int numberOfSpacesToIndent, String method, List<AfterAction> afterActions, StringBuffer output) {
        if (afterActions != null && !afterActions.isEmpty()) {
            AfterActionToJavaSerializer afterActionSerializer = new AfterActionToJavaSerializer();
            appendNewLineAndIndent(numberOfSpacesToIndent * INDENT_SIZE, output).append(method);
            for (int i = 0; i < afterActions.size(); i++) {
                output.append(i > 0 ? "," : "").append(afterActionSerializer.serialize(numberOfSpacesToIndent + 1, afterActions.get(i)));
            }
            appendNewLineAndIndent(numberOfSpacesToIndent * INDENT_SIZE, output).append(")");
        }
    }

    private void appendAction(int numberOfSpacesToIndent, String method, String serializedAction, Action<?> action, StringBuffer output) {
        appendNewLineAndIndent(numberOfSpacesToIndent * INDENT_SIZE, output).append(method);
        output.append(serializedAction);
        if (action.isPrimary()) {
            appendNewLineAndIndent((numberOfSpacesToIndent + 2) * INDENT_SIZE, output).append(".withPrimary(true)");
        }
        appendNewLineAndIndent(numberOfSpacesToIndent * INDENT_SIZE, output).append(")");
    }

    private StringBuffer appendNewLineAndIndent(int numberOfSpacesToIndent, StringBuffer output) {
        return output.append(NEW_LINE).append(Strings.padStart("", numberOfSpacesToIndent, ' '));
    }
}
