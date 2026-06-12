# 四、核心子系统详细设计 — ohmo 个人代理层

> **源码锚点**：
> - ohmo 全部: `ohmo/` 目录（19 个文件，约 6300 行 Python）
> - Gateway: `ohmo/gateway/service.py`, `bridge.py`, `router.py`, `runtime.py`, `config.py`, `models.py`, `notify.py`, `provider_commands.py`, `group_tool.py`
> - Workspace: `ohmo/workspace.py`
> - 记忆: `ohmo/memory.py`
> - 会话存储: `ohmo/session_storage.py`
> - 提示词: `ohmo/prompts.py`
> - CLI: `ohmo/cli.py`, `__main__.py`
> - 群组: `ohmo/group_registry.py`

---

## 4.43 ohmo 个人代理层（openharness-ohmo）

**对标**：`ohmo/` 目录（19 个文件，约 6300 行 Python）

> **说明**：ohmo 不是 OpenHarness Core 的一部分，而是构建在 OpenHarness 之上的个人 AI Agent 应用。README 明确说明 "ohmo is a personal AI agent built on OpenHarness"。Java 复刻若要完整对齐，必须包含此模块。

### 4.43.1 总体架构

```
ohmo（个人代理层）
├── Gateway Service          # 前台/后台服务生命周期
│   ├── MessageBus           # 接入 channels 消息总线
│   ├── ChannelManager       # 管理 10 个渠道连接
│   └── OhmoGatewayBridge    # 消息桥接：渠道 ↔ RuntimePool
├── Session Runtime Pool     # 按 chat/thread 隔离的会话池
│   ├── RuntimeBundle        # 每个会话独立的 Agent 运行时
│   ├── Session Resume       # 从 session_storage 恢复历史
│   └── Gateway Tools        # ohmo 专属工具（如创建飞书群组）
├── Router                   # 会话路由策略
├── Workspace (~/.ohmo)      # 个人工作空间
│   ├── soul.md              # Agent 人格/价值观
│   ├── user.md              # 用户画像
│   ├── identity.md          # Agent 身份
│   ├── memory/              # 个人级持久记忆
│   ├── skills/              # 个人技能
│   ├── sessions/            # 会话持久化
│   └── plugins/             # 个人插件
├── Prompts                  # ohmo 专用系统提示词组装
├── Memory                   # ohmo 级记忆后端
├── Session Storage          # 按 session_key 的会话快照
└── CLI (ohmo)               # ohmo 专属命令入口
```

### 4.43.2 Gateway Service

```java
/**
 * 对标 Python OhmoGatewayService。
 * 前台/后台服务包装器，整合所有 Gateway 组件。
 */
public class OhmoGatewayService {
    private final MessageBus bus;
    private final ChannelManager manager;
    private final OhmoSessionRuntimePool runtimePool;
    private final OhmoGatewayBridge bridge;
    private final GatewayConfig config;

    public OhmoGatewayService(String cwd, String workspace) {
        this.config = loadGatewayConfig(workspace);
        this.bus = new MessageBus();
        this.manager = new ChannelManager(buildConfig(config), bus);
        this.runtimePool = new OhmoSessionRuntimePool(cwd, workspace, config.providerProfile());
        this.bridge = new OhmoGatewayBridge(bus, runtimePool, workspace);
    }

    public void start() {
        manager.connectAll();
        bridge.run();
    }

    public void stop() {
        bridge.shutdown();
        manager.disconnectAll();
    }

    public GatewayState getState() {
        return new GatewayState(
            running,
            ProcessHandle.current().pid(),
            runtimePool.activeSessions(),
            config.providerProfile(),
            config.enabledChannels()
        );
    }
}
```

### 4.43.3 Gateway Bridge

