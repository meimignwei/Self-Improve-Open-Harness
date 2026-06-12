package com.openharness.extensions.themes;

import java.util.Map;

public record ThemeConfig(
        String name,
        ColorsConfig colors,
        BorderConfig border,
        IconConfig icons,
        LayoutConfig layout
) {}

record ColorsConfig(
        String primary,
        String secondary,
        String accent,
        String error,
        String muted,
        String background,
        String foreground
) {}

record BorderConfig(String style, String char_) {}

record IconConfig(String spinner, String tool, String error, String success, String agent) {}

record LayoutConfig(boolean compact, boolean showTokens, boolean showTime) {}
