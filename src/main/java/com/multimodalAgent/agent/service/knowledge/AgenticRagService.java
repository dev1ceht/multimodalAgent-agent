package com.multimodalAgent.agent.service.knowledge;

import com.fasterxml.jackson.core.JsonParser;
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
import com.multimodalAgent.agent.service.observability.OperationalMetrics;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/**
 * Agentic RAG 编排模块。
 *
 * <p>这里只负责查询改写、证据去重和证据审核；具体检索后端隐藏在
 * {@link EvidenceRetriever} interface 后面。</p>
 */
@Service
public class AgenticRagService {

    private final EvidenceRetriever evidenceRetriever;
    private final multimodalAgentProperties properties;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;
    private final EvaluationTraceService evaluationTraceService;
    private final EvidenceQualityPolicy evidenceQualityPolicy;
    private final OperationalMetrics operationalMetrics;

    public AgenticRagService(
            EvidenceRetriever evidenceRetriever,
            multimodalAgentProperties properties,
            AiClient aiClient,
            ObjectMapper objectMapper,
            EvaluationTraceService evaluationTraceService,
            EvidenceQualityPolicy evidenceQualityPolicy,
            OperationalMetrics operationalMetrics
    ) {
        this.evidenceRetriever = evidenceRetriever;
        this.properties = properties;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.evaluationTraceService = evaluationTraceService;
        this.evidenceQualityPolicy = evidenceQualityPolicy;
        this.operationalMetrics = operationalMetrics;
    }

    public AgenticRagResult retrieve(String userInput, List<AiMessage> history) {
        long started = System.nanoTime();
        try {
            QueryRewrite rewrite = rewrite(userInput, history);
            SearchBatch initial = search(rewrite.query(), properties.getKnowledge().getTopK());
            if (initial.status() == RetrievalStatus.FAILED) {
                recordTrace(rewrite, 0, initial);
                return new AgenticRagResult(
                        rewrite.query(),
                        List.of(rewrite.query()),
                        List.of(),
                        "知识库检索当前不可用，无法可靠判断证据是否充分。",
                        false,
                        RetrievalStatus.FAILED);
            }

            List<SearchResult> evidence = initial.evidence();
            RagReview review = enforceEvidenceQuality(review(userInput, evidence), evidence);

            List<SearchResult> answerEvidence = evidenceQualityPolicy.usableEvidence(evidence);
            SearchBatch finalBatch = new SearchBatch(initial.status(), answerEvidence, initial.reason());
            recordTrace(rewrite, 1, finalBatch);
            return new AgenticRagResult(
                    rewrite.query(),
                    List.of(rewrite.query()),
                    finalBatch.evidence(),
                    review.reason(),
                    review.sufficient() && initial.status() != RetrievalStatus.FAILED,
                    initial.status());
        } finally {
            evaluationTraceService.duration("ragMs", started);
        }
    }

    private void recordTrace(QueryRewrite rewrite, int reviewCount, SearchBatch result) {
        evaluationTraceService.put("ragQueryCount", rewrite.query().isBlank() ? 0 : 1);
        evaluationTraceService.put("ragRetrievalCallCount", rewrite.query().isBlank() ? 0 : 1);
        evaluationTraceService.put("ragReviewCount", reviewCount);
        evaluationTraceService.put("ragRetrievalStatus", result.status().name());
        boolean qualityAccepted = evidenceQualityPolicy.accepts(result.evidence());
        evaluationTraceService.put(
                "ragEvidenceQualityAccepted",
                qualityAccepted);
        operationalMetrics.recordEvidenceQuality(qualityAccepted);
        if (!result.reason().isBlank()) {
            evaluationTraceService.put("ragRetrievalReason", result.reason());
        }
        evaluationTraceService.put("ragEvidence", IntStream.range(0, result.evidence().size())
                .mapToObj(index -> {
                    SearchResult evidence = result.evidence().get(index);
                    EvidenceProvenance provenance = evidence.provenance();
                    Map<String, Object> trace = new LinkedHashMap<>();
                    trace.put("evidenceId", "E" + (index + 1));
                    trace.put("chunkId", evidence.chunkId() == null ? "" : evidence.chunkId());
                    trace.put("source", evidence.source() == null ? "" : evidence.source());
                    trace.put("score", evidence.score());
                    trace.put("knowledgeVersionKey", provenance.knowledgeVersionKey());
                    trace.put("vectorId", provenance.vectorId());
                    trace.put("sourceIndex", provenance.sourceIndex());
                    trace.put("parentKey", provenance.parentKey());
                    trace.put("childIndex", provenance.childIndex());
                    trace.put("sectionPath", provenance.sectionPath());
                    trace.put("startOffset", provenance.startOffset());
                    trace.put("endOffset", provenance.endOffset());
                    trace.put("pageStart", provenance.pageStart());
                    trace.put("pageEnd", provenance.pageEnd());
                    return trace;
                })
                .toList());
    }

