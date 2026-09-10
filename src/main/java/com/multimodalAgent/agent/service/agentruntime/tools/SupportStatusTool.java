package com.multimodalAgent.agent.service.agentruntime.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.MindCareAgentProperties;
import com.multimodalAgent.agent.service.mcp.AgentMcpSdkServer;
import com.multimodalAgent.agent.dto.StudentSupportStatusResponse;
import com.multimodalAgent.agent.service.agentruntime.AgentBudgetPolicy;
import com.multimodalAgent.agent.service.agentruntime.AgentRunContext;
import com.multimodalAgent.agent.service.agentruntime.AgentToolPolicy;
import com.multimodalAgent.agent.service.agentruntime.AgentToolResult;
import com.multimodalAgent.agent.service.agentruntime.JsonAgentToolCallback;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Read-only support projection for the authenticated student, with no model-supplied identity. */
@Component
public final class SupportStatusTool {

    private final SupportStatusProvider provider;
    private final AgentMcpSdkServer mcpServer;
    private final AgentToolPolicy toolPolicy;
    private final AgentBudgetPolicy budgetPolicy;
    private final ObjectMapper objectMapper;
    private final int maxToolResultCharacters;

    public SupportStatusTool(
            SupportStatusProvider provider,
            AgentToolPolicy toolPolicy,
            AgentBudgetPolicy budgetPolicy,
            ObjectMapper objectMapper,
            MindCareAgentProperties agentProperties
    ) {
        this.provider = provider;
        this.toolPolicy = toolPolicy;
        this.budgetPolicy = budgetPolicy;
        this.objectMapper = objectMapper;
        this.maxToolResultCharacters = Math.max(256, agentProperties.getMaxToolResultChars());
        this.mcpServer = null;
    }

    @Autowired
    public SupportStatusTool(
            AgentMcpSdkServer mcpServer,
            AgentToolPolicy toolPolicy,
            AgentBudgetPolicy budgetPolicy,
            ObjectMapper objectMapper,
            MindCareAgentProperties agentProperties
    ) {
        this.provider = null;
        this.mcpServer = mcpServer;
        this.toolPolicy = toolPolicy;
        this.budgetPolicy = budgetPolicy;
        this.objectMapper = objectMapper;
        this.maxToolResultCharacters = Math.max(256, agentProperties.getMaxToolResultChars());
    }

    public JsonAgentToolCallback callback(AgentRunContext context) {
        return new JsonAgentToolCallback(
                AgentToolPolicy.GET_SUPPORT_STATUS,
                "Read the authenticated student's public support case status; never request another identity.",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                context,
                toolPolicy,
                budgetPolicy,
                objectMapper,
                maxToolResultCharacters,
                arguments -> read(context));
    }

    private AgentToolResult read(AgentRunContext context) {
        if (mcpServer != null) {
            return readViaMcp(context);
        }
        try {
            List<StudentSupportStatusResponse> statuses = provider.statusFor(context.identity());
            if (statuses == null || statuses.isEmpty()) {
                return AgentToolResult.empty("support_status_empty");
            }
            List<Map<String, Object>> publicStatuses = new ArrayList<>();
            for (StudentSupportStatusResponse status : statuses) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("caseId", status.caseId());
                item.put("status", status.status() == null ? "UNKNOWN" : status.status().name());
                item.put("hasActiveReferral", status.hasActiveReferral());
                item.put("updatedAt", status.updatedAt() == null ? null : status.updatedAt().toString());
                publicStatuses.add(item);
            }
            return AgentToolResult.success(AgentToolResult.listData("statuses", publicStatuses));
        } catch (RuntimeException exception) {
            return AgentToolResult.failed("support_status_failed", false);
        }
    }

    private AgentToolResult readViaMcp(AgentRunContext context) {
        McpSyncClient client = null;
        try {
            client = mcpServer.openScopedClient(context.identity());
            client.initialize();
            List<McpSchema.Tool> tools = client.listTools().tools();
            if (tools == null || tools.size() != 1
                    || !AgentMcpSdkServer.SUPPORT_STATUS_TOOL.equals(tools.get(0).name())) {
                return AgentToolResult.failed("mcp_tool_not_allowlisted", false);
            }
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                    AgentMcpSdkServer.SUPPORT_STATUS_TOOL, Map.of()));
            if (result == null || Boolean.TRUE.equals(result.isError())) {
                return AgentToolResult.failed("mcp_support_status_failed", false);
            }
            String payload = result.content().stream()
                    .filter(McpSchema.TextContent.class::isInstance)
                    .map(content -> ((McpSchema.TextContent) content).text())
                    .findFirst()
                    .orElse("[]");
            JsonNode statusesNode = objectMapper.readTree(payload);
            if (!statusesNode.isArray()) {
                return AgentToolResult.failed("mcp_invalid_support_status", false);
            }
            List<Map<String, Object>> statuses = objectMapper.convertValue(
                    statusesNode, new TypeReference<List<Map<String, Object>>>() {
                    });
            if (statuses.isEmpty()) {
                return AgentToolResult.empty("support_status_empty");
            }
            return AgentToolResult.success(AgentToolResult.listData("statuses", statuses));
        } catch (RuntimeException | java.io.IOException exception) {
            return AgentToolResult.failed("mcp_support_status_failed", false);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

}
