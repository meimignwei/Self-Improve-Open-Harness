package com.openharness.cli;

import com.openharness.config.Settings;
import com.openharness.engine.tool.ToolRegistry;
import com.openharness.tools.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CLI entry point for OpenHarness.
 * Java equivalent of Python's CLI entry point (Typer).
 */
public final class OpenHarnessCLI {

    private static final Logger LOG = Logger.getLogger(OpenHarnessCLI.class.getName());

    private OpenHarnessCLI() {}

    public static void main(String[] args) {
        // Parse args (minimal for now, full Picocli integration comes later)
        String command = args.length > 0 ? args[0] : "run";
        Settings settings = Settings.load();

        switch (command) {
            case "run" -> runInteractive(settings);
            case "version" -> printVersion();
            case "config" -> printConfig(settings);
            default -> {
                System.out.println("OpenHarness v0.1.0");
                System.out.println("Usage: openharness [run|version|config]");
            }
        }
    }

    private static void runInteractive(Settings settings) {
        Path cwd = Path.of("").toAbsolutePath();

        // Build tool registry
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BashTool());
        registry.register(new FileReadTool());
        registry.register(new FileWriteTool());
        registry.register(new FileEditTool());
        registry.register(new GrepTool());
        registry.register(new GlobTool());
        registry.register(new WebFetchTool());
        registry.register(new WebSearchTool());

        System.out.println("OpenHarness v0.1.0");
        System.out.println("Model: " + settings.model());
        System.out.println("Provider: " + settings.provider());
        System.out.println("Permission mode: " + settings.permission().mode());
        System.out.println("CWD: " + cwd);
        System.out.println();
        System.out.println(settings.systemPrompt() != null
                ? settings.systemPrompt()
                : "Ready. Type /help for available commands.");
    }

    private static void printVersion() {
        System.out.println("openharness v0.1.0");
    }

    private static void printConfig(Settings settings) {
        System.out.println("Active settings:");
        System.out.println("  model: " + settings.model());
        System.out.println("  provider: " + settings.provider());
        System.out.println("  permission: " + settings.permission().mode());
        System.out.println("  theme: " + settings.theme());
        System.out.println("  fastMode: " + settings.fastMode());
    }
}
