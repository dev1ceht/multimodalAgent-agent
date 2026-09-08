package com.multimodalAgent.agent.service.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MemoryStructuredOutputSchemas {
    private MemoryStructuredOutputSchemas() {
    }

    static Map<String, Object> extraction() {
        Map<String, Object> fact = object(Map.of(
                "content", Map.of("type", "string"),
                "occurredAt", Map.of("type", "string", "format", "date-time")),
                List.of("content", "occurredAt"));
        Map<String, Object> topic = object(Map.of(
                "key", Map.of("type", "string"),
                "title", Map.of("type", "string"),
                "summary", Map.of("type", "string")),
                List.of("key", "title", "summary"));
        Map<String, Object> relation = object(Map.of(
                "source", Map.of("type", "string"),
                "target", Map.of("type", "string"),
                "type", Map.of("type", "string", "enum", List.of(
                        "CAUSES", "TEMPORAL_BEFORE", "TEMPORAL_AFTER", "SUPPORTS",
                        "CONTRADICTS", "ELABORATES", "SIMILAR_TO", "CO_OCCURS")),
                "confidence", Map.of("type", "number", "minimum", 0.0, "maximum", 1.0)),
                List.of("source", "target", "type", "confidence"));
        Map<String, Object> membership = object(Map.of(
                "fact", Map.of("type", "string"),
                "topicKey", Map.of("type", "string")),
                List.of("fact", "topicKey"));
        return object(Map.of(
                "facts", array(fact, 12),
                "topics", array(topic, 6),
                "relations", array(relation, 24),
                "memberships", array(membership, 24)),
                List.of("facts", "topics", "relations", "memberships"));
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> array(Map<String, Object> items, int maximum) {
        return Map.of("type", "array", "items", items, "minItems", 0, "maxItems", maximum);
    }
}
