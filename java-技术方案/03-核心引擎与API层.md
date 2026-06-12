# 四、核心子系统详细设计 — 核心引擎与API层

> **源码锚点**：
> - Agent Loop: `src/openharness/engine/query.py` (1058行), `query_engine.py`, `messages.py`, `stream_events.py`, `cost_tracker.py`
> - LLM API: `src/openharness/api/client.py`, `openai_client.py`, `copilot_client.py`, `codex_client.py`, `copilot_auth.py`, `registry.py`, `errors.py`, `usage.py`

---

## 4.1 Agent Loop 引擎 (openharness-engine）

**对标**：`src/openharness/engine/query.py`(1058行）

### 4.1.1 核心类设计

```java
/**
 * 位于 openharness-common。
 * 打破 openharness-engine ↔ openharness-tools 循环依赖的关键接口。
 * AgentTool 仅依赖此接口，无需反向依赖 QueryEngine 实现类。
 */
public interface AgentRuntime {
    Flow.Publisher<StreamEvent> runQuery(
            List<ConversationMessage> messages,
            QueryOptions options
    );
}

/**
 * 核心 Agent 循环引擎。
 * 对标 Python QueryEngine，使用 Virtual Threads + StructuredTaskScope 替代 asyncio。
 */
public class QueryEngine implements AgentRuntime {

    private final ApiClient apiClient;
    private final ToolRegistry toolRegistry;
    private final PermissionChecker permissionChecker;
    private final HookExecutor hookExecutor;
    private final UsageTracker usageTracker;
    private final CompactionService compactionService;
    private final Tracer tracer; // * OpenTelemetry
    private final MeterRegistry meters; // * Micrometer

    // 核心方法：执行一次完整的 Agent 循环
    // 使用 Flow.Publisher 替代 Stream：支持背压、错误通道分离、
    // 跨轮次状态保持（如压缩触发、工具结果追加），更适合长生命周期的 Agent Loop。
    @Override
    public Flow.Publisher<StreamEvent> runQuery(
            List<ConversationMessage> messages,
            QueryOptions options
    ) {...}

    // 单次 LLM 调用＋工具执行循环
    private Flow.Publisher<StreamEvent> executeLoop(
            List<ConversationMessage> messages,
            int maxTurns
    ) {
        // 1．调用 LLM API（流式）
        // 2．收集 assistant 响应（文本＋tooluse 块）
        // 3．如果有 tool_use：
        // a.并行执行所有工具（StructuredTaskScope，自动取消子任务）
        // b.收集 tool_result
        // c．追加到消息列表，回到步骤1
        // 4．如果无 tool_use：返回最终响应
        // 5．超过 maxTurns 安全限制时抛出 MaxTurnsExceededException
    }
}

/**
 * 将 Flow.Publisher 适配为 Stream/List，便于同步消费或测试收集。
 * 位于 openharness-common，基于 BlockingQueue + Spliterator 实现。
 */
public class PublisherAdapter {
    public static <T> Stream<T> toStream(Flow.Publisher<T> publisher) { ... }
    public static <T> List<T> toList(Flow.Publisher<T> publisher) { return toStream(publisher).toList(); }
}
```

### 4.1.2 消息模型

```java
// 对标 Python ConversationMessage + ContentBlock
public sealed interface ContentBlock permits
        TextBlock, ImageBlock, ToolUseBlock, ToolResultBlock {
}

public record TextBlock(String text) implements ContentBlock {}

public record ImageBlock(
        String mediaType,
        String base64Data
) implements ContentBlock {}

public record ToolUseBlock(
        String id,
        String name,
        JsonNode input
) implements ContentBlock {}

public record ToolResultBlock(
        String toolUseId,
        String content,
        boolean isError
) implements ContentBlock {}

public record ConversationMessage(
        Role role, // USER, ASSISTANT, SYSTEM
        List<ContentBlock> content
) {}
```

### 4.1.3 流式事件

```java
// 对标 Python ApiStreamEvent
public sealed interface ApiStreamEvent permits
        ContentDelta, ToolUseStart, ToolUseInputDelta,
        ToolUseComplete, TurnComplete, ErrorEvent {
}

// 对标 Python StreamEvent（给UI 消费）
public sealed interface StreamEvent permits
        AssistantTextDelta, AssistantTurnComplete,
        ToolStarted, ToolCompleted,
        StatusEvent, CompactProgressEvent, ErrorStreamEvent {
}
```

### 4.1.4 可观测性埋点

```java
// ★ Agent Loop 可观测性
// 每次循环迭代生成一个 Span
Span loopSpan = tracer.spanBuilder("agent.loop.iteration")
        .setAttribute("turn.number", turnNumber)
        .setAttribute("tool.count", toolCalls.size())
        .startSpan();

// 指标
Counter loopIterations = Counter.builder("agent.loop.iterations")
        .register(meters);
Timer loopDuration = Timer.builder("agent.loop.duration")
        .register(meters);
AtomicInteger activeLoops = new AtomicInteger(0);
Gauge.builder("agent.loop.active", activeLoops, AtomicInteger::get)
        .register(meters);
DistributionSummary toolsPerTurn = DistributionSummary.builder("agent.loop.tools_per_turn")
        .register(meters);
```

---

## 4.2 LLM API 客户端层 (openharness-api）

**对标**：`src/openharness/api/`(10个文件）

### 4.2.1 统一协议接口

```java
/**
 * 对标 Python SupportsStreamingMessages 协议。
 * 所有 LLM 提供商必须实现此接口。
 */
public interface StreamingApiClient {

    /**
     * 发送消息并返回流式事件。
     */
    Stream<ApiStreamEvent> streamMessages(
            String model,
            String systemPrompt,
            List<ConversationMessage> messages,
            List<ToolDefinition> tools,
            StreamOptions options
    );

    /**
     * 获取提供商信息。
     */
    ProviderInfo getProviderInfo();
}
```

### 4.2.2 五大客户端实现

|客户端|对标 Python 文件|Java 实现|关键特性|
|---|---|---|---|
|AnthropicClient|`api/client.py`|AnthropicMessagesClient|SSE 流式解析、Token 计数、图像预处理|
|OpenAIClient|`api/openai_client.py`|OpenAICompatibleClient|ChatCompletion SSE、＜think＞块剥离、Bearer认证|
|CopilotClient|`api/copilot_client.py`|CopilotClient|OAuth Device Flow、Token 刷新|
|CodexClient|`api/codex_client.py`|CodexClient|Responses API SSE|
|SubscriptionBridgeClient|`api/client.py` (subscription mode)|SubscriptionBridgeClient|本地CLI进程桥接|

### 4.2.3 提供商注册表

```java
/**
 * 对标 Python ProviderSpec 注册表。
 * 支持20＋提供商的自动发现与配置。
 */
public class ProviderRegistry {

    private static final Map<String, ProviderSpec> PROVIDERS = Map.ofEntries(
            entry("anthropic", new ProviderSpec("Anthropic",
                    ApiFormat.ANTHROPIC, "https://api.anthropic.com")),
            entry("openai", new ProviderSpec("OpenAI",
                    ApiFormat.OPENAI, "https://api.openai.com/v1")),
            entry("dashscope", new ProviderSpec("DashScope",
                    ApiFormat.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1")),
            entry("deepseek", new ProviderSpec("DeepSeek",
                    ApiFormat.OPENAI, "https://api.deepseek.com/v1")),
            entry("moonshot", new ProviderSpec("Moonshot",
                    ApiFormat.ANTHROPIC, "https://api.moonshot.cn")),
            entry("gemini", new ProviderSpec("Gemini",
                    ApiFormat.OPENAI,
                    "https://generativelanguage.googleapis.com/v1beta/openai")),
            entry("minimax", new ProviderSpec("MiniMax",
                    ApiFormat.ANTHROPIC, "https://api.minimax.chat")),
            entry("groq", new ProviderSpec("Groq",
                    ApiFormat.OPENAI, "https://api.groq.com/openai/v1")),
            entry("ollama", new ProviderSpec("Ollama",
                    ApiFormat.OPENAI, "http://localhost:11434/v1")),
            // ...20＋更多提供商
    );

    public ProviderSpec resolve(String profileName, Settings settings) {...}
}
```

### 4.2.4 可观测性埋点

```java
// * LLM API 可观测性

// Tracing：每次 API 调用生成独立 Span
Span apiSpan = tracer.spanBuilder("llm.api.call")
        .setAttribute("llm.provider", provider.name())
        .setAttribute("llm.model", model)
        .setAttribute("llm.input_tokens", inputTokens)
        .setAttribute("llm.output_tokens", outputTokens)
        .setAttribute("llm.stop_reason", stopReason)
        .startSpan();

// Metrics
Counter apiCalls = Counter.builder("llm.api.calls")
        .tags("provider", provider, "model", model, "status", "success|error")
        .register(meters);
Timer apiLatency = Timer.builder("llm.api.latency")
        .tags("provider", provider, "model", model)
        .register(meters);
Counter tokensUsed = Counter.builder("llm.tokens.used")
        .tags("provider", provider, "model", model, "direction", "input|output")
        .register(meters);
Counter apiCost = Counter.builder("llm.api.cost.usd")
        .tags("provider", provider, "model", model)
        .register(meters);
Counter apiRetries = Counter.builder("llm.api.retries")
        .tags("provider", provider, "error_type", errorType)
        .register(meters);
Timer ttft = Timer.builder("llm.api.ttft")
        .tag("provider", provider)
        .register(meters); // Time To First Token
DistributionSummary streamChunkSize = DistributionSummary.builder("llm.api.stream_chunk_bytes")
        .register(meters);
```

### 4.2.5 错误处理与重试

```java
/**
 * 对标 Python API 错误处理。
 * HTTP 错误映射为类型化异常，支持指数退避重试。
 */
public sealed interface ApiError permits AuthError, RateLimitError, RequestError, ServerError {
    int statusCode();
    String message();
}

public record AuthError(int statusCode, String message) implements ApiError {}       // 401
public record RateLimitError(int statusCode, String message, Instant retryAfter) implements ApiError {} // 429
public record RequestError(int statusCode, String message) implements ApiError {}     // 400/422
public record ServerError(int statusCode, String message) implements ApiError {}      // 500/502/503

public class ApiRetryPolicy {
    private static final int MAX_RETRIES = 3;
    private static final Duration BASE_DELAY = Duration.ofSeconds(1);

    public <T> T executeWithRetry(Supplier<T> call) throws ApiError {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return call.get();
            } catch (RateLimitError e) {
                Duration delay = e.retryAfter() != null
                    ? Duration.between(Instant.now(), e.retryAfter())
                    : BASE_DELAY.multipliedBy((long) Math.pow(2, attempt));
                Thread.sleep(delay.toMillis());
                apiRetries.increment();
            } catch (ServerError e) {
                if (attempt == MAX_RETRIES - 1) throw e;
                Thread.sleep(BASE_DELAY.multipliedBy((long) Math.pow(2, attempt)).toMillis());
                apiRetries.increment();
            }
        }
        throw new RequestError(-1, "Max retries exceeded");
    }
}
```

### 4.2.6 其他 API 客户端

```java
/**
 * GitHub Copilot OAuth 客户端。
 * 对标 Python CopilotClient。
 */
public class CopilotClient implements StreamingApiClient {
    private final HttpClient httpClient;
    private final DeviceCodeFlow deviceFlow;

    // OAuth Device Flow 认证
    // Token 过期自动刷新
    // 使用 Copilot Chat Completions API
}

/**
 * OpenAI Codex Client。
 * 对标 Python CodexClient。
 * 使用 OpenAI Responses API（SSE），支持 reasoning 块解析。
 */
public class CodexClient implements StreamingApiClient {
    // Responses API SSE 流式
    // reasoning / text / tool_call 三种事件类型
}

/**
 * 订阅桥接客户端。
 * 对标 Python SubscriptionBridgeClient。
 * 本地 CLI 进程桥接模式，通过 stdout/stdin JSON-Lines 通信。
 */
public class SubscriptionBridgeClient implements StreamingApiClient {
    private Process bridgeProcess; // java -jar openharness-app.jar --output backend
    private final BufferedReader stdout;
    private final BufferedWriter stdin;

    // 读取子进程 stdout 的 JSON-Lines BackendEvent
    // 写入子进程 stdin 的 JSON FrontendRequest
}
```
