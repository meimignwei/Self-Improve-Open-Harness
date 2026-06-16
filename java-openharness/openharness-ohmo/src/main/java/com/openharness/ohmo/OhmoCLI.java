package com.openharness.ohmo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Entry point for ohmo CLI commands.
 * Java equivalent of Python ohmo/cli.py (Typer CLI).
 *
 * <pre>
 * Usage: ohmo [command] [options]
 *
 * Commands:
 *   (no args)       Launch ohmo interactive session
 *   init            Initialize the .ohmo workspace
 *   config          Configure provider profile and gateway channels
 *   doctor          Check workspace and provider readiness
 *   memory list     List personal memories
 *   memory add      Add a personal memory entry
 *   memory remove   Remove a personal memory entry
 *   soul show       Show soul.md
 *   soul edit       Edit soul.md
 *   user show       Show user.md
 *   user edit       Edit user.md
 *   gateway start   Start gateway as daemon
 *   gateway stop    Stop gateway
 *   gateway restart Restart gateway
 *   gateway status  Show gateway status
 *   gateway run     Run gateway in foreground
 * </pre>
 */
public class OhmoCLI {

    private final Path workspaceRoot;
    private final WorkspaceManager wm;

    public OhmoCLI(String workspace) {
        this.wm = new WorkspaceManager();
        this.workspaceRoot = wm.resolve(workspace);
        wm.initialize(workspaceRoot);
    }

    // ==================================================================
    // workspace init
    // ==================================================================

    public void init(boolean interactive) {
        boolean existed = Files.exists(workspaceRoot.resolve("soul.md"));
        System.out.println("Initialized ohmo workspace at " + workspaceRoot);
        if (existed) {
            System.out.println("ohmo workspace already exists.");
            if (!interactive) {
                System.out.println("Use `ohmo config` to update provider and channel settings.");
                return;
            }
        }
        if (interactive) {
            System.out.println("Interactive config not available in headless mode.");
            System.out.println("Edit gateway.json to configure: " + workspaceRoot.resolve("gateway.json"));
        }
    }

    // ==================================================================
    // config
    // ==================================================================

    public void config() {
        GatewayConfig config = GatewayConfig.loadFromWorkspace(workspaceRoot);
        System.out.println("Current gateway config:");
        System.out.println("  provider_profile: " + config.providerProfile());
        System.out.println("  channels: " + String.join(", ", config.enabledChannels()));
        System.out.println("  send_progress: " + config.sendProgress());
        System.out.println("  send_tool_hints: " + config.sendToolHints());
        System.out.println("  log_level: " + config.logLevel());
        System.out.println("Edit: " + workspaceRoot.resolve("gateway.json"));
    }

    // ==================================================================
    // doctor — health check
    // ==================================================================

    public void doctor() {
        Map<String, Boolean> health = wm.healthCheck(workspaceRoot);
        System.out.println("ohmo doctor:");
        for (var entry : health.entrySet()) {
            System.out.printf("  - %s: %s%n", entry.getKey(), entry.getValue() ? "ok" : "missing");
        }
        System.out.println("  - workspace_root: " + workspaceRoot);
        System.out.println("  - state: " + workspaceRoot.resolve("state.json"));
        System.out.println("  - gateway_config: " + workspaceRoot.resolve("gateway.json"));
    }

    // ==================================================================
    // memory
    // ==================================================================

    public void memoryList() {
        OhmoMemoryBackend mem = new OhmoMemoryBackend(workspaceRoot.resolve("memory"));
        var entries = mem.listEntries();
        if (entries.isEmpty()) {
            System.out.println("No memories found.");
        } else {
            entries.forEach(e -> System.out.println("  [" + e.name() + "] " + e.content()));
        }
    }

    public void memoryAdd(String name, String content) {
        OhmoMemoryBackend mem = new OhmoMemoryBackend(workspaceRoot.resolve("memory"));
        mem.addEntry(name, content);
        System.out.println("Memory saved: " + name);
    }

