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
import com.multimodalAgent.agent.repository.KnowledgeVersionSectionRepository;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import com.multimodalAgent.agent.service.knowledge.EmbeddingClient;
import com.multimodalAgent.agent.service.knowledge.ElasticsearchGateway;
import com.multimodalAgent.agent.service.knowledge.ElasticsearchHybridQuery;
import com.multimodalAgent.agent.service.knowledge.EvidenceProvenance;
import com.multimodalAgent.agent.service.knowledge.SearchResult;
import com.multimodalAgent.agent.service.knowledge.TokenVectorizer;
import com.multimodalAgent.agent.service.observability.OperationalMetrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 知识版本检索模块；旧 KnowledgeChunk 仅作为显式 local baseline 的兼容来源。 */
@Component
public class KnowledgeRetriever implements EvidenceRetriever {

    private final KnowledgeChunkRepository legacyChunkRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final KnowledgeVersionChunkRepository versionChunkRepository;
    private final KnowledgeVersionSectionRepository versionSectionRepository;
    private final multimodalAgentProperties properties;
    private final ElasticsearchGateway elasticsearchGateway;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final EvaluationTraceService evaluationTraceService;
    private final EvidenceReranker evidenceReranker;
    private final OperationalMetrics operationalMetrics;
    private final TokenVectorizer vectorizer = new TokenVectorizer();

    public KnowledgeRetriever(
            KnowledgeChunkRepository legacyChunkRepository,
            KnowledgeVersionRepository versionRepository,
            KnowledgeVersionChunkRepository versionChunkRepository,
            KnowledgeVersionSectionRepository versionSectionRepository,
            multimodalAgentProperties properties,
            ElasticsearchGateway elasticsearchGateway,
            EmbeddingClient embeddingClient,
            ObjectMapper objectMapper,
            EvaluationTraceService evaluationTraceService,
            EvidenceReranker evidenceReranker,
            OperationalMetrics operationalMetrics
    ) {
        this.legacyChunkRepository = legacyChunkRepository;
        this.versionRepository = versionRepository;
        this.versionChunkRepository = versionChunkRepository;
        this.versionSectionRepository = versionSectionRepository;
        this.properties = properties;
        this.elasticsearchGateway = elasticsearchGateway;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
        this.evaluationTraceService = evaluationTraceService;
        this.evidenceReranker = evidenceReranker;
        this.operationalMetrics = operationalMetrics;
    }

    @Override
    public RetrievalResult retrieve(RetrievalQuery request) {
        long started = System.nanoTime();
        try {
            RetrievalMode mode = RetrievalMode.parse(properties.getKnowledge().getRetrievalMode());
            KnowledgeVersion activeVersion = versionRepository
                    .findTopByStatusOrderByActivatedAtDesc(KnowledgeVersionStatus.ACTIVE)
                    .orElse(null);
            RetrievalResult result = switch (mode) {
                case ELASTICSEARCH_REQUIRED -> retrieveFromElasticsearch(request, activeVersion);
                case LOCAL_BASELINE -> retrieveFromLocalBaseline(request, activeVersion);
            };
            operationalMetrics.recordRetrieval(
                    result.backend(),
                    result.status(),
                    result.reason(),
                    System.nanoTime() - started);
            return result;
        } catch (RuntimeException exception) {
            operationalMetrics.recordRetrieval(
                    "unknown",
                    RetrievalStatus.FAILED,
                    exception.getMessage(),
                    System.nanoTime() - started);
            throw exception;
        } finally {
            evaluationTraceService.duration("retrievalMs", started);
        }
    }

