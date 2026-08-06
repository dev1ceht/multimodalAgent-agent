package com.multimodalAgent.agent.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.ai.PromptTemplates;
import com.multimodalAgent.agent.service.ai.StructuredOutputSchemas;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import com.multimodalAgent.agent.service.knowledge.retrieval.EvidenceRetriever;
import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalQuery;
import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalResult;
import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Agentic RAG 编排模块。
 *
 * <p>这里只负责查询规划、证据去重和证据审核；具体检索后端隐藏在
 * {@link EvidenceRetriever} interface 后面。</p>
 */
@Service
public class AgenticRagService {

    private static final int MAX_QUERIES = 3;

    private final EvidenceRetriever evidenceRetriever;
    private final multimodalAgentProperties properties;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;
    private final EvaluationTraceService evaluationTraceService;

    public AgenticRagService(
            EvidenceRetriever evidenceRetriever,
            multimodalAgentProperties properties,
            AiClient aiClient,
            ObjectMapper objectMapper,
            EvaluationTraceService evaluationTraceService
    ) {
        this.evidenceRetriever = evidenceRetriever;
        this.properties = properties;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.evaluationTraceService = evaluationTraceService;
    }

    public AgenticRagResult retrieve(String userInput, List<AiMessage> history) {
        long started = System.nanoTime();
        try {
            RagPlan plan = plan(userInput, history);
            SearchBatch initial = search(plan.queries(), properties.getKnowledge().getTopK());
            if (initial.status() == RetrievalStatus.FAILED) {
                recordTrace(plan, 0, initial);
                return new AgenticRagResult(
                        plan.reason(),
                        plan.queries(),
                        List.of(),
                        "知识库检索当前不可用，无法可靠判断证据是否充分。",
                        false,
                        RetrievalStatus.FAILED);
            }

            List<SearchResult> evidence = initial.evidence();
            RagReview review = review(userInput, evidence);
            int reviewCount = 1;
            RetrievalStatus status = initial.status();

            if (!review.sufficient() && !review.followUpQueries().isEmpty()) {
                SearchBatch followUp = search(review.followUpQueries(), properties.getKnowledge().getTopK());
                if (followUp.status() == RetrievalStatus.FAILED) {
                    status = evidence.isEmpty() ? RetrievalStatus.FAILED : RetrievalStatus.DEGRADED;
                    review = new RagReview(false, "补充检索当前不可用，现有证据不足以支持完整回答。", List.of());
                } else {
                    List<SearchResult> expanded = new ArrayList<>(evidence);
                    expanded.addAll(followUp.evidence());
                    evidence = dedupe(expanded, properties.getKnowledge().getTopK());
                    review = review(userInput, evidence);
                    reviewCount++;
                    status = mergeStatus(status, followUp.status());
                }
            }

            SearchBatch finalBatch = new SearchBatch(status, evidence, initial.reason());
            recordTrace(plan, reviewCount, finalBatch);
            return new AgenticRagResult(
                    plan.reason(),
                    plan.queries(),
                    evidence,
                    review.reason(),
                    review.sufficient() && status != RetrievalStatus.FAILED,
                    status);
        } finally {
            evaluationTraceService.duration("ragMs", started);
        }
    }

    private void recordTrace(RagPlan plan, int reviewCount, SearchBatch result) {
        evaluationTraceService.put("ragQueryCount", plan.queries().size());
        evaluationTraceService.put("ragReviewCount", reviewCount);
        evaluationTraceService.put("ragRetrievalStatus", result.status().name());
        if (!result.reason().isBlank()) {
            evaluationTraceService.put("ragRetrievalReason", result.reason());
        }
        evaluationTraceService.put("ragEvidence", result.evidence().stream()
                .map(evidence -> Map.of(
                        "chunkId", evidence.chunkId() == null ? "" : evidence.chunkId(),
                        "source", evidence.source(),
                        "score", evidence.score()))
                .toList());
    }