    public void memoryRemove(String name) {
        OhmoMemoryBackend mem = new OhmoMemoryBackend(workspaceRoot.resolve("memory"));
        mem.removeEntry(name);
        System.out.println("Memory removed: " + name);
    }

    // ==================================================================
    // soul
    // ==================================================================

    public void soulShow() { showOrEdit(workspaceRoot.resolve("soul.md"), null); }

    public void soulEdit(String setText) { showOrEdit(workspaceRoot.resolve("soul.md"), setText); }

    // ==================================================================
    // user
    // ==================================================================

    public void userShow() { showOrEdit(workspaceRoot.resolve("user.md"), null); }

    public void userEdit(String setText) { showOrEdit(workspaceRoot.resolve("user.md"), setText); }

    // ==================================================================
    // identity
    // ==================================================================

    public void identityShow() { showOrEdit(workspaceRoot.resolve("identity.md"), null); }

    public void identityEdit(String setText) { showOrEdit(workspaceRoot.resolve("identity.md"), setText); }

    // ==================================================================
    // gateway
    // ==================================================================

    public void gatewayRun() {
        OhmoGatewayService service = new OhmoGatewayService(
                System.getProperty("user.dir"), workspaceRoot.toString());
        service.startAndWait();
    }

    public void gatewayStart() {
        OhmoGatewayService service = new OhmoGatewayService(
                System.getProperty("user.dir"), workspaceRoot.toString());
        service.start();
        System.out.println("ohmo gateway started (pid=" + ProcessHandle.current().pid() + ")");
    }

    public void gatewayStop() {
        OhmoGatewayService service = new OhmoGatewayService(
                System.getProperty("user.dir"), workspaceRoot.toString());
        service.stop();
        System.out.println("ohmo gateway stopped.");
    }

    public void gatewayRestart() {
        OhmoGatewayService service = new OhmoGatewayService(
                System.getProperty("user.dir"), workspaceRoot.toString());
        service.restart();
        System.out.println("ohmo gateway restarted (pid=" + ProcessHandle.current().pid() + ")");
    }

    public void gatewayStatus() {
        OhmoGatewayService service = new OhmoGatewayService(
                System.getProperty("user.dir"), workspaceRoot.toString());
        GatewayState state = service.getState();
        System.out.println("Gateway: " + (state.running() ? "running" : "stopped"));
        if (state.pid() != null) System.out.println("  pid: " + state.pid());
        System.out.println("  active_sessions: " + state.activeSessions());
        System.out.println("  provider_profile: " + state.providerProfile());
        System.out.println("  channels: " + String.join(", ", state.enabledChannels()));
        if (state.lastError() != null) System.out.println("  last_error: " + state.lastError());
    }

    // ==================================================================
    // sessions
    // ==================================================================

    public void sessionList() {
        OhmoSessionBackend backend = new OhmoSessionBackend(workspaceRoot);
        var snapshots = backend.listSnapshots(20);
        if (snapshots.isEmpty()) {
            System.out.println("No sessions found.");
            return;
        }
        for (var s : snapshots) {
            System.out.printf("  [%s] %s | %s | %d msgs | %s%n",
                    s.get("session_id"), s.get("created_at"),
                    s.get("model"), s.get("message_count"), s.get("summary"));
        }
    }

    // ==================================================================
    // groups
    // ==================================================================

    public void groupList() {
        GroupRegistry registry = new GroupRegistry(workspaceRoot);
        for (String channel : List.of("feishu", "slack", "discord", "telegram")) {
            var records = registry.listRecords(channel);
            if (!records.isEmpty()) {
                System.out.println("  [" + channel + "]");
                for (var r : records) {
                    System.out.printf("    %s: %s (cwd=%s, status=%s)%n",
                            r.get("chat_id"), r.get("name"),
                            r.getOrDefault("cwd", "-"), r.get("binding_status"));
                }
            }
        }
    }

    // ==================================================================
    // interactive / print mode
    // ==================================================================

