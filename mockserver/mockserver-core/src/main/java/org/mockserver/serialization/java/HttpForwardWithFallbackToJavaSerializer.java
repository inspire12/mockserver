package org.mockserver.serialization.java;

import org.mockserver.model.HttpForwardWithFallback;

public class HttpForwardWithFallbackToJavaSerializer implements ToJavaSerializer<HttpForwardWithFallback> {

    @Override
    public String serialize(int numberOfSpacesToIndent, HttpForwardWithFallback forwardWithFallback) {
        if (forwardWithFallback == null) {
            return "";
        }
        return new FluentJavaBuilder(numberOfSpacesToIndent, "HttpForwardWithFallback.forwardWithFallback()")
            .withObject("withForward", forwardWithFallback.getHttpForward(), new HttpForwardToJavaSerializer())
            .withObject("withFallback", forwardWithFallback.getFallbackResponse(), new HttpResponseToJavaSerializer())
            .withLiterals("withFallbackOnStatusCodes", forwardWithFallback.getFallbackOnStatusCodes())
            .with("withFallbackOnTimeout", forwardWithFallback.getFallbackOnTimeout())
            .withDelay("withDelay", forwardWithFallback.getDelay())
            .build();
    }
}
