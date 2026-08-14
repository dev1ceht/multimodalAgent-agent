package com.multimodalAgent.agent.service.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 请求路由模块。
 *
 * <p>该模块隐藏模型提示词、JSON 解析、风险硬兜底和多模态风险合并。
 * 调用方只需要消费一个不可变的 {@link RoutingDecision}。</p>
 */
public class RequestRouter {

    private final AiClient aiClient;
    private final ObjectMapper objectMapper;
    private final EvaluationTraceService evaluationTraceService;

    public RequestRouter(
            AiClient aiClient,
            ObjectMapper objectMapper,
            EvaluationTraceService evaluationTraceService
    ) {
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.evaluationTraceService = evaluationTraceService;
    }

    public RoutingDecision decide(
            String input,
            List<AiMessage> history,
            RiskLevel externalRisk
    ) {
        long started = System.nanoTime();
        try {
            RoutingDecision decision = decideInternal(input, history);
            if (!decision.needsRag() && MentalHealthTopicGate.matches(input, history)) {
                decision = decision.withNeedsRagFloor("命中心理健康知识主题兜底");
                evaluationTraceService.put("routingSource", "model_and_topic_gate");
            }
            if (externalRisk == RiskLevel.HIGH || externalRisk == RiskLevel.MEDIUM) {
                decision = decision.withRiskFloor(externalRisk, "多模态信号提高风险等级");
                evaluationTraceService.put("routingSource", "model_and_multimodal");
            }
            evaluationTraceService.put("needsRag", decision.needsRag());
            evaluationTraceService.put("riskLevel", decision.riskLevel().name());
            evaluationTraceService.put("routingConfidence", decision.confidence());
            evaluationTraceService.put("routingReason", decision.reason());
            return decision;
        } finally {
            evaluationTraceService.duration("routingMs", started);
        }
    }

    public RoutingDecision decide(String input, List<AiMessage> history) {
        return decide(input, history, RiskLevel.NONE);
    }

    private RoutingDecision decideInternal(String input, List<AiMessage> history) {
        String normalized = input.toLowerCase(Locale.ROOT);
        if (RiskLexicon.hasExplicitHighRiskSignal(normalized)) {
            evaluationTraceService.put("routingSource", "risk_lexicon");
            evaluationTraceService.put("routingJsonValid", true);
            return RoutingDecision.highRisk("命中明确的自伤、伤人或即时危险信号");
        }

        try {
            String raw = aiClient.completeJson(
                    PromptTemplates.routingPrompt(history, input),
                    StructuredOutputSchemas.routing());
            RoutingDecision decision = parse(raw);
            evaluationTraceService.put("routingSource", "model");
            evaluationTraceService.put("routingJsonValid", true);
            return decision;
        } catch (Exception exception) {
            evaluationTraceService.put("routingSource", "heuristic_fallback");
            evaluationTraceService.put("routingJsonValid", false);
            evaluationTraceService.put("routingError", exception.getClass().getSimpleName());
            return fallback(normalized, history);
        }
    }

    private RoutingDecision parse(String raw) throws Exception {
        String json = raw == null ? "{}" : raw.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }
        JsonNode node = objectMapper.readTree(json);
        if (node.size() != 4
                || !node.has("needsRag")
                || !node.has("riskLevel")
                || !node.has("confidence")
                || !node.has("reason")
                || !node.path("needsRag").isBoolean()
                || !node.path("confidence").isNumber()
                || !node.path("reason").isTextual()) {
            throw new IllegalArgumentException("Routing JSON is missing required fields");
        }
        boolean needsRag = node.path("needsRag").asBoolean(false);
        RiskLevel riskLevel = RiskLevel.valueOf(
                node.path("riskLevel").asText("NONE").toUpperCase(Locale.ROOT));
        double confidence = node.path("confidence").asDouble(Double.NaN);
        String reason = node.path("reason").asText().trim();
        if (!Double.isFinite(confidence)
                || confidence < 0.0
                || confidence > 1.0
                || reason.isBlank()) {
            throw new IllegalArgumentException("Routing JSON contains invalid values");
        }
        return new RoutingDecision(needsRag, riskLevel, confidence, reason);
    }

    private RoutingDecision fallback(String input, List<AiMessage> history) {
        if (RiskLexicon.hasConsultSignal(input) || hasRecentConsultContext(history)) {
            return new RoutingDecision(true, RiskLevel.LOW, 0.55, "心理支持关键词兜底");
        }
        return new RoutingDecision(false, RiskLevel.NONE, 0.50, "未发现心理支持或知识库需求");
    }

    private boolean hasRecentConsultContext(List<AiMessage> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        return history.stream()
                .skip(Math.max(0, history.size() - 6))
                .map(message -> message.content().toLowerCase(Locale.ROOT))
                .anyMatch(RiskLexicon::hasConsultSignal);
    }
}
