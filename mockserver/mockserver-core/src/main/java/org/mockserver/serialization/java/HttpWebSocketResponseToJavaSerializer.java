package org.mockserver.serialization.java;

import org.mockserver.model.GraphQLBody;
import org.mockserver.model.HttpWebSocketResponse;
import org.mockserver.model.NottableOptionalString;
import org.mockserver.model.NottableString;
import org.mockserver.model.WebSocketMessage;
import org.mockserver.model.WebSocketMessageMatcher;

public class HttpWebSocketResponseToJavaSerializer implements ToJavaSerializer<HttpWebSocketResponse> {

    @Override
    public String serialize(int numberOfSpacesToIndent, HttpWebSocketResponse webSocketResponse) {
        if (webSocketResponse == null) {
            return "";
        }
        return new FluentJavaBuilder(numberOfSpacesToIndent, "HttpWebSocketResponse.webSocketResponse()")
            .with("withSubprotocol", webSocketResponse.getSubprotocol())
            .withEach("withMessages", webSocketResponse.getMessages(), HttpWebSocketResponseToJavaSerializer::serializeMessage)
            .withEach("withMatchers", webSocketResponse.getMatchers(), HttpWebSocketResponseToJavaSerializer::serializeMatcher)
            .with("withCloseConnection", webSocketResponse.getCloseConnection())
            .withObject("withGraphqlSubscriptionFilter", webSocketResponse.getGraphqlSubscriptionFilter(), HttpWebSocketResponseToJavaSerializer::serializeGraphQL)
            .with("withTemplateType", webSocketResponse.getTemplateType())
            .withDelay("withDelay", webSocketResponse.getDelay())
            .build();
    }

    static String serializeMessage(int numberOfSpacesToIndent, WebSocketMessage message) {
        return new FluentJavaBuilder(numberOfSpacesToIndent, "WebSocketMessage.webSocketMessage()")
            .with("withText", message.getText())
            .withBytes("withBinary", message.getBinary())
            .withDelay("withDelay", message.getDelay())
            .build();
    }

    private static String serializeMatcher(int numberOfSpacesToIndent, WebSocketMessageMatcher matcher) {
        // the text matcher first: setting it also sets the frame type, which is then set as it was
        return new FluentJavaBuilder(numberOfSpacesToIndent, "WebSocketMessageMatcher.webSocketMessageMatcher()")
            .withCode("withTextMatcher", matcher.getTextMatcher(), HttpWebSocketResponseToJavaSerializer::nottableString)
            .with("withFrameType", matcher.getFrameType())
            .withEach("withResponses", matcher.getResponses(), HttpWebSocketResponseToJavaSerializer::serializeMessage)
            .build();
    }

    private static String serializeGraphQL(int numberOfSpacesToIndent, GraphQLBody graphQL) {
        return new FluentJavaBuilder(numberOfSpacesToIndent, "new GraphQLBody(" + FluentJavaBuilder.literal(graphQL.getQuery()) + ", " + FluentJavaBuilder.literal(graphQL.getOperationName()) + ", " + FluentJavaBuilder.literal(graphQL.getVariablesSchema()) + ")")
            .with("withSelectionSetMatchType", graphQL.getSelectionSetMatchType())
            .withLiterals("withFields", graphQL.getFields())
            .with("withSchema", graphQL.getSchema())
            .build();
    }

    /**
     * The value is taken verbatim, so a value that begins with a negation or optional marker keeps it.
     */
    static String nottableString(NottableString value) {
        String factory = value instanceof NottableOptionalString ? "NottableOptionalString.optional(" : "NottableString.string(";
        return factory + FluentJavaBuilder.literal(value.getValue()) + ", " + value.isNot() + ")";
    }
}