```java
/**
 * 对标 Python OhmoGatewayBridge。
 * 消费渠道入站消息，通过 Runtime Pool 生成回复，发布到出站总线。
 */
public class OhmoGatewayBridge {
    private final MessageBus bus;
    private final OhmoSessionRuntimePool runtimePool;
    private final String feishuGroupPolicy; // "managed_or_mention" / "open"

    public void run() {
        while (running) {
            InboundMessage msg = bus.consumeInbound(timeout);
            if (!shouldProcess(msg)) continue;

            String sessionKey = router.sessionKeyFor(msg);
            for (GatewayStreamUpdate update : runtimePool.streamMessage(msg, sessionKey)) {
                bus.publishOutbound(new OutboundMessage(msg.chatId(), update.text()));
            }
        }
    }

    /**
     * 飞书群组策略过滤。
     */
    private boolean shouldProcess(InboundMessage msg) {
        if (!"feishu".equals(msg.channel())) return true;
        // 群聊中仅处理 @ mentions 或 managed 群组
        return switch (feishuGroupPolicy) {
            case "open" -> true;
            case "managed_or_mention" -> msg.isMention() || isManagedGroup(msg.chatId());
            default -> msg.isMention();
        };
    }
}
```

### 4.43.4 Session Runtime Pool

```java
/**
 * 对标 Python OhmoSessionRuntimePool。
 * 为每个 chat/thread 维护独立的 RuntimeBundle，支持会话恢复和 cwd 变更重建。
 */
public class OhmoSessionRuntimePool {
    private final Map<String, RuntimeBundle> bundles = new ConcurrentHashMap<>();
    private final OhmoSessionBackend sessionBackend;
    private final String providerProfile;

    /**
     * 获取或创建会话运行时。
     * 私聊按 channel:chat_id 复用；群聊按 sender 隔离。
     */
    public RuntimeBundle getBundle(String sessionKey, String userPrompt, Path cwd) {
        RuntimeBundle existing = bundles.get(sessionKey);
        if (existing != null && existing.cwd().equals(cwd)) {
            existing.engine().setSystemPrompt(buildRuntimePrompt(cwd, userPrompt));
            return existing;
        }
        // 重建：cwd 变更或新会话
        if (existing != null) closeRuntime(existing);

        Map<String, Object> snapshot = sessionBackend.loadLatestForSessionKey(sessionKey);
        RuntimeBundle bundle = buildRuntime(
            cwd,
            systemPrompt: buildOhmoSystemPrompt(cwd),
            activeProfile: providerProfile,
            sessionBackend: sessionBackend,
            restoreMessages: sanitize(snapshot.get("messages")),
            extraSkillDirs: List.of(workspaceSkillsDir()),
            extraPluginRoots: List.of(workspacePluginsDir()),
            memoryBackend: createOhmoMemoryBackend(workspace),
            autodreamContext: buildAutodreamContext(workspace)
        );
        registerGatewayTools(bundle); // 注入 ohmo 专属工具
        bundles.put(sessionKey, bundle);
        return bundle;
    }

    /**
     * 处理入站消息，流式返回进度更新和最终回复。
     */
    public List<GatewayStreamUpdate> streamMessage(InboundMessage msg, String sessionKey) {
        Path cwd = resolveCwd(msg);
        RuntimeBundle bundle = getBundle(sessionKey, msg.content(), cwd);

        // 斜杠命令处理
        var parsed = bundle.commands().lookup(msg.content());
        if (parsed != null) {
            return executeSlashCommand(parsed, bundle, msg);
        }

        // 普通消息：提交给 QueryEngine
        List<ConversationMessage> messages = buildInboundUserMessage(msg);
        return PublisherAdapter.toStream(bundle.engine().runQuery(messages))
            .map(this::toGatewayUpdate)
            .toList();
    }

    public int activeSessions() { return bundles.size(); }
}
```

### 4.43.5 会话路由（Router）

