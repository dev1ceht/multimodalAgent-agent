package com.multimodalAgent.agent.service.routing;

import com.multimodalAgent.agent.service.ai.AiMessage;
import java.util.List;
import java.util.Locale;

/** Deterministic floor for campus mental-health knowledge questions. */
final class MentalHealthTopicGate {

    private static final List<String> DIRECT_TOPIC_TERMS = List.of(
            "心理", "焦虑", "压力", "抑郁", "低落", "情绪", "孤独", "睡眠", "失眠", "熬夜", "补觉",
            "睡够", "睡不着", "打鼾", "白天困", "人际", "关系冲突", "分手", "边界", "威胁",
            "求助", "心理咨询", "辅导员", "危机", "陪伴", "心理中心", "保密", "拖延", "喘不过气",
            "没劲", "不想回消息", "不想活", "自杀", "自伤", "12356", "通宵", "惊恐障碍",
            "恋爱关系", "查看手机", "限制社交", "转专业", "诊断", "开药", "吃什么药"
    );

    private static final List<String> SUPPORT_CONTEXT_TERMS = List.of(
            "压力", "焦虑", "紧张", "失眠", "睡不", "低落", "情绪", "逃避", "不想", "撑不住",
            "求助", "心理", "困难", "影响", "拆分", "小步骤", "不知道先", "后悔", "不舒服",
            "害怕", "冲突", "越界", "控制", "限制", "查我", "吵", "缺课", "适应"
    );

    private MentalHealthTopicGate() {
    }

    static boolean matches(String input, List<AiMessage> history) {
        return containsTopic(input) || recentHistoryContainsTopic(history);
    }

    private static boolean containsTopic(String input) {
        String normalized = normalize(input);
        if (normalized.isBlank()) {
            return false;
        }
        if (DIRECT_TOPIC_TERMS.stream().anyMatch(normalized::contains)) {
            return true;
        }
        if (normalized.contains("12356")
                || (normalized.contains("120") && normalized.contains("110"))) {
            return true;
        }
        if (containsAny(normalized, "任务太多", "不知道先做", "不知道从哪", "学习困难", "拆分任务")) {
            return true;
        }
        if (containsAny(normalized, "考试", "复习", "学习", "课程", "论文", "作业", "ddl")
                && containsSupportContext(normalized)) {
            return true;
        }
        if (containsAny(normalized, "室友", "对象", "伴侣", "家里", "同学")
                && containsSupportContext(normalized)) {
            return true;
        }
        if (containsAny(normalized, "新生", "转专业", "求职", "职业", "就业")
                && containsSupportContext(normalized)) {
            return true;
        }
        return false;
    }

    private static boolean containsSupportContext(String normalized) {
        return SUPPORT_CONTEXT_TERMS.stream().anyMatch(normalized::contains);
    }

    private static boolean containsAny(String normalized, String... terms) {
        for (String term : terms) {
            if (normalized.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean recentHistoryContainsTopic(List<AiMessage> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        return history.stream()
                .skip(Math.max(0, history.size() - 6))
                .map(AiMessage::content)
                .anyMatch(MentalHealthTopicGate::containsTopic);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
