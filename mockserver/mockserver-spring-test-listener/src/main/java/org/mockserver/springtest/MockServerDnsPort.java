package org.mockserver.springtest;

import org.springframework.beans.factory.annotation.Value;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the UDP port of MockServer's DNS mock server, the port the operating system chose for the default
 * {@code dnsPort} of 0, or -1 if DNS mocking is not on (turn it on with {@code mockserver.dnsEnabled=true}).
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Value("${mockServerDnsPort}")
public @interface MockServerDnsPort {
}