```java
/**
 * 对标 Python session_key_for_message()。
 * 私聊保持长期可恢复会话；群聊按 sender 隔离避免多人共享记忆。
 */
public class OhmoSessionRouter {
    public String sessionKeyFor(InboundMessage msg) {
        if (msg.sessionKeyOverride() != null) return msg.sessionKeyOverride();

        String sender = str(msg.senderId()).orElse("anonymous");
        String chatType = str(msg.metadata().get("chat_type")).orElse("").toLowerCase();
        boolean isShared = Set.of("group", "chat", "supergroup", "channel", "room").contains(chatType);
        String threadId = or(msg.metadata().get("thread_id"),
                             msg.metadata().get("thread_ts"),
                             msg.metadata().get("message_thread_id"));

        if (threadId != null) {
            return isShared ? fmt("{}:{}:{}:{}", msg.channel(), msg.chatId(), threadId, sender)
                            : fmt("{}:{}:{}", msg.channel(), msg.chatId(), threadId);
        }
        return isShared ? fmt("{}:{}", msg.channel(), msg.chatId(), sender)
                        : fmt("{}:{}", msg.channel(), msg.chatId());
    }
}
```

### 4.43.6 Workspace 管理

```java
/**
 * 对标 Python ohmo/workspace.py。
 * 管理 ~/.ohmo 个人工作空间的初始化和健康检查。
 */
public class WorkspaceManager {
    private static final String WORKSPACE_DIRNAME = ".ohmo";

    public Path getWorkspaceRoot(String workspace) {
        if (workspace != null) return Path.of(workspace).resolve();
        String env = System.getenv("OHMO_WORKSPACE");
        if (env != null) return Path.of(env).resolve();
        return Path.of(System.getProperty("user.home")).resolve(WORKSPACE_DIRNAME);
    }

    public Path initialize(Path root) {
        root.mkdirs();
        createDir(root.resolve("memory"));
        createDir(root.resolve("skills"));
        createDir(root.resolve("plugins"));
        createDir(root.resolve("groups"));
        createDir(root.resolve("sessions"));
        createDir(root.resolve("logs"));
        createDir(root.resolve("attachments"));

        // 种子模板文件（首次创建）
        writeIfMissing(root.resolve("soul.md"), SOUL_TEMPLATE);
        writeIfMissing(root.resolve("user.md"), USER_TEMPLATE);
        writeIfMissing(root.resolve("identity.md"), IDENTITY_TEMPLATE);
        writeIfMissing(root.resolve("memory/MEMORY.md"), MEMORY_INDEX_TEMPLATE);
        writeIfMissing(root.resolve("state.json"), "{\"app\": \"ohmo\"}");
        writeIfMissing(root.resolve("gateway.json"), defaultGatewayConfig());
        return root;
    }

    public Map<String, Boolean> healthCheck(Path root) {
        return Map.of(
            "workspace", root.exists(),
            "soul", root.resolve("soul.md").exists(),
            "user", root.resolve("user.md").exists(),
            "identity", root.resolve("identity.md").exists(),
            "memory_dir", root.resolve("memory").exists(),
            "gateway_config", root.resolve("gateway.json").exists()
        );
    }
}
```

### 4.43.7 ohmo 专用系统提示词

```java
/**
 * 对标 Python ohmo/prompts.py build_ohmo_system_prompt()。
 * 组装 ohmo 人格提示词：Base + Soul + Identity + User Profile + Workspace Memory。
 */
public class OhmoSystemPromptBuilder {
    public String build(Path cwd, Path workspace) {
        List<String> sections = new ArrayList<>();
        sections.add(getBaseSystemPrompt());

        String soul = readText(workspace.resolve("soul.md"));
        if (soul != null) sections.add("# ohmo Soul\n" + soul);

        String identity = readText(workspace.resolve("identity.md"));
        if (identity != null) sections.add("# ohmo Identity\n" + identity);

        String user = readText(workspace.resolve("user.md"));
        if (user != null) sections.add("# User Profile\n" + user);

        String bootstrap = readText(workspace.resolve("BOOTSTRAP.md"));
        if (bootstrap != null) sections.add("# First-Run Bootstrap\n" + bootstrap);

        sections.add("""
            # ohmo Workspace
            - Personal workspace root: %s
            - Resume only within ohmo sessions; do not assume interoperability with plain OpenHarness sessions.
            """.formatted(workspace));

        String ohmoMemory = loadOhmoMemoryPrompt(workspace);
        if (ohmoMemory != null) sections.add(ohmoMemory);

        return String.join("\n\n", sections);
    }
}
```

