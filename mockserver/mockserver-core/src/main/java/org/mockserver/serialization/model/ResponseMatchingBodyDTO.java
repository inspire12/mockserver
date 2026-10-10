package org.mockserver.serialization.model;

import org.mockserver.model.Not;
import org.mockserver.model.ResponseMatchingBody;

/**
 * The DTO for a {@link ResponseMatchingBody}: a request body matcher used as a response verification
 * body. It serialises as the wrapped body matcher itself.
 */
public class ResponseMatchingBodyDTO extends BodyWithContentTypeDTO {

    private final BodyDTO body;

    public ResponseMatchingBodyDTO(BodyDTO body) {
        super(body.getType(), null);
        this.body = body;
    }

    public BodyDTO getBody() {
        return body;
    }

    @Override
    public ResponseMatchingBody buildObject() {
        return new ResponseMatchingBody(Not.not(body.buildObject(), body.getNot()));
    }
}
