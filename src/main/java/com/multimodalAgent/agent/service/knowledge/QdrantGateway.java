package com.multimodalAgent.agent.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Qdrant REST adapter for immutable knowledge-version collections. */
@Component
public class QdrantGateway {
    private final WebClient webClient;

    public QdrantGateway(multimodalAgentProperties properties, WebClient.Builder builder) {
        this.webClient = builder.clone()
                .baseUrl(properties.getKnowledge().getQdrantBaseUrl())
                .build();
    }

    public List<SearchResult> vectorSearch(QdrantQuery query) {
        JsonNode response = webClient.post()
                .uri("/collections/{collection}/points/query", safeName(query.indexName()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "query", query.queryVector(),
                        "limit", query.resultSize(),
                        "with_payload", true,
                        "with_vector", false))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        JsonNode points = response == null ? null : response.path("result").path("points");
        if (points == null || !points.isArray()) {
            points = response == null ? null : response.path("result");
        }
        if (points == null || !points.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(points.spliterator(), false)
                .map(this::toSearchResult)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public void prepareVersionIndex(String collectionName, int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Qdrant vector dimensions must be positive.");
        }
        String collection = safeName(collectionName);
        webClient.delete().uri("/collections/{collection}", collection)
                .exchangeToMono(response -> response.statusCode().value() == 404
                        ? Mono.empty() : response.releaseBody())
                .block();
        webClient.put().uri("/collections/{collection}", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("vectors", Map.of("size", dimensions, "distance", "Cosine")))
                .retrieve().toBodilessEntity().block();
    }

    public void indexVersionChunk(
            String collectionName, String vectorId, Long chunkId, String versionKey,
            String source, int sourceIndex, String content, List<Double> embedding
    ) {
        indexVersionChunk(collectionName, vectorId, chunkId, versionKey, source, sourceIndex,
                content, content, "", -1, "", -1, -1, null, null, embedding);
    }

    public void indexVersionChunk(
            String collectionName, String vectorId, Long chunkId, String versionKey,
            String source, int sourceIndex, String content, String searchText,
            String parentKey, int childIndex, String sectionPath, int startOffset,
            int endOffset, Integer pageStart, Integer pageEnd, List<Double> embedding
    ) {
        if (vectorId == null || vectorId.isBlank() || chunkId == null || source == null
                || source.isBlank() || content == null || content.isBlank()
                || embedding == null || embedding.isEmpty()) {
            throw new IllegalArgumentException("Qdrant knowledge point is incomplete.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunk_id", chunkId);
        payload.put("version_key", versionKey);
        payload.put("vector_id", vectorId);
        payload.put("source", source);
        payload.put("source_index", sourceIndex);
        payload.put("content", content);
        payload.put("search_text", searchText);
        put(payload, "parent_key", parentKey);
        if (childIndex >= 0) payload.put("child_index", childIndex);
        put(payload, "section_path", sectionPath);
        if (startOffset >= 0) payload.put("start_offset", startOffset);
        if (endOffset >= 0) payload.put("end_offset", endOffset);
        if (pageStart != null) payload.put("page_start", pageStart);
        if (pageEnd != null) payload.put("page_end", pageEnd);
        Map<String, Object> point = Map.of(
                "id", UUID.nameUUIDFromBytes(vectorId.getBytes(StandardCharsets.UTF_8)).toString(),
                "vector", embedding,
                "payload", payload);
        webClient.put().uri("/collections/{collection}/points?wait=true", safeName(collectionName))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("points", List.of(point)))
                .retrieve().toBodilessEntity().block();
    }

    public long refreshAndCount(String collectionName) {
        JsonNode response = webClient.post()
                .uri("/collections/{collection}/points/count", safeName(collectionName))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("exact", true))
                .retrieve().bodyToMono(JsonNode.class).block();
        return response == null ? 0L : response.path("result").path("count").asLong(0L);
    }

    /** Active version is selected transactionally in MySQL; an external alias is unnecessary. */
    public void activateAlias(String collectionName, String aliasName) {
        safeName(collectionName);
        safeName(aliasName);
    }

    private SearchResult toSearchResult(JsonNode point) {
        JsonNode payload = point.path("payload");
        String content = payload.path("content").asText("");
        String source = payload.path("source").asText("");
        if (content.isBlank() || source.isBlank()) return null;
        EvidenceProvenance provenance = new EvidenceProvenance(
                payload.path("version_key").asText(""),
                payload.path("vector_id").asText(""),
                payload.path("source_index").asInt(-1),
                payload.path("parent_key").asText(""),
                payload.path("child_index").asInt(-1),
                payload.path("section_path").asText(""),
                payload.path("start_offset").asInt(-1),
                payload.path("end_offset").asInt(-1),
                payload.path("page_start").asInt(-1),
                payload.path("page_end").asInt(-1));
        return new SearchResult(
                payload.path("chunk_id").canConvertToLong() ? payload.path("chunk_id").asLong() : null,
                source, content, clamp(point.path("score").asDouble()), provenance);
    }

    private String safeName(String value) {
        String name = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9][a-z0-9._-]{0,254}")) {
            throw new IllegalArgumentException("Invalid Qdrant collection name.");
        }
        return name;
    }

    private double clamp(double score) {
        return Double.isFinite(score) ? Math.max(0.0, Math.min(1.0, score)) : 0.0;
    }

    private void put(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }
}
