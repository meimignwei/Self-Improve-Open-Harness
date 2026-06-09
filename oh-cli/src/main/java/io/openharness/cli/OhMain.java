package io.openharness.cli;

import io.openharness.cli.commands.ClearCommand;
import io.openharness.cli.commands.ConfigCommand;
import io.openharness.cli.commands.HealthCommand;
import io.openharness.cli.commands.ModelCommand;
import io.openharness.cli.session.SessionManager;
import io.openharness.cli.tui.TerminalUI;
import io.openharness.core.AgentAssembler;
import io.openharness.core.commands.CommandRegistry;
import io.openharness.core.config.DataSourceConfig;
import io.openharness.core.config.Settings;
import io.openharness.core.config.SettingsLoader;
import io.openharness.core.observability.HealthChecker;
import io.openharness.core.session.SessionContext;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.sql.DataSource;
import java.nio.file.Path;

@Command(
    name = "oh",
    description = "OpenHarness - CLI AI coding assistant",
    mixinStandardHelpOptions = true,
    version = "0.1.0"
)
public class OhMain implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(OhMain.class);

    @Option(names = {"-m", "--model"}, description = "Model to use (sonnet/opus/haiku)")
    private String model;

    @Option(names = {"-c", "--continue"}, description = "Continue last session")
    private boolean continueLast;

    @Option(names = {"-p", "--prompt"}, description = "Initial prompt (non-interactive mode)")
    private String initialPrompt;

    @Option(names = {"--log-level"}, description = "Log level: DEBUG|INFO|WARN|ERROR")
    private String logLevel = "INFO";

    @Option(names = {"--health-check"}, description = "Run health check and exit")
    private boolean healthCheck;

    @Override
    public void run() {
        Settings settings = new SettingsLoader().load();
        String provider = settings.getProvider() != null ? settings.getProvider() : "anthropic";

        if (model != null) {
            String resolved = model;
            if ("anthropic".equalsIgnoreCase(provider)) {
                resolved = switch (model.toLowerCase()) {
                    case "sonnet" -> "claude-sonnet-4-6";
                    case "opus" -> "claude-opus-4-7";
                    case "haiku" -> "claude-haiku-4-5";
                    default -> model;
                };
            }
            settings.setModel(resolved);
        }

        if (healthCheck) {
            HealthChecker checker = new HealthChecker(settings);
            var report = checker.check();
            System.out.println("OpenHarness Health Check");
            System.out.println("========================");
            report.checks().forEach((name, status) ->
                System.out.printf("  [%s] %s%n",
                    status == HealthChecker.Status.UP ? " OK " :
                    status == HealthChecker.Status.DOWN ? "FAIL" : "WARN",
                    name));
            System.out.println("Overall: " + report.overall());
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Received shutdown signal, cleaning up...");
        }));

        Path workspaceDir = Path.of(settings.getWorkspaceDir());

        SqlSessionFactory sessionFactory = null;
        try {
            DataSource ds = DataSourceConfig.createHikariDataSource(
                    settings.getDbUrl(), settings.getDbUser(), settings.getDbPassword());
            sessionFactory = DataSourceConfig.createSqlSessionFactory(ds);
        } catch (Exception e) {
            log.warn("Database not available, running without persistence: {}", e.getMessage());
        }

        SessionManager sessionManager;

        if (sessionFactory != null) {
            sessionManager = new SessionManager(sessionFactory, settings);
        } else {
            log.info("Session persistence disabled");
            sessionManager = null;
        }

        SessionContext ctx;
        if (continueLast && sessionManager != null) {
            ctx = sessionManager.restoreLastSession()
                    .orElseGet(() -> {
                        log.info("No previous session found, creating new one");
                        return sessionManager.createSession(workspaceDir);
                    });
        } else if (sessionManager != null) {
            ctx = sessionManager.createSession(workspaceDir);
        } else {
            ctx = SessionContext.builder()
                    .sessionId("no-db-session")
                    .workspaceDir(workspaceDir)
                    .settings(settings)
                    .model(settings.getModel())
                    .build();
        }

        if (model != null) {
            ctx.setModel(settings.getModel());
        }

        CommandRegistry registry = new CommandRegistry();
        registry.register(new ConfigCommand(settings));
        registry.register(new ModelCommand(ctx));
        registry.register(new ClearCommand());

        if (sessionFactory != null && sessionManager != null) {
            HealthChecker healthChecker = new HealthChecker(settings);
            registry.register(new HealthCommand(healthChecker));
        }

        TerminalUI ui;
        if (sessionFactory != null) {
            AgentAssembler assembler = new AgentAssembler(sessionFactory, sessionManager.getWriter());
            ui = new TerminalUI(assembler, sessionManager, registry, ctx);
        } else {
            ui = new TerminalUI(null, sessionManager, registry, ctx);
        }

        int exitCode;
        if (initialPrompt != null) {
            exitCode = ui.runOnce(initialPrompt);
        } else {
            exitCode = ui.runInteractive();
        }

        System.exit(exitCode);
    }

    public String getModel() { return model; }
    public boolean isContinueLast() { return continueLast; }
    public String getInitialPrompt() { return initialPrompt; }
    public String getLogLevel() { return logLevel; }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new OhMain()).execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
