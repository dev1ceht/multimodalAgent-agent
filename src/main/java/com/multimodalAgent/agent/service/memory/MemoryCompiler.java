package com.multimodalAgent.agent.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.service.ai.AiClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** 把单轮对话编译为可持久化的原子事实、主题与受控关系。 */
@Component
public class MemoryCompiler {
    private static final int MAX_FACTS = 12;
    private static final int MAX_TOPICS = 6;
    private static final int MAX_RELATIONS = 24;

    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

    public MemoryCompiler(AiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    public CompiledMemory compile(MemoryCompilationInput input) {
        try {
            JsonNode root = objectMapper.readTree(aiClient.completeJson(
                    MemoryPromptTemplates.extraction(input),
                    MemoryStructuredOutputSchemas.extraction()));
            return new CompiledMemory(
                    parseFacts(root.path("facts"), input.occurredAt()),
                    parseTopics(root.path("topics")),
                    parseRelations(root.path("relations")),
                    parseMemberships(root.path("memberships")));
        } catch (Exception exception) {
            throw new IllegalStateException("长期记忆结构化抽取失败", exception);
        }
    }

    private List<CompiledFact> parseFacts(JsonNode nodes, String fallbackOccurredAt) {
        List<CompiledFact> result = new ArrayList<>();
        if (!nodes.isArray()) {
            return result;
        }
        for (JsonNode node : nodes) {
            String content = normalized(node.path("content").asText());
            if (!content.isBlank()) {
                String occurredAt = normalized(node.path("occurredAt").asText(fallbackOccurredAt));
                result.add(new CompiledFact(content, Instant.parse(occurredAt)));
            }
            if (result.size() == MAX_FACTS) {
                break;
            }
        }
        return result;
    }

    private List<CompiledTopic> parseTopics(JsonNode nodes) {
        List<CompiledTopic> result = new ArrayList<>();
        if (!nodes.isArray()) {
            return result;
        }
        for (JsonNode node : nodes) {
            String key = normalized(node.path("key").asText()).toLowerCase(Locale.ROOT);
            String title = normalized(node.path("title").asText());
            if (!key.isBlank() && !title.isBlank()) {
                result.add(new CompiledTopic(key, title, normalized(node.path("summary").asText())));
            }
            if (result.size() == MAX_TOPICS) {
                break;
            }
        }
        return result;
    }

    private List<CompiledRelation> parseRelations(JsonNode nodes) {
        List<CompiledRelation> result = new ArrayList<>();
        if (!nodes.isArray()) {
            return result;
        }
        for (JsonNode node : nodes) {
            try {
                MemoryRelationType type = MemoryRelationType.valueOf(
                        normalized(node.path("type").asText()).toUpperCase(Locale.ROOT));
                String source = normalized(node.path("source").asText());
                String target = normalized(node.path("target").asText());
                if (!source.isBlank() && !target.isBlank()) {
                    result.add(new CompiledRelation(source, target, type,
                            Math.max(0.0, Math.min(1.0, node.path("confidence").asDouble()))));
                }
            } catch (IllegalArgumentException ignored) {
                // Unsupported relation types are never allowed to enter the graph.
            }
            if (result.size() == MAX_RELATIONS) {
                break;
            }
        }
        return result;
    }

    private List<CompiledMembership> parseMemberships(JsonNode nodes) {
        List<CompiledMembership> result = new ArrayList<>();
        if (!nodes.isArray()) {
            return result;
        }
        for (JsonNode node : nodes) {
            String fact = normalized(node.path("fact").asText());
            String topicKey = normalized(node.path("topicKey").asText()).toLowerCase(Locale.ROOT);
            if (!fact.isBlank() && !topicKey.isBlank()) {
                result.add(new CompiledMembership(fact, topicKey));
            }
        }
        return result;
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
