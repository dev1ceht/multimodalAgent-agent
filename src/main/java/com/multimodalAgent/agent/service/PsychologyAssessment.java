package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.domain.RiskLevel;
import java.util.List;

/**
 * 一次后台心理状态评估结果。
 *
 * <p>该对象只在服务端报告和工具链中使用，不作为学生端消息内容。</p>
 */
public record PsychologyAssessment(
        EmotionLabel emotion,
        double emotionScore,
        RiskLevel risk,
        double confidence,
        String summary,
        List<ObservedState> observedStates,
        FunctionalImpact functionalImpact,
        List<String> evidence
) {
    public PsychologyAssessment(
            EmotionLabel emotion,
            double emotionScore,
            RiskLevel risk,
            double confidence,
            String summary
    ) {
        this(
                emotion,
                emotionScore,
                risk,
                confidence,
                summary,
                List.of(defaultState(emotion)),
                FunctionalImpact.NONE,
                List.of());
    }

    public PsychologyAssessment {
        observedStates = observedStates == null ? List.of() : List.copyOf(observedStates);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public PsychologyAssessment withRiskFloor(RiskLevel floor) {
        if (floor == null || floor.ordinal() <= risk.ordinal()) {
            return this;
        }
        EmotionLabel updatedEmotion = floor == RiskLevel.HIGH ? EmotionLabel.HIGH_RISK : emotion;
        double minimumScore = switch (floor) {
            case HIGH -> 4.0;
            case MEDIUM -> 3.0;
            case LOW -> 1.0;
            case NONE -> 0.0;
        };
        return new PsychologyAssessment(
                updatedEmotion,
                Math.max(emotionScore, minimumScore),
                floor,
                confidence,
                summary,
                observedStates,
                functionalImpact,
                evidence);
    }

    private static ObservedState defaultState(EmotionLabel emotion) {
        return switch (emotion) {
            case ANXIETY -> ObservedState.ANXIOUS;
            case DEPRESSED -> ObservedState.LOW_MOOD;
            case HIGH_RISK -> ObservedState.OTHER;
            case NORMAL -> ObservedState.NEUTRAL;
        };
    }
}
