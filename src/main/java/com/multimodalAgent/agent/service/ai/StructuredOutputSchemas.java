package com.multimodalAgent.agent.service.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台模型调用使用的 JSON Schema。
 */
public final class StructuredOutputSchemas {

    private StructuredOutputSchemas() {
    }

    public static Map<String, Object> routing() {
        return objectSchema(
                Map.of(
                        "needsRag", Map.of("type", "boolean"),
                        "riskLevel", enumString("NONE", "LOW", "MEDIUM", "HIGH"),
                        "confidence", number(0.0, 1.0),
                        "reason", Map.of("type", "string")),
                List.of("needsRag", "riskLevel", "confidence", "reason"));
    }

    public static Map<String, Object> psychologyAssessment() {
        return objectSchema(
                Map.of(
                        "observedStates", enumArray(
                                1,
                                4,
                                "NEUTRAL",
                                "ANXIOUS",
                                "LOW_MOOD",
                                "SLEEP_DIFFICULTY",
                                "OVERWHELMED",
                                "HOPELESS",
                                "AGITATED",
                                "OTHER"),
                        "functionalImpact", enumString("NONE", "MILD", "SIGNIFICANT"),
                        "safetyRisk", enumString("NONE", "LOW", "MEDIUM", "HIGH"),
                        "evidence", stringArray(0, 3),
                        "confidence", number(0.0, 1.0),
                        "summary", Map.of("type", "string")),
                List.of(
                        "observedStates",
                        "functionalImpact",
                        "safetyRisk",
                        "evidence",
                        "confidence",
                        "summary"));
    }

    public static Map<String, Object> ragPlan() {
        return objectSchema(
                Map.of(
                        "reason", Map.of("type", "string"),
                        "queries", stringArray(2, 3)),
                List.of("reason", "queries"));
    }

    public static Map<String, Object> ragReview() {
        return objectSchema(
                Map.of(
                        "sufficient", Map.of("type", "boolean"),
                        "reason", Map.of("type", "string"),
                        "followUpQueries", stringArray(0, 2)),
                List.of("sufficient", "reason", "followUpQueries"));
    }

    private static Map<String, Object> objectSchema(
            Map<String, Object> properties,
            List<String> required
    ) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> enumString(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private static Map<String, Object> number(double minimum, double maximum) {
        return Map.of("type", "number", "minimum", minimum, "maximum", maximum);
    }

    private static Map<String, Object> stringArray(int minimum, int maximum) {
        return Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "minItems", minimum,
                "maxItems", maximum);
    }

    private static Map<String, Object> enumArray(
            int minimum,
            int maximum,
            String... values
    ) {
        return Map.of(
                "type", "array",
                "items", enumString(values),
                "minItems", minimum,
                "maxItems", maximum,
                "uniqueItems", true);
    }
}
