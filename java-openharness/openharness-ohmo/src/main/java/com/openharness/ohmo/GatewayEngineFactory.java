package com.openharness.ohmo;

import com.openharness.api.AnthropicMessagesClient;
import com.openharness.api.StreamingApiClient;
import com.openharness.common.AgentRuntime;
import com.openharness.config.PermissionSettings;
import com.openharness.engine.QueryEngine;
import com.openharness.engine.tool.ToolRegistry;
import com.openharness.extensions.swarm.BackendRegistry;
import com.openharness.extensions.swarm.InProcessBackend;
import com.openharness.permissions.PermissionChecker;
import com.openharness.tools.AgentTool;
import com.openharness.tools.SendMessageTool;
import com.openharness.tools.ToolBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

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

        // Phase 1: basic tool registry, then engine
        ToolRegistry registry = ToolBootstrap.createBasicRegistry();
        QueryEngine qe = new QueryEngine(apiClient, registry, permissionChecker);

        // Phase 2: add agent-dependent tools that need the engine reference
        registry.register(new AgentTool(qe));
        registry.register(new SendMessageTool(null));
        registry.register(new com.openharness.tools.ToolSearchTool(registry));

        // Inject engine into InProcessBackend so in-process agents can actually run
        BackendRegistry backendRegistry = BackendRegistry.getInstance();
        InProcessBackend inProc = (InProcessBackend) backendRegistry.get("in_process");
        if (inProc != null) {
            inProc.setAgentRuntime(qe);
            logger.info("Injected AgentRuntime into InProcessBackend");
        }

        this.engine = qe;
        this.toolRegistry = registry;
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

    static String resolveBaseUrl(String providerProfile) {
        if (providerProfile == null) return null;
        String baseUrlKey = providerProfile.toUpperCase().replace('-', '_') + "_BASE_URL";
        String baseUrl = System.getenv(baseUrlKey);
        if (baseUrl != null && !baseUrl.isBlank()) return baseUrl;

        // Check for generic OPENHARNESS_BASE_URL
        baseUrl = System.getenv("OPENHARNESS_BASE_URL");
        return (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : null;
    }
}
