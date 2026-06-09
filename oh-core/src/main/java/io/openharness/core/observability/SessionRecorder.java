package io.openharness.core.observability;

import io.openharness.core.persistence.AsyncPersistenceWriter;
import io.openharness.core.persistence.mapper.ReplayMapper;
import io.openharness.core.persistence.model.ReplayEvent;

import java.util.List;
import java.util.Map;

/**
 * 会话录制与回放。通过 AsyncPersistenceWriter 异步写入 MySQL replay_events 表。
 * record* 方法只做 queue.offer()，不阻塞 Agent 主循环。
 */
public class SessionRecorder {

    private final AsyncPersistenceWriter writer;
    private final ReplayMapper replayMapper;

    public SessionRecorder(AsyncPersistenceWriter writer, ReplayMapper replayMapper) {
        this.writer = writer;
        this.replayMapper = replayMapper;
    }

    public void recordApiRequest(String sessionId, int turn, String requestJson) {
        writer.enqueue(() -> replayMapper.insert(
            new ReplayEvent(null, sessionId, turn, "api_request", requestJson, null, null, null, null)));
    }

    public void recordApiResponse(String sessionId, int turn, String responseJson) {
        writer.enqueue(() -> replayMapper.insert(
            new ReplayEvent(null, sessionId, turn, "api_response", null, responseJson, null, null, null)));
    }

    public void recordToolCall(String sessionId, int turn,
                                String toolName, Map<String, Object> args, Map<String, Object> result) {
        writer.enqueue(() -> replayMapper.insert(
            new ReplayEvent(null, sessionId, turn, "tool_call",
                null, null, toJson(args), toJson(result), null)));
    }

    public List<ReplayEvent> loadReplay(String sessionId) {
        return replayMapper.findBySessionId(sessionId);
    }

    private String toJson(Object obj) {
        // Phase 2: introduce Jackson serialization
        return obj != null ? obj.toString() : null;
    }
}
