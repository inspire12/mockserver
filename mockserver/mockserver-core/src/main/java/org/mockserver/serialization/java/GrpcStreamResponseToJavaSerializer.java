package org.mockserver.serialization.java;

import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.GrpcStreamResponse;

public class GrpcStreamResponseToJavaSerializer implements ToJavaSerializer<GrpcStreamResponse> {

    @Override
    public String serialize(int numberOfSpacesToIndent, GrpcStreamResponse grpcStreamResponse) {
        if (grpcStreamResponse == null) {
            return "";
        }
        return new FluentJavaBuilder(numberOfSpacesToIndent, "GrpcStreamResponse.grpcStreamResponse()")
            .with("withStatusName", grpcStreamResponse.getStatusName())
            .with("withStatusMessage", grpcStreamResponse.getStatusMessage())
            .withHeaders(grpcStreamResponse.getHeaders())
            .withEach("withMessages", grpcStreamResponse.getMessages(), GrpcStreamResponseToJavaSerializer::serializeMessage)
            .with("withCloseConnection", grpcStreamResponse.getCloseConnection())
            .withDelay("withDelay", grpcStreamResponse.getDelay())
            .build();
    }

    static String serializeMessage(int numberOfSpacesToIndent, GrpcStreamMessage message) {
        return new FluentJavaBuilder(numberOfSpacesToIndent, "GrpcStreamMessage.grpcStreamMessage()")
            .with("withJson", message.getJson())
            .with("withTemplateType", message.getTemplateType())
            .withDelay("withDelay", message.getDelay())
            .build();
    }
}
