package org.mockserver.validator.jsonschema;

import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;

/**
 * @author jamesdbloom
 */
public class JsonSchemaHttpResponseValidator extends JsonSchemaValidator {

    private JsonSchemaHttpResponseValidator(MockServerLogger mockServerLogger) {
        super(
            mockServerLogger,
            HttpResponse.class,
            "org/mockserver/model/schema/",
            "httpResponse",
            "stringOrJsonSchema",
            "bodyWithContentType",
            "delay",
            "connectionOptions",
            "recoverAfter",
            "keyToMultiValue",
            "keyToValue",
            "draft-07"
        );
    }

    private JsonSchemaHttpResponseValidator(MockServerLogger mockServerLogger, JsonSchemaHttpResponseValidator compiled) {
        super(compiled, mockServerLogger);
    }

    private static volatile JsonSchemaHttpResponseValidator compiled;

    /**
     * A validator that logs to {@code mockServerLogger}. The schema is compiled once per JVM, by a validator that
     * holds no server's logger, so a stopped server is not kept by it.
     */
    public static JsonSchemaHttpResponseValidator jsonSchemaHttpResponseValidator(MockServerLogger mockServerLogger) {
        JsonSchemaHttpResponseValidator shared = compiled;
        if (shared == null) {
            shared = new JsonSchemaHttpResponseValidator(new MockServerLogger(JsonSchemaHttpResponseValidator.class));
            compiled = shared;
        }
        return new JsonSchemaHttpResponseValidator(mockServerLogger, shared);
    }

}
