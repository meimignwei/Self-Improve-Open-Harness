# Java OpenHarness 与 Python 原版 Gap 分析

> 本文档对照 Python 版 OpenHarness 源码，逐项列出当前 Java 实现中**未实现、部分实现或仅为 stub** 的功能模块，用于后续复刻完成度评估。
> 
> 评估基准：Python 版功能完整性为 100%，Java 版按模块标注状态。

---

## 一、核心引擎层 (openharness-engine)

| 功能点 | 状态 | 说明 |
|--------|------|------|
| QueryEngine 主循环 | **已实现** | SSE 流式读取、ToolUse 解析、多轮对话、虚拟线程执行完整 |
| QueryEngine JSON 反序列化 | **部分实现** | `executeSingleTool()` 已接入 `OpenHarnessObjectMapper.treeToValue()`，但 record 字段缺少 `@JsonPropertyDescription`，复杂嵌套结构待验证 |
| QueryEngine Tool Schema 生成 | **部分实现** | `buildToolDefinitions()` 目前仅提取 inputSchema 的顶层字段，未递归生成完整 JSON Schema（缺少 required、类型映射、嵌套对象描述） |
| CostTracker | **已实现（基础）** | 类已存在，能累加 UsageSnapshot，但缺少多 provider/model 的细粒度定价表，未通过 OpenHarnessMeters 暴露指标 |
| AutoCompactState | **未实现** | 类占位符可能缺失；QueryEngine 中引用 `autoCompactState` 但无 L1/L2/L3 压实逻辑。Python 版支持 MicroCompact、Session Checkpoint、LLM 摘要三级压实 |
| ToolCarryover | **未实现** | 类占位符可能缺失；QueryEngine 中引用 `toolCarryover` 但无跨会话结果持久化逻辑。Python 版会自动保留重要工具输出并注入后续 system prompt |
| CompactionService | **未实现** | 会话历史压缩服务缺失 |

---

## 二、API 层 (openharness-api)

| 功能点 | 状态 | 说明 |
|--------|------|------|
| AnthropicMessagesClient | **已实现** | SSE 流式消息、ToolUse 解析完整 |
| OpenAICompatibleClient | **已实现** | 兼容 OpenAI 格式 |
| StreamOptions / ApiStreamEvent | **已实现** | 事件模型完整 |

---

## 三、工具层 (openharness-tools)

当前 Java 版已注册工具约 19 个，Python 版约 42 个，差距如下：

### 3.1 已实现的工具

| 工具名 | 状态 |
|--------|------|
| BashTool | 已实现 |
| EditTool / MultiEditTool | 已实现 |
| GlobTool / GrepTool | 已实现 |
| ReadTool / WriteTool | 已实现 |
| NotebookEditTool | 已实现 |
| LSCommand | 已实现 |
| ViewTool | 已实现 |
| McpTool (直接调用) | 已实现 |
| McpToolAdapter (动态注册) | 已实现 |
| ListMcpResourcesTool / ReadMcpResourceTool | 已实现 |
| MemoryCreateTool / MemoryReadTool / MemorySearchTool / MemoryDeleteTool | 已实现 |
| ToolSearchTool | 已实现 |

### 3.2 未实现或 stub 的工具

| 工具名 | 状态 | 与 Python 版差距 |
|--------|------|------------------|
| BriefTool | **未实现** | Python 版支持将长文本截断至指定长度（默认 200 字符），用于减少 token |
| SleepTool | **未实现** | Python 版支持延迟执行 0-30 秒 |
| ConfigTool | **未实现** | Python 版支持 `action=show/set`，可运行时查看和修改配置项 |
| EnterWorktreeTool / ExitWorktreeTool | **未实现** | Python 版支持 `git worktree add/remove`，Java 版无对应封装 |
| RemoteTriggerTool | **未实现** | Python 版支持按名称查找 cron job 并远程触发执行 |
| ImageToTextTool | **未实现** | Python 版支持读取图片文件，调用 vision-capable LLM 进行 OCR/描述 |
| ImageGenerationTool | **未实现** | Python 版支持多 provider SSE 图片生成（OpenAI DALL-E 等），含编辑、变体功能 |
| LspTool | **未实现** | Python 版封装了 LSP 客户端，支持 documentSymbols、hover、go-to-definition、find-references |
| McpAuthTool | **未实现** | Python 版支持为 MCP server 持久化认证配置（bearer/header/env）并触发重连 |
| TeamCreateTool / TeamDeleteTool | **未实现** | Python 版通过 CoordinatorMode.TeamRegistry 管理团队生命周期 |
| CronToggleTool | **未实现** | Python 版支持启用/禁用定时任务；Java 版 CronRegistry 缺少 `toggleJob(id)` 方法 |

