package com.multimodalAgent.agent.service.agentruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.multimodalAgent.agent.config.MindCareAgentProperties;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.RiskLevel;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Allow-list, schema and request-context gate for model-visible tools. */
@Component
public final class AgentToolPolicy {

    public static final String SEARCH_KNOWLEDGE = "search_knowledge";
    public static final String RECALL_MEMORY = "recall_memory";
    public static final String GET_SUPPORT_STATUS = "get_support_status";
    private static final int MAX_QUERY_LENGTH = 1000;
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            SEARCH_KNOWLEDGE,
            RECALL_MEMORY,
            GET_SUPPORT_STATUS);

    private final int defaultTopK;

    public AgentToolPolicy(
            multimodalAgentProperties applicationProperties,
            MindCareAgentProperties agentProperties
    ) {
        int configured = applicationProperties == null
                ? 4
                : applicationProperties.getKnowledge().getTopK();
        defaultTopK = Math.max(1, Math.min(20, configured));
    }

    public AgentToolPolicy() {
        this.defaultTopK = 4;
    }

    public Set<String> allowedTools() {
        return ALLOWED_TOOLS;
    }

    public boolean isAllowed(String toolName) {
        return toolName != null && ALLOWED_TOOLS.contains(toolName.trim());
    }

    public ToolValidation authorize(AgentRunContext context, String toolName) {
        if (context == null || !context.consentValidated()) {
            return ToolValidation.denied("consent_required", "当前请求未完成必要的授权确认。");
        }
        if (!isAllowed(toolName)) {
            return ToolValidation.denied("tool_not_allowed", "该工具不在当前对话允许范围内。");
        }
        if (RECALL_MEMORY.equals(toolName.trim()) && context.riskLevel() == RiskLevel.HIGH) {
            return ToolValidation.denied("memory_disabled_for_high_risk", "当前安全响应不会调用长期记忆。");
        }
        return ToolValidation.accepted();
    }

    public ToolValidation validate(String toolName, JsonNode arguments) {
        if (!isAllowed(toolName)) {
            return ToolValidation.denied("tool_not_allowed", "该工具不在当前对话允许范围内。");
        }
        if (arguments == null || !arguments.isObject()) {
            return ToolValidation.invalid("invalid_tool_arguments", "工具参数必须是 JSON 对象。", true);
        }
        String name = toolName.trim();
        return switch (name) {
            case SEARCH_KNOWLEDGE -> validateSearch(arguments);
            case RECALL_MEMORY -> validateMemory(arguments);
            case GET_SUPPORT_STATUS -> validateEmpty(arguments);
            default -> ToolValidation.denied("tool_not_allowed", "该工具不在当前对话允许范围内。");
        };
    }

    public int defaultTopK() {
        return defaultTopK;
    }

    private ToolValidation validateSearch(JsonNode arguments) {
        if (!hasOnly(arguments, "query", "topK")) {
            return ToolValidation.invalid("unknown_tool_argument", "工具参数包含未允许的字段。", true);
        }
        JsonNode query = arguments.get("query");
        if (query == null || !query.isTextual() || query.asText().isBlank()
                || query.asText().length() > MAX_QUERY_LENGTH) {
            return ToolValidation.invalid("invalid_query", "query 必须是 1 到 1000 字符的文本。", true);
        }
        JsonNode topK = arguments.get("topK");
        if (topK != null && (!topK.isIntegralNumber()
                || topK.asInt() < 1
                || topK.asInt() > defaultTopK)) {
            return ToolValidation.invalid(
                    "invalid_top_k",
                    "topK 必须是 1 到当前配置上限之间的整数。",
                    true);
        }
        return ToolValidation.accepted();
    }

    private ToolValidation validateMemory(JsonNode arguments) {
        if (!hasOnly(arguments, "query")) {
            return ToolValidation.invalid("unknown_tool_argument", "工具参数包含未允许的字段。", true);
        }
        JsonNode query = arguments.get("query");
        if (query == null || !query.isTextual() || query.asText().isBlank()
                || query.asText().length() > MAX_QUERY_LENGTH) {
            return ToolValidation.invalid("invalid_query", "query 必须是 1 到 1000 字符的文本。", true);
        }
        return ToolValidation.accepted();
    }

    private ToolValidation validateEmpty(JsonNode arguments) {
        return arguments.size() == 0
                ? ToolValidation.accepted()
                : ToolValidation.invalid("unknown_tool_argument", "该工具不接受参数。", true);
    }

    private static boolean hasOnly(JsonNode arguments, String... allowed) {
        java.util.Iterator<String> fields = arguments.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            boolean known = false;
            for (String candidate : allowed) {
                if (candidate.equals(field)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                return false;
            }
        }
        return true;
    }

    public record ToolValidation(
            boolean valid,
            String errorCode,
            String publicMessage,
            boolean retryable
    ) {
        public static ToolValidation accepted() {
            return new ToolValidation(true, "", "", false);
        }

        public static ToolValidation invalid(String code, String message, boolean retryable) {
            return new ToolValidation(false, code, message, retryable);
        }

        public static ToolValidation denied(String code, String message) {
            return new ToolValidation(false, code, message, false);
        }
    }
}
