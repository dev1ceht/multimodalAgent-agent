package com.multimodalAgent.agent.service.knowledge.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeChunk;
import com.multimodalAgent.agent.domain.KnowledgeVersion;
import com.multimodalAgent.agent.domain.KnowledgeVersionChunk;
import com.multimodalAgent.agent.domain.KnowledgeVersionStatus;
import com.multimodalAgent.agent.repository.KnowledgeChunkRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionChunkRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionRepository;
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

/** 知识版本检索模块；旧 KnowledgeChunk 仅作为显式 local baseline 的兼容来源。 */
@Component
public class KnowledgeRetriever implements EvidenceRetriever {

    private static final int MAX_CHROMA_CANDIDATES = 100;

    private final KnowledgeChunkRepository legacyChunkRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final KnowledgeVersionChunkRepository versionChunkRepository;
    private final multimodalAgentProperties properties;
    private final ChromaGateway chromaGateway;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final EvaluationTraceService evaluationTraceService;
    private final EvidenceReranker evidenceReranker;
    private final TokenVectorizer vectorizer = new TokenVectorizer();

    public KnowledgeRetriever(
            KnowledgeChunkRepository legacyChunkRepository,
            KnowledgeVersionRepository versionRepository,
            KnowledgeVersionChunkRepository versionChunkRepository,
            multimodalAgentProperties properties,
            ChromaGateway chromaGateway,
            EmbeddingClient embeddingClient,
            ObjectMapper objectMapper,
            EvaluationTraceService evaluationTraceService,
            EvidenceReranker evidenceReranker
    ) {
        this.legacyChunkRepository = legacyChunkRepository;
        this.versionRepository = versionRepository;
        this.versionChunkRepository = versionChunkRepository;
        this.properties = properties;
        this.chromaGateway = chromaGateway;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
        this.evaluationTraceService = evaluationTraceService;
        this.evidenceReranker = evidenceReranker;
    }

    @Override
    public RetrievalResult retrieve(RetrievalQuery request) {
        long started = System.nanoTime();
        try {
            RetrievalMode mode = RetrievalMode.parse(properties.getKnowledge().getRetrievalMode());
            KnowledgeVersion activeVersion = versionRepository
                    .findTopByStatusOrderByActivatedAtDesc(KnowledgeVersionStatus.ACTIVE)
                    .orElse(null);
            if (mode == RetrievalMode.CHROMA_REQUIRED) {
                return retrieveFromChroma(request, activeVersion);
            }
            return retrieveFromLocalBaseline(request, activeVersion);
        } finally {
            evaluationTraceService.duration("retrievalMs", started);
        }
    }