---

## 四、MCP 扩展层 (openharness-extensions/mcp)

| 功能点 | 状态 | 说明 |
|--------|------|------|
| McpClientManager stdio 连接 | **已实现** | JSON-RPC 2.0、initialize、tools/list、resources/list、tools/call、resources/read 完整 |
| McpClientManager HTTP 连接 | **stub** | `connectHttp()` 仅返回 CONNECTED 状态，未实现真正的 HTTP/SSE 传输 |
| McpClientManager WebSocket 连接 | **stub** | `connectWs()` 仅返回 CONNECTED 状态，未实现真正的 WS 传输 |
| MCP Auth 配置持久化 | **未实现** | 无 `McpAuthTool`，不支持为 server 设置 bearer token / header 并保存到 `~/.openharness/mcp_servers.json` |

---

## 五、配置与权限层 (openharness-config / permissions)

| 功能点 | 状态 | 说明 |
|--------|------|------|
| Settings 加载/保存 | **已实现** | JSON 配置文件读写完整 |
| ProviderProfile | **已实现** | 多 provider 配置合并完整 |
| PermissionChecker | **已实现** | 规则引擎、只读/读写分类、确认回调完整 |
| 交互式权限确认 (TUI) | **已实现** | PermissionDialog 支持 Allow/Allow All/Deny |
| 交互式权限确认 (Print) | **已实现** | Scanner 读取 y/n |

---

## 六、UI 层 (openharness-ui)

| 功能点 | 状态 | 说明 |
|--------|------|------|
| EventLoop | **已实现** | 接入 AgentRuntime.runQuery()，订阅 Flow.Publisher，真实执行 LLM + 工具循环 |
| TerminalUI / RuntimeOutput | **已实现** | 支持 PRINT / TUI / BACKEND 三种模式 |
| OpenHarnessApp | **已实现** | 整合 Settings、Mode、QueryEngine，支持 prompt / interactive 两种入口 |
| OutputRenderer | **已实现** | 文本增量渲染、工具执行状态渲染 |

---

## 七、记忆系统 (openharness-extensions/memory)

| 功能点 | 状态 | 说明 |
|--------|------|------|
| MemoryManager | **已实现** | 创建、读取、搜索、删除完整，支持语义评分 |
| MemoryEntry / MemoryHeader | **已实现** | 数据模型完整 |
| MemoryExtractionService | **未实现** | Python 版自动从对话中提取关键信息写入记忆，Java 版无此服务 |

---

## 八、技能系统 (openharness-extensions/skills)

| 功能点 | 状态 | 说明 |
|--------|------|------|
| SkillLoader 文件系统加载 | **已实现** | 从 `.openharness/skills/` 加载 `.md` 技能文件 |
| SkillLoader classpath 加载 | **未实现** | Python 版内置 8 个 bundled skills（commit/debug/diagnose/plan/review/simplify/skill-creator/test），Java 版 `loadFromResourceDir()` 为空实现 |
| SystemPromptBuilder 技能注入 | **未实现** | Python 版会在 system prompt 中追加可用技能列表，Java 版缺少此逻辑 |

---

## 九、Cron / 调度系统 (openharness-extensions/services)

| 功能点 | 状态 | 说明 |
|--------|------|------|
| CronRegistry | **部分实现** | 基础注册、调度框架存在，但缺少 `toggleJob(id)` 方法 |
| CronJob 持久化 | **未验证** | 是否支持重启后恢复待确认 |

---

## 十、Ohmo 个人代理层 (openharness-ohmo)

| 功能点 | 状态 | 说明 |
|--------|------|------|
| OhmoGatewayService | **stub** | `gatewayStart()` / `gatewayStop()` 仅打印日志，无实际网关进程管理 |
| AutoDreamService | **未实现** | 自动反思/梦境循环缺失 |
| CodeIntelligence | **未实现** | 代码理解、索引、智能提示缺失 |
| CommandRegistry | **stub** | 命令注册表框架存在，但内置命令实现不完整 |
| AppState | **stub** | 应用状态管理占位 |
| VoiceMode | **未实现** | 语音输入/输出全流程缺失 |
| PluginLoader | **stub** | 插件加载机制占位，无动态加载实现 |

