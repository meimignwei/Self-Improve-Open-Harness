package com.openharness.ui;

import com.openharness.config.Settings;

/**
 * Application main entry point.
 * Wires Settings + RuntimeOutput + EventLoop together.
 * Java equivalent of Python ui/app.py OpenHarnessApp.
 */
public class OpenHarnessApp {

    private final Settings settings;
    private final RuntimeOutput.Mode mode;

    public OpenHarnessApp(Settings settings, RuntimeOutput.Mode mode) {
        this.settings = settings;
        this.mode = mode;
    }

    public void run(String initialPrompt) {
        RuntimeOutput output = RuntimeFactory.create(mode);

        if (mode == RuntimeOutput.Mode.TUI) {
            var tui = new TerminalUI();
            tui.start(settings, initialPrompt);
        } else {
            output.emitReady("session-" + System.currentTimeMillis());
            if (initialPrompt != null && !initialPrompt.isEmpty()) {
                output.emitStatus("Starting with prompt: " + initialPrompt);
            }
            new EventLoop(output, settings).run();
        }
    }
}
