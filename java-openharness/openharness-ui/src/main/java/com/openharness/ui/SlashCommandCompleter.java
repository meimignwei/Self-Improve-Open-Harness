package com.openharness.ui;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * JLine 3 auto-completion for slash commands.
 * Provides completions for /help, /model, /theme, /clear, /exit, etc.
 */
public class SlashCommandCompleter implements Completer {

    private static final List<CommandDef> COMMANDS = List.of(
            new CommandDef("/help", "Show available commands"),
            new CommandDef("/model", "claude-sonnet-4-6", "Switch AI model"),
            new CommandDef("/theme", "default", "Switch UI theme (default/dark/minimal)"),
            new CommandDef("/clear", "Clear the screen"),
            new CommandDef("/vim", "Toggle vim mode"),
            new CommandDef("/voice", "Toggle voice input"),
            new CommandDef("/tasks", "Show task list"),
            new CommandDef("/exit", "Exit OpenHarness"),
            new CommandDef("/quit", "Exit OpenHarness")
    );

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line();
        if (buffer.startsWith("/")) {
            for (CommandDef cmd : COMMANDS) {
                if (cmd.name.startsWith(buffer)) {
                    candidates.add(new Candidate(cmd.name, cmd.name, null, cmd.description, null, null, true));
                }
            }
        }
    }

    record CommandDef(String name, String description) {
        CommandDef(String name, String defaultArg, String description) {
            this(name, description);
        }
    }
}
