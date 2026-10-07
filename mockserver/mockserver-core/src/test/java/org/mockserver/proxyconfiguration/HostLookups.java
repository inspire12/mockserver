package org.mockserver.proxyconfiguration;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gives one name a sequence of answers to the checks {@link InetAddressValidator} makes on the calling thread, as a
 * name whose DNS answer changes between lookups would: each check gets the next answer and the last is repeated.
 * Other names, and every lookup made outside the validator (the connection's own), are answered as usual.
 */
public final class HostLookups implements AutoCloseable {

    private final AtomicInteger lookups = new AtomicInteger();

    private HostLookups(String name, List<InetAddress> answers) {
        InetAddressValidator.lookupOnThisThread(host -> {
            if (!host.equalsIgnoreCase(name)) {
                return InetAddress.getByName(host);
            }
            int lookup = lookups.getAndIncrement();
            return answers.get(Math.min(lookup, answers.size() - 1));
        });
    }

    public static HostLookups answer(String name, InetAddress... answers) {
        return new HostLookups(name, Arrays.asList(answers));
    }

    /**
     * @return how many times the validator looked the name up
     */
    public int lookups() {
        return lookups.get();
    }

    @Override
    public void close() {
        InetAddressValidator.lookupOnThisThread(null);
    }
}
