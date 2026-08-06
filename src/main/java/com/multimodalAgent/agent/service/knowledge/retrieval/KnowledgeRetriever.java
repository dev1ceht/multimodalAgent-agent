package com.multimodalAgent.agent.service.knowledge.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeChunk;
import com.multimodalAgent.agent.repository.KnowledgeChunkRepository;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import com.multimodalAgent.agent.service.knowledge.ChromaGateway;
import com.multimodalAgent.agent.service.knowledge.EmbeddingClient;
import com.multimodalAgent.agent.service.knowledge.SearchResult;
import com.multimodalAgent.agent.service.knowledge.TokenVectorizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 知识库检索模块的实现。
 *
 * <p>Chroma 和 local baseline 都隐藏在这个模块之后。上层只面对
 * {@link EvidenceRetriever}，因此后续可以替换检索适配器而不改 Agentic RAG 编排。</p>
 */
@Component
public class KnowledgeRetriever implements EvidenceRetriever {

    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final multimodalAgentProperties properties;
    private final ChromaGateway chromaGateway;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final EvaluationTraceService evaluationTraceService;
    private final TokenVectorizer vectorizer = new TokenVectorizer();

    public KnowledgeRetriever(
            KnowledgeChunkRepository knowledgeChunkRepository,
            multimodalAgentProperties properties,
            ChromaGateway chromaGateway,
            EmbeddingClient embeddingClient,
            ObjectMapper objectMapper,
            EvaluationTraceService evaluationTraceService
    ) {
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.properties = properties;
        this.chromaGateway = chromaGateway;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
        this.evaluationTraceService = evaluationTraceService;
    }

    @Override
    public RetrievalResult retrieve(RetrievalQuery request) {
        long started = System.nanoTime();
        try {
            RetrievalMode mode = RetrievalMode.parse(properties.getKnowledge().getRetrievalMode());
            if (mode == RetrievalMode.CHROMA_REQUIRED) {
                return retrieveFromChroma(request);
            }
            return retrieveFromLocalBaseline(request);
        } finally {
            evaluationTraceService.duration("retrievalMs", started);
        }
    }

    private RetrievalResult retrieveFromChroma(RetrievalQuery request) {
        if (!properties.getKnowledge().isUseChroma()) {
            return failedOrThrow(
                    "chroma",
                    "RAG retrieval mode requires Chroma, but Chroma is disabled.",
                    null);
        }

        List<Double> queryEmbedding = safeEmbedding(request.text());
        if (queryEmbedding.isEmpty()) {
            return failedOrThrow(
                    "chroma",
                    "RAG retrieval requires a configured embedding client.",
                    null);
        }

        try {
            List<SearchResult> results = expandBestContext(
                    chromaGateway.query(queryEmbedding, request.topK()),
                    request.topK());
            return tracedResult(
                    "chroma",
                    results.isEmpty() ? RetrievalStatus.EMPTY : RetrievalStatus.READY,
                    results,
                    results.isEmpty() ? "检索完成，但没有找到相关证据。" : "");
        } catch (RuntimeException exception) {
            return failedOrThrow("chroma", "Chroma retrieval failed.", exception);
        }
    }

    private RetrievalResult retrieveFromLocalBaseline(RetrievalQuery request) {
        List<Double> queryEmbedding = safeEmbedding(request.text());
        List<SearchResult> embeddingResults = retrieveByEmbedding(queryEmbedding, request.topK());
        if (!embeddingResults.isEmpty()) {
            return tracedResult(
                    "database_embedding",
                    RetrievalStatus.READY,
                    expandBestContext(embeddingResults, request.topK()),
                    "");
        }

        List<SearchResult> ranked = knowledgeChunkRepository.findAll().stream()
                .map(chunk -> new SearchResult(
                        chunk.getId(),
                        chunk.getSource(),
                        chunk.getContent(),
                        hybridScore(request.text(), chunk.getContent())))
                .filter(result -> result.score() > 0.0)
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(request.topK())
                .toList();
        List<SearchResult> expanded = expandBestContext(ranked, request.topK());
        return tracedResult(
                "local_baseline",
                expanded.isEmpty() ? RetrievalStatus.EMPTY : RetrievalStatus.READY,
                expanded,
                expanded.isEmpty() ? "本地 baseline 检索完成，但没有找到相关证据。" : "");
    }

