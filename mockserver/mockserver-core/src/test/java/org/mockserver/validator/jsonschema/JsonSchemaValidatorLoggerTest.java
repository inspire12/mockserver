package org.mockserver.validator.jsonschema;

import com.networknt.schema.Schema;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;

import java.lang.reflect.Field;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Each server's serializers ask for the schema validators with that server's logger, which
 * reaches the server. A validator kept for the JVM with the first server's logger logged every server's validation
 * failures to that server and kept it in memory after it stopped.
 */
public class JsonSchemaValidatorLoggerTest {

    private static final String MALFORMED_JSON = "{ \"httpRequest\": ";

    @Test
    public void anExpectationValidatorLogsToTheLoggerItWasAskedWith() throws Exception {
        assertLogsToItsOwnLoggerAndSharesTheCompiledSchema(JsonSchemaExpectationValidator::jsonSchemaExpectationValidator);
    }

    @Test
    public void aRequestDefinitionValidatorLogsToTheLoggerItWasAskedWith() throws Exception {
        assertLogsToItsOwnLoggerAndSharesTheCompiledSchema(JsonSchemaRequestDefinitionValidator::jsonSchemaRequestDefinitionValidator);
    }

    @Test
    public void anExpectationIdValidatorLogsToTheLoggerItWasAskedWith() throws Exception {
        assertLogsToItsOwnLoggerAndSharesTheCompiledSchema(JsonSchemaExpectationIdValidator::jsonSchemaExpectationIdValidator);
    }

    @Test
    public void anHttpRequestValidatorLogsToTheLoggerItWasAskedWith() throws Exception {
        assertLogsToItsOwnLoggerAndSharesTheCompiledSchema(JsonSchemaHttpRequestValidator::jsonSchemaHttpRequestValidator);
    }

    @Test
    public void anHttpResponseValidatorLogsToTheLoggerItWasAskedWith() throws Exception {
        assertLogsToItsOwnLoggerAndSharesTheCompiledSchema(JsonSchemaHttpResponseValidator::jsonSchemaHttpResponseValidator);
    }

    @Test
    public void anHttpRequestAndHttpResponseValidatorLogsToTheLoggerItWasAskedWith() throws Exception {
        assertLogsToItsOwnLoggerAndSharesTheCompiledSchema(JsonSchemaHttpRequestAndHttpResponseValidator::jsonSchemaHttpRequestAndHttpResponseValidator);
    }

    @Test
    public void anOpenAPIExpectationValidatorLogsToTheLoggerItWasAskedWith() throws Exception {
        assertLogsToItsOwnLoggerAndSharesTheCompiledSchema(JsonSchemaOpenAPIExpectationValidator::jsonSchemaOpenAPIExpectationValidator);
    }

    @Test
    public void aVerificationValidatorLogsToTheLoggerItWasAskedWith() throws Exception {
        assertLogsToItsOwnLoggerAndSharesTheCompiledSchema(JsonSchemaVerificationValidator::jsonSchemaVerificationValidator);
    }

    @Test
    public void aVerificationSequenceValidatorLogsToTheLoggerItWasAskedWith() throws Exception {
        assertLogsToItsOwnLoggerAndSharesTheCompiledSchema(JsonSchemaVerificationSequenceValidator::jsonSchemaVerificationSequenceValidator);
    }

    private static void assertLogsToItsOwnLoggerAndSharesTheCompiledSchema(Function<MockServerLogger, ? extends JsonSchemaValidator> validatorFor) throws Exception {
        MockServerLogger firstServers = mock(MockServerLogger.class);
        MockServerLogger secondServers = mock(MockServerLogger.class);
        JsonSchemaValidator first = validatorFor.apply(firstServers);
        JsonSchemaValidator second = validatorFor.apply(secondServers);

        second.isValid(MALFORMED_JSON);

        verify(secondServers).logEvent(any(LogEntry.class));
        verifyNoInteractions(firstServers);
        assertThat("the schema is compiled once", compiledSchemaOf(second), sameInstance(compiledSchemaOf(first)));
    }

    private static Schema compiledSchemaOf(JsonSchemaValidator validator) throws Exception {
        Field field = JsonSchemaValidator.class.getDeclaredField("validator");
        field.setAccessible(true);
        return (Schema) field.get(validator);
    }
}
