package com.openharness.extensions.themes;

import java.util.Map;

/**
 * Five built-in themes.
 * Java equivalent of Python themes/builtin.py.
 */
public final class BuiltinThemes {

    public static final ThemeConfig DEFAULT = new ThemeConfig("default",
            new ColorsConfig("#569CD6", "#4EC9B0", "#CE9178", "#F44747", "#808080", "#1E1E1E", "#D4D4D4"),
            new BorderConfig("single", "─"),
            new IconConfig("⠋", "🔧", "❌", "✅", "🤖"),
            new LayoutConfig(false, true, true));

    public static final ThemeConfig DARK = new ThemeConfig("dark",
            new ColorsConfig("#7AA2F7", "#9ECE6A", "#E0AF68", "#F7768E", "#565F89", "#1A1B26", "#C0CAF5"),
            new BorderConfig("rounded", "─"),
            new IconConfig("⠋", "🔧", "❌", "✅", "🤖"),
            new LayoutConfig(false, true, true));

    public static final ThemeConfig MINIMAL = new ThemeConfig("minimal",
            new ColorsConfig("#000000", "#000000", "#000000", "#000000", "#666666", "#FFFFFF", "#000000"),
            new BorderConfig("none", ""),
            new IconConfig("", "", "", "", ""),
            new LayoutConfig(true, false, false));

    public static final ThemeConfig CYBERPUNK = new ThemeConfig("cyberpunk",
            new ColorsConfig("#00FF00", "#00CC00", "#00AA00", "#004400", "#008800", "#000000", "#00FF00"),
            new BorderConfig("double", "═"),
            new IconConfig(">", "#", "!", "$", "@"),
            new LayoutConfig(false, true, false));

    public static final ThemeConfig SOLARIZED = new ThemeConfig("solarized",
            new ColorsConfig("#268BD2", "#859900", "#B58900", "#DC322F", "#93A1A1", "#002B36", "#839496"),
            new BorderConfig("single", "─"),
            new IconConfig("⠋", "🔧", "❌", "✅", "🤖"),
            new LayoutConfig(false, true, true));

    public static final Map<String, ThemeConfig> ALL = Map.of(
            "default", DEFAULT,
            "dark", DARK,
            "minimal", MINIMAL,
            "cyberpunk", CYBERPUNK,
            "solarized", SOLARIZED);

    private BuiltinThemes() {}
}
