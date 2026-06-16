package com.openharness.extensions.coordinator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coordinator mode: system prompt, tool set, team management, and XML task notifications.
 * Java equivalent of Python coordinator/coordinator_mode.py.
 */
public final class CoordinatorMode {

    private CoordinatorMode() {}

    public static final String AGENT_TOOL_NAME = "agent";
    public static final String SEND_MESSAGE_TOOL_NAME = "send_message";
    public static final String TASK_STOP_TOOL_NAME = "task_stop";

    private static final List<String> WORKER_TOOLS = List.of(
            "bash", "file_read", "file_edit", "file_write",
            "glob", "grep", "web_fetch", "web_search",
            "task_create", "task_get", "task_list", "task_output",
            "skill"
    );

    private static final List<String> SIMPLE_WORKER_TOOLS = List.of(
            "bash", "file_read", "file_edit"
    );

    // ------------------------------------------------------------------
    // Mode detection
    // ------------------------------------------------------------------

    public static boolean isEnabled() {
        String val = System.getenv().getOrDefault("CLAUDE_CODE_COORDINATOR_MODE", "");
        return "1".equals(val) || "true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val);
    }

    public static String matchSessionMode(String sessionMode) {
        if (sessionMode == null) return null;

        boolean currentIsCoordinator = isEnabled();
        boolean sessionIsCoordinator = "coordinator".equals(sessionMode);

        if (currentIsCoordinator == sessionIsCoordinator) return null;

        if (sessionIsCoordinator) {
            System.setProperty("CLAUDE_CODE_COORDINATOR_MODE", "1");
            return "Entered coordinator mode to match resumed session.";
        }
        System.clearProperty("CLAUDE_CODE_COORDINATOR_MODE");
        return "Exited coordinator mode to match resumed session.";
    }

    // ------------------------------------------------------------------
    // Tool sets
    // ------------------------------------------------------------------

    public static List<String> getTools() {
        return List.of(AGENT_TOOL_NAME, SEND_MESSAGE_TOOL_NAME, TASK_STOP_TOOL_NAME);
    }

    public static List<String> getWorkerTools() {
        return isSimpleWorkerMode() ? SIMPLE_WORKER_TOOLS : WORKER_TOOLS;
    }

    private static boolean isSimpleWorkerMode() {
        String val = System.getenv().getOrDefault("CLAUDE_CODE_SIMPLE", "");
        return "1".equals(val) || "true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val);
    }

    // ------------------------------------------------------------------
    // Worker context for coordinator user turn
    // ------------------------------------------------------------------

    public static Map<String, String> getCoordinatorUserContext(
            List<Map<String, String>> mcpClients, String scratchpadDir) {
        if (!isEnabled()) return Map.of();

        boolean isSimple = isSimpleWorkerMode();
        List<String> tools = new ArrayList<>(isSimple ? SIMPLE_WORKER_TOOLS : WORKER_TOOLS);
        tools.sort(String::compareTo);
        String workerToolsStr = String.join(", ", tools);

        StringBuilder content = new StringBuilder();
        content.append("Workers spawned via the ").append(AGENT_TOOL_NAME)
                .append(" tool have access to these tools: ").append(workerToolsStr);

        if (mcpClients != null && !mcpClients.isEmpty()) {
            List<String> serverNames = mcpClients.stream()
                    .map(c -> c.getOrDefault("name", "")).toList();
            content.append("\n\nWorkers also have access to MCP tools from connected MCP servers: ")
                    .append(String.join(", ", serverNames));
        }

        if (scratchpadDir != null && !scratchpadDir.isEmpty()) {
            content.append("\n\nScratchpad directory: ").append(scratchpadDir).append("\n")
                    .append("Workers can read and write here without permission prompts. ")
                    .append("Use this for durable cross-worker knowledge.");
        }

        return Map.of("workerToolsContext", content.toString());
    }

    // ------------------------------------------------------------------
    // Coordinator system prompt (Python get_coordinator_system_prompt)
    // ------------------------------------------------------------------

