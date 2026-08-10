package com.multimodalAgent.agent.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Elasticsearch HTTP adapter for versioned knowledge indexing and hybrid retrieval. */
@Component
public class ElasticsearchGateway {

    private static final int RRF_RETRIEVER_COUNT = 2;

    private final WebClient webClient;
    public ElasticsearchGateway(
            multimodalAgentProperties properties,
            WebClient.Builder webClientBuilder
    ) {
        this.webClient = webClientBuilder.clone()
                .baseUrl(properties.getKnowledge().getElasticsearchBaseUrl())
                .build();
    }

    public List<SearchResult> hybridSearch(ElasticsearchHybridQuery query) {
        JsonNode response = webClient.post()
                .uri("/{index}/_search", safeIndexName(query.indexName()))
                .bodyValue(searchRequest(query))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        if (response == null) {
            return List.of();
        }
        JsonNode hits = response.path("hits").path("hits");
        if (!hits.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(hits.spliterator(), false)
                .map(hit -> toSearchResult(hit, query.rankConstant()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public void prepareVersionIndex(String indexName, int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Elasticsearch vector dimensions must be positive.");
        }
        String safeIndex = safeIndexName(indexName);
        webClient.delete()
                .uri("/{index}", safeIndex)
                .retrieve()
                .onStatus(status -> status.value() == 404, response -> Mono.empty())
                .toBodilessEntity()
                .block();

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chunk_id", Map.of("type", "long"));
        fields.put("source", Map.of("type", "keyword"));
        fields.put("source_index", Map.of("type", "integer"));
        fields.put("content", Map.of("type", "text", "analyzer", "standard"));
        fields.put("content_vector", Map.of(
                "type", "dense_vector",
                "dims", dimensions,
                "index", true,
                "similarity", "cosine"));
        fields.put("version_key", Map.of("type", "keyword"));
        fields.put("vector_id", Map.of("type", "keyword"));
        Map<String, Object> body = Map.of(
                "mappings", Map.of(
                        "dynamic", "strict",
                        "properties", fields));
        webClient.put()
                .uri("/{index}", safeIndex)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    public void indexVersionChunk(
            String indexName,
            String vectorId,
            Long chunkId,
            String versionKey,
            String source,
            int sourceIndex,
            String content,
            List<Double> embedding
    ) {
        if (vectorId == null || vectorId.isBlank() || chunkId == null
                || versionKey == null || versionKey.isBlank()
                || source == null || source.isBlank()
                || content == null || content.isBlank()
                || embedding == null || embedding.isEmpty()) {
            throw new IllegalArgumentException("Elasticsearch knowledge chunk is incomplete.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chunk_id", chunkId);
        body.put("source", source);
        body.put("source_index", sourceIndex);
        body.put("content", content);
        body.put("content_vector", embedding);
        body.put("version_key", versionKey);
        body.put("vector_id", vectorId);
        webClient.put()
                .uri("/{index}/_doc/{id}", safeIndexName(indexName), vectorId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    public long refreshAndCount(String indexName) {
        String safeIndex = safeIndexName(indexName);
        webClient.post()
                .uri("/{index}/_refresh", safeIndex)
                .retrieve()
                .toBodilessEntity()
                .block();
        JsonNode response = webClient.get()
                .uri("/{index}/_count", safeIndex)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        return response == null ? 0L : response.path("count").asLong(0L);
    }

    public void activateAlias(String indexName, String aliasName) {
        String safeIndex = safeIndexName(indexName);
        String safeAlias = safeIndexName(aliasName);
        JsonNode aliases = webClient.get()
                .uri("/_alias/{alias}", safeAlias)
                .exchangeToMono(response -> {
                    if (response.statusCode().value() == 404) {
                        return Mono.empty();
                    }
                    if (response.statusCode().isError()) {
                        return response.createException().flatMap(Mono::error);
                    }
                    return response.bodyToMono(JsonNode.class);
                })
                .block();
        List<Map<String, Object>> actions = new ArrayList<>();
        if (aliases != null && aliases.isObject()) {
            aliases.fieldNames().forEachRemaining(existingIndex -> actions.add(Map.of(
                    "remove", Map.of("index", existingIndex, "alias", safeAlias))));
        }
        actions.add(Map.of("add", Map.of("index", safeIndex, "alias", safeAlias)));
        webClient.post()
                .uri("/_aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("actions", actions))
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    private Map<String, Object> searchRequest(ElasticsearchHybridQuery query) {
        Map<String, Object> matchContent = Map.of(
                "query", query.queryText());
        Map<String, Object> standard = Map.of(
                "standard", Map.of(
                        "query", Map.of(
                                "match", Map.of("content", matchContent))));
        Map<String, Object> knn = Map.of(
                "knn", Map.of(
                        "field", "content_vector",
                        "query_vector", query.queryVector(),
                        "k", query.knnK(),
                        "num_candidates", query.knnNumCandidates()));
        Map<String, Object> rrf = new LinkedHashMap<>();
        rrf.put("retrievers", List.of(standard, knn));
        rrf.put("rank_window_size", query.rankWindowSize());
        rrf.put("rank_constant", query.rankConstant());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("size", query.resultSize());
        request.put("_source", List.of(
                "chunk_id",
                "source",
                "source_index",
                "content",
                "version_key",
                "vector_id"));
        request.put("retriever", Map.of("rrf", rrf));
        return request;
    }

    private SearchResult toSearchResult(JsonNode hit, int rankConstant) {
        JsonNode source = hit.path("_source");
        if (!source.isObject()) {
            return null;
        }
        String content = source.path("content").asText("");
        String origin = source.path("source").asText("");
        if (content.isBlank() || origin.isBlank()) {
            return null;
        }
        Long chunkId = source.path("chunk_id").canConvertToLong()
                ? source.path("chunk_id").asLong()
                : null;
        String vectorId = source.path("vector_id").asText(hit.path("_id").asText(""));
        EvidenceProvenance provenance = new EvidenceProvenance(
                source.path("version_key").asText(""),
                vectorId,
                source.path("source_index").asInt(-1));
        return new SearchResult(
                chunkId,
                origin,
                content,
                normalizeRrfScore(hit.path("_score").asDouble(0.0), rankConstant),
                provenance);
    }

    private double normalizeRrfScore(double rawScore, int rankConstant) {
        if (!Double.isFinite(rawScore) || rawScore <= 0.0) {
            return 0.0;
        }
        double theoreticalMaximum = RRF_RETRIEVER_COUNT / (double) (rankConstant + 1);
        return Math.max(0.0, Math.min(1.0, rawScore / theoreticalMaximum));
    }

    private String safeIndexName(String indexName) {
        String normalized = indexName == null
                ? ""
                : indexName.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,254}")) {
            throw new IllegalArgumentException("Invalid Elasticsearch index name.");
        }
        return normalized;
    }
}