    private RagPlan plan(String userInput, List<AiMessage> history) {
        try {
            String raw = aiClient.completeJson(
                    PromptTemplates.agenticRagPlanPrompt(history, userInput),
                    StructuredOutputSchemas.ragPlan());
            JsonNode node = objectMapper.readTree(extractJson(raw));
            if (!node.isObject()
                    || node.size() != 2
                    || !node.has("reason")
                    || !node.path("reason").isTextual()
                    || node.path("reason").asText().isBlank()
                    || !node.has("queries")
                    || !node.path("queries").isArray()) {
                throw new IllegalArgumentException("RAG plan is missing required fields");
            }
            List<String> queries = jsonStrings(node.path("queries"));
            if (queries.size() < 2 || queries.size() > MAX_QUERIES) {
                throw new IllegalArgumentException("RAG plan must contain two or three queries");
            }
            evaluationTraceService.put("ragPlanJsonValid", true);
            return new RagPlan(node.path("reason").asText().trim(), queries);
        } catch (Exception exception) {
            evaluationTraceService.put("ragPlanJsonValid", false);
            evaluationTraceService.put("ragPlanError", exception.getClass().getSimpleName());
            String fallback = userInput == null ? "" : userInput;
            return new RagPlan("模型规划失败，使用用户原问题直接检索。", List.of(fallback));
        }
    }

    private RagReview review(String userInput, List<SearchResult> evidence) {
        try {
            String raw = aiClient.completeJson(
                    PromptTemplates.agenticRagReviewPrompt(userInput, evidence),
                    StructuredOutputSchemas.ragReview());
            JsonNode node = objectMapper.readTree(extractJson(raw));
            if (!node.isObject()
                    || node.size() != 3
                    || !node.has("sufficient")
                    || !node.path("sufficient").isBoolean()
                    || !node.has("reason")
                    || !node.path("reason").isTextual()
                    || node.path("reason").asText().isBlank()
                    || !node.has("followUpQueries")
                    || !node.path("followUpQueries").isArray()) {
                throw new IllegalArgumentException("RAG review is missing required fields");
            }
            List<String> followUpQueries = jsonStrings(node.path("followUpQueries"));
            if (followUpQueries.size() > 2
                    || (!node.path("sufficient").asBoolean() && followUpQueries.isEmpty())) {
                throw new IllegalArgumentException("RAG review contains invalid follow-up queries");
            }
            evaluationTraceService.append("ragReviewJsonValid", true);
            return new RagReview(
                    node.path("sufficient").asBoolean(),
                    node.path("reason").asText().trim(),
                    followUpQueries);
        } catch (Exception exception) {
            evaluationTraceService.append("ragReviewJsonValid", false);
            evaluationTraceService.append("ragReviewErrors", exception.getClass().getSimpleName());
            return new RagReview(false, "无法可靠完成证据复核。", List.of());
        }
    }

    private SearchBatch search(List<String> queries, int topK) {
        List<SearchResult> merged = new ArrayList<>();
        RetrievalStatus status = RetrievalStatus.EMPTY;
        String reason = "";
        for (String query : queries) {
            if (query == null || query.isBlank()) {
                continue;
            }
            RetrievalResult result = evidenceRetriever.retrieve(new RetrievalQuery(query, topK));
            if (result.status() == RetrievalStatus.FAILED) {
                return new SearchBatch(RetrievalStatus.FAILED, List.of(), result.reason());
            }
            merged.addAll(result.evidence());
            status = mergeStatus(status, result.status());
            if (!result.reason().isBlank()) {
                reason = result.reason();
            }
        }
        return new SearchBatch(status, dedupe(merged, topK), reason);
    }

    private RetrievalStatus mergeStatus(RetrievalStatus left, RetrievalStatus right) {
        if (left == RetrievalStatus.FAILED || right == RetrievalStatus.FAILED) {
            return RetrievalStatus.FAILED;
        }
        if (left == RetrievalStatus.DEGRADED || right == RetrievalStatus.DEGRADED) {
            return RetrievalStatus.DEGRADED;
        }
        if (left == RetrievalStatus.READY || right == RetrievalStatus.READY) {
            return RetrievalStatus.READY;
        }
        return RetrievalStatus.EMPTY;
    }

    private List<SearchResult> dedupe(List<SearchResult> results, int topK) {
        Map<String, SearchResult> best = new LinkedHashMap<>();
        for (SearchResult result : results) {
            String key = result.chunkId() == null
                    ? result.source() + ":" + result.content()
                    : "id:" + result.chunkId();
            SearchResult previous = best.get(key);
            if (previous == null || result.score() > previous.score()) {
                best.put(key, result);
            }
        }
        return best.values().stream()
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(topK)
                .toList();
    }

    private List<String> jsonStrings(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        node.forEach(item -> {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("JSON array must contain strings");
            }
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return List.copyOf(values);
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private record RagPlan(String reason, List<String> queries) {
    }

    private record RagReview(boolean sufficient, String reason, List<String> followUpQueries) {
    }

    private record SearchBatch(RetrievalStatus status, List<SearchResult> evidence, String reason) {
        private SearchBatch {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            reason = reason == null ? "" : reason;
        }
    }
}
