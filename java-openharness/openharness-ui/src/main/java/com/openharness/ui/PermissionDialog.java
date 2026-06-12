package com.openharness.ui;

import java.io.PrintWriter;
import java.util.Scanner;

/**
 * Interactive permission approval dialog with y/n/A/q options.
 * Java equivalent of Python ui/permission_dialog.py.
 */
public class PermissionDialog {

    public enum Response { ALLOW, DENY, ALLOW_ALL, QUIT }

    private final PrintWriter writer;
    private final Scanner scanner;

    public PermissionDialog() {
        this.writer = new PrintWriter(System.out, true);
        this.scanner = new Scanner(System.in);
    }

    public Response ask(String toolName, String details) {
        writer.println();
        writer.println("┌─ Permission Check ─────────────────────");
        writer.println("│ Tool: " + toolName);
        if (details != null && !details.isEmpty()) {
            writer.println("│ " + details);
        }
        writer.println("│");
        writer.println("│ [y] Allow once  [n] Deny  [A] Allow all  [q] Quit");
        writer.print("└─> ");
        writer.flush();

        while (true) {
            String input = scanner.nextLine().trim().toLowerCase();
            switch (input) {
                case "y", "yes" -> { return Response.ALLOW; }
                case "n", "no" -> { return Response.DENY; }
                case "a" -> { return Response.ALLOW_ALL; }
                case "q", "quit" -> { return Response.QUIT; }
                default -> {
                    writer.print("Invalid choice. [y/n/A/q]> ");
                    writer.flush();
                }
            }
        }
    }

    public boolean askBoolean(String message, boolean defaultValue) {
        String hint = defaultValue ? "[Y/n]" : "[y/N]";
        writer.print(message + " " + hint + "> ");
        writer.flush();

        String input = scanner.nextLine().trim().toLowerCase();
        if (input.isEmpty()) return defaultValue;
        return input.startsWith("y");
    }
}
