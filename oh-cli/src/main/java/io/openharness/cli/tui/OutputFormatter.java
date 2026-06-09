package io.openharness.cli.tui;

import java.util.List;

public class OutputFormatter {

    private static final int MAX_OUTPUT_LENGTH = 5000;

    public static String format(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";

        String text = markdown;
        text = formatCodeBlocks(text);
        text = formatBold(text);
        text = formatItalic(text);
        text = formatInlineCode(text);
        text = formatHeaders(text);
        text = formatUnorderedLists(text);
        text = formatLinks(text);

        if (text.length() > MAX_OUTPUT_LENGTH) {
            text = text.substring(0, MAX_OUTPUT_LENGTH)
                    + AnsiRenderer.dim("\n[...output truncated (" + (text.length() - MAX_OUTPUT_LENGTH) + " more chars)]");
        }

        return text;
    }

    static String formatBold(String text) {
        return text.replaceAll("\\*\\*(.+?)\\*\\*", AnsiRenderer.BOLD + "$1" + AnsiRenderer.RESET);
    }

    static String formatItalic(String text) {
        return text.replaceAll("\\*(.+?)\\*", AnsiRenderer.ITALIC + "$1" + AnsiRenderer.RESET);
    }

    static String formatInlineCode(String text) {
        return text.replaceAll("`([^`]+)`", AnsiRenderer.CYAN + "$1" + AnsiRenderer.RESET);
    }

    static String formatHeaders(String text) {
        return text.replaceAll("(?m)^### (.+)$", AnsiRenderer.bold("$1"))
                .replaceAll("(?m)^## (.+)$", AnsiRenderer.bold(AnsiRenderer.underline("$1")))
                .replaceAll("(?m)^# (.+)$", AnsiRenderer.bold(AnsiRenderer.blue(AnsiRenderer.underline("$1"))));
    }

    static String formatCodeBlocks(String text) {
        StringBuilder sb = new StringBuilder();
        boolean inCodeBlock = false;
        for (String line : text.split("\n")) {
            if (line.trim().startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                if (inCodeBlock) {
                    sb.append(AnsiRenderer.dim("--- code ---\n"));
                } else {
                    sb.append(AnsiRenderer.dim("--- end ---\n"));
                }
            } else if (inCodeBlock) {
                sb.append(AnsiRenderer.CYAN).append(line).append(AnsiRenderer.RESET).append('\n');
            } else {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    static String formatUnorderedLists(String text) {
        return text.replaceAll("(?m)^[-*] ", AnsiRenderer.GREEN + "  • " + AnsiRenderer.RESET);
    }

    static String formatLinks(String text) {
        return text.replaceAll("\\[([^]]+)]\\(([^)]+)\\)",
                AnsiRenderer.CYAN + "$1" + AnsiRenderer.RESET + AnsiRenderer.dim(" ($2)"));
    }

    public static String formatToolResult(String toolName, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiRenderer.dim("[" + toolName + " result]\n"));
        sb.append(trimToLength(content, 2000));
        return sb.toString();
    }

    static String trimToLength(String text, int maxLen) {
        return text.length() > maxLen
                ? text.substring(0, maxLen) + AnsiRenderer.dim("\n...[truncated]")
                : text;
    }
}
