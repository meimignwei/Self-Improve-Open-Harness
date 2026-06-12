package com.openharness.extensions.utils;

import java.net.URI;
import java.util.Set;

/**
 * SSRF defense: blocks HTTP requests to internal/private addresses.
 * Java equivalent of Python utils/network_guard.py.
 */
public final class NetworkGuard {

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "127.0.0.1", "::1", "0.0.0.0",
            "169.254.169.254", "metadata.google.internal");

    private NetworkGuard() {}

    public static void validateUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) throw new NetworkGuardException("Missing host in URL: " + url);
            String hostLower = host.toLowerCase();

            if (BLOCKED_HOSTS.contains(hostLower)) {
                throw new NetworkGuardException("Blocked internal address: " + host);
            }
            if (hostLower.startsWith("10.") || hostLower.startsWith("192.168.")) {
                throw new NetworkGuardException("Blocked private address: " + host);
            }
        } catch (IllegalArgumentException e) {
            throw new NetworkGuardException("Invalid URL: " + url);
        }
    }

    public static class NetworkGuardException extends SecurityException {
        public NetworkGuardException(String message) {
            super(message);
        }
    }
}