    public void printMode(String prompt, String model, Integer maxTurns, String profile) {
        System.out.println("ohmo print mode — prompt: " + truncate(prompt, 80));
        OhmoSessionRuntimePool pool = new OhmoSessionRuntimePool(
                workspaceRoot,
                profile != null ? profile : GatewayConfig.loadFromWorkspace(workspaceRoot).providerProfile(),
                model, maxTurns);
        // Process as a one-shot and print the result
        String sessionKey = "print:" + UUID.randomUUID().toString().substring(0, 8);
        var updates = pool.streamMessage(
                new MessageBus.InboundMessage("cli", sessionKey, "user", prompt,
                        false, Map.of(), null),
                sessionKey);
        for (var update : updates) {
            if ("final".equals(update.kind())) {
                System.out.println(update.text());
            } else if ("error".equals(update.kind())) {
                System.err.println("Error: " + update.text());
            } else if ("progress".equals(update.kind())) {
                System.err.println(update.text());
            }
        }
    }

    // ==================================================================
    // main entry point
    // ==================================================================

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String workspace = extractOption(args, "--workspace", "-w");
        OhmoCLI cli = new OhmoCLI(workspace);
        String cmd = args[0];

        try {
            switch (cmd) {
                case "init" -> {
                    boolean interactive = !hasFlag(args, "--no-interactive");
                    cli.init(interactive);
                }
                case "config" -> cli.config();
                case "doctor" -> cli.doctor();

                case "memory" -> handleMemory(cli, shift(args));
                case "soul" -> handleSoul(cli, shift(args));
                case "user" -> handleUser(cli, shift(args));
                case "identity" -> handleIdentity(cli, shift(args));
                case "gateway" -> handleGateway(cli, shift(args));
                case "session" -> handleSession(cli, shift(args));
                case "group" -> handleGroup(cli, shift(args));

                // Direct print mode
                case "-p", "--print" -> {
                    String prompt = args.length > 1 ? args[1] : "";
                    String model = extractOption(args, "--model", "-m");
                    String profile = extractOption(args, "--profile", "-P");
                    Integer maxTurns = extractIntOption(args, "--max-turns");
                    cli.printMode(prompt, model, maxTurns, profile);
                }

                default -> {
                    System.err.println("Unknown command: " + cmd);
                    printUsage();
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    // ==================================================================
    // Subcommand handlers
    // ==================================================================

    private static void handleMemory(OhmoCLI cli, String[] args) {
        String sub = args.length > 0 ? args[0] : "list";
        switch (sub) {
            case "list" -> cli.memoryList();
            case "add" -> {
                if (args.length < 3) { System.err.println("Usage: ohmo memory add <name> <content>"); return; }
                cli.memoryAdd(args[1], args[2]);
            }
            case "remove", "rm" -> {
                if (args.length < 2) { System.err.println("Usage: ohmo memory remove <name>"); return; }
                cli.memoryRemove(args[1]);
            }
            default -> System.err.println("Unknown memory subcommand: " + sub);
        }
    }

    private static void handleSoul(OhmoCLI cli, String[] args) {
        String sub = args.length > 0 ? args[0] : "show";
        switch (sub) {
            case "show" -> cli.soulShow();
            case "edit" -> {
                String text = extractOption(args, "--set");
                cli.soulEdit(text);
            }
            default -> System.err.println("Unknown soul subcommand: " + sub);
        }
    }

    private static void handleUser(OhmoCLI cli, String[] args) {
        String sub = args.length > 0 ? args[0] : "show";
        switch (sub) {
            case "show" -> cli.userShow();
            case "edit" -> {
                String text = extractOption(args, "--set");
                cli.userEdit(text);
            }
            default -> System.err.println("Unknown user subcommand: " + sub);
        }
    }

    private static void handleIdentity(OhmoCLI cli, String[] args) {
        String sub = args.length > 0 ? args[0] : "show";
        switch (sub) {
            case "show" -> cli.identityShow();
            case "edit" -> {
                String text = extractOption(args, "--set");
                cli.identityEdit(text);
            }
            default -> System.err.println("Unknown identity subcommand: " + sub);
        }
    }

    private static void handleGateway(OhmoCLI cli, String[] args) {
        String sub = args.length > 0 ? args[0] : "start";
        switch (sub) {
            case "run" -> cli.gatewayRun();
            case "start" -> cli.gatewayStart();
            case "stop" -> cli.gatewayStop();
            case "restart" -> cli.gatewayRestart();
            case "status" -> cli.gatewayStatus();
            default -> System.err.println("Unknown gateway subcommand: " + sub);
        }
    }

    private static void handleSession(OhmoCLI cli, String[] args) {
        String sub = args.length > 0 ? args[0] : "list";
        if ("list".equals(sub)) cli.sessionList();
        else System.err.println("Unknown session subcommand: " + sub);
    }

    private static void handleGroup(OhmoCLI cli, String[] args) {
        String sub = args.length > 0 ? args[0] : "list";
        if ("list".equals(sub)) cli.groupList();
        else System.err.println("Unknown group subcommand: " + sub);
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private void showOrEdit(Path path, String setText) {
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException ignored) {}
        if (setText != null) {
            try {
                Files.writeString(path, setText.strip() + "\n", StandardCharsets.UTF_8);
                System.out.println("Updated " + path);
            } catch (IOException e) {
                System.err.println("Failed to write: " + path);
            }
            return;
        }
        if (!Files.exists(path)) {
            System.err.println(path + " does not exist yet.");
            return;
        }
        try {
            System.out.println(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("Failed to read: " + path);
        }
    }

    private static void printUsage() {
        System.out.println("ohmo: personal AI agent built on OpenHarness");
        System.out.println();
        System.out.println("Usage: ohmo <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  init              Initialize the .ohmo workspace");
        System.out.println("  config            Show gateway configuration");
        System.out.println("  doctor            Check workspace and provider readiness");
        System.out.println("  memory list       List personal memories");
        System.out.println("  memory add N C    Add a personal memory entry");
        System.out.println("  memory remove N   Remove a personal memory entry");
        System.out.println("  soul show         Show soul.md");
        System.out.println("  soul edit --set T Edit soul.md");
        System.out.println("  user show         Show user.md");
        System.out.println("  user edit --set T Edit user.md");
        System.out.println("  gateway start     Start gateway as daemon");
        System.out.println("  gateway stop      Stop gateway");
        System.out.println("  gateway restart   Restart gateway");
        System.out.println("  gateway status    Show gateway status");
        System.out.println("  gateway run       Run gateway in foreground");
        System.out.println("  session list      List session snapshots");
        System.out.println("  group list        List managed groups");
        System.out.println("  -p, --print TEXT  Run a single prompt and print result");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --workspace, -w   Path to ohmo workspace (default: ~/.ohmo)");
        System.out.println("  --model, -m       Override the model for this session");
        System.out.println("  --profile, -P     Override the provider profile");
        System.out.println("  --max-turns N     Override max turns");
    }

    static String[] shift(String[] args) {
        if (args.length <= 1) return new String[0];
        return Arrays.copyOfRange(args, 1, args.length);
    }

    static String extractOption(String[] args, String... names) {
        for (int i = 0; i < args.length - 1; i++) {
            for (String name : names) {
                if (name.equals(args[i])) return args[i + 1];
            }
        }
        return null;
    }

    static boolean hasFlag(String[] args, String... names) {
        for (String arg : args) {
            for (String name : names) {
                if (name.equals(arg)) return true;
            }
        }
        return false;
    }

    static Integer extractIntOption(String[] args, String name) {
        String val = extractOption(args, name);
        if (val != null) {
            try { return Integer.parseInt(val); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    static String truncate(String s, int limit) {
        if (s == null) return "";
        if (s.length() <= limit) return s;
        return s.substring(0, limit - 3) + "...";
    }
}
