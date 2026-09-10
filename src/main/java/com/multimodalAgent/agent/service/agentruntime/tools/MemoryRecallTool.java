package com.multimodalAgent.agent.service.agentruntime.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.MindCareAgentProperties;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.service.agentruntime.AgentBudgetPolicy;
import com.multimodalAgent.agent.service.agentruntime.AgentRunContext;
import com.multimodalAgent.agent.service.agentruntime.AgentToolPolicy;
import com.multimodalAgent.agent.service.agentruntime.AgentToolResult;
import com.multimodalAgent.agent.service.agentruntime.JsonAgentToolCallback;
import com.multimodalAgent.agent.service.memory.LongTermMemoryQuery;
import com.multimodalAgent.agent.service.memory.LongTermMemoryRecall;
import com.multimodalAgent.agent.service.memory.LongTermMemoryRetriever;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Read-only, identity-scoped long-term memory retrieval. */
@Component
public final class MemoryRecallTool {

    private final LongTermMemoryRetriever retriever;
    private final AgentToolPolicy toolPolicy;
    private final AgentBudgetPolicy budgetPolicy;
    private final ObjectMapper objectMapper;
    private final int maxToolResultCharacters;
    private final int resultCharacterBudget;

    public MemoryRecallTool(
            LongTermMemoryRetriever retriever,
            AgentToolPolicy toolPolicy,
            AgentBudgetPolicy budgetPolicy,
            ObjectMapper objectMapper,
            multimodalAgentProperties applicationProperties,
            MindCareAgentProperties agentProperties
    ) {
        this.retriever = retriever;
        this.toolPolicy = toolPolicy;
        this.budgetPolicy = budgetPolicy;
        this.objectMapper = objectMapper;
        this.maxToolResultCharacters = Math.max(256, agentProperties.getMaxToolResultChars());
        this.resultCharacterBudget = Math.max(256, applicationProperties.getMemory().getTopK() * 900);
    }

    public JsonAgentToolCallback callback(AgentRunContext context) {
        return new JsonAgentToolCallback(
                AgentToolPolicy.RECALL_MEMORY,
                "Recall relevant, identity-scoped long-term memory when it helps the student conversation.",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1000}},\"required\":[\"query\"],\"additionalProperties\":false}",
                context,
                toolPolicy,
                budgetPolicy,
                objectMapper,
                maxToolResultCharacters,
                arguments -> recall(context, arguments));
    }

    private AgentToolResult recall(AgentRunContext context, JsonNode arguments) {
        try {
            LongTermMemoryRecall result = retriever.recall(new LongTermMemoryQuery(
                    context.identity().userId(),
                    context.identity().sessionId(),
                    arguments.get("query").asText().trim()));
            if (result.status() == LongTermMemoryRecall.Status.DISABLED) {
                return AgentToolResult.empty("memory_disabled");
            }
            if (result.items().isEmpty()) {
                return AgentToolResult.empty(result.status() == LongTermMemoryRecall.Status.DEGRADED
                        ? "memory_degraded_empty"
                        : "memory_empty");
            }
            List<Map<String, Object>> items = new ArrayList<>();
            int remaining = resultCharacterBudget;
            for (LongTermMemoryRecall.Item item : result.items()) {
                if (remaining <= 0) {
                    break;
                }
                String content = clip(item.content(), remaining);
                if (content.isBlank()) {
                    continue;
                }
                remaining -= content.length();
                Map<String, Object> publicItem = new LinkedHashMap<>();
                publicItem.put("factId", item.factId());
                publicItem.put("content", content);
                publicItem.put("score", item.score());
                publicItem.put("source", item.source());
                items.add(publicItem);
            }
            if (items.isEmpty()) {
                return AgentToolResult.empty("memory_empty");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("memoryStatus", result.status().name());
            data.put("items", items);
            return AgentToolResult.success(data);
        } catch (RuntimeException exception) {
            return AgentToolResult.failed("memory_retrieval_failed", true);
        }
    }

    private static String clip(String value, int maxCharacters) {
        if (value == null || value.isBlank() || maxCharacters <= 0) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxCharacters
                ? normalized
                : normalized.substring(0, maxCharacters).trim() + "…";
    }
}
