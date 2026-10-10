package org.mockserver.serialization.serializers.body;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.model.ResponseMatchingBody;

import java.io.IOException;

/**
 * Serialises a {@link ResponseMatchingBody} as the body matcher it wraps.
 */
public class ResponseMatchingBodySerializer extends StdSerializer<ResponseMatchingBody> {

    private static final long serialVersionUID = 1L;

    public ResponseMatchingBodySerializer() {
        super(ResponseMatchingBody.class);
    }

    @Override
    public void serialize(ResponseMatchingBody responseMatchingBody, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeObject(responseMatchingBody.getValue());
    }
}
