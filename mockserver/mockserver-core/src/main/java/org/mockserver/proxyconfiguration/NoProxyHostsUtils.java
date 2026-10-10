package org.mockserver.proxyconfiguration;

import io.netty.util.NetUtil;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;

public class NoProxyHostsUtils {

    /**
     * Whether a host is on a {@code noProxyHosts} list, as curl and the JVM match {@code no_proxy}: an entry is a host
     * name, matched exactly; a domain suffix ({@code *.example.com} or {@code .example.com}), which matches the domain
     * and every name under it; or an IP address, which matches only a host given as that address. No name is looked
     * up. Case and the host's port are ignored.
     */
    public static boolean isHostOnNoProxyList(String host, String noProxyHosts) {
        if (StringUtils.isBlank(noProxyHosts) || StringUtils.isBlank(host)) {
            return false;
        }
        String hostOnly = extractHost(host);
        String hostLower = hostOnly.toLowerCase(Locale.ROOT);
        byte[] hostAddress = NetUtil.createByteArrayFromIpAddressString(hostOnly);
        return Stream.of(noProxyHosts.split(","))
            .map(String::trim)
            .filter(StringUtils::isNotBlank)
            .anyMatch(pattern -> {
                String patternLower = pattern.toLowerCase(Locale.ROOT);
                if (patternLower.startsWith("*.") || patternLower.startsWith(".")) {
                    String domain = patternLower.substring(patternLower.indexOf('.') + 1);
                    return hostLower.endsWith("." + domain) || hostLower.equals(domain);
                }
                if (hostAddress != null) {
                    return Arrays.equals(hostAddress, NetUtil.createByteArrayFromIpAddressString(pattern));
                }
                return hostLower.equals(patternLower);
            });
    }

    static String extractHost(String hostHeader) {
        if (StringUtils.isBlank(hostHeader)) {
            return hostHeader;
        }
        String trimmed = hostHeader.trim();
        if (trimmed.startsWith("[")) {
            int closeBracket = trimmed.indexOf(']');
            if (closeBracket > 0) {
                return trimmed.substring(1, closeBracket);
            }
            return trimmed.substring(1);
        }
        long colonCount = trimmed.chars().filter(c -> c == ':').count();
        if (colonCount > 1) {
            return trimmed;
        }
        int lastColon = trimmed.lastIndexOf(':');
        if (lastColon <= 0) {
            return trimmed;
        }
        String afterColon = trimmed.substring(lastColon + 1);
        try {
            Integer.parseInt(afterColon);
            return trimmed.substring(0, lastColon);
        } catch (NumberFormatException e) {
            return trimmed;
        }
    }

}
