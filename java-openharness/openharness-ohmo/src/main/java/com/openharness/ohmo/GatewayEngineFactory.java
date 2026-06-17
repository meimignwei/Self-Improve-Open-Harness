package com.openharness.ohmo;

import com.openharness.api.AnthropicMessagesClient;
import com.openharness.api.StreamOptions;
import com.openharness.api.StreamingApiClient;
import com.openharness.common.AgentRuntime;
import com.openharness.common.ApiStreamEvent;
import com.openharness.common.ConversationMessage;
import com.openharness.common.ContentBlock;
import com.openharness.common.Role;
import com.openharness.config.MemorySettings;
import com.openharness.config.PermissionSettings;
import com.openharness.config.SandboxSettings;
import com.openharness.engine.AutoCompactCallback;
import com.openharness.engine.AutoCompactState;
import com.openharness.engine.QueryEngine;
import com.openharness.engine.tool.ToolRegistry;
import com.openharness.extensions.memory.MemoryManager;
import com.openharness.extensions.memory.MemoryType;
import com.openharness.extensions.sandbox.BashSandboxInterceptor;
import com.openharness.extensions.sandbox.SandboxManager;
import com.openharness.extensions.services.AutoDreamService;
import com.openharness.extensions.services.CompactionService;
import com.openharness.extensions.services.MemoryExtractionService;
import com.openharness.extensions.swarm.BackendRegistry;
import com.openharness.extensions.swarm.InProcessBackend;
import com.openharness.permissions.PermissionChecker;
import com.openharness.tools.AgentTool;
import com.openharness.tools.BashTool;
import com.openharness.tools.SendMessageTool;
import com.openharness.tools.ToolBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Creates and wires the full engine stack for the ohmo gateway.
 * Handles the circular dependency: AgentTool needs AgentRuntime,
 * QueryEngine needs ToolRegistry — resolved via two-phase init.
 */
public class GatewayEngineFactory {

    private static final Logger logger = LoggerFactory.getLogger(GatewayEngineFactory.class);

    private final AgentRuntime engine;
    private final ToolRegistry toolRegistry;

    public GatewayEngineFactory(Path workspaceRoot, GatewayConfig config) {
        String apiKey = resolveApiKey(config.providerProfile());
        String baseUrl = resolveBaseUrl(config.providerProfile());

        StreamingApiClient apiClient = new AnthropicMessagesClient(apiKey, baseUrl, "2023-06-01");

        PermissionSettings permSettings = new PermissionSettings();
        permSettings.setMode(config.permissionMode());
        PermissionChecker permissionChecker = new PermissionChecker(permSettings);

        // Phase 1: basic tool registry, then engine with memory/compaction callbacks
        ToolRegistry registry = ToolBootstrap.createBasicRegistry();

        // Wire memory manager for the workspace
        MemorySettings memSettings = new MemorySettings();
        MemoryManager memoryMgr = buildMemoryManager(workspaceRoot);
        CompactionService compactionSvc = new CompactionService();
        MemoryExtractionService extractionSvc = new MemoryExtractionService();

        Consumer<List<ConversationMessage>> afterTurnCallback = buildAfterTurnCallback(
                memoryMgr, extractionSvc, apiClient, memSettings);

        Runnable memoryPruner = memoryMgr != null ? memoryMgr::pruneExpired : null;

        // Auto-compaction state tracking (thresholds managed by CompactionService)
        AutoCompactState autoCompact = new AutoCompactState();
        // Wrap the compaction service in a callback to avoid module cycle
        AutoCompactCallback compactCallback = (messages, model) -> {
            var result = compactionSvc.autoCompactIfNeeded(
                    messages, apiClient, model, null,
                    autoCompact, 6, null, false, "auto",
                    null, null, null, null);
            return new AutoCompactCallback.CompactResult(result.messages(), result.wasCompacted());
        };

        QueryEngine qe = new QueryEngine(apiClient, registry, permissionChecker,
                null, autoCompact, compactCallback, null, null,
                afterTurnCallback, memoryPruner, null);

        // Phase 2: add agent-dependent tools that need the engine reference
        var agentTool = new AgentTool(qe);
        if (memoryMgr != null) agentTool.setMemoryManager(memoryMgr);
        registry.register(agentTool);
        registry.register(new SendMessageTool(null));
        registry.register(new com.openharness.tools.ToolSearchTool(registry));

        // Inject engine into InProcessBackend so in-process agents can actually run
        BackendRegistry backendRegistry = BackendRegistry.getInstance();
        InProcessBackend inProc = (InProcessBackend) backendRegistry.get("in_process");
        if (inProc != null) {
            inProc.setAgentRuntime(qe);
            if (memoryMgr != null) inProc.setMemoryManager(memoryMgr);
            logger.info("Injected AgentRuntime into InProcessBackend");
        }

        // Wire sandbox into BashTool if SRT is available
        SandboxSettings sandboxSettings = loadSandboxSettings(workspaceRoot, config);
        if (sandboxSettings.enabled()) {
            SandboxManager sandboxManager = new SandboxManager(sandboxSettings);
            BashSandboxInterceptor interceptor = new BashSandboxInterceptor(sandboxManager);
            BashTool.setSandboxInterceptor(interceptor);
            logger.info("Sandbox enabled: backend={} engine={}",
                    sandboxManager.activeBackend(), sandboxManager.sandboxEngine());
        }

        // Wire auto-dream background consolidation
        scheduleAutoDream(workspaceRoot, memSettings);

        this.engine = qe;
        this.toolRegistry = registry;
    }