    private RetrievalResult retrieveFromElasticsearch(
            RetrievalQuery request,
            KnowledgeVersion activeVersion
    ) {
        if (!properties.getKnowledge().isUseElasticsearch()) {
            return failedOrThrow(
                    "elasticsearch_rrf",
                    "RAG retrieval mode requires Elasticsearch, but Elasticsearch is disabled.",
                    null);
        }
        if (activeVersion == null) {
            return failedOrThrow(
                    "elasticsearch_rrf",
                    "No ACTIVE knowledge version is available.",
                    null);
        }
        if (!activeVersion.getEmbeddingModel().equals(embeddingClient.modelName())) {
            return failedOrThrow(
                    "elasticsearch_rrf",
                    "Query embedding model does not match the ACTIVE knowledge version.",
                    null);
        }
        List<Double> queryEmbedding = safeEmbedding(request.text());
        if (queryEmbedding.isEmpty()) {
            return failedOrThrow(
                    "elasticsearch_rrf",
                    "Elasticsearch KNN retrieval requires a configured embedding client.",
                    null);
        }
        if (queryEmbedding.size() != activeVersion.getEmbeddingDimensions()) {
            return failedOrThrow(
                    "elasticsearch_rrf",
                    "Query embedding dimensions do not match the ACTIVE knowledge version.",
                    null);
        }
        try {
            int candidateLimit = candidateTopK(request.topK());
            multimodalAgentProperties.Knowledge knowledge = properties.getKnowledge();
            ElasticsearchHybridQuery query = new ElasticsearchHybridQuery(
                    activeVersion.getCollectionName(),
                    request.text(),
                    queryEmbedding,
                    Math.max(candidateLimit, knowledge.getKnnK()),
                    Math.max(knowledge.getKnnNumCandidates(), knowledge.getKnnK()),
                    Math.max(
                            Math.max(candidateLimit, knowledge.getRrfRankWindowSize()),
                            knowledge.getKnnK()),
                    Math.max(0, knowledge.getRrfRankConstant()),
                    candidateLimit);
            List<SearchResult> candidates = elasticsearchGateway.hybridSearch(query).stream()
                    .map(result -> result.withProvenance(
                            result.provenance().withKnowledgeVersionKey(activeVersion.getVersionKey())))
                    .toList();
            int rankedLimit = isHierarchical(activeVersion) ? candidateLimit : request.topK();
            List<SearchResult> ranked = knowledge.isRerankEnabled()
                    ? evidenceReranker.rerank(request.text(), candidates, rankedLimit)
                    : candidates.stream().limit(rankedLimit).toList();
            List<SearchResult> results = expandVersionContext(activeVersion.getId(), ranked, request.topK());
            return tracedResult(
                    "elasticsearch_rrf",
                    results.isEmpty() ? RetrievalStatus.EMPTY : RetrievalStatus.READY,
                    results,
                    results.isEmpty()
                            ? "Elasticsearch hybrid retrieval completed without relevant evidence."
                            : "version=" + activeVersion.getVersionKey());
        } catch (RuntimeException exception) {
            return failedOrThrow(
                    "elasticsearch_rrf",
                    "Elasticsearch KNN + BM25 + RRF retrieval failed.",
                    exception);
        }
    }

