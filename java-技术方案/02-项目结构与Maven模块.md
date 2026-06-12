# 三、项目结构设计

> **源码锚点**：本章节对标 Python 版模块组织结构。Python 源码入口为 `src/openharness/__init__.py` 和 `src/openharness/cli.py`，ohmo 入口为 `ohmo/__main__.py` 和 `ohmo/cli.py`。

---

## 3.1 Maven 多模块结构（11 个模块）

将原 33 个模块按功能域合并为 **11 个模块**，在保持包级内聚性的同时大幅降低构建编排开销。合并原则：

- **单文件/少文件的辅助模块**（state、keybindings、themes、vim、voice、utils 等）统一并入 `openharness-extensions`
- **紧密协作的扩展系统**（hooks、skills、plugins、commands、mcp、memory、coordinator、tasks、swarm、services、bridge、personalization、autopilot）统一并入 `openharness-extensions`
- **认证与 API** 天然耦合，合并为 `openharness-api`
- **引擎与权限** 在 Agent Loop 中高频交互，合并为 `openharness-engine`
- **UI 层仅保留协议编码**，移除终端渲染相关设计（前端为独立 TypeScript 应用）

```
java-openharness/                          # 项目根目录
├── pom.xml                                # 父 POM（11 模块声明、统一版本管理）
│
├── openharness-common/                    # [1] 共享基础类型
│   └── src/main/java/com/openharness/common/
│       ├── ContentBlock.java              # sealed interface: Text/Image/ToolUse/ToolResult
│       ├── ConversationMessage.java       # record: role + content blocks
│       ├── StreamEvent.java               # sealed interface: 8 种 UI 事件
│       ├── ApiStreamEvent.java            # sealed interface: LLM 流式事件
│       ├── ToolResult.java                # record: content + isError + mediaFiles
│       ├── UsageSnapshot.java             # record: inputTokens + outputTokens
│       ├── Role.java                      # enum: USER, ASSISTANT, SYSTEM
│       ├── QueryOptions.java              # 查询配置 record（放置于 common 以打破 engine↔tools 循环依赖）
│       ├── AgentRuntime.java              # Agent 执行接口（AgentTool 只依赖此接口）
│       ├── PublisherAdapter.java          # Flow.Publisher → Stream/List 适配器
│       └── OpenHarnessObjectMapper.java   # 全局 ObjectMapper（对齐 Pydantic 序列化行为）
│
├── openharness-config/                    # [2] 配置系统
│   └── src/main/java/com/openharness/config/
│       ├── Settings.java                  # 中心配置 POJO（30+ 字段、12 内置提供商）
│       ├── PermissionSettings.java        # 权限配置（模式、路径规则）
│       ├── SandboxSettings.java           # 沙箱配置
│       ├── WebSettings.java               # Web/搜索配置
│       ├── ProviderProfile.java           # 提供商配置档案
│       ├── Paths.java                     # 路径工具（~/.openharness 结构）
│       └── AtomicFileWriter.java          # 原子化 JSON 持久化
│
├── openharness-api/                       # [3] LLM API 客户端层 + 认证系统
│   ├── src/main/java/com/openharness/api/
│   │   ├── StreamingApiClient.java        # 统一流式接口
│   │   ├── AnthropicMessagesClient.java   # Anthropic SSE 流式
│   │   ├── OpenAICompatibleClient.java    # OpenAI ChatCompletion 兼容
│   │   ├── CopilotClient.java             # GitHub Copilot OAuth
│   │   ├── CodexClient.java               # OpenAI Codex Responses API
│   │   ├── SubscriptionBridgeClient.java  # 本地 CLI 进程桥接
│   │   ├── ProviderRegistry.java          # 20+ 提供商注册表
│   │   └── errors/                        # AuthError, RateLimitError, RequestError
│   └── src/main/java/com/openharness/auth/
│       ├── AuthManager.java               # 认证中心（12 提供商、12 认证源）
│       ├── AuthFlow.java                  # 认证流程抽象 + 3 种实现
│       ├── ApiKeyFlow.java                # API Key 输入流程
│       ├── DeviceCodeFlow.java            # GitHub OAuth Device Flow
│       ├── BrowserFlow.java               # 浏览器 OAuth 流程
│       ├── ExternalAuthBinding.java       # 外部 CLI 凭据绑定
│       ├── CredentialStorage.java         # 双后端存储（文件 + 系统 Keyring）
│       └── OAuthTokenRefresher.java       # OAuth Token 自动刷新
│
├── openharness-engine/                    # [4] Agent Loop 引擎 + 权限系统
│   ├── src/main/java/com/openharness/engine/
│   │   ├── QueryEngine.java               # 核心 Agent 循环
│   │   ├── CostTracker.java               # Token 成本追踪
│   │   ├── AutoCompactState.java          # 自动压缩状态机
│   │   ├── ToolCarryover.java             # 工具执行记录（跨压缩传递）
│   │   └── UsageTracker.java              # 用量统计
│   └── src/main/java/com/openharness/permissions/
│       ├── PermissionChecker.java         # 三模式权限检查
│       ├── PermissionMode.java            # DEFAULT / PLAN / FULL_AUTO
│       ├── PathRule.java                  # 路径级权限规则
│       └── PermissionResult.java          # ALLOWED / DENIED / NEEDS_APPROVAL
│
├── openharness-tools/                     # [5] 工具系统（44 个内置工具）
│   └── src/main/java/com/openharness/tools/
│       ├── BaseTool.java                  # 工具基类
│       ├── ToolRegistry.java              # 工具注册表（含 MCP 动态工具）
│       ├── ToolContext.java               # 工具执行上下文
│       ├── bash/BashTool.java             # Shell 命令执行
│       ├── file/FileReadTool.java         # 文件读取
│       ├── file/FileWriteTool.java        # 文件写入
│       ├── file/FileEditTool.java         # 文件编辑（精确字符串替换）
│       ├── file/NotebookEditTool.java     # Jupyter Notebook 编辑
│       ├── search/GrepTool.java           # ripgrep 内容搜索
│       ├── search/GlobTool.java           # Glob 文件匹配
│       ├── web/WebFetchTool.java          # 网页抓取
│       ├── web/WebSearchTool.java         # 网络搜索
│       ├── agent/AgentTool.java           # 子 Agent 调度
│       ├── agent/SendMessageTool.java     # Agent 间消息传递
│       ├── tasks/TaskCreateTool.java      # 任务创建
│       ├── tasks/TaskListTool.java        # 任务列表
│       ├── tasks/TaskGetTool.java         # 任务详情
│       ├── tasks/TaskOutputTool.java      # 任务输出读取
│       ├── tasks/TaskStopTool.java        # 任务停止
│       ├── tasks/TaskUpdateTool.java      # 任务更新
│       ├── mcp/DynamicMcpTool.java        # MCP 动态工具代理
│       └── ... (其余 24 个工具)            # 完整 44 工具
│
├── openharness-extensions/                # [6] 扩展系统（原 18 个原子模块合并）
│   ├── src/main/java/com/openharness/hooks/
│   │   ├── HookDefinition.java            # 4 种 Hook 类型定义
│   │   ├── HookEvent.java                 # 9 种 Hook 事件枚举
│   │   ├── HookExecutor.java              # Hook 执行引擎
│   │   ├── HookRegistry.java              # Hook 注册表（按事件分组）
│   │   ├── HookLoader.java                # 从 settings + plugins 加载
│   │   └── HookReloader.java              # 热重载（文件修改时间检测）
│   ├── src/main/java/com/openharness/skills/
│   │   ├── SkillDefinition.java           # 技能定义 record
│   │   ├── SkillRegistry.java             # 技能注册表（多键索引）
│   │   ├── SkillLoader.java               # 多目录发现 + 加载
│   │   ├── SkillFrontmatter.java          # YAML Frontmatter 解析
│   │   └── bundled/                       # 内置 8 个技能
│   ├── src/main/java/com/openharness/plugins/
│   │   ├── PluginManifest.java            # plugin.json 模式
│   │   ├── LoadedPlugin.java              # 运行时插件实例
│   │   ├── PluginLoader.java              # 发现 + 加载 + 组件提取
│   │   ├── PluginInstaller.java           # 安装/卸载
│   │   └── PluginCommandDef.java          # 插件贡献的命令
│   ├── src/main/java/com/openharness/commands/
│   │   ├── SlashCommand.java              # 命令定义 record
│   │   ├── CommandRegistry.java           # 注册 + 别名解析 + 分发
│   │   ├── CommandContext.java            # 命令执行上下文
│   │   ├── CommandResult.java             # 命令返回结果
│   │   └── builtin/                       # 内置 30+ 斜杠命令
│   ├── src/main/java/com/openharness/mcp/
│   │   ├── McpClientManager.java          # MCP 连接管理（stdio + HTTP + WS）
│   │   ├── McpStdioTransport.java         # stdio 传输层
│   │   ├── McpHttpTransport.java          # HTTP SSE 传输层
│   │   ├── McpServerConfig.java           # 服务端配置类型
│   │   ├── McpToolInfo.java               # 工具元数据
│   │   ├── McpResourceInfo.java           # 资源元数据
│   │   └── McpConfigLoader.java           # 配置合并（settings + plugins）
│   ├── src/main/java/com/openharness/memory/
│   │   ├── MemoryEntry.java               # 记忆条目（header + body）
│   │   ├── MemoryManager.java             # CRUD 管理
│   │   ├── MemorySearch.java              # 相关性搜索（元数据 2x + 正文）
│   │   ├── MemoryRelevance.java           # 启发式评分
│   │   ├── MemoryScanner.java             # 目录扫描 + 签名去重
│   │   └── MemoryPaths.java               # 路径管理
│   ├── src/main/java/com/openharness/coordinator/
│   │   ├── AgentDefinition.java           # Agent 定义模型（YAML Frontmatter）
│   │   ├── AgentDefinitionsLoader.java    # 内置 + 用户 + 插件 Agent 加载
│   │   ├── CoordinatorMode.java           # 协调器模式检测 + 系统提示词
│   │   ├── TeamRegistry.java              # 团队注册表
│   │   └── TaskNotification.java          # XML 信封序列化
│   ├── src/main/java/com/openharness/tasks/
│   │   ├── BackgroundTaskManager.java     # 任务管理器（Shell + Agent 子进程）
│   │   ├── TaskRecord.java                # 任务记录
│   │   ├── TaskType.java                  # local_bash / local_agent / remote_agent
│   │   ├── TaskStatus.java                # pending / running / completed / failed / killed
│   │   ├── ShellTaskSpawner.java          # Shell 任务启动
│   │   └── AgentTaskSpawner.java          # Agent 子进程任务启动
│   ├── src/main/java/com/openharness/swarm/
│   │   ├── TeammateBackend.java           # 后端抽象接口
│   │   ├── SubprocessBackend.java         # 子进程后端
│   │   ├── InProcessBackend.java          # Virtual Threads 进程内后端
│   │   ├── TmuxBackend.java               # Tmux 会话后端
│   │   ├── PermissionSyncProtocol.java    # Leader-Worker 权限代理
│   │   └── FileMailbox.java               # 文件邮箱通信
│   ├── src/main/java/com/openharness/services/
│   │   ├── compact/CompactionService.java # 三级压缩
│   │   ├── compact/MicroCompact.java      # 微压缩
│   │   ├── compact/SessionMemoryCompact.java # 会话记忆检查点
│   │   ├── compact/FullCompact.java       # 完整 LLM 压缩
│   │   ├── autodream/AutoDreamService.java # 记忆整合（4 阶段）
│   │   ├── autodream/DreamLock.java       # PID 锁
│   │   ├── autodream/DreamBackup.java     # 备份 + Diff + 回滚
│   │   ├── SessionStorage.java            # 会话快照持久化
│   │   ├── SessionBackend.java            # 会话后端协议
│   │   ├── CronRegistry.java              # Cron 注册表
│   │   ├── CronScheduler.java             # Cron 调度（ScheduledExecutorService）
│   │   ├── TokenEstimator.java            # Token 估算（字符 / 4 启发式）
│   │   └── ToolOutputLimiter.java         # 工具输出截断配置
│   ├── src/main/java/com/openharness/bridge/
│   │   ├── BridgeSessionManager.java      # 桥接会话管理（单例）
│   │   ├── SessionHandle.java             # 会话句柄（process + cwd）
│   │   ├── SessionSpawner.java            # 子进程创建
│   │   ├── BridgeTypes.java               # WorkData / WorkSecret / BridgeConfig
│   │   └── WorkSecretCodec.java           # Base64URL 编解码
│   ├── src/main/java/com/openharness/personalization/
│   │   ├── FactExtractor.java             # 正则提取环境事实（SSH/IP/路径/版本）
│   │   ├── RulesGenerator.java            # 事实 → Markdown 规则文档
│   │   ├── FactStorage.java               # 事实持久化 + 去重合并
│   │   └── SessionHook.java               # 会话结束自动更新规则
│   ├── src/main/java/com/openharness/autopilot/
│   │   ├── RepoAutopilotStore.java        # 项目级自动驾驶状态机
│   │   ├── RepoTaskCard.java              # 工作项（13 状态工作流）
│   │   ├── RepoJournalEntry.java          # 追加日志
│   │   ├── AutopilotPolicies.java         # 策略加载（YAML）
│   │   ├── AutopilotDashboard.java        # 仪表盘导出（snapshot.json + HTML）
│   │   └── sources/                       # 数据源扫描
│   ├── src/main/java/com/openharness/state/
│   │   ├── AppState.java                  # 可变 UI/会话状态 record
│   │   ├── AppStateStore.java             # 可观察状态存储（pub/sub）
│   │   └── StateSubscriber.java           # 状态变更监听器
│   ├── src/main/java/com/openharness/keybindings/
│   │   ├── DefaultKeybindings.java        # 默认快捷键
│   │   ├── KeybindingsLoader.java         # 用户自定义加载
│   │   └── KeybindingsResolver.java       # 合并 + 冲突解决
│   ├── src/main/java/com/openharness/themes/
│   │   ├── ThemeConfig.java               # 主题配置模型（颜色/边框/图标/布局）
│   │   ├── BuiltinThemes.java             # 5 个内置主题
│   │   ├── ThemeLoader.java               # 自定义主题加载
│   │   └── ThemeResolver.java             # 主题选择 + 继承
│   ├── src/main/java/com/openharness/vim/
│   │   └── VimMode.java                   # 终端 Vim 快捷键（Normal/Insert 模式）
│   ├── src/main/java/com/openharness/voice/
│   │   ├── VoiceDiagnostics.java          # 录音工具 + STT 提供商检测
│   │   ├── VoiceMode.java                 # 语音模式切换
│   │   ├── StreamSTT.java                 # 流式语音转文本
│   │   └── KeyTerms.java                  # 关键词提取
│   └── src/main/java/com/openharness/utils/
│       ├── Helpers.java                   # 通用辅助（truncate/formatBytes/parseDuration）
│       ├── FileSystemUtils.java           # 原子写入 + Git root 发现
│       ├── FileLock.java                  # PID 文件锁
│       ├── ShellUtils.java                # Shell 命令执行辅助
│       ├── NetworkGuard.java              # SSRF 防御（内网地址拦截）
│       └── PlatformCapabilities.java      # 平台检测（macOS/Linux/WSL/Windows）
│
├── openharness-ui/                        # [7] UI 协议层（前端零改动）
│   └── src/main/java/com/openharness/ui/
│       ├── protocol/BackendEvent.java     # 17 种后端事件 sealed interface
│       ├── protocol/FrontendRequest.java  # 前端请求 sealed interface
│       ├── ReactBackendHost.java          # React/Ink 后端协议主机
│       ├── JsonLinesProtocol.java         # JSON-Lines 协议编解码
│       ├── PermissionPrompt.java          # 权限审批对话框（协议消息）
│       └── runtime/                       # TUI / Backend / Print 三模式运行时配置
│
├── openharness-cli/                       # [8] CLI 入口
│   └── src/main/java/com/openharness/cli/
│       ├── MainCommand.java               # Picocli 主命令
│       ├── RunCommand.java                # java -jar oh.jar -p "query"
│       ├── ConfigCommand.java             # 配置管理子命令
│       ├── DoctorCommand.java             # 环境诊断
│       ├── InitCommand.java               # 初始化
│       └── GatewayCommand.java            # 网关模式
│
├── openharness-ohmo/                      # [9] 个人代理层（ohmo）
│   └── src/main/java/com/openharness/ohmo/
│       ├── gateway/
│       │   ├── OhmoGatewayService.java    # Gateway 服务生命周期
│       │   ├── OhmoGatewayBridge.java     # 渠道消息 ↔ Agent 桥接
│       │   ├── OhmoSessionRuntimePool.java # 会话运行时池
│       │   ├── OhmoSessionRouter.java     # 会话路由策略
│       │   ├── GatewayConfig.java         # Gateway 配置模型
│       │   ├── GatewayState.java          # Gateway 运行时状态
│       │   └── OhmoGroupRegistry.java     # 群组注册表
│       ├── workspace/
│       │   ├── WorkspaceManager.java      # ~/.ohmo 工作空间管理
│       │   ├── SoulMdLoader.java          # soul.md 人格模板
│       │   ├── UserMdLoader.java          # user.md 用户画像
│       │   └── IdentityMdLoader.java      # identity.md 身份模板
│       ├── prompts/
│       │   └── OhmoSystemPromptBuilder.java # ohmo 专用系统提示词组装
│       ├── memory/
│       │   └── OhmoMemoryBackend.java     # ohmo 级个人记忆管理
│       ├── session/
│       │   └── OhmoSessionBackend.java    # 按 session_key 的会话持久化
│       └── cli/
│           └── OhmoCommand.java           # ohmo CLI 子命令（Picocli）
│
├── openharness-observability/             # [10] 可观测性（全新增）
│   └── src/main/java/com/openharness/observability/
│       ├── metrics/AgentLoopMetrics.java  # Agent Loop 指标
│       ├── metrics/LlmApiMetrics.java     # LLM API 指标
│       ├── metrics/ToolMetrics.java       # 工具执行指标
│       ├── metrics/SystemMetrics.java     # JVM/系统指标
│       ├── tracing/OpenTelemetrySetup.java # OTel 初始化
│       ├── tracing/TraceContextPropagator.java # 跨进程 Trace 传播
│       ├── tracing/SemanticAttributes.java # 语义属性常量
│       ├── logging/LoggingConfig.java     # Logback 编程化配置
│       ├── logging/StructuredLogHelper.java # 结构化日志辅助
│       ├── health/HealthServer.java       # 健康检查端点
│       └── dashboard/GrafanaDashboardExporter.java # 面板 JSON 导出
│
└── openharness-app/                       # [11] Fat JAR 打包
    └── src/main/java/com/openharness/
        └── Main.java                      # 主入口（纯 Java，非 Spring Boot）

 docker/                                    # Docker 部署
 ├── Dockerfile                             # 多阶段构建（mvn package + JRE）
 ├── docker-compose.yml                     # 本地开发
 └── docker-compose.observability.yml       # 可观测性全家桶

 helm/                                      # Kubernetes 部署
 └── openharness/
     ├── Chart.yaml
     ├── values.yaml
     └── templates/

 grafana/                                   # Grafana 面板
 └── dashboards/
     ├── agent-loop.json
     ├── tool-execution.json
     ├── llm-api.json
     ├── channel-gateway.json
     └── system-overview.json
```

