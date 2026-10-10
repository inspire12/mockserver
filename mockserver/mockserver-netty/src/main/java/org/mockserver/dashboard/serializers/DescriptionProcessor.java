package org.mockserver.dashboard.serializers;

import io.swagger.v3.oas.models.OpenAPI;
import org.apache.commons.lang3.StringUtils;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.model.DashboardLogEntryDTO;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.OpenAPIDefinition;
import org.mockserver.openapi.OpenAPIParser;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.openapi.OpenAPIParser.buildOpenAPI;

public class DescriptionProcessor {

    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger();
    private final Configuration configuration;
    private int maxHttpRequestLength;
    private int maxOpenAPILength;
    private int maxOpenAPIObjectLength;
    private int maxLogEventLength;

    /**
     * @param configuration the running server's configuration, which applies to what parsing an OpenAPI spec fetches
     */
    public DescriptionProcessor(Configuration configuration) {
        this.configuration = configuration;
    }

    public int getMaxHttpRequestLength() {
        return maxHttpRequestLength;
    }

    public int getMaxOpenAPILength() {
        return maxOpenAPILength;
    }

    public int getMaxOpenAPIObjectLength() {
        return maxOpenAPIObjectLength;
    }

    public int getMaxLogEventLength() {
        return maxLogEventLength;
    }

    public Description description(Object object) {
        return description(object, null);
    }

    public Description description(Object object, String id) {
        Description description = null;
        String idMessage = isNotBlank(id) ? id + ": " : "";
        if (object instanceof HttpRequest) {
            HttpRequest httpRequest = (HttpRequest) object;
            description = new RequestDefinitionDescription(idMessage + httpRequest.getMethod().getValue(), httpRequest.getPath().getValue(), this, false);
            if (description.length() >= maxHttpRequestLength) {
                maxHttpRequestLength = description.length();
            }
        } else if (object instanceof OpenAPIDefinition) {
            OpenAPIDefinition openAPIDefinition = (OpenAPIDefinition) object;
            String operationId = isNotBlank(openAPIDefinition.getOperationId()) ? openAPIDefinition.getOperationId() : "";
            String specUrlOrPayload = openAPIDefinition.getSpecUrlOrPayload().trim();
            OpenAPI openAPI = OpenAPIParser.isSpecUrl(specUrlOrPayload) ? null : parse(specUrlOrPayload);
            if (openAPI == null) {
                String name = OpenAPIParser.isSpecUrl(specUrlOrPayload) ? StringUtils.substringAfterLast(specUrlOrPayload, "/") : "spec";
                description = new RequestDefinitionDescription(idMessage + name, operationId, this, true);
                if (description.length() >= maxOpenAPILength) {
                    maxOpenAPILength = description.length();
                }
            } else {
                description = new RequestDefinitionObjectDescription(idMessage + "spec ", openAPI, operationId, this);
                if (description.length() >= maxOpenAPIObjectLength) {
                    maxOpenAPIObjectLength = description.length();
                }
            }
        } else if (object instanceof DashboardLogEntryDTO) {
            DashboardLogEntryDTO logEntryDTO = (DashboardLogEntryDTO) object;
            description = new LogMessageDescription(idMessage + StringUtils.substringAfter(logEntryDTO.getTimestamp(), "-"), logEntryDTO.getType() != null ? logEntryDTO.getType().name() : "", this);
            if (description.length() >= maxLogEventLength) {
                maxLogEventLength = description.length();
            }
        }

        return description;
    }

    /**
     * A spec that cannot be parsed, a refused fetch included, is described by name alone rather than failing the
     * whole dashboard update.
     */
    private OpenAPI parse(String specUrlOrPayload) {
        try {
            return buildOpenAPI(specUrlOrPayload, MOCK_SERVER_LOGGER, configuration);
        } catch (IllegalArgumentException unparseable) {
            return null;
        }
    }
}