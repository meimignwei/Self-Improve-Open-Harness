package com.openharness.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JLine 3 multi-line input with history and slash-command completion.
 * Java equivalent of Python ui/input.py.
 */
public class InputHandler {

    private final org.jline.reader.LineReader reader;
    private final SlashCommandCompleter completer;

    public InputHandler() {
        this.completer = new SlashCommandCompleter();
        var terminal = createTerminal();
        this.reader = org.jline.reader.LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(completer)
                .variable(org.jline.reader.LineReader.HISTORY_FILE,
                        java.nio.file.Path.of(System.getProperty("user.home"), ".openharness", "history"))
                .build();
    }

    private static org.jline.terminal.Terminal createTerminal() {
        try {
            return org.jline.terminal.TerminalBuilder.builder()
                    .system(true)
                    .jansi(true)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create terminal", e);
        }
    }

    public String readLine(String prompt) {
        return reader.readLine(prompt);
    }

    public org.jline.reader.LineReader lineReader() {
        return reader;
    }

    public void close() throws IOException {
        reader.getTerminal().close();
    }

    record UserInput(String text, boolean isCommand) {
        public UserInput(String text) {
            this(text, text.startsWith("/"));
        }
    }
}
