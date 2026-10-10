package org.mockserver.serialization.deserializers.body;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.serialization.model.BodyDTO;
import org.mockserver.serialization.model.BodyWithContentTypeDTO;
import org.mockserver.serialization.model.HttpResponseDTO;
import org.mockserver.serialization.model.ResponseMatchingBodyDTO;

import java.io.IOException;

/**
 * Deserialises the response template of a verification. Its body is a matcher, so it is read exactly as
 * a request body matcher is (every body type, plus subString, matchType and the other matching options)
 * instead of as a body to return, which only understands string, JSON, XML, binary and file bodies.
 */
public class VerificationHttpResponseDTODeserializer extends StdDeserializer<HttpResponseDTO> {

    private static final long serialVersionUID = 1L;

    public VerificationHttpResponseDTODeserializer() {
        super(HttpResponseDTO.class);
    }

    @Override
    public HttpResponseDTO deserialize(JsonParser jsonParser, DeserializationContext ctxt) throws IOException {
        JsonNode node = ctxt.readTree(jsonParser);
        if (!(node instanceof ObjectNode)) {
            return ctxt.readTreeAsValue(node, HttpResponseDTO.class);
        }
        JsonNode body = ((ObjectNode) node).remove("body");
        HttpResponseDTO httpResponseDTO = ctxt.readTreeAsValue(node, HttpResponseDTO.class);
        if (body != null && !body.isNull()) {
            BodyDTO bodyDTO = ctxt.readTreeAsValue(body, BodyDTO.class);
            if (bodyDTO instanceof BodyWithContentTypeDTO) {
                httpResponseDTO.setBody((BodyWithContentTypeDTO) bodyDTO);
            } else if (bodyDTO != null) {
                httpResponseDTO.setBody(new ResponseMatchingBodyDTO(bodyDTO));
            }
        }
        return httpResponseDTO;
    }
}