---

## 十一、沙箱系统 (openharness-extensions/sandbox)

| 功能点 | 状态 | 说明 |
|--------|------|------|
| SandboxAdapter | **stub** | 仅有接口/空实现，无真正的路径隔离、权限限制、命令白名单 |
| PathValidator | **stub** | 路径校验逻辑为空 |
| 命令白名单 / 文件系统隔离 | **未实现** | Python 版支持 chroot、允许路径列表、禁止命令列表；Java 版完全缺失 |

---

## 十二、Swarm / 协调器系统 (openharness-extensions/swarm, bridge)

| 功能点 | 状态 | 说明 |
|--------|------|------|
| CoordinatorMode | **未实现** | 多 Agent 协调、任务分派缺失 |
| TeamRegistry | **stub** | 团队注册表占位，无实际团队生命周期管理 |
| Bridge / 桥接服务 | **stub** | 跨进程/跨机器 Agent 通信缺失 |

---

## 十三、Autopilot 系统 (openharness-extensions/autopilot)

| 功能点 | 状态 | 说明 |
|--------|------|------|
| RepoAutopilotStore | **stub** | 自动托管配置存储占位 |
| AutopilotTypes | **stub** | 数据模型定义存在，无业务逻辑 |
| 自动 PR / 自动诊断 / 自动修复 | **未实现** | Python 版支持基于 cron 的仓库自动维护 |

---

## 十四、可观测性 (openharness-observability)

| 功能点 | 状态 | 说明 |
|--------|------|------|
| OpenHarnessMeters | **已实现** | Micrometer 指标注册完整 |
| 具体指标埋点 | **部分实现** | API 调用、工具执行有基础埋点，但 CostTracker 指标未接入，缺少细粒度延迟直方图 |

---

## 十五、CLI 入口 (openharness-cli)

| 功能点 | 状态 | 说明 |
|--------|------|------|
| MainCommand 根命令 | **已实现** | oh / run / config / doctor / init / gateway 完整 |
| RunCmd 启动流程 | **已实现** | API 客户端、ToolRegistry、MemoryTools、MCP 工具、权限检查、QueryEngine 组装完整 |
| GatewayCmd | **stub** | 仅打印提示，未实际启停 ohmo gateway |
| ohmo 子命令 | **未实现** | Python 版支持 `oh ohmo gateway` 等完整生命周期管理 |

---

## 十六、汇总统计

| 维度 | Java 版 | Python 版 | 完成度 |
|------|---------|-----------|--------|
| 工具总数 | ~19 | ~42 | ~45% |
| 核心引擎 (QueryEngine) | 主要功能完整 | 完整 | ~80% |
| 记忆系统 | 基础 CRUD | 基础 + 自动提取 | ~60% |
| 技能系统 | 文件加载 | 内置 8 项 + prompt 注入 | ~30% |
| MCP 支持 | stdio 完整 | stdio + http + sse + auth | ~60% |
| UI / CLI | 可用 | 可用 | ~90% |
| Ohmo 代理层 | stub | 完整 | ~10% |
| 沙箱 | stub | 完整 | ~5% |
| Swarm / 协调器 | stub | 完整 | ~5% |
| Autopilot | stub | 完整 | ~5% |
| 可观测性 | 基础 | 完整 | ~50% |

---

## 十七、建议的后续补完优先级

1. **P0 - 工具补完（影响可用性）**：BriefTool、SleepTool、ConfigTool、CronToggleTool
2. **P1 - 引擎增强**：AutoCompactState、ToolCarryover、完整 Schema 生成
3. **P2 - 技能系统**：8 个 bundled skills 放入 classpath、SystemPromptBuilder 注入
4. **P3 - MCP 增强**：HTTP/SSE 传输、McpAuthTool
5. **P4 - 高级工具**：ImageToTextTool、ImageGenerationTool（MVP）、LspTool（MVP）
6. **P5 - 基础设施**：SandboxAdapter、PathValidator、命令白名单
7. **P6 - Ohmo / Swarm / Autopilot**：大模块，可独立迭代