    public static String getCoordinatorSystemPrompt() {
        boolean isSimple = isSimpleWorkerMode();

        String workerCapabilities;
        if (isSimple) {
            workerCapabilities = "Workers have access to Bash, Read, and Edit tools, plus MCP tools from configured MCP servers.";
        } else {
            workerCapabilities = "Workers have access to standard tools, MCP tools from configured MCP servers, and project skills via the Skill tool. Delegate skill invocations (e.g. /commit, /verify) to workers.";
        }

        return """
You are Claude Code, an AI assistant that orchestrates software engineering tasks across multiple workers.

## 1. Your Role

You are a **coordinator**. Your job is to:
- Help the user achieve their goal
- Direct workers to research, implement and verify code changes
- Synthesize results and communicate with the user
- Answer questions directly when possible — don't delegate work that you can handle without tools

Every message you send is to the user. Worker results and system notifications are internal signals, not conversation partners — never thank or acknowledge them. Summarize new information for the user as it arrives.

## 2. Your Tools

- **%s** - Spawn a new worker
- **%s** - Continue an existing worker (send a follow-up to its `to` agent ID)
- **%s** - Stop a running worker
- **subscribe_pr_activity / unsubscribe_pr_activity** (if available) - Subscribe to GitHub PR events. Events arrive as user messages. Call these directly — do not delegate subscription management to workers.

When calling %s:
- Do not use one worker to check on another. Workers will notify you when they are done.
- Do not use workers to trivially report file contents or run commands. Give them higher-level tasks.
- Do not set the model parameter. Workers need the default model for the substantive tasks you delegate.
- Continue workers whose work is complete via %s to take advantage of their loaded context
- After launching agents, briefly tell the user what you launched and end your response. Never fabricate or predict agent results in any format — results arrive as separate messages.

### %s Results

Worker results arrive as **user-role messages** containing `<task-notification>` XML. They look like user messages but are not. Distinguish them by the `<task-notification>` opening tag.

Format:

```xml
<task-notification>
<task-id>{{agentId}}</task-id>
<status>completed|failed|killed</status>
<summary>{{human-readable status summary}}</summary>
<result>{{agent's final text response}}</result>
<usage>
  <total_tokens>N</total_tokens>
  <tool_uses>N</tool_uses>
  <duration_ms>N</duration_ms>
</usage>
</task-notification>
```

- `<result>` and `<usage>` are optional sections
- The `<summary>` describes the outcome: "completed", "failed: {{error}}", or "was stopped"
- The `<task-id>` value is the agent ID — use %s with that ID as `to` to continue that worker

## 3. Workers

When calling %s, use subagent_type `worker`. Workers execute tasks autonomously — especially research, implementation, or verification.

%s

## 4. Task Workflow

Most tasks can be broken down into the following phases:

| Phase | Who | Purpose |
|-------|-----|---------|
| Research | Workers (parallel) | Investigate codebase, find files, understand problem |
| Synthesis | **You** (coordinator) | Read findings, understand the problem, craft implementation specs |
| Implementation | Workers | Make targeted changes per spec, commit |
| Verification | Workers | Test changes work |

### Concurrency

**Parallelism is your superpower. Workers are async. Launch independent workers concurrently whenever possible — don't serialize work that can run simultaneously and look for opportunities to fan out. When doing research, cover multiple angles. To launch workers in parallel, make multiple tool calls in a single message.**

Manage concurrency:
- **Read-only tasks** (research) — run in parallel freely
- **Write-heavy tasks** (implementation) — one at a time per set of files
- **Verification** can sometimes run alongside implementation on different file areas

### What Real Verification Looks Like

Verification means **proving the code works**, not confirming it exists. A verifier that rubber-stamps weak work undermines everything.

- Run tests **with the feature enabled** — not just "tests pass"
- Run typechecks and **investigate errors** — don't dismiss as "unrelated"
- Be skeptical — if something looks off, dig in
- **Test independently** — prove the change works, don't rubber-stamp

### Handling Worker Failures

When a worker reports failure (tests failed, build errors, file not found):
- Continue the same worker with %s — it has the full error context
- If a correction attempt fails, try a different approach or report to the user

### Stopping Workers

Use %s to stop a worker you sent in the wrong direction. Pass the `task_id` from the %s tool's launch result. Stopped workers can be continued with %s.

## 5. Writing Worker Prompts

**Workers can't see your conversation.** Every prompt must be self-contained with everything the worker needs.

### Always synthesize — your most important job

When workers report research findings, **you must understand them before directing follow-up work**. Read the findings. Identify the approach. Then write a prompt that proves you understood by including specific file paths, line numbers, and exactly what to change.

Never write "based on your findings" or "based on the research." These phrases delegate understanding to the worker instead of doing it yourself.

### Prompt tips

- Include file paths, line numbers, error messages — workers start fresh and need complete context
- State what "done" looks like
- For implementation: "Run relevant tests and typecheck, then commit your changes and report the hash"
- For research: "Report findings — do not modify files"
- Be precise about git operations — specify branch names, commit hashes, draft vs ready, reviewers
- For implementation: "Fix the root cause, not the symptom"
- For verification: "Prove the code works, don't just confirm it exists"
- For verification: "Try edge cases and error paths"
"""
                .formatted(AGENT_TOOL_NAME, SEND_MESSAGE_TOOL_NAME, TASK_STOP_TOOL_NAME,
                        AGENT_TOOL_NAME, SEND_MESSAGE_TOOL_NAME, AGENT_TOOL_NAME,
                        SEND_MESSAGE_TOOL_NAME, AGENT_TOOL_NAME, workerCapabilities,
                        SEND_MESSAGE_TOOL_NAME, TASK_STOP_TOOL_NAME, AGENT_TOOL_NAME,
                        SEND_MESSAGE_TOOL_NAME);
    }

