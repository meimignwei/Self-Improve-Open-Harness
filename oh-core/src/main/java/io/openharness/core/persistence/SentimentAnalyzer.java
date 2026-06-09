package io.openharness.core.persistence;

import java.util.List;
import java.util.Set;

/**
 * 关键词级情绪识别。扫描用户消息中的积极/消极词汇，产出 -1.0 到 1.0 的分数。
 * 不调 LLM，零延迟。
 */
public class SentimentAnalyzer {

    private static final Set<String> NEGATIVE = Set.of(
        "不对", "不行", "错误", "错了", "不对的", "重来", "算了", "放弃",
        "你还是没理解", "不是我想要的", "太慢了", "太啰嗦",
        "no", "wrong", "incorrect", "not working", "doesn't work",
        "redo", "start over", "too slow", "useless", "bad"
    );

    private static final Set<String> POSITIVE = Set.of(
        "对的", "好的", "正确", "没错", "很好", "太好了", "完美", "谢谢",
        "正是我要的", "可以了", "没问题", "厉害",
        "yes", "correct", "perfect", "great", "thanks", "thank you",
        "exactly", "works", "awesome", "good"
    );

    public static double analyze(String userMessage) {
        String lower = userMessage.toLowerCase();
        int neg = 0, pos = 0;
        for (String w : NEGATIVE) { if (lower.contains(w)) neg++; }
        for (String w : POSITIVE) { if (lower.contains(w)) pos++; }
        if (neg + pos == 0) return 0;
        return (double) (pos - neg) / (pos + neg);
    }

    public static double sessionAverage(List<String> userMessages) {
        return userMessages.stream()
            .mapToDouble(SentimentAnalyzer::analyze)
            .average().orElse(0);
    }

    public static String label(double score) {
        if (score > 0.2) return "positive";
        if (score < -0.2) return "negative";
        return "neutral";
    }
}
