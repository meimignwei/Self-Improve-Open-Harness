package com.openharness.extensions.swarm;

import com.openharness.common.AgentRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process agent execution using Virtual Threads with per-teammate context isolation.
 * Java equivalent of Python swarm/in_process.py.
 */
public class InProcessBackend implements TeammateBackend {

    private static final Logger logger = LoggerFactory.getLogger(InProcessBackend.class);

    private final AgentRuntime agentRuntime;
    private final Map<String, TeammateEntry> active = new ConcurrentHashMap<>();

    // ThreadLocal for per-teammate context isolation (Java equivalent of Python ContextVar)
    private static final InheritableThreadLocal<TeammateContext> teammateContextHolder = new InheritableThreadLocal<>();

    public InProcessBackend(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    // ------------------------------------------------------------------
    // Static context accessors (Python get_teammate_context / set_teammate_context)
    // ------------------------------------------------------------------

    public static TeammateContext getTeammateContext() {
        return teammateContextHolder.get();
    }

    public static void setTeammateContext(TeammateContext ctx) {
        teammateContextHolder.set(ctx);
    }

    // ------------------------------------------------------------------
    // TeammateBackend protocol
    // ------------------------------------------------------------------

    @Override
    public String type() {
        return "in_process";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public SpawnResult spawn(TeammateSpec spec) {
        String agentId = spec.name() + "@" + spec.team();
        String taskId = "in_process_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        if (active.containsKey(agentId)) {
            TeammateEntry existing = active.get(agentId);
            if (existing.thread.isAlive()) {
                logger.warn("[InProcessBackend] spawn(): {} is already running", agentId);
                return SpawnResult.failure(taskId, agentId, type(), "Agent '" + agentId + "' is already running");
            }
        }

        TeammateAbortController abortController = new TeammateAbortController();

        Thread vt = Thread.startVirtualThread(() -> {
            try {
                startInProcessTeammate(spec, agentId, abortController);
            } catch (Exception e) {
                onTeammateError(agentId, e);
            } finally {
                active.remove(agentId);
            }
        });
        TeammateEntry entry = new TeammateEntry(vt, abortController, taskId);
        active.put(agentId, entry);

        logger.debug("[InProcessBackend] spawned {} (task_id={})", agentId, taskId);
        return SpawnResult.success(taskId, agentId, type());
    }

    @Override
    public void sendMessage(String agentId, TeammateMessage message) {
        if (!agentId.contains("@")) {
            throw new IllegalArgumentException("Invalid agent_id '" + agentId + "': expected 'agentName@teamName'");
        }
        String[] parts = agentId.split("@", 2);
        String agentName = parts[0];
        String teamName = parts[1];

        FileMailbox.MailboxMessage msg = FileMailbox.createUserMessage(
                message.fromAgent(), agentId, message.text());

        try {
            var mailbox = new FileMailbox(teamName, agentName);
            mailbox.write(msg);
        } catch (Exception e) {
            logger.error("[InProcessBackend] Failed to send message to {}", agentId, e);
        }
        logger.debug("[InProcessBackend] sent message to {}", agentId);
    }

    @Override
    public boolean shutdown(String agentId, boolean force) {
        TeammateEntry entry = active.get(agentId);
        if (entry == null) {
            logger.debug("[InProcessBackend] shutdown(): {} not found in active tasks", agentId);
            return false;
        }

        if (!entry.thread.isAlive()) {
            active.remove(agentId);
            return true;
        }

        if (force) {
            entry.abortController.requestCancel("force shutdown", true);
            entry.thread.interrupt();
        } else {
            entry.abortController.requestCancel("graceful shutdown");
            try {
                entry.thread.join(10_000);
            } catch (InterruptedException e) {
                logger.warn("[InProcessBackend] {} did not exit within 10s — forcing cancel", agentId);
                entry.abortController.requestCancel("timeout — forcing", true);
                entry.thread.interrupt();
            }
        }

        cleanupTeammate(agentId);
        logger.debug("[InProcessBackend] shut down {}", agentId);
        return true;
    }

    @Override
    public TeammateStatus getStatus(String agentId) {
        TeammateEntry entry = active.get(agentId);
        if (entry == null) {
            return TeammateStatus.unknown(agentId);
        }
        if (!!entry.thread.isAlive()) {
            return TeammateStatus.running(agentId, -1);
        }
        return statusMap.getOrDefault(agentId, TeammateStatus.unknown(agentId));
    }

    private final Map<String, TeammateStatus> statusMap = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Agent execution
    // ------------------------------------------------------------------

    private void startInProcessTeammate(TeammateSpec config, String agentId,
                                         TeammateAbortController abortController) {
        TeammateContext ctx = new TeammateContext(
                agentId,
                config.name(),
                config.team(),
                config.parentSessionId(),
                config.color(),
                config.planModeRequired(),
                abortController
        );
        setTeammateContext(ctx);

        String teamName = config.team();
        FileMailbox mailbox = new FileMailbox(teamName, agentId);

        logger.debug("[in_process] {}: starting", agentId);

        try {
            ctx.status = "running";

            if (agentRuntime != null) {
                runQueryLoop(config, ctx, mailbox);
            } else {
                logger.info("[in_process] {}: no agentRuntime supplied — stub run for prompt: {}",
                        agentId, config.prompt() != null ? config.prompt().substring(0, Math.min(80, config.prompt().length())) : "");
                ctx.status = "idle";
                for (int i = 0; i < 10; i++) {
                    if (abortController.isCancelled()) {
                        logger.debug("[in_process] {}: cancelled during stub run", agentId);
                        return;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("[in_process] {}: unhandled exception in agent loop", agentId, e);
        } finally {
            ctx.status = "stopped";
            try {
                FileMailbox.MailboxMessage idleMsg = FileMailbox.createIdleNotification(
                        agentId, "leader",
                        config.name() + " finished (tools=" + ctx.toolUseCount + ", tokens=" + ctx.totalTokens + ")");
                FileMailbox leaderMailbox = new FileMailbox(teamName, "leader");
                leaderMailbox.write(idleMsg);
            } catch (Exception e) {
                // best effort
            }
            logger.debug("[in_process] {}: exiting (tools={}, tokens={})",
                    agentId, ctx.toolUseCount, ctx.totalTokens);
            setTeammateContext(null);
        }
    }

    private void runQueryLoop(TeammateSpec config, TeammateContext ctx, FileMailbox mailbox) {
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", config.prompt()));

        var queryMessages = java.util.List.of(
                new com.openharness.common.ConversationMessage(
                        com.openharness.common.Role.USER,
                        java.util.List.of(new com.openharness.common.ContentBlock.TextBlock(
                                config.prompt() != null ? config.prompt() : "Start agent task"))));

        var opts = com.openharness.common.QueryOptions.defaults()
                .withModel(config.model() != null ? config.model() : "claude-sonnet-4-6")
                .withMaxTurns(200);

        try {
            var publisher = agentRuntime.runQuery(queryMessages, opts);
            var events = com.openharness.common.PublisherAdapter.toList(publisher);

            for (var event : events) {
                if (event instanceof com.openharness.common.StreamEvent.AssistantTextDelta delta) {
                    ctx.totalTokens += estimateTokens(delta.text());
                }

                if (ctx.abortController.isCancelled()) {
                    logger.debug("[in_process] {}: abort_controller cancelled, stopping query loop", ctx.agentId);
                    return;
                }

                if (drainMailbox(mailbox, ctx)) {
                    return;
                }

                while (!ctx.messageQueue.isEmpty()) {
                    TeammateMessage queued = ctx.messageQueue.poll();
                    if (queued != null) {
                        logger.debug("[in_process] {}: injecting queued message from {}", ctx.agentId, queued.fromAgent());
                        // Inject as a new user turn - in a full implementation this would
                        // restart the query loop with the new message
                    }
                }
            }
        } catch (Exception e) {
            logger.error("[in_process] {}: query loop error", ctx.agentId, e);
        }

        ctx.status = "idle";
    }

    private boolean drainMailbox(FileMailbox mailbox, TeammateContext ctx) {
        try {
            List<FileMailbox.MailboxMessage> pending = mailbox.readAll(true);
            for (FileMailbox.MailboxMessage msg : pending) {
                try {
                    mailbox.markRead(msg.id);
                } catch (Exception e) {
                    // best effort
                }

                if ("shutdown".equals(msg.type)) {
                    logger.debug("[in_process] {}: received shutdown message", ctx.agentId);
                    ctx.abortController.requestCancel("shutdown message received");
                    return true;
                }

                if ("user_message".equals(msg.type)) {
                    logger.debug("[in_process] {}: queuing user_message from mailbox", ctx.agentId);
                    ctx.messageQueue.add(new TeammateMessage(
                            msg.getContent(),
                            msg.sender));
                }
            }
        } catch (Exception e) {
            // mailbox may not exist yet
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Enhanced lifecycle management (Python _cleanup_teammate, _on_teammate_error, etc.)
    // ------------------------------------------------------------------

    private void cleanupTeammate(String agentId) {
        TeammateEntry entry = active.remove(agentId);
        if (entry == null) return;

        if (!entry.abortController.isCancelled()) {
            entry.abortController.requestCancel("cleanup");
        }
        statusMap.remove(agentId);
        logger.debug("[InProcessBackend] _cleanup_teammate: {} removed from registry", agentId);
    }

    private void onTeammateError(String agentId, Throwable error) {
        TeammateEntry entry = active.get(agentId);
        double duration = 0;
        if (entry != null) {
            duration = (System.currentTimeMillis() - entry.startedAt.toEpochMilli()) / 1000.0;
            active.remove(agentId);
        }
        logger.error("[InProcessBackend] Teammate {} raised an unhandled exception (duration={}s): {}: {}",
                agentId, String.format("%.1f", duration), error.getClass().getSimpleName(), error.getMessage());
    }

    public boolean isActive(String agentId) {
        TeammateEntry entry = active.get(agentId);
        return entry != null && !!entry.thread.isAlive();
    }

    public List<String> activeAgents() {
        List<String> result = new ArrayList<>();
        for (var e : active.entrySet()) {
            if (!e.getValue().thread.isAlive()) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    public List<TeammateStatus> listTeammates() {
        List<TeammateStatus> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (var e : active.entrySet()) {
            boolean running = !e.getValue().thread.isAlive();
            double duration = (now - e.getValue().startedAt.toEpochMilli()) / 1000.0;
            result.add(new TeammateStatus(
                    e.getKey(),
                    running ? TeammateStatus.State.RUNNING : TeammateStatus.State.COMPLETED,
                    e.getValue().startedAt,
                    Instant.now(),
                    -1, -1));
        }
        return result;
    }

    /**
     * Shut down all active teammates.
     * Java equivalent of Python shutdown_all().
     */
    public void shutdownAll(boolean force, long timeoutSeconds) {
        List<String> agentIds = new ArrayList<>(active.keySet());
        for (String aid : agentIds) {
            try {
                shutdown(aid, force);
            } catch (Exception e) {
                // continue with other agents
            }
        }
    }

    public void shutdownAll() {
        shutdownAll(false, 10);
    }

    // ------------------------------------------------------------------
    // TeammateAbortController (Python dual-signal abort controller)
    // ------------------------------------------------------------------

    static class TeammateAbortController {
        private final AtomicBoolean cancelEvent = new AtomicBoolean(false);
        private final AtomicBoolean forceCancel = new AtomicBoolean(false);
        private volatile String reason;

        boolean isCancelled() {
            return cancelEvent.get() || forceCancel.get();
        }

        void requestCancel(String reason, boolean force) {
            this.reason = reason;
            if (force) {
                logger.debug("[TeammateAbortController] Force-cancel requested: {}", reason);
                forceCancel.set(true);
                cancelEvent.set(true);
            } else {
                logger.debug("[TeammateAbortController] Graceful cancel requested: {}", reason);
                cancelEvent.set(true);
            }
        }

        void requestCancel(String reason) {
            requestCancel(reason, false);
        }

        String getReason() {
            return reason;
        }
    }

    // ------------------------------------------------------------------
    // TeammateContext (Python per-teammate state via ContextVar)
    // ------------------------------------------------------------------

    public static class TeammateContext {
        public final String agentId;
        public final String agentName;
        public final String teamName;
        public final String parentSessionId;
        public final String color;
        public final boolean planModeRequired;
        public final TeammateAbortController abortController;
        public final ConcurrentLinkedQueue<TeammateMessage> messageQueue = new ConcurrentLinkedQueue<>();
        public volatile String status = "starting";
        public final long startedAt = System.currentTimeMillis();
        public volatile int toolUseCount;
        public volatile int totalTokens;

        TeammateContext(String agentId, String agentName, String teamName,
                        String parentSessionId, String color, boolean planModeRequired,
                        TeammateAbortController abortController) {
            this.agentId = agentId;
            this.agentName = agentName;
            this.teamName = teamName;
            this.parentSessionId = parentSessionId;
            this.color = color;
            this.planModeRequired = planModeRequired;
            this.abortController = abortController;
        }
    }

    // ------------------------------------------------------------------
    // Internal entry
    // ------------------------------------------------------------------

    private static class TeammateEntry {
        final Thread thread;
        final TeammateAbortController abortController;
        final String taskId;
        final Instant startedAt = Instant.now();

        TeammateEntry(Thread thread, TeammateAbortController abortController, String taskId) {
            this.thread = thread;
            this.abortController = abortController;
            this.taskId = taskId;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int estimateTokens(String text) {
        if (text == null) return 0;
        return text.length() / 4;
    }
}
