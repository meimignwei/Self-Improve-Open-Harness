package com.openharness.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Root CLI entry point for OpenHarness.
 * Java equivalent of Python's CLI (Typer) entry.
 */
@Command(name = "oh",
        description = "OpenHarness — AI coding assistant CLI framework",
        subcommands = {MainCommand.RunCmd.class, MainCommand.ConfigCmd.class,
                MainCommand.DoctorCmd.class, MainCommand.InitCmd.class,
                MainCommand.GatewayCmd.class},
        mixinStandardHelpOptions = true,
        versionProvider = MainCommand.VersionProvider.class)
public class MainCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MainCommand()).execute(args);
        System.exit(exitCode);
    }

    static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"OpenHarness Java v0.1.0", "Java 21+ / Picocli 4.7 / JLine 3"};
        }
    }

    @Command(name = "run", description = "Start an interactive assistant session")
    static class RunCmd implements Callable<Integer> {
        @Option(names = {"-p", "--prompt"}, description = "Initial prompt (non-interactive)")
        private String prompt;

        @Option(names = {"-m", "--model"}, description = "Model override")
        private String model;

        @Option(names = {"-o", "--output"}, description = "Output style: print|tui|backend")
        private String outputStyle;

        @Override
        public Integer call() {
            var settings = com.openharness.config.Settings.load();
            if (model != null) settings.setModel(model);
            if (outputStyle != null) settings.setOutputStyle(outputStyle);

            var mode = com.openharness.ui.RuntimeFactory.resolveMode(
                    outputStyle != null ? outputStyle : settings.outputStyle());

            var app = new com.openharness.ui.OpenHarnessApp(settings, mode);
            app.run(prompt);
            return 0;
        }
    }

    @Command(name = "config", description = "View or modify configuration")
    static class ConfigCmd implements Callable<Integer> {
        @Option(names = {"show", "--show"}, defaultValue = "true",
                description = "Show current settings")
        private boolean show;

        @Option(names = {"--set"}, description = "Set a config key=value pair")
        private String set;

        @Override
        public Integer call() {
            var settings = com.openharness.config.Settings.load();

            if (set != null) {
                String[] parts = set.split("=", 2);
                if (parts.length == 2) {
                    applySetting(settings, parts[0].trim(), parts[1].trim());
                    settings.save();
                    System.out.println("Saved: " + parts[0] + " = " + parts[1]);
                } else {
                    System.err.println("Invalid format. Use key=value");
                }
            }

            if (show) {
                System.out.println("Active configuration:");
                System.out.println("  model: " + settings.model());
                System.out.println("  provider: " + settings.provider());
                System.out.println("  theme: " + settings.theme());
                System.out.println("  outputStyle: " + settings.outputStyle());
                System.out.println("  permission: " + settings.permission().mode());
                System.out.println("  vimMode: " + settings.vimMode());
                System.out.println("  voiceMode: " + settings.voiceMode());
            }
            return 0;
        }

        private void applySetting(com.openharness.config.Settings s, String key, String value) {
            switch (key) {
                case "model" -> s.setModel(value);
                case "provider" -> s.setProvider(value);
                case "theme" -> s.setTheme(value);
                case "outputStyle" -> s.setOutputStyle(value);
                case "vimMode" -> s.setVimMode(Boolean.parseBoolean(value));
                case "voiceMode" -> s.setVoiceMode(Boolean.parseBoolean(value));
                default -> System.err.println("Unknown key: " + key);
            }
        }
    }

    @Command(name = "doctor", description = "Diagnose environment and configuration")
    static class DoctorCmd implements Callable<Integer> {

        @Override
        public Integer call() {
            System.out.println("OpenHarness Doctor Report");
            System.out.println("=========================");
            System.out.println();

            System.out.println("--- OS ---");
            System.out.println("  os.name: " + System.getProperty("os.name"));
            System.out.println("  os.version: " + System.getProperty("os.version"));
            System.out.println("  os.arch: " + System.getProperty("os.arch"));
            System.out.println();

            System.out.println("--- Java ---");
            System.out.println("  java.version: " + System.getProperty("java.version"));
            System.out.println("  java.home: " + System.getProperty("java.home"));
            System.out.println("  virtual threads: " + (Runtime.version().feature() >= 21 ? "available" : "unavailable"));
            System.out.println();

            System.out.println("--- OpenHarness ---");
            var settings = com.openharness.config.Settings.load();
            System.out.println("  config dir: " + com.openharness.config.Paths.configDir());
            System.out.println("  config file: " + com.openharness.config.Paths.configFilePath());
            System.out.println("  config exists: " + java.nio.file.Files.exists(com.openharness.config.Paths.configFilePath()));
            System.out.println("  model: " + settings.model());
            System.out.println("  provider: " + settings.provider());
            System.out.println();

            System.out.println("--- Environment ---");
            String[] envVars = {"JAVA_HOME", "OPENHARNESS_CONFIG_DIR", "ANTHROPIC_API_KEY",
                    "OPENAI_API_KEY", "SLACK_BOT_TOKEN", "FEISHU_APP_ID"};
            for (String env : envVars) {
                String val = System.getenv(env);
                System.out.println("  " + env + ": " + (val != null ? "***set***" : "(not set)"));
            }

            return 0;
        }
    }

    @Command(name = "init", description = "Initialize a new OpenHarness workspace")
    static class InitCmd implements Callable<Integer> {
        @Option(names = {"-d", "--dir"}, description = "Target directory")
        private String dir = ".";

        @Override
        public Integer call() {
            java.nio.file.Path cwd = java.nio.file.Path.of(dir).toAbsolutePath().normalize();
            java.nio.file.Path ohDir = cwd.resolve(".openharness");

            try {
                java.nio.file.Files.createDirectories(ohDir);
                java.nio.file.Files.createDirectories(ohDir.resolve("skills"));
                java.nio.file.Files.createDirectories(ohDir.resolve("memory"));
                java.nio.file.Files.createDirectories(ohDir.resolve("autopilot"));

                java.nio.file.Path settingsFile = ohDir.resolve("settings.json");
                if (!java.nio.file.Files.exists(settingsFile)) {
                    java.nio.file.Files.writeString(settingsFile, "{\n  \"model\": \"claude-sonnet-4-6\"\n}\n");
                }

                System.out.println("Initialized OpenHarness workspace at: " + ohDir);
                System.out.println("Created .openharness/ with skills/, memory/, autopilot/");
                return 0;
            } catch (java.io.IOException e) {
                System.err.println("Failed to initialize: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "gateway", description = "ohmo gateway lifecycle management")
    static class GatewayCmd implements Callable<Integer> {
        @Option(names = {"--start"}, description = "Start the gateway")
        private boolean start;

        @Option(names = {"--stop"}, description = "Stop the gateway")
        private boolean stop;

        @Option(names = {"--status"}, description = "Show gateway status")
        private boolean status;

        @Override
        public Integer call() {
            if (status || (!start && !stop)) {
                System.out.println("Gateway status: checking...");
                System.out.println("  Use --start or --stop to manage the ohmo gateway.");
                System.out.println("  For full gateway functionality, use: oh ohmo gateway");
                return 0;
            }

            if (start) {
                System.out.println("Starting ohmo gateway...");
                System.out.println("  Use 'oh ohmo gateway --start' for full gateway control.");
            }

            if (stop) {
                System.out.println("Stopping ohmo gateway...");
                System.out.println("  Use 'oh ohmo gateway --stop' for full gateway control.");
            }

            return 0;
        }
    }
}
