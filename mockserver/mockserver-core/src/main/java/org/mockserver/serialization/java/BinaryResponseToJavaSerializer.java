package org.mockserver.serialization.java;

import org.mockserver.model.BinaryResponse;

public class BinaryResponseToJavaSerializer implements ToJavaSerializer<BinaryResponse> {

    @Override
    public String serialize(int numberOfSpacesToIndent, BinaryResponse binaryResponse) {
        if (binaryResponse == null) {
            return "";
        }
        return new FluentJavaBuilder(numberOfSpacesToIndent, "BinaryResponse.binaryResponse()")
            .withBytes("withBinaryData", binaryResponse.getBinaryData())
            .withDelay("withDelay", binaryResponse.getDelay())
            .with("withUpstream", binaryResponse.getUpstream())
            .build();
    }
}
