package org.mockserver.serialization.java;

import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcBidiRule;

public class GrpcBidiResponseToJavaSerializer implements ToJavaSerializer<GrpcBidiResponse> {

    @Override
    public String serialize(int numberOfSpacesToIndent, GrpcBidiResponse grpcBidiResponse) {
        if (grpcBidiResponse == null) {
            return "";
        }
        return new FluentJavaBuilder(numberOfSpacesToIndent, "GrpcBidiResponse.grpcBidiResponse()")
            .with("withStatusName", grpcBidiResponse.getStatusName())
            .with("withStatusMessage", grpcBidiResponse.getStatusMessage())
            .withHeaders(grpcBidiResponse.getHeaders())
            .withEach("withMessages", grpcBidiResponse.getMessages(), GrpcStreamResponseToJavaSerializer::serializeMessage)
            .withEach("withRules", grpcBidiResponse.getRules(), GrpcBidiResponseToJavaSerializer::serializeRule)
            .with("withCloseConnection", grpcBidiResponse.getCloseConnection())
            .withDelay("withDelay", grpcBidiResponse.getDelay())
            .build();
    }

    private static String serializeRule(int numberOfSpacesToIndent, GrpcBidiRule rule) {
        return new FluentJavaBuilder(numberOfSpacesToIndent, "GrpcBidiRule.grpcBidiRule()")
            .withCode("withMatchJson", rule.getMatchJson(), HttpWebSocketResponseToJavaSerializer::nottableString)
            .withEach("withResponses", rule.getResponses(), GrpcStreamResponseToJavaSerializer::serializeMessage)
            .build();
    }
}