    private int candidateTopK(int finalTopK) {
        if (!properties.getKnowledge().isRerankEnabled()) {
            return finalTopK;
        }
        int multiplier = Math.max(1, properties.getKnowledge().getRerankCandidateMultiplier());
        long expanded = (long) finalTopK * multiplier;
        return (int) Math.min(100, expanded);
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
                        cosine(queryEmbedding, parseEmbedding(chunk.getEmbeddingJson())),
                        provenance(activeVersion, chunk)))
                .filter(result -> result.score() > 0.0)
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(isHierarchical(activeVersion) ? candidateTopK(request.topK()) : request.topK())
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
                        hybridScore(request.text(), retrievalText(chunk)),
                        provenance(activeVersion, chunk)))
                .filter(result -> result.score() > 0.0)
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(isHierarchical(activeVersion) ? candidateTopK(request.topK()) : request.topK())
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
                                : cosine(queryEmbedding, parseEmbedding(chunk.getEmbeddingJson())),
                        new EvidenceProvenance("", "", chunk.getSourceIndex())))
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
                        .map(result -> traceResult(result))
                        .toList()));
        return new RetrievalResult(status, backend, results, reason);
    }

    private Map<String, Object> traceResult(SearchResult result) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("chunkId", result.chunkId() == null ? "" : result.chunkId());
        trace.put("source", result.source() == null ? "" : result.source());
        trace.put("score", result.score());
        trace.put("knowledgeVersionKey", result.provenance().knowledgeVersionKey());
        trace.put("vectorId", result.provenance().vectorId());
        trace.put("sourceIndex", result.provenance().sourceIndex());
        trace.put("parentKey", result.provenance().parentKey());
        trace.put("childIndex", result.provenance().childIndex());
        trace.put("sectionPath", result.provenance().sectionPath());
        trace.put("startOffset", result.provenance().startOffset());
        trace.put("endOffset", result.provenance().endOffset());
        trace.put("pageStart", result.provenance().pageStart());
        trace.put("pageEnd", result.provenance().pageEnd());
        return trace;
    }

    private List<SearchResult> expandVersionContext(Long versionId, List<SearchResult> ranked, int topK) {
        if (ranked.isEmpty()) {
            return ranked;
        }
        List<Long> chunkIds = ranked.stream()
                .map(SearchResult::chunkId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, KnowledgeVersionChunk> chunks = versionChunkRepository.findAllById(chunkIds).stream()
                .collect(Collectors.toMap(KnowledgeVersionChunk::getId, Function.identity()));
        if (chunks.values().stream().anyMatch(chunk -> chunk.getParentSectionId() != null)) {
            return expandHierarchicalContext(ranked, topK, chunks);
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

    private List<SearchResult> expandHierarchicalContext(
            List<SearchResult> ranked,
            int topK,
            Map<Long, KnowledgeVersionChunk> chunks
    ) {
        Map<Long, SearchResult> parents = new LinkedHashMap<>();
        List<Long> sectionIds = chunks.values().stream()
                .map(KnowledgeVersionChunk::getParentSectionId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, com.multimodalAgent.agent.domain.KnowledgeVersionSection> sections =
                versionSectionRepository.findAllById(sectionIds).stream()
                        .collect(Collectors.toMap(
                                com.multimodalAgent.agent.domain.KnowledgeVersionSection::getId,
                                Function.identity()));
        int budget = Math.max(1, properties.getKnowledge().getEvidenceCharacterBudget());
        int used = 0;
        for (SearchResult result : ranked) {
            if (parents.size() >= topK) {
                break;
            }
            KnowledgeVersionChunk chunk = result.chunkId() == null ? null : chunks.get(result.chunkId());
            if (chunk == null || chunk.getParentSectionId() == null
                    || parents.containsKey(chunk.getParentSectionId())) {
                continue;
            }
            var section = sections.get(chunk.getParentSectionId());
            if (section == null) {
                continue;
            }
            int remaining = budget - used;
            if (remaining <= 0) {
                break;
            }
            String content = section.getContent();
            if (content.length() > remaining) {
                if (!parents.isEmpty()) {
                    continue;
                }
                content = content.substring(0, remaining).stripTrailing();
            }
            EvidenceProvenance provenance = result.provenance().withParent(
                    section.getParentKey(),
                    chunk.getChildIndex() == null ? -1 : chunk.getChildIndex(),
                    section.getSectionPath(),
                    chunk.getStartOffset(),
                    chunk.getEndOffset(),
                    chunk.getPageStart(),
                    chunk.getPageEnd());
            parents.put(
                    chunk.getParentSectionId(),
                    new SearchResult(result.chunkId(), result.source(), content, result.score(), provenance));
            used += content.length();
        }
        return List.copyOf(parents.values());
    }

    private EvidenceProvenance provenance(KnowledgeVersion version, KnowledgeVersionChunk chunk) {
        return new EvidenceProvenance(version.getVersionKey(), chunk.getVectorId(), chunk.getSourceIndex())
                .withParent(
                        "",
                        chunk.getChildIndex() == null ? -1 : chunk.getChildIndex(),
                        "",
                        chunk.getStartOffset(),
                        chunk.getEndOffset(),
                        chunk.getPageStart(),
                        chunk.getPageEnd());
    }

    private String retrievalText(KnowledgeVersionChunk chunk) {
        return chunk.getSearchText() == null || chunk.getSearchText().isBlank()
                ? chunk.getContent()
                : chunk.getSearchText();
    }

    private boolean isHierarchical(KnowledgeVersion version) {
        return version != null && "HIERARCHICAL_V1".equalsIgnoreCase(version.getChunkingStrategy());
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
                    return result.withContent(expandedContent);
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
                    return result.withContent(expandedContent);
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
