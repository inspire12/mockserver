package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Arrays;
import java.util.Objects;

public class BinaryResponse extends Action<BinaryResponse> {
    private int hashCode;
    private byte[] binaryData;

    public static BinaryResponse binaryResponse() {
        return new BinaryResponse();
    }

    public static BinaryResponse binaryResponse(byte[] binaryData) {
        return new BinaryResponse().withBinaryData(binaryData);
    }

    public byte[] getBinaryData() {
        return binaryData;
    }

    /**
     * The bytes written in reply to a matching binary message. With no data, or an empty array, the message has
     * no reply: nothing is written and the connection stays open. The two mean the same, because an empty array
     * is not serialised: set here and sent to MockServer it arrives as no data, and an expectation retrieved
     * from MockServer never has one.
     */
    public BinaryResponse withBinaryData(byte[] binaryData) {
        this.binaryData = binaryData;
        this.hashCode = 0;
        return this;
    }

    @Override
    @JsonIgnore
    public Type getType() {
        return Type.BINARY_RESPONSE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        BinaryResponse that = (BinaryResponse) o;
        return Arrays.equals(binaryData, that.binaryData);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), Arrays.hashCode(binaryData));
        }
        return hashCode;
    }
}
