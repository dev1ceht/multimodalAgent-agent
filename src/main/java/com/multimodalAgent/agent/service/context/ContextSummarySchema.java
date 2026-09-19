package com.multimodalAgent.agent.service.context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON schema shared by the asynchronous context summary compiler. */
public final class ContextSummarySchema {

    private ContextSummarySchema() {
    }

    public static Map<String, Object> summary() {
        Map<String, Object> item = object(Map.of(
                "text", Map.of("type", "string"),
                "time", Map.of("type", "string"),
                "sourceMessageIds", Map.of(
                        "type", "array",
                        "items", Map.of("type", "integer"),
                        "maxItems", 16)),
                List.of("text", "sourceMessageIds"));
        return object(Map.of(
                "schemaVersion", Map.of("type", "string", "enum", List.of("conversation-summary-v1")),
                "topics", array(item, 16),
                "userGoals", array(item, 16),
                "constraints", array(item, 16),
                "events", array(item, 24),
                "attemptedActions", array(item, 16),
                "assistantSuggestions", array(item, 16),
                "openQuestions", array(item, 16),
                "corrections", array(item, 16)),
                List.of("schemaVersion"));
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> array(Map<String, Object> item, int maximum) {
        return Map.of("type", "array", "items", item, "minItems", 0, "maxItems", maximum);
    }
}
