package org.mockserver.validator.jsonschema;

import org.mockserver.logging.MockServerLogger;
import org.mockserver.verify.Verification;

/**
 * @author jamesdbloom
 */
public class JsonSchemaVerificationValidator extends JsonSchemaValidator {

    private JsonSchemaVerificationValidator(MockServerLogger mockServerLogger) {
        super(
            mockServerLogger,
            Verification.class,
            "org/mockserver/model/schema/",
            "verification",
            "expectationId",
            "requestDefinition",
            "conditionalRequestDefinition",
            "openAPIDefinition",
            "httpRequest",
            "httpResponse",
            "stringOrJsonSchema",
            "body",
            "bodyWithContentType",
            "keyToMultiValue",
            "keyToValue",
            "verificationTimes",
            "connectionOptions",
            "delay",
            "socketAddress",
            "protocol",
            "draft-07"
        );
    }

    private JsonSchemaVerificationValidator(MockServerLogger mockServerLogger, JsonSchemaVerificationValidator compiled) {
        super(compiled, mockServerLogger);
    }

    private static volatile JsonSchemaVerificationValidator compiled;

    /**
     * A validator that logs to {@code mockServerLogger}. The schema is compiled once per JVM, by a validator that
     * holds no server's logger, so a stopped server is not kept by it.
     */
    public static JsonSchemaVerificationValidator jsonSchemaVerificationValidator(MockServerLogger mockServerLogger) {
        JsonSchemaVerificationValidator shared = compiled;
        if (shared == null) {
            shared = new JsonSchemaVerificationValidator(new MockServerLogger(JsonSchemaVerificationValidator.class));
            compiled = shared;
        }
        return new JsonSchemaVerificationValidator(mockServerLogger, shared);
    }

}