    private RagReview enforceEvidenceQuality(RagReview review, List<SearchResult> evidence) {
        if (review.sufficient() && !evidenceQualityPolicy.accepts(evidence)) {
            return new RagReview(
                    false,
                    "检索证据相关性不足，无法支撑可靠回答。");
        }
        return review;
    }

    private QueryRewrite rewrite(String userInput, List<AiMessage> history) {
        String fallback = userInput == null ? "" : userInput.trim();
        try {
            String raw = aiClient.completeJson(
                    PromptTemplates.ragQueryRewritePrompt(history, fallback),
                    StructuredOutputSchemas.ragQueryRewrite());
            JsonNode node = readStrictJson(raw);
            if (!node.isObject()
                    || node.size() != 1
                    || !node.has("query")
                    || !node.path("query").isTextual()
                    || node.path("query").asText().isBlank()) {
                throw new IllegalArgumentException("RAG query rewrite is missing required fields");
            }
            evaluationTraceService.put("ragQueryRewriteJsonValid", true);
            return new QueryRewrite(node.path("query").asText().trim());
        } catch (Exception exception) {
            evaluationTraceService.put("ragQueryRewriteJsonValid", false);
            evaluationTraceService.put("ragQueryRewriteError", exception.getClass().getSimpleName());
            return new QueryRewrite(fallback);
        }
    }

    private RagReview review(String userInput, List<SearchResult> evidence) {
        try {
            String raw = aiClient.completeJson(
                    PromptTemplates.agenticRagReviewPrompt(userInput, evidence),
                    StructuredOutputSchemas.ragReview());
            JsonNode node = objectMapper.readTree(extractJson(raw));
            if (!node.isObject()
                    || node.size() != 2
                    || !node.has("sufficient")
                    || !node.path("sufficient").isBoolean()
                    || !node.has("reason")
                    || !node.path("reason").isTextual()
                    || node.path("reason").asText().isBlank()) {
                throw new IllegalArgumentException("RAG review is missing required fields");
            }
            evaluationTraceService.append("ragReviewJsonValid", true);
            return new RagReview(
                    node.path("sufficient").asBoolean(),
                    node.path("reason").asText().trim());
        } catch (Exception exception) {
            evaluationTraceService.append("ragReviewJsonValid", false);
            evaluationTraceService.append("ragReviewErrors", exception.getClass().getSimpleName());
            return new RagReview(false, "无法可靠完成证据复核。");
        }
    }

    private SearchBatch search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return new SearchBatch(RetrievalStatus.EMPTY, List.of(), "未提供可检索的问题。");
        }
        RetrievalResult result = evidenceRetriever.retrieve(new RetrievalQuery(query, topK));
        if (result.status() == RetrievalStatus.FAILED) {
            return new SearchBatch(RetrievalStatus.FAILED, List.of(), result.reason());
        }
        return new SearchBatch(
                result.status(),
                dedupe(result.evidence(), topK),
                result.reason());
    }

    private List<SearchResult> dedupe(List<SearchResult> results, int topK) {
        Map<String, SearchResult> best = new LinkedHashMap<>();
        for (SearchResult result : results) {
            String key = !result.provenance().parentKey().isBlank()
                    ? "parent:" + result.provenance().parentKey()
                    : result.chunkId() == null
                    ? result.source() + ":" + result.content()
                    : "id:" + result.chunkId();
            SearchResult previous = best.get(key);
            if (previous == null || result.score() > previous.score()) {
                best.put(key, result);
            }
        }
        List<SearchResult> ranked = best.values().stream()
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(topK)
                .toList();
        int budget = Math.max(1, properties.getKnowledge().getEvidenceCharacterBudget());
        List<SearchResult> budgeted = new ArrayList<>();
        int used = 0;
        for (SearchResult result : ranked) {
            if (used + result.content().length() > budget) {
                continue;
            }
            budgeted.add(result);
            used += result.content().length();
        }
        return budgeted;
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

    private JsonNode readStrictJson(String raw) throws IOException {
        String json = raw == null ? "" : raw.trim();
        if (json.isBlank()) {
            throw new IllegalArgumentException("RAG query rewrite is blank");
        }
        try (JsonParser parser = objectMapper.getFactory().createParser(json)) {
            JsonNode node = objectMapper.readTree(parser);
            if (node == null || parser.nextToken() != null) {
                throw new IllegalArgumentException("RAG query rewrite must be a single JSON value");
            }
            return node;
        }
    }

    private record QueryRewrite(String query) {
    }

    private record RagReview(boolean sufficient, String reason) {
    }

    private record SearchBatch(RetrievalStatus status, List<SearchResult> evidence, String reason) {
        private SearchBatch {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            reason = reason == null ? "" : reason;
        }
    }
}
