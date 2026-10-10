package org.mockserver.socket;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;
import io.netty.util.internal.PlatformDependent;

import java.lang.management.ManagementFactory;

/**
 * Bounds the direct memory Netty may allocate when MockServer runs as its own process.
 * <p>
 * Netty's limit otherwise defaults to the JVM's {@code MaxDirectMemorySize}, which defaults to the maximum
 * heap, so the heap plus Netty's buffers may reach twice the heap: more than a container sized for the heap
 * has. The default set here is a quarter of the maximum heap, at least 64 MiB and never more than the heap.
 * An allocation beyond it fails with {@code OutOfDirectMemoryError}, which closes that connection rather
 * than the kernel killing the process.
 * <p>
 * It is not applied when {@code io.netty.maxDirectMemory} or {@code -XX:MaxDirectMemorySize} is already
 * set; a user's {@code io.netty.maxDirectMemory} is also copied to the relocated name Netty reads in the
 * shaded jar. It only takes effect if it runs before Netty's {@code PlatformDependent} initialises (a class
 * literal does not initialise it), which reads the property once - so it is called first from the CLI
 * entry point, not from embedded use.
 */
public final class NettyDirectMemoryLimit {

    // The shaded jar relocates Netty and rewrites string literals that start "io.netty", so the name a user
    // sets is assembled at runtime and the name this Netty reads is derived from where its classes live.
    static final String USER_PROPERTY = String.join(".", "io", "netty", "maxDirectMemory");
    static final String NETTY_PROPERTY = nettyPackagePrefix() + "maxDirectMemory";
    static final long MINIMUM_LIMIT_BYTES = 64L * 1024 * 1024;
    static final int MAX_HEAP_DIVISOR = 4;

    private static volatile String source = "JVM default";

    private NettyDirectMemoryLimit() {
        // utility class
    }

    /**
     * @return the limit set in bytes, or -1 if an explicit setting was found or the heap has no maximum
     */
    public static long applyDefault() {
        try {
            String userValue = System.getProperty(USER_PROPERTY);
            if (userValue != null) {
                if (System.getProperty(NETTY_PROPERTY) == null) {
                    System.setProperty(NETTY_PROPERTY, userValue);
                }
                source = "explicit";
                return -1;
            }
            if (System.getProperty(NETTY_PROPERTY) != null || maxDirectMemorySizeSetExplicitly()) {
                source = "explicit";
                return -1;
            }
            long limit = defaultLimitBytes(Runtime.getRuntime().maxMemory());
            if (limit > 0) {
                System.setProperty(NETTY_PROPERTY, Long.toString(limit));
                source = "default";
            }
            return limit;
        } catch (RuntimeException | LinkageError e) {
            return -1;
        }
    }

    /**
     * Where the limit came from: "default" (set by {@link #applyDefault()}), "explicit" (a user's
     * {@code io.netty.maxDirectMemory} or {@code -XX:MaxDirectMemorySize}) or "JVM default".
     */
    public static String source() {
        return source;
    }

    /**
     * The limit in effect and its {@link #source()}, e.g. "64 MiB (default)". Initialises Netty, so call it
     * only after {@link #applyDefault()}.
     */
    public static String describe() {
        return PlatformDependent.maxDirectMemory() / (1024 * 1024) + " MiB (" + source + ")";
    }

    static String nettyPackagePrefix() {
        String name = PlatformDependent.class.getName();
        return name.substring(0, name.indexOf("util.internal."));
    }

    static long defaultLimitBytes(long maxHeapBytes) {
        if (maxHeapBytes <= 0 || maxHeapBytes == Long.MAX_VALUE) {
            return -1;
        }
        return Math.min(maxHeapBytes, Math.max(MINIMUM_LIMIT_BYTES, maxHeapBytes / MAX_HEAP_DIVISOR));
    }

    private static boolean maxDirectMemorySizeSetExplicitly() {
        try {
            HotSpotDiagnosticMXBean hotSpot = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            if (hotSpot != null) {
                // the origin also covers flags that came from JAVA_TOOL_OPTIONS, which are not input arguments
                return hotSpot.getVMOption("MaxDirectMemorySize").getOrigin() != VMOption.Origin.DEFAULT;
            }
        } catch (RuntimeException | LinkageError ignore) {
            // not HotSpot: fall back to the command line
        }
        return ManagementFactory.getRuntimeMXBean().getInputArguments().stream().anyMatch(argument -> argument.startsWith("-XX:MaxDirectMemorySize="));
    }
}
