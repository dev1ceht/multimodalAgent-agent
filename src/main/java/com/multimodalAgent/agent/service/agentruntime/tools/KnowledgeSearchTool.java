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
import com.multimodalAgent.agent.service.knowledge.EvidenceQualityPolicy;
import com.multimodalAgent.agent.service.knowledge.EvidenceProvenance;
import com.multimodalAgent.agent.service.knowledge.SearchResult;
import com.multimodalAgent.agent.service.knowledge.retrieval.EvidenceRetriever;
import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalQuery;
import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalResult;
import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Read-only knowledge retrieval exposed to the Agent. */
@Component
public final class KnowledgeSearchTool {

    private final EvidenceRetriever evidenceRetriever;
    private final EvidenceQualityPolicy evidenceQualityPolicy;
    private final AgentToolPolicy toolPolicy;
    private final AgentBudgetPolicy budgetPolicy;
    private final ObjectMapper objectMapper;
    private final int defaultTopK;
    private final int evidenceCharacterBudget;
    private final int maxToolResultCharacters;

    public KnowledgeSearchTool(
            EvidenceRetriever evidenceRetriever,
            EvidenceQualityPolicy evidenceQualityPolicy,
            AgentToolPolicy toolPolicy,
            AgentBudgetPolicy budgetPolicy,
            ObjectMapper objectMapper,
            multimodalAgentProperties applicationProperties,
            MindCareAgentProperties agentProperties
    ) {
        this.evidenceRetriever = evidenceRetriever;
        this.evidenceQualityPolicy = evidenceQualityPolicy;
        this.toolPolicy = toolPolicy;
        this.budgetPolicy = budgetPolicy;
        this.objectMapper = objectMapper;
        this.defaultTopK = Math.max(1, Math.min(20, applicationProperties.getKnowledge().getTopK()));
        this.evidenceCharacterBudget = Math.max(256, applicationProperties.getKnowledge().getEvidenceCharacterBudget());
        this.maxToolResultCharacters = Math.max(256, agentProperties.getMaxToolResultChars());
    }

    public JsonAgentToolCallback callback(AgentRunContext context) {
        return new JsonAgentToolCallback(
                AgentToolPolicy.SEARCH_KNOWLEDGE,
                "Search the approved student-support knowledge base. Return evidence before answering.",
                schema(),
                context,
                toolPolicy,
                budgetPolicy,
                objectMapper,
                maxToolResultCharacters,
                arguments -> search(context, arguments));
    }

    private AgentToolResult search(AgentRunContext context, JsonNode arguments) {
        context.markKnowledgeAttempted();
        String queryText = arguments.get("query").asText().trim();
        int topK = arguments.has("topK") ? arguments.get("topK").asInt() : defaultTopK;
        try {
            RetrievalResult retrieval = evidenceRetriever.retrieve(new RetrievalQuery(queryText, topK));
            if (retrieval.status() == RetrievalStatus.FAILED) {
                return AgentToolResult.failed("retrieval_failed", true);
            }
            List<SearchResult> usable = evidenceQualityPolicy.usableEvidence(retrieval.evidence());
            if (usable.isEmpty()) {
                return AgentToolResult.empty(retrieval.status() == RetrievalStatus.EMPTY
                        ? "evidence_empty"
                        : "evidence_insufficient");
            }
            List<Map<String, Object>> evidence = new ArrayList<>();
            int remainingCharacters = evidenceCharacterBudget;
            for (SearchResult result : usable) {
                if (remainingCharacters <= 0) {
                    break;
                }
                String content = clip(result.content(), remainingCharacters);
                if (content.isBlank()) {
                    continue;
                }
                remainingCharacters -= content.length();
                String evidenceId = context.registerEvidence(result);
                EvidenceProvenance provenance = result.provenance();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("evidenceId", evidenceId);
                item.put("source", result.source());
                item.put("knowledgeVersion", provenance.knowledgeVersionKey());
                item.put("chunkId", result.chunkId());
                item.put("content", content);
                evidence.add(item);
            }
            if (evidence.isEmpty()) {
                return AgentToolResult.empty("evidence_insufficient");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("retrievalStatus", retrieval.status().name());
            data.put("evidence", evidence);
            return AgentToolResult.success(data);
        } catch (RuntimeException exception) {
            return AgentToolResult.failed("retrieval_failed", true);
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

    private String schema() {
        return "{\"type\":\"object\",\"properties\":{" +
                "\"query\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1000}," +
                "\"topK\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":" + defaultTopK + "}" +
                "},\"required\":[\"query\"],\"additionalProperties\":false}";
    }
}
