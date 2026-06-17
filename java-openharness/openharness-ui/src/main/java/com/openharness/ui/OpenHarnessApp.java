package com.openharness.ui;

import com.openharness.api.AnthropicMessagesClient;
import com.openharness.api.OpenAICompatibleClient;
import com.openharness.api.StreamingApiClient;
import com.openharness.common.AgentRuntime;
import com.openharness.common.ConversationMessage;
import com.openharness.common.ContentBlock;
import com.openharness.common.QueryOptions;
import com.openharness.common.Role;
import com.openharness.common.StreamEvent;
import com.openharness.config.ProviderProfile;
import com.openharness.config.Settings;
import com.openharness.engine.QueryEngine;
import com.openharness.engine.tool.ToolRegistry;
import com.openharness.extensions.mcp.McpClientManager;
import com.openharness.permissions.PermissionChecker;
import com.openharness.tools.ToolBootstrap;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;

/**
 * Application main entry point.
 * Wires Settings + RuntimeOutput + EventLoop + QueryEngine together.
 * Java equivalent of Python ui/app.py OpenHarnessApp.
 */
public class OpenHarnessApp {

    private final Settings settings;
    private final RuntimeOutput.Mode mode;
    private final AgentRuntime agentRuntime;
    private McpClientManager mcpManager;

    public OpenHarnessApp(Settings settings, RuntimeOutput.Mode mode, AgentRuntime agentRuntime) {
        this.settings = settings;
        this.mode = mode;
        this.agentRuntime = agentRuntime;
    }

    public void setMcpManager(McpClientManager mcpManager) {
        this.mcpManager = mcpManager;
    }

    /**
     * Create a fully-wired OpenHarnessApp with basic tools and default QueryEngine.
     * For advanced setups (MemoryTools, MCP dynamic tools), build the registry externally
     * and use the primary constructor.
     */
    public static OpenHarnessApp createDefault(Settings settings, RuntimeOutput.Mode mode) {
        StreamingApiClient apiClient = createApiClient(settings);
        ToolRegistry registry = ToolBootstrap.createBasicRegistry();
        PermissionChecker permissionChecker = new PermissionChecker(settings.permission());
        QueryEngine queryEngine = new QueryEngine(apiClient, registry, permissionChecker);
        return new OpenHarnessApp(settings, mode, queryEngine);
    }

    public void run(String initialPrompt) {
        RuntimeOutput output = RuntimeFactory.create(mode);

        if (mode == RuntimeOutput.Mode.TUI) {
            var tui = new TerminalUI();
            tui.start(settings, initialPrompt, agentRuntime);
        } else {
            output.emitReady("session-" + System.currentTimeMillis());
            if (initialPrompt != null && !initialPrompt.isEmpty()) {
                output.emitStatus("Starting with prompt: " + initialPrompt);
                runSingleQuery(initialPrompt, output);
            }
            EventLoop loop = new EventLoop(output, settings, agentRuntime);
            if (mcpManager != null) loop.setMcpManager(mcpManager);
            loop.run();
        }
    }

    private void runSingleQuery(String prompt, RuntimeOutput output) {
        List<ConversationMessage> messages = List.of(
                new ConversationMessage(Role.USER, List.of(new ContentBlock.TextBlock(prompt)))
        );
        QueryOptions options = QueryOptions.defaults()
                .withModel(settings.model())
                .withMaxTurns(settings.maxTurns())
                .withSystemPrompt(settings.systemPrompt());

        var publisher = agentRuntime.runQuery(messages, options);
        var latch = new CountDownLatch(1);

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(StreamEvent event) {
                switch (event) {
                    case StreamEvent.AssistantTextDelta(var text) -> output.emitAssistantDelta(text);
                    case StreamEvent.StatusEvent(var msg, var level) -> output.emitStatus(msg);
                    case StreamEvent.ErrorStreamEvent(var msg) -> output.emitError(msg);
                    default -> { }
                }
            }
            @Override public void onError(Throwable t) {
                output.emitError("Error: " + t.getMessage());
                latch.countDown();
            }
            @Override public void onComplete() {
                output.emitAssistantDelta("\n");
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static StreamingApiClient createApiClient(Settings settings) {
        String apiKey = resolveApiKey(settings);
        String provider = settings.provider();
        if (provider == null || provider.isBlank()) {
            provider = settings.activeProfile();
        }

        ProviderProfile profile = settings.mergedProfiles().get(provider);
        String apiFormat = profile != null ? profile.apiFormat() : settings.apiFormat();
        String baseUrl = profile != null && profile.baseUrl() != null
                ? profile.baseUrl()
                : settings.baseUrl();

        if ("anthropic".equals(apiFormat)) {
            return new AnthropicMessagesClient(apiKey, baseUrl, null);
        }
        return new OpenAICompatibleClient(apiKey, baseUrl);
    }

    private static String resolveApiKey(Settings settings) {
        String key = settings.apiKey();
        if (key != null && !key.isBlank()) {
            return key;
        }
        String env = System.getenv("ANTHROPIC_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        env = System.getenv("OPENAI_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return "";
    }
}
