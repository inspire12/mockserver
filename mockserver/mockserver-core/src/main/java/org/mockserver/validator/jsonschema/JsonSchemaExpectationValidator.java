package org.mockserver.validator.jsonschema;

import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;

/**
 * @author jamesdbloom
 */
public class JsonSchemaExpectationValidator extends JsonSchemaValidator {

    private JsonSchemaExpectationValidator(MockServerLogger mockServerLogger) {
        super(
            mockServerLogger,
            Expectation.class,
            "org/mockserver/model/schema/",
            "expectation",
            "requestDefinition",
            "conditionalRequestDefinition",
            "openAPIDefinition",
            "binaryRequestDefinition",
            "dnsRequestDefinition",
            "httpRequest",
            "httpResponse",
            "httpTemplate",
            "httpForward",
            "httpClassCallback",
            "httpObjectCallback",
            "httpOverrideForwardedRequest",
            "httpForwardValidateAction",
            "httpForwardWithFallback",
            "httpError",
            "httpSseResponse",
            "httpLlmResponse",
            "httpWebSocketResponse",
            "grpcStreamResponse",
            "grpcBidiResponse",
            "binaryResponse",
            "dnsResponse",
            "dnsRecord",
            "afterAction",
            "captureRule",
            "expectationStep",
            "httpChaosProfile",
            "rateLimit",
            "times",
            "timeToLive",
            "stringOrJsonSchema",
            "body",
            "bodyWithContentType",
            "delay",
            "connectionOptions",
            "recoverAfter",
            "keyToMultiValue",
            "keyToValue",
            "socketAddress",
            "protocol",
            "draft-07"
        );
    }

    private JsonSchemaExpectationValidator(MockServerLogger mockServerLogger, JsonSchemaExpectationValidator compiled) {
        super(compiled, mockServerLogger);
    }

    private static volatile JsonSchemaExpectationValidator compiled;

    /**
     * A validator that logs to {@code mockServerLogger}. The schema is compiled once per JVM, by a validator that
     * holds no server's logger, so a stopped server is not kept by it.
     */
    public static JsonSchemaExpectationValidator jsonSchemaExpectationValidator(MockServerLogger mockServerLogger) {
        JsonSchemaExpectationValidator shared = compiled;
        if (shared == null) {
            shared = new JsonSchemaExpectationValidator(new MockServerLogger(JsonSchemaExpectationValidator.class));
            compiled = shared;
        }
        return new JsonSchemaExpectationValidator(mockServerLogger, shared);
    }
}
