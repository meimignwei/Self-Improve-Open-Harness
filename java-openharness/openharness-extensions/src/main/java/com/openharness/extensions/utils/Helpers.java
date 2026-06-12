package com.openharness.extensions.utils;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * General-purpose utility methods.
 * Java equivalent of Python utils/helpers.py.
 */
public final class Helpers {

    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)(s|m|h|d)$");

    private Helpers() {}

    public static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static Duration parseDuration(String s) {
        Matcher m = DURATION_PATTERN.matcher(s);
        if (m.matches()) {
            long value = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "s" -> Duration.ofSeconds(value);
                case "m" -> Duration.ofMinutes(value);
                case "h" -> Duration.ofHours(value);
                case "d" -> Duration.ofDays(value);
                default -> Duration.ZERO;
            };
        }
        return Duration.ZERO;
    }
}