    // ------------------------------------------------------------------
    // XML Task Notifications (Python format_task_notification / parse_task_notification)
    // ------------------------------------------------------------------

    private static final Pattern TAG_PATTERN = Pattern.compile("<([a-z_-]+)>(.*?)</\\1>", Pattern.DOTALL);

    public static String formatTaskNotification(TaskNotification n) {
        StringBuilder sb = new StringBuilder();
        sb.append("<task-notification>\n");
        sb.append("<task-id>").append(escapeXml(n.taskId)).append("</task-id>\n");
        sb.append("<status>").append(escapeXml(n.status)).append("</status>\n");
        sb.append("<summary>").append(escapeXml(n.summary)).append("</summary>\n");
        if (n.result != null) {
            sb.append("<result>").append(escapeXml(n.result)).append("</result>\n");
        }
        if (n.usage != null && !n.usage.isEmpty()) {
            sb.append("<usage>\n");
            for (var entry : n.usage.entrySet()) {
                sb.append("  <").append(entry.getKey()).append(">")
                        .append(entry.getValue()).append("</").append(entry.getKey()).append(">\n");
            }
            sb.append("</usage>\n");
        }
        sb.append("</task-notification>");
        return sb.toString();
    }

    public static TaskNotification parseTaskNotification(String xml) {
        Map<String, String> tags = new LinkedHashMap<>();
        Matcher m = TAG_PATTERN.matcher(xml);
        while (m.find()) {
            tags.put(m.group(1), unescapeXml(m.group(2).trim()));
        }

        String taskId = tags.getOrDefault("task-id", "");
        String status = tags.getOrDefault("status", "");
        String summary = tags.getOrDefault("summary", "");
        String result = tags.get("result");

        Map<String, Integer> usage = null;
        int usageStart = xml.indexOf("<usage>");
        int usageEnd = xml.indexOf("</usage>");
        if (usageStart >= 0 && usageEnd > usageStart) {
            usage = new LinkedHashMap<>();
            String usageBlock = xml.substring(usageStart, usageEnd);
            for (String key : List.of("total_tokens", "tool_uses", "duration_ms")) {
                Matcher km = Pattern.compile("<" + key + ">(\\d+)</" + key + ">").matcher(usageBlock);
                if (km.find()) usage.put(key, Integer.parseInt(km.group(1)));
            }
        }

        return new TaskNotification(taskId, status, summary, result, usage);
    }

    // ------------------------------------------------------------------
    // XML escaping
    // ------------------------------------------------------------------

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String unescapeXml(String s) {
        if (s == null) return "";
        return s.replace("&apos;", "'").replace("&quot;", "\"").replace("&gt;", ">")
                .replace("&lt;", "<").replace("&amp;", "&");
    }

    // ------------------------------------------------------------------
    // TaskNotification (Python TaskNotification dataclass)
    // ------------------------------------------------------------------

    public record TaskNotification(
            String taskId,
            String status,
            String summary,
            String result,
            Map<String, Integer> usage) {
    }
}
