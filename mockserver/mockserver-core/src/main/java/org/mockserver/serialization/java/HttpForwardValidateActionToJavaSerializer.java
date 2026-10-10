package org.mockserver.serialization.java;

import org.mockserver.model.HttpForwardValidateAction;

public class HttpForwardValidateActionToJavaSerializer implements ToJavaSerializer<HttpForwardValidateAction> {

    @Override
    public String serialize(int numberOfSpacesToIndent, HttpForwardValidateAction forwardValidate) {
        if (forwardValidate == null) {
            return "";
        }
        return new FluentJavaBuilder(numberOfSpacesToIndent, "HttpForwardValidateAction.forwardValidate()")
            .with("withSpecUrlOrPayload", forwardValidate.getSpecUrlOrPayload())
            .with("withHost", forwardValidate.getHost())
            .with("withPort", forwardValidate.getPort())
            .with("withScheme", forwardValidate.getScheme())
            .with("withValidateRequest", forwardValidate.getValidateRequest())
            .with("withValidateResponse", forwardValidate.getValidateResponse())
            .with("withValidationMode", forwardValidate.getValidationMode())
            .withDelay("withDelay", forwardValidate.getDelay())
            .build();
    }
}
