# 四、核心子系统详细设计 — 记忆、Swarm与渠道

> **源码锚点**：
> - 记忆系统: `src/openharness/memory/` (13 files: manager.py, search.py, relevance.py, scan.py, types.py, schema.py, agent.py, team.py, memdir.py, migrate.py, paths.py, usage.py)
> - Swarm: `src/openharness/swarm/` (11 files: registry.py, subprocess_backend.py, in_process.py, tmux.py, mailbox.py, permission_sync.py, spawn_utils.py, team_lifecycle.py, worktree.py, lockfile.py, types.py)
> - 渠道: `src/openharness/channels/` (14 files: adapter.py, bus/, impl/)

---

## 4.5 记忆系统（openharness-memory）

**对标**：`src/openharness/memory/`(12个文件）

### 4.5.1 记忆条目模型

```java
/* 
对标 Python MemoryEntry
* Markdown 文件＋ YAML frontmatter 格式。
*/
public record MemoryEntry(
        MemoryHeader header,
        String body
) {
    public record MemoryHeader(
            int schemaVersion, // 默认 2
            String id, // UUID
            String name,
            String description,
            MemoryType type, // USER, FEEDBACK, PROJECT, REFERENCE
            String category,
            int importance, // 1-10
            String source,
            String signature, // 内容签名（去重）
            Instant createdAt,
            Instant updatedAt,
            Integer ttlDays, // 可选 TTL
            boolean disabled,
            List<String> supersedes // 被取代的记忆 ID 列表
    ) {}
}
```

### 4.5.2 相关性搜索

```java
/**
 * 启发式评分：元数据 2x＋正文
 */
public class MemorySearch {

    public List<ScoredMemory> search(String query, List<MemoryEntry> memories, int topK) {
        return memories.stream()
                .map(memory -> score(query, memory))
                .sorted(Comparator.comparing(ScoredMemory::score).reversed())
                .limit(topK)
                .toList();
    }

    private ScoredMemory score(String query, MemoryEntry memory) {
        double metadataScore = computeMetadataMatch(query, memory.header()) * 2.0;
        double bodyScore = computeBodyMatch(query, memory.body());
        double importanceBoost = memory.header().importance() / 10.0;
        double recencyBoost = computeRecencyBoost(memory.header().updatedAt());
        double usageBoost = computeUsageBoost(memory.header().id());

        double total = metadataScore + bodyScore + importanceBoost + recencyBoost + usageBoost;
        return new ScoredMemory(memory, total);
    }
}
```

### 4.5.3 记忆系统深度解析（补充）

**对标**：`src/openharness/memory/`(agent/team/memdir/migrate/usage)

```java
/**
 * 对标 Python memory/agent.py — Agent 特定记忆上下文。
 */
public class AgentMemoryContext {
    public String buildPrompt(String agentType, MemoryManager mgr) { ... }
}

/**
 * 对标 Python memory/team.py — 团队共享记忆（team_secret 令牌验证）。
 */
public class TeamMemoryManager {
    public boolean validateWriteAccess(String secret) { ... }
}

/**
 * 对标 Python memory/migrate.py — 记忆 schema 迁移。
 */
public class MemoryMigrator {
    public void migrate(Path memoryDir, int fromVersion) {
        backup(memoryDir);
        for (Path f : listMemoryFiles(memoryDir)) {
            writeMemoryFile(f, migrateEntry(parseMemoryFile(f), fromVersion));
        }
    }
}

/**
 * 对标 Python memory/usage.py — 记忆使用追踪。
 */
public class MemoryUsageTracker {
    public void recordUsage(String memoryId) { ... }
    public double computeUsageBoost(String memoryId) {
        int count = getUsageCount(memoryId);
        return Math.min(1.0, count / 10.0);
    }
}
```

### 4.5.4 记忆系统补充

**对标**：`src/openharness/memory/`(schema/relevance/scan/types 等)

```java
public enum MemoryType { USER, FEEDBACK, PROJECT, REFERENCE }

public class MemorySignature {
    public static String compute(String name, String body) {
        return DigestUtils.sha256Hex(name + "::" + body).substring(0, 16);
    }
}

/**
 * 启发式评分：元数据 2x + 正文 BM25 + 重要性 + 新鲜度 (30 天半衰期) + 使用频次。
 */
public class MemoryRelevance {
    public double score(MemoryEntry m, String query) {
        double meta = fuzzyMatch(m.header().name(), query) * 2.0
                    + fuzzyMatch(m.header().description(), query) * 2.0;
        double body = bm25Score(m.body(), query);
        double imp = m.header().importance() / 10.0;
        double rec = Math.exp(-Duration.between(m.header().updatedAt(), Instant.now()).toDays() / 30.0);
        return meta + body + imp + rec + usageTracker.computeUsageBoost(m.header().id());
    }
}
```

---

## 4.6 多智能体 Swarm (openharness-swarm）

**对标**：`src/openharness/swarm/`(11个文件）

### 4.6.1 Teammate 后端抽象

```java
/**
 * 对标 Python TeammateBackend ABC。
 * 智能体执行后端的统一抽象。
 */
public interface TeammateBackend {

    /**
     * 启动一个 teammate 并返回其 ID。
     */
    String spawn(TeammateSpec spec);

    /**
     * 发送消息给 teammate。
     */
    void sendMessage(String teammateId, String message);

    /**
     * 获取 teammate 状态。
     */
    TeammateStatus getStatus(String teammateId);

    /**
     * 终止 teammate。
     */
    void stop(String teammateId);
}

// 三种后端实现
public class SubprocessBackend implements TeammateBackend {...}
public class InProcessBackend implements TeammateBackend {...} // Virtual Threads
public class TmuxBackend implements TeammateBackend {...}
```

### 4.6.2 文件邮箱

```java
/**
 * 对标 Python swarm/mailbox.py。
 * 基于文件系统的邮箱通信：Agent 通过写入/轮询 JSON 文件交换消息。
 */
public class FileMailbox {
    private final Path mailboxDir;

    public FileMailbox(Path mailboxDir) {
        this.mailboxDir = mailboxDir;
        mailboxDir.toFile().mkdirs();
    }

    public void send(String recipientId, MailboxMessage message) {
        Path msgFile = mailboxDir.resolve(recipientId + "_" + UUID.randomUUID() + ".json");
        AtomicFileWriter.writeJson(msgFile, message);
    }

    public List<MailboxMessage> receive(String recipientId) {
        try (var files = Files.newDirectoryStream(mailboxDir, recipientId + "_*.json")) {
            List<MailboxMessage> messages = new ArrayList<>();
            for (Path f : files) {
                messages.add(objectMapper.readValue(f.toFile(), MailboxMessage.class));
                Files.delete(f); // 消费后删除
            }
            return messages;
        }
    }
}

public record MailboxMessage(
    String senderId,
    String recipientId,
    String type,       // "permission_request" / "notification" / "task_result"
    JsonNode payload,
    Instant timestamp
) {}
```

### 4.6.3 权限同步协议

```java
/**
 * 对标 Python swarm/permission_sync.py(1169 行）。
 * Leader-Worker 权限代理：Worker 的权限请求转发给 Leader 审批。
 */
public class PermissionSyncProtocol {

    // Worker 端：请求权限
    public CompletableFuture<PermissionResolution> requestPermission(
            String toolName, JsonNode arguments, Duration timeout
    ) {
        // 1．写入请求文件
        // 2．通过 mailbox 通知 Leader
        // 3. 轮询等待 resolution 文件
        // 4．超时返回 denied
    }

    // Leader 端：处理权限请求
    public void processPermissionRequests(Consumer<PermissionRequest> handler) {
        // 1．扫描 pending 请求文件
        // 2．回调处理器（UI 展示审批对话框）
        // 3．写入 resolution 文件
    }
}
```

### 4.6.4 Swarm 系统深度解析（补充）

**对标**：`src/openharness/swarm/`(11个文件)

```java
/**
 * 对标 Python swarm/registry.py — 后端注册表单例。
 * 对标 Python swarm/worktree.py — Git Worktree 隔离执行。
 * 对标 Python swarm/lockfile.py — flock 文件锁。
 * 对标 Python swarm/spawn_utils.py — 子进程生成 + Trace 传播。
 * 对标 Python swarm/team_lifecycle.py — 团队生命周期。
 */
public class BackendRegistry {
    private static final BackendRegistry INSTANCE = new BackendRegistry();
    private final Map<String, TeammateBackend> backends = new ConcurrentHashMap<>();
    public static BackendRegistry getInstance() { return INSTANCE; }
    public void register(String n, TeammateBackend b) { backends.put(n, b); }
}

public class WorktreeManager {
    public Path create(Path repo, String branch) throws IOException {
        Path wt = Files.createTempDirectory("worktree_");
        new ProcessBuilder("git", "worktree", "add", "--detach", wt.toString(), branch)
            .directory(repo.toFile()).start().waitFor();
        return wt;
    }
    public void remove(Path wt) throws IOException {
        new ProcessBuilder("git", "worktree", "remove", wt.toString()).start().waitFor();
    }
}

public class SpawnUtils {
    public static Map<String,String> buildEnv(TeammateSpec spec) {
        Map<String,String> env = new HashMap<>(System.getenv());
        env.put("OPENHARNESS_TEAMMATE_ID", spec.id());
        env.put("OPENHARNESS_LEADER_MAILBOX", spec.leaderMailboxPath().toString());
        TraceContextPropagator.inject(env);
        return env;
    }
}

public class TeamLifecycle {
    public void destroy(String teamId) { /* terminate + wait + kill */ }
    public boolean isAlive(String teamId) { /* PID 存活检查 */ }
}
```

---

## 4.7 渠道集成（openharness-channels）— ★ Phase 8（后续迭代）

> **注意**：消息渠道（飞书、Slack、Discord、Telegram、钉钉、Email、QQ、Matrix、WhatsApp、Mochat）在 Python 版 `src/openharness/channels/` 中已完整实现（14 个文件）。Java 版因涉及大量第三方 SDK 集成（部分平台无成熟 Java SDK，需直接封装 HTTP API），建议作为 Phase 8 后续迭代（约 4 周）。以下设计保留作为迭代参考。

**对标**：`src/openharness/channels/`(14个文件，10个平台）

### 4.7.1 基础架构

```java
/**
 * 对标 Python BaseChannel。
 */
public abstract class BaseChannel {

    protected final MessageBus bus;
    protected final Set<String> allowFrom; // ACL

    public abstract String getChannelType();
    public abstract void start();
    public abstract void stop();
    public abstract void send(OutboundMessage message);
}

/**
 * 对标 Python MessageBus。
 * 异步双向消息总线。
 */
public class MessageBus {
    private final BlockingQueue<InboundMessage> inbound = new LinkedBlockingQueue<>();
    private final BlockingQueue<OutboundMessage> outbound = new LinkedBlockingQueue<>();

    public void publishInbound(InboundMessage msg) throws InterruptedException {
        inbound.put(msg);
    }
    public InboundMessage consumeInbound() throws InterruptedException {
        return inbound.take();
    }
    public void publishOutbound(OutboundMessage msg) throws InterruptedException {
        outbound.put(msg);
    }
    public OutboundMessage consumeOutbound() throws InterruptedException {
        return outbound.take();
    }
}

/**
 * 对标 Python ChannelBridge。
 * 连接 MessageBus 与 QueryEngine 的消费-处理-发布循环。
 */
public class ChannelBridge {
    public void run() {
        while (!stopped) {
            InboundMessage msg = bus.consumeInbound();
            Stream<StreamEvent> events = PublisherAdapter.toStream(
                    queryEngine.runQuery(buildMessages(msg), options));
            events.forEach(event -> {
                if (event instanceof AssistantTextDelta delta) {
                    bus.publishOutbound(new OutboundMessage(msg.chatId(), delta.text()));
                }
            });
        }
    }
}
```

### 4.7.2 各渠道实现对照

|渠道|Python 行数|Java 实现要点|SDK 依赖|
|---|---|---|---|
|Telegram|526行|长轮询、Markdown→HTML 、语音转录|TelegramBots Java SDK|
|Discord|312行|Gateway WebSocket、速率限制重试|JDA (Java Discord API)|
|Slack|285行|Socket Mode、Markdown→mrkdwn|slack-sdk (Java)|
|飞书|1343行|WebSocket+lark-oapi、富文本／卡片|lark-oapi-java-sdk|
|钉钉|446行|Stream Mode、OAuth Token|dingtalk-sdk-java|
|Email|411行|IMAP轮询＋SMTP、线程追踪|Jakarta Mail|
|QQ|142行|botpy WebSocket|qq-bot-java-sdk|
|Matrix|700行|长轮询同步、E2EE、media|matrix-java-sdk|
|WhatsApp|160行|Node.js Baileys桥接|ProcessBuilder 调用 Node|
|Mochat|896行|Socket.IO+HTTP fallback|socket.io-java-client|