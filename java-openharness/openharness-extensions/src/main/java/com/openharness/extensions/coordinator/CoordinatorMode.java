package com.openharness.extensions.coordinator;

import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.common.TeamRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinator mode: system prompt, tool set, and team management.
 * Java equivalent of Python coordinator/coordinator_mode.py.
 */
public class CoordinatorMode {

    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    public static boolean isEnabled() {
        return System.getenv("CLAUDE_CODE_COORDINATOR_MODE") != null;
    }

    public static List<String> getTools() {
        return List.of("agent", "send_message", "task_stop");
    }

    /**
     * Team registry for coordinator-managed multi-agent teams.
     */
    public static class CoordinatorTeamRegistry extends TeamRegistry {
    }
}
