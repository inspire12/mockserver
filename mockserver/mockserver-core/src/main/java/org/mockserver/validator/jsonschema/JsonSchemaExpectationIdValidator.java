package org.mockserver.validator.jsonschema;

import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.ExpectationId;

/**
 * @author jamesdbloom
 */
public class JsonSchemaExpectationIdValidator extends JsonSchemaValidator {

    private JsonSchemaExpectationIdValidator(MockServerLogger mockServerLogger) {
        super(
            mockServerLogger,
            ExpectationId.class,
            "org/mockserver/model/schema/",
            "expectationId"
        );
    }

    private JsonSchemaExpectationIdValidator(MockServerLogger mockServerLogger, JsonSchemaExpectationIdValidator compiled) {
        super(compiled, mockServerLogger);
    }

    private static volatile JsonSchemaExpectationIdValidator compiled;

    /**
     * A validator that logs to {@code mockServerLogger}. The schema is compiled once per JVM, by a validator that
     * holds no server's logger, so a stopped server is not kept by it.
     */
    public static JsonSchemaExpectationIdValidator jsonSchemaExpectationIdValidator(MockServerLogger mockServerLogger) {
        JsonSchemaExpectationIdValidator shared = compiled;
        if (shared == null) {
            shared = new JsonSchemaExpectationIdValidator(new MockServerLogger(JsonSchemaExpectationIdValidator.class));
            compiled = shared;
        }
        return new JsonSchemaExpectationIdValidator(mockServerLogger, shared);
    }
}