    private RetrievalResult failedOrThrow(String backend, String reason, RuntimeException cause) {
        if (properties.getEvaluation().isEnabled()) {
            if (cause == null) {
                throw new IllegalStateException(reason);
            }
            throw new IllegalStateException(reason, cause);
        }
        return tracedResult(backend, RetrievalStatus.FAILED, List.of(), reason);
    }

    private RetrievalResult tracedResult(
            String backend,
            RetrievalStatus status,
            List<SearchResult> results,
            String reason
    ) {
        evaluationTraceService.append("retrievals", Map.of(
                "backend", backend,
                "status", status.name(),
                "reason", reason,
                "results", results.stream()
                        .map(result -> Map.of(
                                "chunkId", result.chunkId() == null ? "" : result.chunkId(),
                                "source", result.source(),
                                "score", result.score()))
                        .toList()));
        return new RetrievalResult(status, backend, results, reason);
    }

    private List<SearchResult> retrieveByEmbedding(List<Double> queryEmbedding, int topK) {
        if (queryEmbedding.isEmpty()) {
            return List.of();
        }
        return knowledgeChunkRepository.findAll().stream()
                .map(chunk -> new SearchResult(
                        chunk.getId(),
                        chunk.getSource(),
                        chunk.getContent(),
                        cosine(queryEmbedding, parseEmbedding(chunk.getEmbeddingJson()))))
                .filter(result -> result.score() > 0.0)
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    private List<SearchResult> expandBestContext(List<SearchResult> ranked, int topK) {
        if (ranked.isEmpty()) {
            return ranked;
        }
        SearchResult best = ranked.get(0);
        SearchResult expanded = expand(best);
        List<SearchResult> results = new ArrayList<>();
        results.add(expanded);
        ranked.stream()
                .skip(1)
                .filter(result -> !sameChunk(result, expanded))
                .limit(Math.max(0, topK - 1))
                .forEach(results::add);
        return results;
    }

    private SearchResult expand(SearchResult result) {
        if (result.chunkId() == null) {
            return result;
        }
        return knowledgeChunkRepository.findById(result.chunkId())
                .map(chunk -> {
                    List<KnowledgeChunk> neighbors = knowledgeChunkRepository
                            .findBySourceAndSourceIndexBetweenOrderBySourceIndexAsc(
                                    chunk.getSource(),
                                    Math.max(0, chunk.getSourceIndex() - 1),
                                    chunk.getSourceIndex() + 1);
                    String expandedContent = String.join("\n\n", neighbors.stream()
                            .map(KnowledgeChunk::getContent)
                            .toList());
                    return new SearchResult(chunk.getId(), chunk.getSource(), expandedContent, result.score());
                })
                .orElse(result);
    }

    private boolean sameChunk(SearchResult result, SearchResult expanded) {
        return result.chunkId() != null && result.chunkId().equals(expanded.chunkId());
    }

    private List<Double> safeEmbedding(String text) {
        try {
            return embeddingClient.embed(text);
        } catch (Exception exception) {
            if (properties.getEvaluation().isEnabled()) {
                throw new IllegalStateException("Embedding API failed during evaluation.", exception);
            }
            return List.of();
        }
    }

    private List<Double> parseEmbedding(String embeddingJson) {
        if (embeddingJson == null || embeddingJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(embeddingJson, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.size(); i++) {
            double a = left.get(i);
            double b = right.get(i);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private double hybridScore(String query, String content) {
        double semantic = vectorizer.cosine(query, content);
        double keyword = keywordScore(query, content);
        return semantic * 0.75 + keyword * 0.25;
    }

    private double keywordScore(String query, String content) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        List<String> terms = List.of(normalizedQuery.split("[\\s，。！？，；：.!?;:]+"));
        long matched = terms.stream()
                .filter(term -> term.length() >= 2)
                .filter(normalizedContent::contains)
                .count();
        return terms.isEmpty() ? 0.0 : Math.min(1.0, matched / (double) terms.size());
    }
}