    private static void scheduleAutoDream(Path workspaceRoot, MemorySettings memSettings) {
        if (!memSettings.autoDreamEnabled()) return;

        Path memoryDir = workspaceRoot.resolve("memory");
        if (!Files.exists(memoryDir)) return;

        AutoDreamService dreamService = new AutoDreamService(memoryDir);
        long intervalHours = (long) (memSettings.autoDreamMinHours() > 0
                ? memSettings.autoDreamMinHours() : 24);
        int minSessions = memSettings.autoDreamMinSessions() > 0
                ? memSettings.autoDreamMinSessions() : 5;

        Thread.ofVirtual()
                .name("autodream-scheduler")
                .start(() -> {
                    try {
                        while (!Thread.interrupted()) {
                            Thread.sleep(Duration.ofHours(intervalHours));
                            try {
                                // Check minimum session threshold
                                long memFileCount;
                                try (var files = Files.list(memoryDir)) {
                                    memFileCount = files.filter(
                                            f -> f.getFileName().toString().endsWith(".md")).count();
                                }
                                if (memFileCount < minSessions) {
                                    logger.debug("AutoDream: skipping — {} memory files < {} min sessions",
                                            memFileCount, minSessions);
                                    continue;
                                }
                                var result = dreamService.consolidate(List.of());
                                if (result.success()) {
                                    logger.info("AutoDream: oriented={} gathered={} pruned={}",
                                            result.oriented(), result.gathered(), result.pruned());
                                }
                            } catch (Exception e) {
                                logger.debug("AutoDream cycle failed: {}", e.getMessage());
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

        logger.info("AutoDream scheduled every {}h", intervalHours);
    }

    public AgentRuntime engine() {
        return engine;
    }

    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    // ------------------------------------------------------------------
    // API key resolution
    // ------------------------------------------------------------------

    static String resolveApiKey(String providerProfile) {
        if (providerProfile == null) providerProfile = "claude-api";

        String envKey = switch (providerProfile) {
            case "codex", "claude-api", "claude" -> "ANTHROPIC_API_KEY";
            case "openai" -> "OPENAI_API_KEY";
            case "deepseek" -> "DEEPSEEK_API_KEY";
            default -> providerProfile.toUpperCase().replace('-', '_') + "_API_KEY";
        };

        String apiKey = System.getenv(envKey);
        if (apiKey != null && !apiKey.isBlank()) {
            logger.info("Using API key from env var {}", envKey);
            return apiKey;
        }

        // Fallback: try ANTHROPIC_API_KEY for anthropic-compatible profiles
        if (!"ANTHROPIC_API_KEY".equals(envKey)) {
            apiKey = System.getenv("ANTHROPIC_API_KEY");
            if (apiKey != null && !apiKey.isBlank()) {
                logger.info("Using API key from env var ANTHROPIC_API_KEY (fallback for profile {})",
                        providerProfile);
                return apiKey;
            }
        }

        logger.warn("No API key found for profile {} (env var {})", providerProfile, envKey);
        return "";
    }

    static SandboxSettings loadSandboxSettings(Path workspaceRoot, GatewayConfig config) {
        Path sandboxFile = workspaceRoot.resolve("sandbox.json");
        if (java.nio.file.Files.exists(sandboxFile)) {
            try {
                return com.openharness.common.OpenHarnessObjectMapper.get()
                        .readValue(sandboxFile.toFile(), SandboxSettings.class);
            } catch (Exception e) {
                logger.debug("Failed to load sandbox.json: {}", e.getMessage());
            }
        }

        // Default: sandbox enabled if SRT is available and gateway config has sandbox enabled
        SandboxSettings defaults = new SandboxSettings();
        defaults.setEnabled(config.sandboxEnabled());
        return defaults;
    }

    static String resolveBaseUrl(String providerProfile) {
        if (providerProfile == null) return null;
        String baseUrlKey = providerProfile.toUpperCase().replace('-', '_') + "_BASE_URL";
        String baseUrl = System.getenv(baseUrlKey);
        if (baseUrl != null && !baseUrl.isBlank()) return baseUrl;

        // Check for generic OPENHARNESS_BASE_URL
        baseUrl = System.getenv("OPENHARNESS_BASE_URL");
        return (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : null;
    }

    // ------------------------------------------------------------------
    // Memory callback builders
    // ------------------------------------------------------------------

    private static MemoryManager buildMemoryManager(Path workspaceRoot) {
        Path memoryDir = workspaceRoot.resolve("memory");
        return new MemoryManager(memoryDir, new MemorySettings());
    }

    /**
     * Build the after-turn callback that auto-extracts durable memories via LLM.
     */
    private static Consumer<List<ConversationMessage>> buildAfterTurnCallback(
            MemoryManager memoryMgr, MemoryExtractionService extractionSvc,
            StreamingApiClient apiClient, MemorySettings memSettings) {
        if (memoryMgr == null || extractionSvc == null) return null;
        if (!memSettings.autoExtractEnabled()) return null;
        int maxRecords = memSettings.autoExtractMaxRecords() > 0
                ? memSettings.autoExtractMaxRecords() : 3;
        return (conversation) -> {
            var recentMessages = conversation.size() <= 6
                    ? conversation
                    : conversation.subList(conversation.size() - 6, conversation.size());

            try {
                String existingMemories = memoryMgr.listAll().stream()
                        .limit(10)
                        .map(m -> m.header().name() + ": " + m.body())
                        .reduce("", (a, b) -> a + "\n" + b);

                String llmResult = syncComplete(apiClient,
                        MemoryExtractionService.EXTRACTION_SYSTEM_PROMPT,
                        MemoryExtractionService.EXTRACTION_SYSTEM_PROMPT
                                + "\n\nRecent conversation:\n" + summarizeMessages(recentMessages));

                var records = extractionSvc.extract(recentMessages, existingMemories,
                        () -> llmResult);

                int count = 0;
                for (var record : records) {
                    if (count >= maxRecords) break;
                    MemoryType type;
                    try {
                        type = MemoryType.valueOf(record.type().toUpperCase());
                    } catch (IllegalArgumentException e) {
                        type = MemoryType.PROJECT;
                    }
                    memoryMgr.create(type, record.name(), "", record.content());
                    count++;
                }
            } catch (Exception e) {
                // Best-effort
            }
        };
    }

    /**
     * Synchronous LLM completion — collects streaming results into a single string.
     */
    static String syncComplete(StreamingApiClient apiClient,
                                String systemPrompt, String userPrompt) {
        try {
            List<ConversationMessage> messages = List.of(
                    new ConversationMessage(Role.USER, List.of(
                            new ContentBlock.TextBlock(userPrompt != null ? userPrompt : ""))));

            var stream = apiClient.streamMessages(
                    "claude-sonnet-4-6", systemPrompt, messages, List.of(),
                    StreamOptions.defaults());

            StringBuilder result = new StringBuilder();
            for (var event : (Iterable<ApiStreamEvent>) stream::iterator) {
                if (event instanceof ApiStreamEvent.ContentDelta(var text, var idx)) {
                    result.append(text);
                }
            }
            return result.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String summarizeMessages(List<ConversationMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (var msg : messages) {
            sb.append("[").append(msg.role().name()).append("]: ");
            for (var block : msg.content()) {
                if (block instanceof ContentBlock.TextBlock tb) {
                    String t = tb.text();
                    sb.append(t, 0, Math.min(t.length(), 500));
                } else if (block instanceof ContentBlock.ToolUseBlock tub) {
                    sb.append("[tool:").append(tub.name()).append("]");
                } else if (block instanceof ContentBlock.ToolResultBlock trb) {
                    String c = trb.content();
                    sb.append("[result:").append(c, 0, Math.min(c.length(), 200)).append("]");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
