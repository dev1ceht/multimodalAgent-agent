package com.multimodalAgent.agent.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
/**
 * Chroma 向量库网关。
 *
 * <p>当 use-chroma=true 时，把知识库切块镜像到外部向量库，并优先从 Chroma 检索。</p>
 */
public class ChromaGateway {

    private static final String TENANT = "default_tenant";
    private static final String DATABASE = "default_database";

    private final multimodalAgentProperties properties;
    private final WebClient webClient;
    private volatile String collectionId;

    public ChromaGateway(multimodalAgentProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.baseUrl(properties.getKnowledge().getChromaBaseUrl()).build();
    }

    public void mirror(KnowledgeChunk chunk, List<Double> embedding) {
        if (!properties.getKnowledge().isUseChroma()) {
            return;
        }
        if (embedding == null || embedding.isEmpty()) {
            if (properties.getEvaluation().isEnabled()) {
                throw new IllegalStateException("Evaluation requires explicit document embeddings for Chroma.");
            }
            return;
        }
        // 本地数据库仍是主存储；Chroma 只是可选检索加速层。
        String ensuredCollectionId = ensureCollection();
        if (ensuredCollectionId == null) {
            return;
        }
        Map<String, Object> body = Map.of(
                "ids", List.of(String.valueOf(chunk.getId())),
                "documents", List.of(chunk.getContent()),
                "embeddings", List.of(embedding),
                "metadatas", List.of(Map.of(
                        "source", chunk.getSource(),
                        "sourceIndex", chunk.getSourceIndex()))
        );
        webClient.post()
                .uri(
                        "/api/v2/tenants/{tenant}/databases/{database}/collections/{collection}/add",
                        TENANT,
                        DATABASE,
                        ensuredCollectionId)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .onErrorResume(exception -> {
                    if (properties.getEvaluation().isEnabled()) {
                        return reactor.core.publisher.Mono.error(
                                new IllegalStateException("Chroma document mirror failed.", exception));
                    }
                    return reactor.core.publisher.Mono.empty();
                })
                .block();
    }

    public List<SearchResult> query(List<Double> queryEmbedding, int topK) {
        if (!properties.getKnowledge().isUseChroma()) {
            return List.of();
        }
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            if (properties.getEvaluation().isEnabled()) {
                throw new IllegalStateException("Evaluation requires explicit query embeddings for Chroma.");
            }
            return List.of();
        }
        String ensuredCollectionId = ensureCollection();
        if (ensuredCollectionId == null) {
            return List.of();
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
            if (properties.getEvaluation().isEnabled()) {
                throw new IllegalStateException("Chroma query failed during evaluation.", exception);
            }
            // 普通运行时保持原有容错：外部向量库不可用则返回空结果。
            return List.of();
        }
    }

    public void deleteSource(String source) {
        if (!properties.getKnowledge().isUseChroma()) {
            return;
        }
        String ensuredCollectionId = ensureCollection();
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
            Long id = parseId(ids.path(i).asText());
            double score = 1.0 - distances.path(i).asDouble(1.0);
            String source = metadatas.path(i).path("source").asText("chroma");
            results.add(new SearchResult(id, source, docs.path(i).asText(), score));
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

    private String ensureCollection() {
        if (collectionId != null) {
            return collectionId;
        }
        try {
            JsonNode response = webClient.post()
                    .uri(
                            "/api/v2/tenants/{tenant}/databases/{database}/collections",
                            TENANT,
                            DATABASE)
                    .bodyValue(Map.of(
                            "name", properties.getKnowledge().getChromaCollection(),
                            "get_or_create", true,
                            "metadata", Map.of("hnsw:space", "cosine")))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            String resolved = response == null ? "" : response.path("id").asText();
            if (resolved.isBlank()) {
                throw new IllegalStateException("Chroma did not return a collection id.");
            }
            collectionId = resolved;
            return resolved;
        } catch (Exception exception) {
            if (properties.getEvaluation().isEnabled()) {
                throw new IllegalStateException("Cannot create or resolve the Chroma collection.", exception);
            }
            return null;
        }
    }
}
