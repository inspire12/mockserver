package org.mockserver.serialization.java;

import org.mockserver.model.DnsRecord;
import org.mockserver.model.DnsResponse;

public class DnsResponseToJavaSerializer implements ToJavaSerializer<DnsResponse> {

    @Override
    public String serialize(int numberOfSpacesToIndent, DnsResponse dnsResponse) {
        if (dnsResponse == null) {
            return "";
        }
        return new FluentJavaBuilder(numberOfSpacesToIndent, "DnsResponse.dnsResponse()")
            .withEach("withAnswerRecords", dnsResponse.getAnswerRecords(), DnsResponseToJavaSerializer::serializeRecord)
            .withEach("withAuthorityRecords", dnsResponse.getAuthorityRecords(), DnsResponseToJavaSerializer::serializeRecord)
            .withEach("withAdditionalRecords", dnsResponse.getAdditionalRecords(), DnsResponseToJavaSerializer::serializeRecord)
            .with("withResponseCode", dnsResponse.getResponseCode())
            .withDelay("withDelay", dnsResponse.getDelay())
            .build();
    }

    private static String serializeRecord(int numberOfSpacesToIndent, DnsRecord record) {
        return new FluentJavaBuilder(numberOfSpacesToIndent, "DnsRecord.dnsRecord()")
            .with("withName", record.getName())
            .with("withType", record.getType())
            .with("withDnsClass", record.getDnsClass())
            .with("withTtl", record.getTtl())
            .with("withValue", record.getValue())
            .with("withPriority", record.getPriority())
            .with("withWeight", record.getWeight())
            .with("withPort", record.getPort())
            .build();
    }
}
