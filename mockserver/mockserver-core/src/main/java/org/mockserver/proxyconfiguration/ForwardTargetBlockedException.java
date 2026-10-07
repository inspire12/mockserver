package org.mockserver.proxyconfiguration;

/**
 * Thrown when {@code forwardProxyBlockPrivateNetworks} refuses a forward or proxy target: it resolves to a blocked
 * address, or cannot be resolved where MockServer runs. Its message names the target and why it was refused.
 */
public class ForwardTargetBlockedException extends IllegalArgumentException {

    public ForwardTargetBlockedException(String message) {
        super(message);
    }

    public ForwardTargetBlockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
