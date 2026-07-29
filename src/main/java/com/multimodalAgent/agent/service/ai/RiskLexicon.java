package com.multimodalAgent.agent.service.ai;

import java.util.List;

/**
 * 风险和咨询关键词词库。
 *
 * <p>它是模型判断前的硬规则兜底，主要用于快速识别明确危险信号。</p>
 */
public final class RiskLexicon {

    private static final List<String> HIGH_RISK_WORDS = List.of(
            "不想活", "活不下去", "撑不下去", "自杀", "自残", "轻生", "结束生命", "结束这一切",
            "伤害自己", "伤人", "杀了", "想死", "去死", "没有活着的意义", "不想存在", "消失算了",
            "像是在告别", "把东西都送人", "不会再联系任何人", "担心自己会伤害", "怕自己会伤害",
            "suicide", "kill myself", "self harm", "end my life", "hurt myself", "hurt others", "want to die"
    );

    private static final List<String> EXPLICIT_HIGH_RISK_PHRASES = List.of(
            "不想活", "活不下去", "撑不下去", "想死", "我要自杀", "我有自杀想法",
            "我想自残", "我在自残", "我会伤害自己", "我想伤人", "我会伤人", "我怕自己会伤害",
            "伤害身边的人", "朋友像是在告别", "把东西都送人了", "今晚不会再联系任何人",
            "现在可能会伤害自己",
            "kill myself", "end my life", "hurt myself", "hurt others", "want to die"
    );

    private static final List<String> CONSULT_WORDS = List.of(
            "焦虑", "压力", "压抑", "抑郁", "低落", "失眠", "睡不着", "崩溃", "难过", "孤独",
            "情绪", "心理", "心理咨询", "咨询师", "心累", "烦躁", "害怕", "恐惧", "内耗", "想哭",
            "不开心", "没动力", "痛苦", "沮丧", "绝望", "无助", "喘不过气", "panic attack",
            "anxiety", "anxious", "stress", "depress", "sad", "insomnia", "panic", "lonely", "breakup"
    );

    private RiskLexicon() {
    }

    public static boolean hasHighRiskSignal(String text) {
        return containsAny(text, HIGH_RISK_WORDS);
    }

    public static boolean hasExplicitHighRiskSignal(String text) {
        return containsAny(text, EXPLICIT_HIGH_RISK_PHRASES);
    }

    public static boolean hasConsultSignal(String text) {
        return containsAny(text, CONSULT_WORDS);
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
