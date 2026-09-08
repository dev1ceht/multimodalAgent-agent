package com.multimodalAgent.agent.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.service.knowledge.EmbeddingClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class QdrantMemoryVectorStore implements MemoryVectorStore {
    private final WebClient webClient;
    private final EmbeddingClient embeddings;
    private final multimodalAgentProperties properties;
    private final java.util.Set<String> ready = ConcurrentHashMap.newKeySet();

    public QdrantMemoryVectorStore(multimodalAgentProperties properties, EmbeddingClient embeddings,
                                   WebClient.Builder builder) {
        this.properties = properties;
        this.embeddings = embeddings;
        this.webClient = builder.clone().baseUrl(properties.getKnowledge().getQdrantBaseUrl()).build();
    }

    @Override
    public void upsert(MemoryProjectionBatch batch) {
        ensure(properties.getMemory().getFactCollection());
        ensure(properties.getMemory().getTopicCollection());
        for (var fact : batch.facts()) {
            point(properties.getMemory().getFactCollection(), "fact:" + fact.id(), embeddings.embed(fact.content()),
                    Map.of("kind", "fact", "memory_id", fact.id(), "user_id", batch.userId(),
                            "session_id", fact.sessionId(), "content", fact.content(),
                            "occurred_at", fact.occurredAt().toString()));
        }
        for (var topic : batch.topics()) {
            String text = topic.title() + "\n" + topic.summary();
            point(properties.getMemory().getTopicCollection(), "topic:" + topic.id(), embeddings.embed(text),
                    Map.of("kind", "topic", "memory_id", topic.id(), "user_id", batch.userId(),
                            "content", text, "topic_key", topic.key()));
        }
    }

    @Override
    public List<MemoryVectorHit> searchFacts(Long userId, List<Double> vector, int limit) {
        return search(properties.getMemory().getFactCollection(), userId, vector, limit);
    }

    @Override
    public List<MemoryVectorHit> searchTopics(Long userId, List<Double> vector, int limit) {
        return search(properties.getMemory().getTopicCollection(), userId, vector, limit);
    }

    private List<MemoryVectorHit> search(String collection, Long userId, List<Double> vector, int limit) {
        ensure(collection);
        JsonNode response = webClient.post().uri("/collections/{collection}/points/query", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "query", vector,
                        "limit", limit,
                        "with_payload", true,
                        "filter", Map.of("must", List.of(Map.of(
                                "key", "user_id", "match", Map.of("value", userId)))))
                ).retrieve().bodyToMono(JsonNode.class).block();
        JsonNode points = response == null ? null : response.path("result").path("points");
        if (points == null || !points.isArray()) return List.of();
        List<MemoryVectorHit> result = new ArrayList<>();
        for (JsonNode point : points) {
            JsonNode payload = point.path("payload");
            String timestamp = payload.path("occurred_at").asText("");
            result.add(new MemoryVectorHit(
                    payload.path("memory_id").asLong(), payload.path("content").asText(),
                    clamp(point.path("score").asDouble()),
                    payload.path("session_id").isNumber() ? payload.path("session_id").asLong() : null,
                    timestamp.isBlank() ? null : Instant.parse(timestamp)));
        }
        return List.copyOf(result);
    }

    private void ensure(String collection) {
        if (!properties.getMemory().isEnabled() || !ready.add(collection)) return;
        Boolean exists = webClient.get().uri("/collections/{collection}", collection)
                .exchangeToMono(response -> response.statusCode().value() == 404
                        ? Mono.just(false)
                        : response.releaseBody().thenReturn(true)).block();
        if (!Boolean.TRUE.equals(exists)) {
            webClient.put().uri("/collections/{collection}", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("vectors", Map.of(
                            "size", properties.getEmbedding().getDimensions(), "distance", "Cosine")))
                    .retrieve().toBodilessEntity().block();
            webClient.put().uri("/collections/{collection}/index?wait=true", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("field_name", "user_id", "field_schema", "integer"))
                    .retrieve().toBodilessEntity().block();
        }
    }

    private void point(String collection, String key, List<Double> vector, Map<String, Object> payload) {
        Map<String, Object> point = Map.of(
                "id", UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString(),
                "vector", vector, "payload", payload);
        webClient.put().uri("/collections/{collection}/points?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("points", List.of(point)))
                .retrieve().toBodilessEntity().block();
    }

    private double clamp(double score) {
        return Double.isFinite(score) ? Math.max(0.0, Math.min(1.0, score)) : 0.0;
    }
}
