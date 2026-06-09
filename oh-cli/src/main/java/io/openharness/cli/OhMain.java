package io.openharness.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "oh",
    description = "OpenHarness - CLI AI coding assistant",
    mixinStandardHelpOptions = true,
    version = "0.1.0"
)
public class OhMain implements Runnable {

    @CommandLine.Option(names = {"--health-check"}, description = "Run health check and exit")
    private boolean healthCheck;

    @CommandLine.Option(names = {"--config"}, description = "Path to settings.json",
        defaultValue = "${user.home}/.oh/settings.json")
    private String configPath;

    @Override
    public void run() {
        if (healthCheck) {
            System.out.println("Health check: not yet implemented");
            return;
        }
        System.out.println("OpenHarness v0.1.0 - REPL starting...");
        System.out.println("(Phase 4 will implement full REPL loop)");
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new OhMain()).execute(args);
        System.exit(exitCode);
    }
}
