package com.multimodalAgent.agent.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeChunk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** Chroma projection adapter，支持按知识版本使用独立 collection。 */
@Component
public class ChromaGateway {

    private static final String TENANT = "default_tenant";
    private static final String DATABASE = "default_database";

    private final multimodalAgentProperties properties;
    private final WebClient webClient;
    private final Map<String, String> collectionIds = new ConcurrentHashMap<>();

    public ChromaGateway(multimodalAgentProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.baseUrl(properties.getKnowledge().getChromaBaseUrl()).build();
    }

    public void mirror(KnowledgeChunk chunk, List<Double> embedding) {
        mirror(
                properties.getKnowledge().getChromaCollection(),
                String.valueOf(chunk.getId()),
                chunk.getId(),
                null,
                chunk.getSource(),
                chunk.getSourceIndex(),
                chunk.getContent(),
                embedding,
                "/add",
                false);
    }

    public void mirrorVersionChunk(
            String collectionName,
            String vectorId,
            Long chunkId,
            Long versionId,
            String source,
            int sourceIndex,
            String content,
            List<Double> embedding
    ) {
        mirror(
                collectionName,
                vectorId,
                chunkId,
                versionId,
                source,
                sourceIndex,
                content,
                embedding,
                "/upsert",
                true);
    }

    private void mirror(
            String collectionName,
            String vectorId,
            Long chunkId,
            Long versionId,
            String source,
            int sourceIndex,
            String content,
            List<Double> embedding,
            String operation,
            boolean strict
    ) {
        if (!properties.getKnowledge().isUseChroma()) {
            return;
        }
        if (embedding == null || embedding.isEmpty()) {
            if (properties.getEvaluation().isEnabled()) {
                throw new IllegalStateException("Evaluation requires explicit document embeddings for Chroma.");
            }
            return;
        }
        String ensuredCollectionId = ensureCollection(collectionName);
        if (ensuredCollectionId == null) {
            if (strict) {
                throw new IllegalStateException("Chroma collection is unavailable: " + collectionName);
            }
            return;
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("chunkId", chunkId == null ? "" : chunkId);
        metadata.put("knowledgeVersionId", versionId == null ? "" : versionId);
        metadata.put("source", source);
        metadata.put("sourceIndex", sourceIndex);
        Map<String, Object> body = Map.of(
                "ids", List.of(vectorId),
                "documents", List.of(content),
                "embeddings", List.of(embedding),
                "metadatas", List.of(metadata)
        );
        webClient.post()
                .uri(
                        "/api/v2/tenants/{tenant}/databases/{database}/collections/{collection}" + operation,
                        TENANT,
                        DATABASE,
                        ensuredCollectionId)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .onErrorResume(exception -> {
                    collectionIds.remove(collectionName, ensuredCollectionId);
                    if (strict || properties.getEvaluation().isEnabled()) {
                        return reactor.core.publisher.Mono.error(
                                new IllegalStateException("Chroma document mirror failed.", exception));
                    }
                    return reactor.core.publisher.Mono.empty();
                })
                .block();
    }

    public List<SearchResult> query(List<Double> queryEmbedding, int topK) {
        return query(properties.getKnowledge().getChromaCollection(), queryEmbedding, topK);
    }

    public List<SearchResult> query(String collectionName, List<Double> queryEmbedding, int topK) {
        if (!properties.getKnowledge().isUseChroma()) {
            return List.of();
        }
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            throw new IllegalArgumentException("Chroma query embedding must not be empty.");
        }
        String ensuredCollectionId = ensureCollection(collectionName);
        if (ensuredCollectionId == null) {
            throw new IllegalStateException("Chroma collection is unavailable.");
        }
        Map<String, Object> body = Map.of(
                "query_embeddings", List.of(queryEmbedding),
                "n_results", topK,
                "include", List.of("documents", "metadatas", "distances")
        );
        try {
            JsonNode response = webClient.post()
                    .uri(
                            "/api/v2/tenants/{tenant}/databases/{database}/collections/{collection}/query",
                            TENANT,
                            DATABASE,
                            ensuredCollectionId)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            return parseResults(response);
        } catch (Exception exception) {
            collectionIds.remove(collectionName, ensuredCollectionId);
            throw new IllegalStateException("Chroma query failed.", exception);
        }
    }

    public void deleteSource(String source) {
        deleteSource(properties.getKnowledge().getChromaCollection(), source);
    }

    private void deleteSource(String collectionName, String source) {
        if (!properties.getKnowledge().isUseChroma()) {
            return;
        }
        String ensuredCollectionId = ensureCollection(collectionName);
        if (ensuredCollectionId == null) {
            return;
        }
        Map<String, Object> body = Map.of("where", Map.of("source", source));
        webClient.post()
                .uri(
                        "/api/v2/tenants/{tenant}/databases/{database}/collections/{collection}/delete",
                        TENANT,
                        DATABASE,
                        ensuredCollectionId)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .onErrorResume(exception -> {
                    collectionIds.remove(collectionName, ensuredCollectionId);
                    if (properties.getEvaluation().isEnabled()) {
                        return reactor.core.publisher.Mono.error(
                                new IllegalStateException("Chroma source cleanup failed.", exception));
                    }
                    return reactor.core.publisher.Mono.empty();
                })
                .block();
    }

    private List<SearchResult> parseResults(JsonNode response) {
        if (response == null) {
            return List.of();
        }
        List<SearchResult> results = new ArrayList<>();
        JsonNode docs = response.path("documents").path(0);
        JsonNode ids = response.path("ids").path(0);
        JsonNode metadatas = response.path("metadatas").path(0);
        JsonNode distances = response.path("distances").path(0);
        for (int i = 0; i < docs.size(); i++) {
            Long id = metadatas.path(i).path("chunkId").isNumber()
                    ? metadatas.path(i).path("chunkId").asLong()
                    : parseId(ids.path(i).asText());
            double score = 1.0 - distances.path(i).asDouble(1.0);
            String source = metadatas.path(i).path("source").asText("chroma");
            int sourceIndex = metadatas.path(i).path("sourceIndex").isNumber()
                    ? metadatas.path(i).path("sourceIndex").asInt()
                    : -1;
            results.add(new SearchResult(
                    id,
                    source,
                    docs.path(i).asText(),
                    score,
                    new EvidenceProvenance("", ids.path(i).asText(""), sourceIndex)));
        }
        return results;
    }

    private Long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String ensureCollection(String collectionName) {
        String existing = collectionIds.get(collectionName);
        if (existing != null) {
            return existing;
        }
        try {
            JsonNode response = webClient.post()
                    .uri(
                            "/api/v2/tenants/{tenant}/databases/{database}/collections",
                            TENANT,
                            DATABASE)
                    .bodyValue(Map.of(
                            "name", collectionName,
                            "get_or_create", true,
                            "metadata", Map.of("hnsw:space", "cosine")))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            String resolved = response == null ? "" : response.path("id").asText();
            if (resolved.isBlank()) {
                throw new IllegalStateException("Chroma did not return a collection id.");
            }
            collectionIds.put(collectionName, resolved);
            return resolved;
        } catch (Exception exception) {
            if (properties.getEvaluation().isEnabled()) {
                throw new IllegalStateException("Cannot create or resolve the Chroma collection.", exception);
            }
            return null;
        }
    }
}