---

## 3.2 父 POM 配置

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.openharness</groupId>
    <artifactId>openharness-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>OpenHarness Java</name>
    <description>Open-source Java port of OpenHarness AI coding assistant</description>

    <!-- 子模块声明（11 个模块） -->
    <modules>
        <module>openharness-common</module>
        <module>openharness-config</module>
        <module>openharness-api</module>
        <module>openharness-engine</module>
        <module>openharness-tools</module>
        <module>openharness-extensions</module>
        <module>openharness-ui</module>
        <module>openharness-cli</module>
        <module>openharness-ohmo</module>
        <module>openharness-observability</module>
        <module>openharness-app</module>
    </modules>

    <!-- 统一版本号 -->
    <properties>
        <java.version>21</java.version>
        <maven.compiler.source>${java.version}</maven.compiler.source>
        <maven.compiler.target>${java.version}</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

        <!-- 核心依赖版本 -->
        <jackson.version>2.17.0</jackson.version>
        <picocli.version>4.7.6</picocli.version>
        <okhttp.version>4.12.0</okhttp.version>
        <snakeyaml.version>2.2</snakeyaml.version>
        <cron-utils.version>9.2.1</cron-utils.version>
        <caffeine.version>3.1.8</caffeine.version>

        <!-- 可观测性版本 -->
        <micrometer.version>1.13.0</micrometer.version>
        <opentelemetry.version>1.38.0</opentelemetry.version>
        <logback.version>1.5.6</logback.version>
        <logstash.logback.version>7.4</logstash.logback.version>
        <slf4j.version>2.0.13</slf4j.version>

        <!-- 测试版本 -->
        <junit.version>5.10.2</junit.version>
        <mockito.version>5.12.0</mockito.version>
        <testcontainers.version>1.19.8</testcontainers.version>
        <wiremock.version>3.5.4</wiremock.version>
    </properties>

    <!-- 统一依赖管理 -->
    <dependencyManagement>
        <dependencies>
            <!-- Jackson BOM -->
            <dependency>
                <groupId>com.fasterxml.jackson</groupId>
                <artifactId>jackson-bom</artifactId>
                <version>${jackson.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>

            <!-- OpenTelemetry BOM -->
            <dependency>
                <groupId>io.opentelemetry</groupId>
                <artifactId>opentelemetry-bom</artifactId>
                <version>${opentelemetry.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>

            <!-- Micrometer BOM -->
            <dependency>
                <groupId>io.micrometer</groupId>
                <artifactId>micrometer-bom</artifactId>
                <version>${micrometer.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>

            <!-- JUnit BOM -->
            <dependency>
                <groupId>org.junit</groupId>
                <artifactId>junit-bom</artifactId>
                <version>${junit.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>

            <!-- 项目内部模块互相引用 -->
            <dependency>
                <groupId>com.openharness</groupId>
                <artifactId>openharness-common</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.openharness</groupId>
                <artifactId>openharness-config</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.openharness</groupId>
                <artifactId>openharness-api</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.openharness</groupId>
                <artifactId>openharness-engine</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.openharness</groupId>
                <artifactId>openharness-tools</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.openharness</groupId>
                <artifactId>openharness-extensions</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.openharness</groupId>
                <artifactId>openharness-ui</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.openharness</groupId>
                <artifactId>openharness-cli</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.openharness</groupId>
                <artifactId>openharness-ohmo</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.openharness</groupId>
                <artifactId>openharness-observability</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <!-- 全局公共依赖 -->
    <dependencies>
        <!-- SLF4J + Logback（所有模块共享） -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <version>${slf4j.version}</version>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <version>${logback.version}</version>
            <scope>runtime</scope>
        </dependency>

        <!-- JUnit 5（所有模块的测试依赖） -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>${mockito.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <!-- 统一插件管理 -->
    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.13.0</version>
                    <configuration>
                        <release>${java.version}</release>
                        <!-- Picocli 注解处理器 -->
                        <annotationProcessorPaths>
                            <path>
                                <groupId>info.picocli</groupId>
                                <artifactId>picocli-codegen</artifactId>
                                <version>${picocli.version}</version>
                            </path>
                        </annotationProcessorPaths>
                    </configuration>
                </plugin>

                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.2.5</version>
                </plugin>

                <!-- Fat JAR 打包（用于 openharness-app 模块） -->
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-shade-plugin</artifactId>
                    <version>3.5.3</version>
                </plugin>

                <!-- Docker 镜像构建 -->
                <plugin>
                    <groupId>com.google.cloud.tools</groupId>
                    <artifactId>jib-maven-plugin</artifactId>
                    <version>3.4.2</version>
                </plugin>

                <!-- JaCoCo 测试覆盖率 -->
                <plugin>
                    <groupId>org.jacoco</groupId>
                    <artifactId>jacoco-maven-plugin</artifactId>
                    <version>0.8.12</version>
                </plugin>
            </plugins>
        </pluginManagement>

        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>prepare-agent</goal>
                        </goals>
                    </execution>
                    <execution>
                        <id>report</id>
                        <phase>verify</phase>
                        <goals>
                            <goal>report</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### 3.3 子模块 POM 示例（openharness-engine）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.openharness</groupId>
        <artifactId>openharness-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>openharness-engine</artifactId>
    <name>OpenHarness Engine</name>
    <description>Core Agent Loop engine with permission checking</description>

    <dependencies>
        <!-- 项目内部依赖 -->
        <dependency>
            <groupId>com.openharness</groupId>
            <artifactId>openharness-common</artifactId>
        </dependency>
        <dependency>
            <groupId>com.openharness</groupId>
            <artifactId>openharness-config</artifactId>
        </dependency>
        <dependency>
            <groupId>com.openharness</groupId>
            <artifactId>openharness-api</artifactId>
        </dependency>

        <!-- Jackson -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>

        <!-- 可观测性 -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-core</artifactId>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-api</artifactId>
        </dependency>

        <!-- 测试 -->
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <version>${wiremock.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 3.4 打包模块 POM (openharness-app, Fat JAR）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.openharness</groupId>
        <artifactId>openharness-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>openharness-app</artifactId>
    <name>OpenHarness Application</name>
    <description>Fat JAR packaging with all modules</description>

    <dependencies>
        <!-- 聚合所有子模块 -->
        <dependency>
            <groupId>com.openharness</groupId>
            <artifactId>openharness-cli</artifactId>
        </dependency>
        <dependency>
            <groupId>com.openharness</groupId>
            <artifactId>openharness-ohmo</artifactId>
        </dependency>
        <dependency>
            <groupId>com.openharness</groupId>
            <artifactId>openharness-observability</artifactId>
        </dependency>

        <!-- Prometheus Metrics 暴露 -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>

        <!-- OpenTelemetry OTLP Exporter -->
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-exporter-otlp</artifactId>
        </dependency>

        <!-- 结构化日志 -->
        <dependency>
            <groupId>net.logstash.logback</groupId>
            <artifactId>logstash-logback-encoder</artifactId>
            <version>${logstash.logback.version}</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Fat JAR 打包 -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals>
                            <goal>shade</goal>
                        </goals>
                        <configuration>
                            <transformers>
                                <transformer implementation=
                                "org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>com.openharness.Main</mainClass>
                                </transformer>
                                <transformer implementation=
                                "org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            </transformers>
                            <createDependencyReducedPom>false</createDependencyReducedPom>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <!-- Docker 镜像 (mvn jib:dockerBuild) -->
            <plugin>
                <groupId>com.google.cloud.tools</groupId>
                <artifactId>jib-maven-plugin</artifactId>
                <configuration>
                    <from>
                        <image>eclipse-temurin:21-jre-alpine</image>
                    </from>
                    <to>
                        <image>openharness/openharness-java</image>
                        <tags>
                            <tag>${project.version}</tag>
                        </tags>
                    </to>
                    <container>
                        <mainClass>com.openharness.Main</mainClass>
                        <jvmFlags>
                            <jvmFlag>-XX:+UseZGC</jvmFlag>
                            <jvmFlag>-Xmx1g</jvmFlag>
                        </jvmFlags>
                        <ports>
                            <port>8080</port>
                        </ports>
                    </container>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### 3.5 常用 Maven 命令

```bash
# 编译全部模块
mvn clean compile

# 运行全部测试
mvn test

# 打包 Fat JAR
mvn clean package -pl openharness-app -am

# 跳过测试打包（加速构建）
mvn clean package -pl openharness-app -am -DskipTests

# 生成测试覆盖率报告
mvn verify # 报告在 target/site/jacoco/index.html

# 构建 Docker 镜像（本地）
mvn jib:dockerBuild -pl openharness-app

# 推送 Docker 镜像到远程仓库
mvn jib:build -pl openharness-app

# 依赖树分析
mvn dependency:tree

# 检查依赖更新
mvn versions:display-dependency-updates
```
