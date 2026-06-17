package com.openharness.ohmo;

import com.openharness.extensions.coordinator.AgentDefinition;
import com.openharness.extensions.coordinator.AgentDefinitionsLoader;
import com.openharness.extensions.swarm.*;

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
    // setup — interactive workspace wizard
    // ==================================================================

    public void setup() {
        System.out.println();
        System.out.println("  ohmo workspace setup");
        System.out.println("  " + workspaceRoot);
        System.out.println();

        wm.initialize(workspaceRoot);

        // Step 1: Provider profile
        GatewayConfig config = GatewayConfig.loadFromWorkspace(workspaceRoot);
        System.out.println("  Provider profile: " + config.providerProfile());
        System.out.println("  Available: codex (Anthropic), openai, deepseek, dashscope, moonshot, gemini");
        System.out.print("  Change? (enter to keep): ");
        System.out.flush();
        try (var scanner = new java.util.Scanner(System.in)) {
            String profile = scanner.nextLine().trim();
            if (!profile.isEmpty()) {
                config = config.withProviderProfile(profile);
            }

            // Step 2: Channels
            System.out.println();
            System.out.println("  Enable channels?");
            System.out.println("    1. none (terminal only)");
            System.out.println("    2. feishu");
            System.out.println("    3. feishu + slack");
            System.out.print("  Choice [1]: ");
            System.out.flush();
            String chChoice = scanner.nextLine().trim();
            if (chChoice.isEmpty()) chChoice = "1";

            Map<String, Map<String, Object>> channelConfigs = new LinkedHashMap<>();
            List<String> channels = new ArrayList<>();
            switch (chChoice) {
                case "2" -> {
                    channels = List.of("feishu");
                    channelConfigs.put("feishu", configureFeishu(scanner));
                }
                case "3" -> {
                    channels = List.of("feishu", "slack");
                    channelConfigs.put("feishu", configureFeishu(scanner));
                }
                default -> channels = List.of();
            }
            config = config.withChannels(channels, channelConfigs);

            // Save
            Path saved = config.saveToWorkspace(workspaceRoot);
            System.out.println();
            System.out.println("  Gateway config saved to " + saved);
            System.out.println("  Run 'ohmo run' to start — use 'ohmo run --with-channels' for Feishu.");
        }
    }

    private Map<String, Object> configureFeishu(java.util.Scanner scanner) {
        Map<String, Object> feishu = new LinkedHashMap<>();
        System.out.println();
        System.out.println("  Feishu (Lark) configuration — get credentials from https://open.feishu.cn");
        System.out.print("  App ID: ");
        System.out.flush();
        feishu.put("app_id", scanner.nextLine().trim());
        System.out.print("  App Secret: ");
        System.out.flush();
        feishu.put("app_secret", scanner.nextLine().trim());
        System.out.print("  Verification Token: ");
        System.out.flush();
        feishu.put("verification_token", scanner.nextLine().trim());
        System.out.print("  Webhook port [18080]: ");
        System.out.flush();
        String port = scanner.nextLine().trim();
        feishu.put("webhook_port", port.isEmpty() ? 18080 : Integer.parseInt(port));
        return feishu;
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

    /**
     * Start an interactive coordinator session from the terminal.
     * Equivalent to: CLAUDE_CODE_COORDINATOR_MODE=1 oh run
     */
    public void run(String model, String profile, boolean coordinator, boolean withChannels) {
        GatewayConfig gwConfig = GatewayConfig.loadFromWorkspace(workspaceRoot);
        String effectiveProfile = profile != null ? profile : gwConfig.providerProfile();

        // Enable coordinator mode if requested
        if (coordinator) {
            System.setProperty("CLAUDE_CODE_COORDINATOR_MODE", "1");
        }

        // Create the full engine stack
        GatewayEngineFactory factory = new GatewayEngineFactory(workspaceRoot, gwConfig);
        com.openharness.common.AgentRuntime engine = factory.engine();

        // Optionally start channels (Feishu, etc.) for external input
        OhmoGatewayService gwService = null;
        if (withChannels) {
            gwService = new OhmoGatewayService(
                    System.getProperty("user.dir"), workspaceRoot.toString());
            gwService.start();
            System.out.println("Channels started: " + gwConfig.enabledChannels());
        }

        // Create runtime pool with the engine
        OhmoSessionRuntimePool pool = new OhmoSessionRuntimePool(
                workspaceRoot, effectiveProfile, model, null, engine);

        String sessionKey = "term:" + UUID.randomUUID().toString().substring(0, 8);
        String modeLabel = coordinator ? "coordinator" : "single-agent";
        System.out.println("ohmo " + modeLabel + " session [" + sessionKey + "]");
        System.out.println("  workspace: " + workspaceRoot);
        System.out.println("  profile: " + effectiveProfile);
        System.out.println("  model: " + (model != null ? model : "default"));
        if (withChannels) {
            System.out.println("  channels: " + gwConfig.enabledChannels());
        }
        System.out.println("  coordinator mode: " + coordinator);
        System.out.println("Type your prompt (Ctrl+D or /exit to quit):");
        System.out.println();

        // Read-eval-print loop
        try (var scanner = new java.util.Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                System.out.flush();
                if (!scanner.hasNextLine()) break;
                String line = scanner.nextLine().trim();

                if (line.isEmpty()) continue;
                if (line.equals("/exit") || line.equals("/quit") || line.equals("/q")) break;

                System.out.println();
                var updates = pool.streamMessage(
                        new MessageBus.InboundMessage("terminal", sessionKey,
                                "user", line, false, Map.of(), null),
                        sessionKey);

                for (var update : updates) {
                    switch (update.kind()) {
                        case "text" -> System.out.print(update.text());
                        case "final" -> {
                            if (update.text() != null && !update.text().isBlank()) {
                                System.out.println("\n---");
                                System.out.println(update.text());
                            }
                        }
                        case "error" -> System.err.println("\n[Error] " + update.text());
                        case "progress" -> {
                            if (update.text() != null && !update.text().isBlank()) {
                                System.out.println("[...] " + update.text());
                            }
                        }
                        case "tool_use" -> {
                            if (update.text() != null) {
                                System.out.println("[tool] " + update.text());
                            }
                        }
                    }
                }
                System.out.println();
            }
        }

        System.out.println("\nShutting down...");
        if (gwService != null) {
            gwService.stop();
        }
    }

    // ==================================================================
    // swarm team commands
    // ==================================================================

    private final TeamLifecycle teamLifecycle = new TeamLifecycle();

    public void teamCreate(String name, String description) {
        TeamLifecycle.TeamFile team = teamLifecycle.createTeam(name, description != null ? description : "");
        System.out.println("Team created: " + team.name);
    }

    public void teamDelete(String name) {
        teamLifecycle.deleteTeam(name);
        System.out.println("Team deleted: " + name);
    }

    public void teamList() {
        List<TeamLifecycle.TeamFile> teams = teamLifecycle.listTeams();
        if (teams.isEmpty()) {
            System.out.println("No teams found.");
            return;
        }
        for (TeamLifecycle.TeamFile team : teams) {
            System.out.println("  " + team.name + " — " + team.description +
                    " (members: " + team.members.size() + ", created: " +
                    java.time.Instant.ofEpochSecond((long) team.createdAt) + ")");
        }
    }

    public void teamShow(String name) {
        TeamLifecycle.TeamFile team = teamLifecycle.getTeam(name);
        if (team == null) {
            System.out.println("Team not found: " + name);
            return;
        }
        System.out.println("Team: " + team.name);
        System.out.println("  description: " + team.description);
        System.out.println("  created_at: " + java.time.Instant.ofEpochSecond((long) team.createdAt));
        System.out.println("  lead_agent_id: " + team.leadAgentId);
        System.out.println("  hidden_panes: " + team.hiddenPaneIds.size());
        System.out.println("  members:");
        for (TeamLifecycle.TeamMember m : team.members.values()) {
            System.out.printf("    - %s (%s) mode=%s active=%s backend=%s%n",
                    m.name, m.agentId, m.mode, m.isActive, m.backendType);
        }
    }

    // ==================================================================
    // swarm agent commands
    // ==================================================================

    public void agentSpawn(String teamName, String agentType, String model, String prompt) {
        if (teamName == null || agentType == null) {
            System.err.println("Usage: ohmo agent spawn --team <name> --type <agent-type> [--model <m>] [--prompt <p>]");
            return;
        }

        // Look up agent definition
        AgentDefinitionsLoader loader = new AgentDefinitionsLoader();
        AgentDefinition def = loader.getDefinition(agentType);
        if (def == null) {
            System.err.println("Unknown agent type: " + agentType);
            System.err.println("Available types: " +
                    loader.loadAll(List.of()).stream().map(AgentDefinition::name).toList());
            return;
        }

        // Get existing team
        TeamLifecycle.TeamFile team = teamLifecycle.getTeam(teamName);
        if (team == null) {
            System.err.println("Team not found: " + teamName);
            return;
        }

        String agentId = agentType + "-" + UUID.randomUUID().toString().substring(0, 6);
        String effectiveModel = model != null ? model : def.model();
        String effectivePrompt = prompt != null ? prompt : def.systemPrompt();

        TeamLifecycle.TeamMember member = new TeamLifecycle.TeamMember(
                agentId, agentType, "in_process", System.currentTimeMillis() / 1000.0);
        member.model = effectiveModel;
        member.prompt = effectivePrompt;
        member.agentType = agentType;
        member.permissions = new ArrayList<>(def.permissions());
        member.mode = def.permissionMode() == com.openharness.permissions.PermissionMode.PLAN ? "plan" : "auto";

        // Assign a worktree if the team has one
        Path teamWorktrees = WorktreeManager.getWorktreesBaseDir().resolve(teamName);
        if (Files.exists(teamWorktrees)) {
            Path agentWorktree = WorktreeManager.getAgentWorktreeDir(agentId);
            member.worktreePath = agentWorktree.toString();
        }

        teamLifecycle.addMember(teamName, member);
        System.out.println("Agent spawned: " + agentId);
        System.out.println("  team: " + teamName);
        System.out.println("  type: " + agentType);
        System.out.println("  model: " + effectiveModel);
        System.out.println("  mode: " + member.mode);
    }

    public void agentStatus(String teamName, String agentName) {
        TeamLifecycle.TeamFile team = teamLifecycle.getTeam(teamName);
        if (team == null) {
            System.out.println("Team not found: " + teamName);
            return;
        }

        for (TeamLifecycle.TeamMember m : team.members.values()) {
            if (agentName == null || m.name.equals(agentName) || m.agentId.equals(agentName)) {
                System.out.println("Agent: " + m.name + " (" + m.agentId + ")");
                System.out.println("  status: " + m.status);
                System.out.println("  active: " + m.isActive);
                System.out.println("  mode: " + m.mode);
                System.out.println("  model: " + m.model);
                System.out.println("  backend: " + m.backendType);
                System.out.println("  worktree: " + (m.worktreePath != null ? m.worktreePath : "none"));
                System.out.println("  pane: " + (m.tmuxPaneId != null ? m.tmuxPaneId : "none"));
                System.out.println("  permissions: " + m.permissions);
                System.out.println();
            }
        }
    }

    public void agentKill(String teamName, String agentName) {
        TeamLifecycle.TeamFile team = teamLifecycle.getTeam(teamName);
        if (team == null) {
            System.out.println("Team not found: " + teamName);
            return;
        }

        String targetId = null;
        for (TeamLifecycle.TeamMember m : team.members.values()) {
            if (m.name.equals(agentName) || m.agentId.equals(agentName)) {
                targetId = m.agentId;
                break;
            }
        }
        if (targetId == null) {
            System.err.println("Agent not found in team " + teamName + ": " + agentName);
            return;
        }

        // Clean up worktree
        TeamLifecycle.TeamMember member = team.members.get(targetId);
        if (member != null && member.worktreePath != null) {
            WorktreeManager.destroyWorktree(Path.of(member.worktreePath));
        }

        // Remove from team
        teamLifecycle.removeMember(teamName, targetId);
        System.out.println("Agent killed: " + agentName + " (" + targetId + ") from team " + teamName);
    }

    public void agentList(String teamName) {
        if (teamName != null) {
            teamShow(teamName);
            return;
        }
        // List agents across all teams
        List<TeamLifecycle.TeamFile> teams = teamLifecycle.listTeams();
        if (teams.isEmpty()) {
            System.out.println("No teams found.");
            return;
        }
        for (TeamLifecycle.TeamFile team : teams) {
            for (TeamLifecycle.TeamMember m : team.members.values()) {
                System.out.println("  [" + team.name + "] " + m.name + " (" + m.agentId +
                        ") status=" + m.status + " active=" + m.isActive + " mode=" + m.mode);
            }
        }
    }

    // ==================================================================
    // swarm status
    // ==================================================================

    public void swarmStatus() {
        List<TeamLifecycle.TeamFile> teams = teamLifecycle.listTeams();
        if (teams.isEmpty()) {
            System.out.println("No active swarms.");
            return;
        }

        int totalAgents = 0;
        int activeAgents = 0;
        System.out.println("Swarms: " + teams.size());
        for (TeamLifecycle.TeamFile team : teams) {
            int active = 0;
            for (TeamLifecycle.TeamMember m : team.members.values()) {
                if (m.isActive) active++;
            }
            totalAgents += team.members.size();
            activeAgents += active;
            System.out.println("  " + team.name + ": " + active + "/" + team.members.size() + " agents active");
        }
        System.out.println("Total: " + activeAgents + "/" + totalAgents + " agents active");

        // Show backend registry status
        BackendRegistry backendRegistry = BackendRegistry.getInstance();
        System.out.println("Backends: " + backendRegistry.availableBackends());
        System.out.println("Default backend: " + backendRegistry.getDefault().type());
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
                case "run" -> {
                    String model = extractOption(args, "--model", "-m");
                    String profile = extractOption(args, "--profile", "-P");
                    boolean coordinator = !hasFlag(args, "--no-coordinator");
                    boolean withChannels = hasFlag(args, "--with-channels");
                    cli.run(model, profile, coordinator, withChannels);
                }
                case "init" -> {
                    boolean interactive = !hasFlag(args, "--no-interactive");
                    cli.init(interactive);
                }
                case "setup" -> cli.setup();
                case "config" -> cli.config();
                case "doctor" -> cli.doctor();

                case "memory" -> handleMemory(cli, shift(args));
                case "soul" -> handleSoul(cli, shift(args));
                case "user" -> handleUser(cli, shift(args));
                case "identity" -> handleIdentity(cli, shift(args));
                case "gateway" -> handleGateway(cli, shift(args));
                case "session" -> handleSession(cli, shift(args));
                case "group" -> handleGroup(cli, shift(args));
                case "team" -> handleTeam(cli, shift(args));
                case "agent" -> handleAgent(cli, shift(args));
                case "swarm" -> handleSwarm(cli, shift(args));

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

    private static void handleTeam(OhmoCLI cli, String[] args) {
        String sub = args.length > 0 ? args[0] : "list";
        switch (sub) {
            case "create" -> {
                String name = extractOption(args, "--name", "-n");
                String desc = extractOption(args, "--description", "-d");
                if (name == null) { System.err.println("Usage: ohmo team create --name <name> [--description <desc>]"); return; }
                cli.teamCreate(name, desc);
            }
            case "delete", "rm" -> {
                String name = extractOption(args, "--name", "-n");
                if (name == null) { System.err.println("Usage: ohmo team delete --name <name>"); return; }
                cli.teamDelete(name);
            }
            case "show" -> {
                String name = extractOption(args, "--name", "-n");
                if (name == null) { System.err.println("Usage: ohmo team show --name <name>"); return; }
                cli.teamShow(name);
            }
            case "list" -> cli.teamList();
            default -> System.err.println("Unknown team subcommand: " + sub);
        }
    }

    private static void handleAgent(OhmoCLI cli, String[] args) {
        String sub = args.length > 0 ? args[0] : "list";
        switch (sub) {
            case "spawn" -> {
                String team = extractOption(args, "--team", "-t");
                String type = extractOption(args, "--type");
                String model = extractOption(args, "--model", "-m");
                String prompt = extractOption(args, "--prompt", "-p");
                cli.agentSpawn(team, type, model, prompt);
            }
            case "status" -> {
                String team = extractOption(args, "--team", "-t");
                String name = extractOption(args, "--name", "-n");
                if (team == null) { System.err.println("Usage: ohmo agent status --team <name> [--name <agent>]"); return; }
                cli.agentStatus(team, name);
            }
            case "kill" -> {
                String team = extractOption(args, "--team", "-t");
                String name = extractOption(args, "--name", "-n");
                if (team == null || name == null) { System.err.println("Usage: ohmo agent kill --team <name> --name <agent>"); return; }
                cli.agentKill(team, name);
            }
            case "list" -> {
                String team = extractOption(args, "--team", "-t");
                cli.agentList(team);
            }
            default -> System.err.println("Unknown agent subcommand: " + sub);
        }
    }

    private static void handleSwarm(OhmoCLI cli, String[] args) {
        String sub = args.length > 0 ? args[0] : "status";
        if ("status".equals(sub)) cli.swarmStatus();
        else System.err.println("Unknown swarm subcommand: " + sub);
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
        System.out.println("  setup             Interactive setup wizard for ohmo workspace");
        System.out.println("  run               Start interactive session (--no-coordinator for single-agent)");
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
        System.out.println("  team create       Create a new agent team");
        System.out.println("  team delete       Delete an agent team");
        System.out.println("  team list         List all teams");
        System.out.println("  team show         Show team details");
        System.out.println("  agent spawn       Spawn a new agent in a team");
        System.out.println("  agent status      Show agent status");
        System.out.println("  agent kill        Kill and remove an agent");
        System.out.println("  agent list        List all agents (optionally by team)");
        System.out.println("  swarm status      Show overall swarm status");
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
