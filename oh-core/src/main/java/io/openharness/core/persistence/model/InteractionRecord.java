package io.openharness.core.persistence.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

public record InteractionRecord(
    String requestId,
    String sessionId,
    Instant timestamp,

    // 任务特征
    String userIntent,
    String summary,
    int inputTokens,
    int contextTokens,

    // 情绪信号 (SentimentAnalyzer 自动识别)
    double sentimentScore,
    String sentimentLabel,

    // 执行效率
    int turns,
    int toolCalls,
    int toolsFailed,
    List<String> toolsUsed,
    long durationMs,
    double cost,

    // 用户交互
    int permissionDenials,
    int compactionCount,
    String modelSwitchedTo,

    // 质量信号
    boolean firstTurnCorrect,
    boolean taskCompleted,
    int userCorrections,
    boolean userAccepted,
    Double userRating,
    String fallbackTriggered,

    // 自进化来源追踪
    String evolutionVersion,
    List<String> skillsUsed,
    boolean evolutionRelated
) {
    public InteractionRecord {
        toolsUsed = toolsUsed != null ? List.copyOf(toolsUsed) : Collections.emptyList();
        skillsUsed = skillsUsed != null ? List.copyOf(skillsUsed) : Collections.emptyList();
    }
}
