package com.multimodalAgent.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.ai.PromptTemplates;
import com.multimodalAgent.agent.service.ai.RiskLexicon;
import com.multimodalAgent.agent.service.ai.StructuredOutputSchemas;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
/**
 * 后台心理状态评估服务。
 *
 * <p>结合高风险词库、模型结构化输出和关键词兜底，生成报告所需的情绪与风险字段。</p>
 */
public class PsychologicalAssessmentService {

    private final AiClient aiClient;
    private final ObjectMapper objectMapper;
    private final EvaluationTraceService evaluationTraceService;

    public PsychologicalAssessmentService(
            AiClient aiClient,
            ObjectMapper objectMapper,
            EvaluationTraceService evaluationTraceService
    ) {
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.evaluationTraceService = evaluationTraceService;
    }

    public PsychologyAssessment assess(String input) {
        return assess(input, List.of());
    }

    public PsychologyAssessment assess(String input, List<AiMessage> history) {
        long started = System.nanoTime();
        try {
            return assessInternal(input, history);
        } finally {
            evaluationTraceService.duration("assessmentMs", started);
        }
    }

    private PsychologyAssessment assessInternal(String input, List<AiMessage> history) {
        // 高风险词库是硬规则，优先于模型判断，保证明显自伤/伤人信号不会被漏掉。
        if (RiskLexicon.hasHighRiskSignal(input.toLowerCase())) {
            evaluationTraceService.put("assessmentSource", "risk_lexicon");
            evaluationTraceService.put("assessmentJsonValid", true);
            return new PsychologyAssessment(
                    EmotionLabel.HIGH_RISK,
                    4.0,
                    RiskLevel.HIGH,
                    0.95,
                    "Explicit high-risk signal detected.",
                    List.of(ObservedState.OTHER),
                    FunctionalImpact.SIGNIFICANT,
                    List.of("命中明确的当前安全信号"));
        }
        try {
            String raw = aiClient.completeJson(
                    PromptTemplates.psychologyPrompt(history, input),
                    StructuredOutputSchemas.psychologyAssessment());
            PsychologyAssessment assessment = parseJson(raw);
            evaluationTraceService.put("assessmentSource", "model");
            evaluationTraceService.put("assessmentJsonValid", true);
            return assessment;
        } catch (Exception exception) {
            evaluationTraceService.put("assessmentSource", "heuristic_fallback");
            evaluationTraceService.put("assessmentJsonValid", false);
            evaluationTraceService.put("assessmentError", exception.getClass().getSimpleName());
            // 模型输出格式异常或调用失败时，使用关键词兜底，保证报告链路仍可运行。
            return heuristic(input);
        }
    }

    private PsychologyAssessment parseJson(String raw) throws Exception {
        String json = raw.trim();
        // 兼容模型在 JSON 前后额外输出少量文本的情况，只截取最外层 JSON 对象。
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }
        JsonNode node = objectMapper.readTree(json);
        if (!node.isObject() || node.size() != 6) {
            throw new IllegalArgumentException("Assessment JSON contains unexpected fields");
        }
        requireFields(
                node,
                "observedStates",
                "functionalImpact",
                "safetyRisk",
                "evidence",
                "confidence",
                "summary");
        if (!node.path("observedStates").isArray() || node.path("observedStates").isEmpty()) {
            throw new IllegalArgumentException("observedStates must be a non-empty array");
        }
        List<ObservedState> states = new java.util.ArrayList<>();
        node.path("observedStates").forEach(value -> {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("observedStates must contain strings");
            }
            states.add(ObservedState.valueOf(value.asText().toUpperCase(Locale.ROOT)));
        });
        if (states.size() > 4
                || states.stream().distinct().count() != states.size()
                || (states.contains(ObservedState.NEUTRAL) && states.size() > 1)) {
            throw new IllegalArgumentException("observedStates contains an invalid combination");
        }
        FunctionalImpact impact = FunctionalImpact.valueOf(
                node.path("functionalImpact").asText().toUpperCase(Locale.ROOT));
        RiskLevel risk = RiskLevel.valueOf(
                node.path("safetyRisk").asText().toUpperCase(Locale.ROOT));
        if (!node.path("evidence").isArray() || node.path("evidence").size() > 3) {
            throw new IllegalArgumentException("evidence must be an array with at most three items");
        }
        List<String> evidence = new java.util.ArrayList<>();
        node.path("evidence").forEach(value -> {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("evidence must contain strings");
            }
            evidence.add(value.asText());
        });
        double confidence = node.path("confidence").asDouble(Double.NaN);
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        String summary = node.path("summary").asText();
        EmotionLabel emotion = legacyEmotion(states, risk);
        double score = scoreForEmotion(emotion);
        return new PsychologyAssessment(
                emotion,
                score,
                risk,
                confidence,
                summary,
                states,
                impact,
                evidence);
    }

    private PsychologyAssessment heuristic(String input) {
        String normalized = input.toLowerCase();
        if (containsAny(normalized, "抑郁", "低落", "压抑", "崩溃", "难过", "depress", "hopeless")) {
            return new PsychologyAssessment(
                    EmotionLabel.DEPRESSED,
                    3.0,
                    RiskLevel.MEDIUM,
                    0.75,
                    "Low mood keywords detected.",
                    List.of(ObservedState.LOW_MOOD),
                    FunctionalImpact.SIGNIFICANT,
                    List.of("命中持续低落相关表达"));
        }
        if (containsAny(normalized, "焦虑", "压力", "睡不着", "失眠", "anxious", "stress", "insomnia")) {
            return new PsychologyAssessment(
                    EmotionLabel.ANXIETY,
                    2.0,
                    RiskLevel.LOW,
                    0.72,
                    "Anxiety or pressure keywords detected.",
                    List.of(ObservedState.ANXIOUS),
                    FunctionalImpact.MILD,
                    List.of("命中焦虑、压力或睡眠困难相关表达"));
        }
        return new PsychologyAssessment(
                EmotionLabel.NORMAL,
                0.0,
                RiskLevel.NONE,
                0.66,
                "No obvious risk signal.",
                List.of(ObservedState.NEUTRAL),
                FunctionalImpact.NONE,
                List.of());
    }

    private void requireFields(JsonNode node, String... names) {
        for (String name : names) {
            if (!node.has(name) || node.path(name).isNull()) {
                throw new IllegalArgumentException("Missing required assessment field: " + name);
            }
        }
    }

    private EmotionLabel legacyEmotion(List<ObservedState> states, RiskLevel risk) {
        if (risk == RiskLevel.HIGH) {
            return EmotionLabel.HIGH_RISK;
        }
        if (states.contains(ObservedState.LOW_MOOD) || states.contains(ObservedState.HOPELESS)) {
            return EmotionLabel.DEPRESSED;
        }
        if (states.stream().anyMatch(state -> state == ObservedState.ANXIOUS
                || state == ObservedState.SLEEP_DIFFICULTY
                || state == ObservedState.OVERWHELMED
                || state == ObservedState.AGITATED)) {
            return EmotionLabel.ANXIETY;
        }
        return EmotionLabel.NORMAL;
    }

    private double scoreForEmotion(EmotionLabel emotion) {
        return switch (emotion) {
            case HIGH_RISK -> 4.0;
            case DEPRESSED -> 3.0;
            case ANXIETY -> 2.0;
            case NORMAL -> 0.0;
        };
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
