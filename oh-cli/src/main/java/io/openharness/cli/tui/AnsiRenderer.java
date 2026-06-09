package io.openharness.cli.tui;

public class AnsiRenderer {

    public static final String RESET = "\033[0m";
    public static final String BOLD = "\033[1m";
    public static final String DIM = "\033[2m";
    public static final String ITALIC = "\033[3m";
    public static final String UNDERLINE = "\033[4m";
    public static String underline(String text) { return UNDERLINE + text + RESET; }
    public static final String CYAN = "\033[36m";
    public static final String GREEN = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String BLUE = "\033[34m";

    public static String bold(String text) { return BOLD + text + RESET; }
    public static String dim(String text) { return DIM + text + RESET; }
    public static String italic(String text) { return ITALIC + text + RESET; }
    public static String cyan(String text) { return CYAN + text + RESET; }
    public static String green(String text) { return GREEN + text + RESET; }
    public static String blue(String text) { return BLUE + text + RESET; }
    public static String yellow(String text) { return YELLOW + text + RESET; }

    public static String prompt() {
        return bold(green("oh> "));
    }

    public static String clearScreen() {
        return "\033[H\033[2J";
    }
}
