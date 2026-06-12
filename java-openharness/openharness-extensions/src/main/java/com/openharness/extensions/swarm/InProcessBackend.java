package com.openharness.extensions.swarm;

import com.openharness.common.AgentRuntime;
import com.openharness.tools.AgentTool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process agent execution using Virtual Threads.
 * Java equivalent of Python swarm/in_process.py.
 */
public class InProcessBackend implements TeammateBackend {

    private final AgentRuntime agentRuntime;
    private final Map<String, TeammateStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, Thread> threads = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    public InProcessBackend(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @Override
    public String spawn(TeammateSpec spec) {
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        stopFlags.put(spec.id(), stopFlag);
        statuses.put(spec.id(), TeammateStatus.running(spec.id(), -1));

        Thread thread = Thread.startVirtualThread(() -> {
            try {
                var messages = java.util.List.of(
                        new com.openharness.common.ConversationMessage(
                                com.openharness.common.Role.USER,
                                java.util.List.of(new com.openharness.common.ContentBlock.TextBlock(
                                        spec.systemPrompt() != null ? spec.systemPrompt() : "Start agent task"))));

                var opts = com.openharness.common.QueryOptions.defaults()
                        .withModel(spec.model() != null ? spec.model() : "claude-sonnet-4-6")
                        .withMaxTurns(200);

                var publisher = agentRuntime.runQuery(messages, opts);

                var events = com.openharness.common.PublisherAdapter.toList(publisher);

                statuses.put(spec.id(), new TeammateStatus(
                        spec.id(), TeammateStatus.State.COMPLETED,
                        java.time.Instant.now(), java.time.Instant.now(), -1, 0));
            } catch (Exception e) {
                statuses.put(spec.id(), new TeammateStatus(
                        spec.id(), TeammateStatus.State.FAILED,
                        null, java.time.Instant.now(), -1, -1));
            }
        });

        threads.put(spec.id(), thread);
        return spec.id();
    }

    @Override
    public void sendMessage(String teammateId, String message) {
        // In-process agents share memory via FileMailbox, not stdin
    }

    @Override
    public TeammateStatus getStatus(String teammateId) {
        return statuses.getOrDefault(teammateId, TeammateStatus.unknown(teammateId));
    }

    @Override
    public void stop(String teammateId) {
        AtomicBoolean flag = stopFlags.get(teammateId);
        if (flag != null) flag.set(true);
        Thread thread = threads.remove(teammateId);
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
        stopFlags.remove(teammateId);
        statuses.remove(teammateId);
    }

    /**
     * Creates an AgentTool backed by this runtime for spawning sub-agents.
     */
    public AgentTool createAgentTool() {
        return new AgentTool(agentRuntime);
    }
}