### 4.43.8 ohmo 记忆与会话存储

```java
/**
 * 对标 Python ohmo/memory.py。
 * ohmo 级个人记忆后端（独立于项目级 MEMORY.md）。
 */
public class OhmoMemoryBackend {
    private final Path memoryDir;

    public List<MemoryEntry> listEntries() { /* 扫描 memoryDir 下 .md 文件 */ }
    public void addEntry(String name, String content, MemoryType type) { /* 写入 memory/<name>.md */ }
    public void removeEntry(String name) { /* 删除 */ }
    public String loadMemoryPrompt() { /* 加载所有记忆为 prompt 段落 */ }
}

/**
 * 对标 Python ohmo/session_storage.py。
 * 按 session_key 持久化会话历史（messages + tool_metadata）。
 */
public class OhmoSessionBackend {
    private final Path sessionsDir;

    public void save(String sessionKey, String sessionId, List<ConversationMessage> messages,
                     Map<String, Object> toolMetadata) {
        Path file = sessionsDir.resolve(sessionKey + ".json");
        AtomicFileWriter.writeJson(file, Map.of(
            "session_key", sessionKey,
            "session_id", sessionId,
            "messages", messages,
            "tool_metadata", toolMetadata,
            "updated_at", Instant.now()
        ));
    }

    public Map<String, Object> loadLatestForSessionKey(String sessionKey) {
        Path file = sessionsDir.resolve(sessionKey + ".json");
        return file.exists() ? AtomicFileWriter.readJson(file) : Map.of();
    }
}
```

### 4.43.9 Gateway 配置与状态

```java
/**
 * 对标 Python GatewayConfig / GatewayState（ohmo/gateway/models.py）。
 */
public record GatewayConfig(
    String providerProfile,           // 默认 "codex"
    List<String> enabledChannels,     // 启用的渠道列表
    String sessionRouting,            // "chat-thread"
    boolean sendProgress,             // 发送进度提示
    boolean sendToolHints,            // 发送工具使用提示
    String permissionMode,            // "default"
    boolean sandboxEnabled,
    boolean allowRemoteAdminCommands, // 是否允许远程管理命令
    List<String> allowedRemoteAdminCommands,
    String logLevel,
    Map<String, Map<String, Object>> channelConfigs
) {}

public record GatewayState(
    boolean running,
    Long pid,
    int activeSessions,
    String providerProfile,
    List<String> enabledChannels,
    String lastError
) {}
```

### 4.43.10 ohmo CLI

```java
/**
 * 对标 Python ohmo/cli.py。
 * Picocli 实现的 ohmo 专属命令入口。
 */
@Command(name = "ohmo", description = "ohmo: a personal-agent app built on top of OpenHarness")
public class OhmoCommand {
    @Command(name = "memory", description = "Manage .ohmo memory")
    public void memory(@Option(names = "--add") String add,
                       @Option(names = "--remove") String remove,
                       @Option(names = "--list") boolean list) { ... }

    @Command(name = "soul", description = "Inspect or edit soul.md")
    public void soul() { ... }

    @Command(name = "user", description = "Inspect or edit user.md")
    public void user() { ... }

    @Command(name = "gateway", description = "Run the ohmo gateway")
    public void gateway(@Option(names = "--start") boolean start,
                        @Option(names = "--stop") boolean stop,
                        @Option(names = "--status") boolean status) { ... }
}
```