    private RetrievalResult retrieveFromChroma(RetrievalQuery request, KnowledgeVersion activeVersion) {
        if (!properties.getKnowledge().isUseChroma()) {
            return failedOrThrow(
                    "chroma",
                    "RAG retrieval mode requires Chroma, but Chroma is disabled.",
                    null);
        }
        if (activeVersion == null) {
            return failedOrThrow(
                    "chroma",
                    "No ACTIVE knowledge version is available.",
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
            int candidateTopK = candidateTopK(request.topK());
            List<SearchResult> candidates = chromaGateway.query(
                    activeVersion.getCollectionName(),
                    queryEmbedding,
                    candidateTopK);
            List<SearchResult> ranked = properties.getKnowledge().isRerankEnabled()
                    ? evidenceReranker.rerank(request.text(), candidates, request.topK())
                    : candidates.stream().limit(request.topK()).toList();
            List<SearchResult> results = expandVersionContext(
                    activeVersion.getId(),
                    ranked,
                    request.topK());
            return tracedResult(
                    "chroma",
                    results.isEmpty() ? RetrievalStatus.EMPTY : RetrievalStatus.READY,
                    results,
                    results.isEmpty()
                            ? "检索完成，但没有找到相关证据。"
                            : "version=" + activeVersion.getVersionKey());
        } catch (RuntimeException exception) {
            return failedOrThrow("chroma", "Chroma retrieval failed.", exception);
        }
    }

    private int candidateTopK(int finalTopK) {
        if (!properties.getKnowledge().isRerankEnabled()) {
            return finalTopK;
        }
        int multiplier = Math.max(1, properties.getKnowledge().getRerankCandidateMultiplier());
        long expanded = (long) finalTopK * multiplier;
        return (int) Math.min(MAX_CHROMA_CANDIDATES, expanded);
    }

    private RetrievalResult retrieveFromLocalBaseline(RetrievalQuery request, KnowledgeVersion activeVersion) {
        if (activeVersion != null) {
            return retrieveActiveVersionLocally(request, activeVersion);
        }
        return retrieveLegacyLocally(request);
    }

    private RetrievalResult retrieveActiveVersionLocally(
            RetrievalQuery request,
            KnowledgeVersion activeVersion
    ) {
        List<Double> queryEmbedding = safeEmbedding(request.text());
        List<SearchResult> embeddingResults = versionChunkRepository
                .findByKnowledgeVersionIdOrderBySourceAscSourceIndexAsc(activeVersion.getId())
                .stream()
                .map(chunk -> new SearchResult(
                        chunk.getId(),
                        chunk.getSource(),
                        chunk.getContent(),
                        cosine(queryEmbedding, parseEmbedding(chunk.getEmbeddingJson()))))
                .filter(result -> result.score() > 0.0)
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(request.topK())
                .toList();
        if (!embeddingResults.isEmpty()) {
            return tracedResult(
                    "version_database_embedding",
                    RetrievalStatus.READY,
                    expandVersionContext(activeVersion.getId(), embeddingResults, request.topK()),
                    "version=" + activeVersion.getVersionKey());
        }

        List<SearchResult> ranked = versionChunkRepository
                .findByKnowledgeVersionIdOrderBySourceAscSourceIndexAsc(activeVersion.getId())
                .stream()
                .map(chunk -> new SearchResult(
                        chunk.getId(),
                        chunk.getSource(),
                        chunk.getContent(),
                        hybridScore(request.text(), chunk.getContent())))
                .filter(result -> result.score() > 0.0)
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(request.topK())
                .toList();
        List<SearchResult> expanded = expandVersionContext(activeVersion.getId(), ranked, request.topK());
        return tracedResult(
                "version_local_baseline",
                expanded.isEmpty() ? RetrievalStatus.EMPTY : RetrievalStatus.READY,
                expanded,
                expanded.isEmpty()
                        ? "本地 baseline 检索完成，但没有找到相关证据。"
                        : "version=" + activeVersion.getVersionKey());
    }

    private RetrievalResult retrieveLegacyLocally(RetrievalQuery request) {
        List<Double> queryEmbedding = safeEmbedding(request.text());
        List<SearchResult> ranked = legacyChunkRepository.findAll().stream()
                .map(chunk -> new SearchResult(
                        chunk.getId(),
                        chunk.getSource(),
                        chunk.getContent(),
                        queryEmbedding.isEmpty()
                                ? hybridScore(request.text(), chunk.getContent())
                                : cosine(queryEmbedding, parseEmbedding(chunk.getEmbeddingJson()))))
                .filter(result -> result.score() > 0.0)
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(request.topK())
                .toList();
        List<SearchResult> expanded = expandLegacyContext(ranked, request.topK());
        return tracedResult(
                "legacy_local_baseline",
                expanded.isEmpty() ? RetrievalStatus.EMPTY : RetrievalStatus.READY,
                expanded,
                expanded.isEmpty() ? "旧格式本地 baseline 没有找到相关证据。" : "");
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

    private List<SearchResult> expandVersionContext(Long versionId, List<SearchResult> ranked, int topK) {
        if (ranked.isEmpty()) {
            return ranked;
        }
        SearchResult best = ranked.get(0);
        SearchResult expanded = expandVersion(best, versionId);
        List<SearchResult> results = new ArrayList<>();
        results.add(expanded);
        ranked.stream()
                .skip(1)
                .filter(result -> !sameChunk(result, expanded))
                .limit(Math.max(0, topK - 1))
                .forEach(results::add);
        return results;
    }

    private SearchResult expandVersion(SearchResult result, Long versionId) {
        if (result.chunkId() == null) {
            return result;
        }
        return versionChunkRepository.findById(result.chunkId())
                .map(chunk -> {
                    List<KnowledgeVersionChunk> neighbors = versionChunkRepository
                            .findByKnowledgeVersionIdAndSourceAndSourceIndexBetweenOrderBySourceIndexAsc(
                                    versionId,
                                    chunk.getSource(),
                                    Math.max(0, chunk.getSourceIndex() - 1),
                                    chunk.getSourceIndex() + 1);
                    String expandedContent = String.join("\n\n", neighbors.stream()
                            .map(KnowledgeVersionChunk::getContent)
                            .toList());
                    return new SearchResult(chunk.getId(), chunk.getSource(), expandedContent, result.score());
                })
                .orElse(result);
    }

    private List<SearchResult> expandLegacyContext(List<SearchResult> ranked, int topK) {
        if (ranked.isEmpty()) {
            return ranked;
        }
        SearchResult best = ranked.get(0);
        SearchResult expanded = expandLegacy(best);
        List<SearchResult> results = new ArrayList<>();
        results.add(expanded);
        ranked.stream()
                .skip(1)
                .filter(result -> !sameChunk(result, expanded))
                .limit(Math.max(0, topK - 1))
                .forEach(results::add);
        return results;
    }

    private SearchResult expandLegacy(SearchResult result) {
        if (result.chunkId() == null) {
            return result;
        }
        return legacyChunkRepository.findById(result.chunkId())
                .map(chunk -> {
                    List<KnowledgeChunk> neighbors = legacyChunkRepository
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
